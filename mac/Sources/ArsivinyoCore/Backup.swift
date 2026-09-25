import CryptoKit
import Foundation
import UniformTypeIdentifiers

/// The sections of a backup, in the order the phone writes them. Wire values.
public enum BackupSection: String, CaseIterable, Sendable, Identifiable {
    case vault, music, settings, cookies
    public var id: String { rawValue }
}

/// Making and restoring `.avsbck` files, the phone's backup format.
///
/// What goes in each section, and how each comes back, follows the phone's `BackupPorts`, so
/// a backup made on either restores on the other:
///
/// - vault: each item decrypted into the backup and encrypted again under this Mac's key on
///   the way back. Titles and types travel; the per-device ciphertext does not.
/// - music: each track's file, its artwork just before it, then the playlists as a blob, and
///   the auto-apply presets if any are set.
/// - settings: the phone's two preset keys. Its theme and download folder are its own.
/// - cookies: each profile, decrypted, by site and name.
///
/// A restore only adds. Files are matched by content, so running one twice adds nothing the
/// second time; cookie profiles by name, so a stale backup never replaces a working login.
public enum Backup {

    public struct Sources: @unchecked Sendable {
        public var vault: Vault?
        public var library: MusicLibrary?
        public var presets: PresetStore?
        public var cookies: CookieStore?

        public init(vault: Vault? = nil, library: MusicLibrary? = nil,
                    presets: PresetStore? = nil, cookies: CookieStore? = nil) {
            self.vault = vault
            self.library = library
            self.presets = presets
            self.cookies = cookies
        }
    }

    public enum Failure: Error, Equatable, CustomStringConvertible {
        case wrongSecret
        case cancelled
        case locked
        case io(String)

        public var description: String {
            switch self {
            case .wrongSecret: return String(localized: "That passphrase does not open this backup.")
            case .cancelled: return String(localized: "Cancelled.")
            case .locked: return String(localized: "Unlock the vault first: its contents and your cookies are encrypted.")
            case .io(let why): return why
            }
        }
    }

    /// What a restore did. Counts only: which files they were is not something to put on
    /// a screen someone else may be looking at.
    public struct Report: Sendable, Equatable {
        /// Files added to the vault or the library.
        public var restored = 0
        /// Playlists, presets, cover art and cookie profiles written.
        public var applied = 0
        /// Already here, by content.
        public var duplicates = 0
        /// A cookie profile of the same name already here, left alone.
        public var existing = 0
        public var failed = 0
        /// Why items failed, each reason once.
        public var reasons: [String] = []

        mutating func fail(_ why: String) {
            failed += 1
            if !reasons.contains(why) { reasons.append(why) }
        }
    }

    /// Bytes done and bytes expected, for a progress bar. Called from the working thread.
    public typealias Progress = @Sendable (Int64, Int64) -> Void

    /// What the phone writes, and what a backup made here is derived with.
    public static let kdf = Crypto.Argon2idParams.shipped

    // MARK: - Reading the header

    public static func preview(_ url: URL) throws -> BackupHeader {
        try BackupFileReader(url: url).header
    }

    // MARK: - Making one

    /// Writes a backup of the chosen sections.
    ///
    /// Written beside the destination first and moved into place once whole, so a failure
    /// or a cancel never leaves half a backup under the name of a good one.
    public static func create(at url: URL, secret: String, sections: [BackupSection],
                              from sources: Sources, appVersion: String,
                              progress: Progress? = nil,
                              isCancelled: @escaping @Sendable () -> Bool = { false }) throws -> BackupHeader {
        let chosen = BackupSection.allCases.filter(sections.contains)
        var plans: [(BackupSection, [Planned])] = []
        for section in chosen {
            plans.append((section, try plan(section, from: sources)))
        }

        let salt = try Crypto.randomBytes(16)
        let master = try Crypto.argon2id(secret: Data(secret.utf8), salt: salt, params: kdf)
        let header = BackupHeader(
            createdAt: Date(), producerVersion: appVersion, kdf: kdf,
            slots: [.init(id: "default", salt: salt, verifier: try Crypto.backupVerifier(master: master),
                          secretKind: secretKind(of: secret))],
            sections: plans.map { section, items in
                .init(id: section.rawValue, keySlot: "default", itemCount: items.count,
                      plaintextBytes: items.reduce(0) { $0 + max(0, $1.entry.size) })
            })

        let partial = url.deletingLastPathComponent()
            .appendingPathComponent(".\(url.lastPathComponent).\(UUID().uuidString).partial")
        let writer = try BackupFileWriter(url: partial, header: header)
        let total = header.sections.reduce(0) { $0 + $1.plaintextBytes }
        var done: Int64 = 0

        for (section, items) in plans {
            try writer.beginSection(section.rawValue, key: Crypto.backupSectionKey(master: master, sectionId: section.rawValue))
            for item in items {
                if isCancelled() { throw Failure.cancelled }
                try writer.beginEntry(item.entry)
                let complete = try item.write { chunk in
                    try writer.write(chunk)
                    done += Int64(chunk.count)
                    progress?(done, total)
                    if isCancelled() { throw Failure.cancelled }
                }
                try writer.endEntry(complete: complete)
            }
            try writer.endSection()
        }
        try writer.finish()

        if FileManager.default.fileExists(atPath: url.path) {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: partial)
        } else {
            try FileManager.default.moveItem(at: partial, to: url)
        }
        return header
    }

    /// One entry to write: its header, and how to stream its payload. The writer returns
    /// false when its source could not be read in full; the entry is then marked incomplete.
    struct Planned {
        let entry: BackupEntry
        let write: (_ emit: (Data) throws -> Void) throws -> Bool
    }

    static func plan(_ section: BackupSection, from sources: Sources) throws -> [Planned] {
        switch section {
        case .vault:
            guard let vault = sources.vault else { return [] }
            let items: [Vault.Item]
            do { items = try vault.items() } catch Vault.Failure.locked { throw Failure.locked }
            return items.map { item in
                var meta: [String: Any] = [
                    "vaultId": item.id,
                    "mimeType": UTType(item.contentType)?.preferredMIMEType ?? "application/octet-stream",
                    "createdAt": Int64(item.addedAt.timeIntervalSince1970 * 1000),
                    "tags": item.tags,
                ]
                if let folder = item.folderId { meta["folderId"] = folder }
                return Planned(entry: BackupEntry(name: item.title, size: item.sizeBytes, kind: "media", meta: meta)) { emit in
                    guard let reader = try? vault.reader(for: item.id) else { return false }
                    var offset: Int64 = 0
                    while offset < reader.size {
                        guard let chunk = try? reader.read(offset: offset, length: 1 << 20), !chunk.isEmpty else { return false }
                        try emit(chunk)
                        offset += Int64(chunk.count)
                    }
                    return true
                }
            }

        case .music:
            guard let library = sources.library else { return [] }
            let (tracks, playlists) = library.load()
            var out: [Planned] = []
            for track in tracks {
                // Artwork first: the other side attaches it as the track arrives.
                if let art = library.artworkURL(for: track), FileManager.default.fileExists(atPath: art.path) {
                    out.append(Planned(entry: BackupEntry(name: art.lastPathComponent, size: 0, kind: "thumbnail",
                                                          meta: ["ownerId": track.id])) { emit in
                        try streamFile(art, emit)
                    })
                }
                var meta: [String: Any] = [
                    "songId": track.id, "title": track.title, "artist": track.artist,
                    "durationSec": track.durationSeconds,
                    "createdAt": Int64(track.createdAt.timeIntervalSince1970 * 1000),
                ]
                if let preset = track.presetId { meta["presetId"] = preset }
                if let source = track.sourceSongId { meta["sourceSongId"] = source }
                let file = library.fileURL(for: track)
                out.append(Planned(entry: BackupEntry(name: track.fileName, size: track.sizeBytes, kind: "media", meta: meta)) { emit in
                    try streamFile(file, emit)
                })
            }
            // The playlists as the phone's index holds them, by song id.
            out.append(blob("music-index", [
                "playlists": playlists.map { ["id": $0.id, "name": $0.name, "songIds": $0.trackIds, "system": $0.isSystem] },
            ]))
            if let presets = sources.presets, !presets.autoApply.presetIds.isEmpty {
                out.append(blob("auto-presets", presets.autoApplyBlob()))
            }
            return out

        case .settings:
            guard let presets = sources.presets else { return [] }
            return [blob("app-settings", presets.settingsBlob())]

        case .cookies:
            guard let cookies = sources.cookies else { return [] }
            return try cookies.profiles().map { profile in
                let plain: Data
                do { plain = try cookies.plaintext(profile.scope, name: profile.name) }
                catch CookieStore.Failure.locked { throw Failure.locked }
                return Planned(entry: BackupEntry(
                    name: profile.scope.folderName + "/" + profile.name, size: Int64(plain.count), kind: "cookie-profile",
                    meta: ["platform": profile.scope.folderName, "profileName": profile.name, "isDefault": profile.isDefault])) { emit in
                    try emit(plain)
                    return true
                }
            }
        }
    }

    private static func blob(_ id: String, _ object: [String: Any]) -> Planned {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
        return Planned(entry: BackupEntry(name: id + ".json", size: Int64(data.count), kind: "blob", meta: ["blobId": id])) { emit in
            try emit(data)
            return true
        }
    }

    /// False, not a throw, when the file goes away or stops being readable part way: the
    /// entry is already open and has to be closed, as incomplete.
    private static func streamFile(_ url: URL, _ emit: (Data) throws -> Void) throws -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        while true {
            // nil is the end of the file; only a thrown error is a failed read.
            let chunk: Data?
            do { chunk = try handle.read(upToCount: 1 << 20) } catch { return false }
            guard let chunk, !chunk.isEmpty else { return true }
            try emit(chunk)
        }
    }

    /// Four or more words reads as a passphrase, anything else as a password. The phone
    /// shows the other side which it was, so it can ask for the right thing.
    static func secretKind(of secret: String) -> String {
        secret.split(whereSeparator: \.isWhitespace).count >= 4 ? "passphrase" : "password"
    }

    // MARK: - Restoring one

    /// Restores the chosen sections. Blocking work: run it off the main actor.
    ///
    /// - Parameter staging: a private folder for payloads on their way in. Each is written
    ///   there, checked against its recorded hash, and only then handed to the vault or the
    ///   library, so a damaged or duplicate item never lands anywhere.
    public static func restore(from url: URL, secret: String, sections: Set<BackupSection>,
                               into sources: Sources, staging: URL,
                               progress: Progress? = nil,
                               isCancelled: @escaping @Sendable () -> Bool = { false }) async throws -> Report {
        // On a thread of its own: the C reader calls back synchronously, and taking a track
        // into the library is async, so the walk waits on it. Blocking a thread from the
        // shared pool that way can starve the very task it is waiting for.
        try await withCheckedThrowingContinuation { continuation in
            let thread = Thread {
                do {
                    continuation.resume(returning: try restoreBlocking(
                        url: url, secret: secret, sections: sections, sources: sources,
                        staging: staging, progress: progress, isCancelled: isCancelled))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
            thread.stackSize = 4 << 20
            thread.start()
        }
    }

    private static func restoreBlocking(url: URL, secret: String, sections: Set<BackupSection>,
                                        sources: Sources, staging: URL, progress: Progress?,
                                        isCancelled: @escaping @Sendable () -> Bool) throws -> Report {
        let reader = try BackupFileReader(url: url)
        let header = reader.header
        guard let slot = header.slots.first(where: { $0.id == "default" }) ?? header.slots.first else {
            throw Failure.io("no key slot")
        }
        let master = try Crypto.argon2id(secret: Data(secret.utf8), salt: slot.salt, params: header.kdf)
        guard Crypto.constantTimeEquals(try Crypto.backupVerifier(master: master), slot.verifier) else {
            throw Failure.wrongSecret
        }

        try? FileManager.default.removeItem(at: staging)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: staging) }

        var context = RestoreContext(sources: sources, staging: staging,
                                     total: header.sections.filter { sections.contains(BackupSection(rawValue: $0.id) ?? .vault) }
                                        .reduce(0) { $0 + $1.plaintextBytes },
                                     progress: progress, isCancelled: isCancelled)
        for declared in header.sections {
            guard let section = BackupSection(rawValue: declared.id), sections.contains(section) else {
                try reader.skipSection()
                continue
            }
            if (section == .vault || section == .cookies), !(sources.vault?.keyboxIsUnlocked ?? false) {
                throw Failure.locked
            }
            try reader.readSection(declared.id, key: Crypto.backupSectionKey(master: master, sectionId: declared.id),
                                   onEntry: { entry, payload in context.take(entry, payload, in: section) },
                                   onVerdict: { verified, why in context.settle(verified, why) })
            if context.cancelled { throw Failure.cancelled }
            if section == .music { context.finishMusic() }
        }
        // Last, once the presets it names have been restored from the settings.
        if let blob = context.autoPresets { sources.presets?.restoreAutoApply(from: blob) }
        return context.report
    }
}

// MARK: - Restoring, entry by entry

/// Holds a restore's state between the reader's two callbacks: `take` stages an entry's
/// payload, `settle` commits or drops it once its hash is known to match.
private struct RestoreContext {
    let sources: Backup.Sources
    let staging: URL
    let total: Int64
    let progress: Backup.Progress?
    let isCancelled: @Sendable () -> Bool

    var report = Backup.Report()
    var cancelled = false
    private var done: Int64 = 0
    private var pending: Pending = .nothing

    /// Backup song id → the id it has here, so playlists point at the right tracks.
    private var songIds: [String: String] = [:]
    /// Renders restored before the track they were made from, which the library lists
    /// after them. Repointed once the section is in.
    private var unresolvedSources: [(track: String, source: String)] = []
    private var artwork: [String: URL] = [:]
    private(set) var autoPresets: [String: Any]?
    private var vaultIndex: DuplicateIndex<Vault.Item>?
    private var musicIndex: DuplicateIndex<MusicLibrary.Track>?

    private enum Pending {
        case nothing
        case skipped
        case vault(Staged)
        case track(Staged)
        case artwork(owner: String, Staged)
        case blob(id: String, Data)
        case cookie(BackupEntry, Data)
    }

    struct Staged {
        let entry: BackupEntry
        let file: URL
        let size: Int64
        let sha256: String
    }

    init(sources: Backup.Sources, staging: URL, total: Int64, progress: Backup.Progress?,
         isCancelled: @escaping @Sendable () -> Bool) {
        self.sources = sources
        self.staging = staging
        self.total = total
        self.progress = progress
        self.isCancelled = isCancelled
    }

    // MARK: Taking

    mutating func take(_ entry: BackupEntry, _ payload: BackupFileReader.Payload, in section: BackupSection) -> Bool {
        if isCancelled() {
            cancelled = true
            pending = .skipped
            return false
        }
        do {
            switch (section, entry.kind) {
            case (.vault, "media"):
                pending = .vault(try stage(entry, payload, extension: vaultExtension(entry)))
            case (.music, "media"):
                pending = .track(try stage(entry, payload, extension: (entry.name as NSString).pathExtension))
            case (.music, "thumbnail"):
                let owner = entry.string("ownerId") ?? ""
                pending = .artwork(owner: owner, try stage(entry, payload, extension: (entry.name as NSString).pathExtension))
            case (.music, "blob"), (.settings, "blob"):
                pending = .blob(id: entry.string("blobId") ?? "", try readAll(payload))
            case (.cookies, "cookie-profile"):
                pending = .cookie(entry, try readAll(payload))
            default:
                // Not something this side keeps. The reader drains the payload.
                pending = .skipped
            }
        } catch {
            pending = .nothing
            report.fail(String(describing: error))
        }
        return true
    }

    private mutating func stage(_ entry: BackupEntry, _ payload: BackupFileReader.Payload,
                                extension ext: String) throws -> Staged {
        let file = staging.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext.isEmpty ? "bin" : ext)
        guard FileManager.default.createFile(atPath: file.path, contents: nil, attributes: [.posixPermissions: 0o600]),
              let handle = try? FileHandle(forWritingTo: file) else {
            throw Backup.Failure.io(String(localized: "Could not make room for an item on its way in."))
        }
        defer { try? handle.close() }
        var hasher = SHA256()
        var size: Int64 = 0
        while true {
            let chunk = try payload.read()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
            try handle.write(contentsOf: chunk)
            size += Int64(chunk.count)
            done += Int64(chunk.count)
            progress?(done, total)
        }
        return Staged(entry: entry, file: file, size: size,
                      sha256: hasher.finalize().map { String(format: "%02x", $0) }.joined())
    }

    private mutating func readAll(_ payload: BackupFileReader.Payload) throws -> Data {
        var data = Data()
        while true {
            let chunk = try payload.read()
            if chunk.isEmpty { return data }
            data.append(chunk)
            done += Int64(chunk.count)
        }
    }

    private func vaultExtension(_ entry: BackupEntry) -> String {
        let fromName = (entry.name as NSString).pathExtension
        if !fromName.isEmpty, UTType(filenameExtension: fromName) != nil { return fromName }
        return entry.string("mimeType").flatMap { UTType(mimeType: $0)?.preferredFilenameExtension } ?? "mp4"
    }

    // MARK: Settling

    mutating func settle(_ verified: Bool, _ why: String) {
        let current = pending
        pending = .nothing
        guard verified else {
            if case .vault(let staged) = current { try? FileManager.default.removeItem(at: staged.file) }
            if case .track(let staged) = current { try? FileManager.default.removeItem(at: staged.file) }
            if case .artwork(_, let staged) = current { try? FileManager.default.removeItem(at: staged.file) }
            report.fail(why)
            return
        }
        do {
            switch current {
            case .nothing, .skipped:
                break
            case .vault(let staged):
                try commitVault(staged)
            case .track(let staged):
                try commitTrack(staged)
            case .artwork(let owner, let staged):
                if owner.isEmpty { try? FileManager.default.removeItem(at: staged.file) } else { artwork[owner] = staged.file }
            case .blob(let id, let data):
                try commitBlob(id, data)
            case .cookie(let entry, let data):
                try commitCookie(entry, data)
            }
        } catch {
            report.fail(String(describing: error))
        }
    }

    private mutating func commitVault(_ staged: Staged) throws {
        defer { try? FileManager.default.removeItem(at: staged.file) }
        guard let vault = sources.vault else { return }
        if vaultIndex == nil {
            vaultIndex = DuplicateIndex(items: (try? vault.items()) ?? [], size: \.sizeBytes) { item in
                guard let reader = try? vault.reader(for: item.id) else { return nil }
                var hasher = SHA256()
                var offset: Int64 = 0
                while offset < reader.size {
                    guard let chunk = try? reader.read(offset: offset, length: 1 << 20), !chunk.isEmpty else { return nil }
                    hasher.update(data: chunk)
                    offset += Int64(chunk.count)
                }
                return hasher.finalize().map { String(format: "%02x", $0) }.joined()
            }
        }
        if vaultIndex?.match(size: staged.size, sha256: staged.sha256) != nil {
            report.duplicates += 1
            return
        }
        // The name is a title; the phone's carry no extension, a file name would.
        let ext = staged.file.pathExtension
        var title = staged.entry.name
        if !ext.isEmpty, title.lowercased().hasSuffix("." + ext.lowercased()) { title = String(title.dropLast(ext.count + 1)) }
        let item = try vault.add(staged.file, title: title.isEmpty ? String(localized: "Restored item") : title,
                                 removeOriginal: true)
        vaultIndex?.add(item, size: staged.size, sha256: staged.sha256)
        report.restored += 1
    }

    private mutating func commitTrack(_ staged: Staged) throws {
        guard let library = sources.library else {
            try? FileManager.default.removeItem(at: staged.file)
            return
        }
        let songId = staged.entry.string("songId")
        let art = songId.flatMap { artwork.removeValue(forKey: $0) }
        defer { if let art { try? FileManager.default.removeItem(at: art) } }

        if musicIndex == nil {
            musicIndex = DuplicateIndex(items: library.load().tracks, size: \.sizeBytes) { track in
                Self.sha256(of: library.fileURL(for: track))
            }
        }
        if let existing = musicIndex?.match(size: staged.size, sha256: staged.sha256) {
            // Still mapped: a playlist in this backup may name it.
            if let songId { songIds[songId] = existing.id }
            try? FileManager.default.removeItem(at: staged.file)
            report.duplicates += 1
            return
        }

        // A clean file name for the library, from the one the backup recorded.
        let name = (staged.entry.name as NSString).lastPathComponent
        let named = staging.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: named, withIntermediateDirectories: true)
        let target = named.appendingPathComponent(name.isEmpty ? staged.file.lastPathComponent : name)
        try FileManager.default.moveItem(at: staged.file, to: target)

        let entry = staged.entry
        let source = entry.string("sourceSongId").flatMap { songIds[$0] ?? $0 }
        let sourceIsLocal = entry.string("sourceSongId").map { songIds[$0] != nil } ?? true
        let track = try Self.wait {
            try await library.adopt(target, title: entry.string("title"), artist: entry.string("artist"),
                                    artwork: art, move: true,
                                    presetId: entry.string("presetId"), sourceSongId: source)
        }
        try? FileManager.default.removeItem(at: named)
        if let songId { songIds[songId] = track.id }
        if !sourceIsLocal, let source { unresolvedSources.append((track.id, source)) }
        musicIndex?.add(track, size: staged.size, sha256: staged.sha256)
        report.restored += 1
    }

    private mutating func commitBlob(_ id: String, _ data: Data) throws {
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        switch id {
        case "music-index":
            restorePlaylists(object["playlists"] as? [[String: Any]] ?? [])
            report.applied += 1
        case "auto-presets":
            autoPresets = object
            report.applied += 1
        case "app-settings":
            sources.presets?.restoreSettings(from: object)
            report.applied += 1
        default:
            break
        }
    }

    /// Favourites by membership, the rest by name: a playlist of the same name is reused,
    /// so a restore run twice does not make two of everything.
    private mutating func restorePlaylists(_ playlists: [[String: Any]]) {
        guard let library = sources.library else { return }
        for playlist in playlists {
            let mapped = (playlist["songIds"] as? [String] ?? []).compactMap { songIds[$0] }
            guard !mapped.isEmpty else { continue }
            if playlist["system"] as? Bool == true || playlist["id"] as? String == MusicLibrary.favoritesId {
                mapped.forEach { library.setFavorite($0, true) }
                continue
            }
            let name = (playlist["name"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { continue }
            let existing = library.load().playlists.first {
                !$0.isSystem && $0.name.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(name) == .orderedSame
            }
            let target = existing ?? library.createPlaylist(named: name)
            let have = Set(target.trackIds)
            library.add(mapped.filter { !have.contains($0) }, to: target.id)
        }
    }

    private mutating func commitCookie(_ entry: BackupEntry, _ data: Data) throws {
        guard let cookies = sources.cookies,
              let scope = CookieStore.Scope(folderName: entry.string("platform") ?? "") else { return }
        let name = CookieStore.sanitizedName(entry.string("profileName") ?? "main")
        if cookies.exists(scope, name: name) {
            report.existing += 1
            return
        }
        try cookies.store(scope, name: name, plaintext: data,
                          makeDefault: (entry.meta["isDefault"] as? Bool) == true ? true : nil)
        report.applied += 1
    }

    /// Repoints renders at their sources by the ids those have here. A source that never
    /// arrived keeps the backup's id, which matches nothing: the render stays, unlinked.
    mutating func finishMusic() {
        for link in unresolvedSources {
            if let mapped = songIds[link.source] { try? sources.library?.setSource(of: link.track, to: mapped) }
        }
        unresolvedSources = []
    }

    // MARK: Helpers

    static func sha256(of url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try? handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// Runs async work to completion from the restore's own thread, which is not one of the
    /// shared pool's, so waiting here cannot starve it.
    static func wait<T: Sendable>(_ work: @escaping @Sendable () async throws -> T) throws -> T {
        let semaphore = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var result: Result<T, Error>?
        Task.detached {
            do { result = .success(try await work()) } catch { result = .failure(error) }
            semaphore.signal()
        }
        semaphore.wait()
        return try result!.get()
    }
}

/// "Is this already here?" by size first, and by content only where a size matches: hashing
/// the whole vault up front would mean decrypting all of it before restoring anything.
private struct DuplicateIndex<Item> {
    private var bySize: [Int64: [Item]] = [:]
    private var hashes: [Int64: [(String, Item)]] = [:]
    private let hash: (Item) -> String?

    init(items: [Item], size: KeyPath<Item, Int64>, hash: @escaping (Item) -> String?) {
        for item in items { bySize[item[keyPath: size], default: []].append(item) }
        self.hash = hash
    }

    mutating func match(size: Int64, sha256: String) -> Item? {
        guard let candidates = bySize[size] else { return nil }
        if hashes[size] == nil {
            hashes[size] = candidates.compactMap { item in hash(item).map { ($0, item) } }
        }
        return hashes[size]?.first { $0.0 == sha256 }?.1
    }

    mutating func add(_ item: Item, size: Int64, sha256: String) {
        bySize[size, default: []].append(item)
        hashes[size, default: []].append((sha256, item))
    }
}
