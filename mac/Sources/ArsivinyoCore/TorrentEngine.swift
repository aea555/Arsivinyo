import ArsivinyoTorrentC
import Foundation
import SystemConfiguration

/// Torrents on the Mac (`shared/watch/CONTRACT.md`, "The torrent engine"): one engine session
/// over shared/torrent, the same C++ and libtorrent the phone runs, with its state and its
/// cache under the app's support folder. Nothing here logs a torrent's name or files.
public final class TorrentEngine: @unchecked Sendable {

    /// This device's rules; the phone keeps the same shape in its own settings.json.
    public struct Settings: Codable, Equatable, Sendable {
        /// Stop seeding at this ratio; 0 is "never seed".
        public var seedRatio: Double = 1.0
        public var cacheLimitBytes: Int64 = 10 << 30
        /// "Don't show again" on the heads-up about IP addresses.
        public var headsUpDismissed = false

        public init() {}
    }

    public struct Failure: Error, CustomStringConvertible {
        public let code: String
        public var description: String { code }
    }

    public struct Stream: Sendable {
        public var id: String
        public var file: Int
        public var url: URL
        public var size: Int64
    }

    private let root: URL
    private let lock = NSLock()
    private var session: OpaquePointer?
    private var serverBase: String?

    public var cacheFolder: URL { root.appendingPathComponent("cache", isDirectory: true) }
    private var stateFolder: URL { root.appendingPathComponent("state", isDirectory: true) }
    /// Where downloads are fetched to before they are filed.
    private var downloadsFolder: URL { root.appendingPathComponent("downloads", isDirectory: true) }
    private var settingsFile: URL { root.appendingPathComponent("settings.json") }

    public init(root: URL) {
        self.root = root
    }

    deinit { stop() }

    // MARK: - Settings

    public func settings() -> Settings {
        lock.withLock {
            (try? JSONDecoder().decode(Settings.self, from: Data(contentsOf: settingsFile))) ?? Settings()
        }
    }

    public func setSettings(_ settings: Settings) throws {
        try lock.withLock {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try JSONEncoder().encode(settings).write(to: settingsFile, options: .atomic)
            if let session { apply(settings, to: session) }
        }
    }

    private func withSettings<T>(_ settings: Settings, _ body: (inout at_settings) -> T) -> T {
        stateFolder.path.withCString { state in
            var s = at_settings(state_dir: state, listen_port: 0, seed_ratio: settings.seedRatio, upload_allowed: 1,
                                discovery: 1, upload_limit: 0, download_limit: 0)
            return body(&s)
        }
    }

    private func apply(_ settings: Settings, to session: OpaquePointer) {
        withSettings(settings) { at_session_apply(session, &$0) }
    }

    // MARK: - The session

    /// The session, started on first use; it resumes whatever was there before.
    public func handle() throws -> OpaquePointer {
        try lock.withLock {
            if let session { return session }
            try FileManager.default.createDirectory(at: stateFolder, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: cacheFolder, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: downloadsFolder, withIntermediateDirectories: true)
            let settings = (try? JSONDecoder().decode(Settings.self, from: Data(contentsOf: settingsFile))) ?? Settings()
            var error = [CChar](repeating: 0, count: 256)
            let made = withSettings(settings) { at_session_create(&$0, &error) }
            guard let made else { throw Failure(code: "TORRENT_UNAVAILABLE") }
            session = made
            removeUnrecorded(made, library)  // the lock is held here: passed, not taken
            return made
        }
    }

    /// Saves everything's resume data and stops.
    public func stop() {
        lock.withLock {
            if let session { at_session_destroy(session) }
            session = nil
            serverBase = nil
        }
    }

    public func status() throws -> [[String: Any]] {
        guard let raw = at_status(try handle()) else { return [] }
        defer { at_free(raw) }
        return (try? JSONSerialization.jsonObject(with: Data(String(cString: raw).utf8)) as? [[String: Any]]) ?? []
    }

    // MARK: - Streaming

    /// A torrent's file, ready for the player: fetched into the cache (or played from a
    /// download already here), its metadata waited for, the cache trimmed to its limit
    /// around it. Blocks while metadata comes: call it off the main actor.
    public func stream(magnet: String, fileIdx: Int?, filename: String?) throws -> Stream {
        let session = try handle()
        var code: Int32 = 0
        let raw = cacheFolder.path.withCString { cache in
            filename.withOptionalCString { hint in
                at_stream(session, magnet, cache, Int32(fileIdx ?? -1), hint, 90_000, &code)
            }
        }
        guard let raw else { throw Failure(code: code == Int32(AT_NO_METADATA) ? "TORRENT_NO_METADATA" : "TORRENT_FAILED") }
        defer { at_free(raw) }
        guard let result = try? JSONSerialization.jsonObject(with: Data(String(cString: raw).utf8)) as? [String: Any],
              let id = result["id"] as? String, let file = (result["file"] as? NSNumber)?.intValue,
              let path = result["path"] as? String else { throw Failure(code: "TORRENT_FAILED") }
        _ = at_cache_trim(session, cacheFolder.path, settings().cacheLimitBytes, id)
        let base = try lock.withLock { () throws -> String in
            if let serverBase { return serverBase }
            guard let raw = at_server_start(session) else { throw Failure(code: "TORRENT_FAILED") }
            defer { at_free(raw) }
            serverBase = String(cString: raw)
            return serverBase!
        }
        // The file's name at the end, so the player sees its extension.
        let name = (path as NSString).lastPathComponent.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "file"
        guard let url = URL(string: "\(base)/\(id)/\(file)/\(name)") else { throw Failure(code: "TORRENT_FAILED") }
        return Stream(id: id, file: file, url: url, size: (result["size"] as? NSNumber)?.int64Value ?? 0)
    }

    // MARK: - Downloads

    /// Files a finished file where it belongs; false to try again later (a locked vault).
    public struct Taker: Sendable {
        public var filePublic: @Sendable (URL, String) -> Bool
        public var intoVault: @Sendable (URL, String) -> Bool
        public init(filePublic: @escaping @Sendable (URL, String) -> Bool, intoVault: @escaping @Sendable (URL, String) -> Bool) {
            self.filePublic = filePublic
            self.intoVault = intoVault
        }
    }

    private var library: WatchLibrary?
    private var taker: Taker?
    private var holdUntilChosen: Set<String> = []

    /// The encrypted library the records live in, and the filing step.
    public func attach(library: WatchLibrary, taker: Taker) {
        lock.withLock {
            self.library = library
            self.taker = taker
        }
    }

    private func records() throws -> WatchLibrary {
        guard let library = lock.withLock({ library }) else { throw Failure(code: "TORRENT_UNAVAILABLE") }
        return library
    }

    private func json(_ raw: UnsafeMutablePointer<CChar>?) -> Any? {
        guard let raw else { return nil }
        defer { at_free(raw) }
        return try? JSONSerialization.jsonObject(with: Data(String(cString: raw).utf8))
    }

    /// Starts a download from a magnet or a .torrent's bytes; nothing but its metadata is
    /// fetched until the files are chosen.
    public func add(magnet: String? = nil, torrent: Data? = nil) throws -> String {
        let session = try handle()
        var id = [CChar](repeating: 0, count: 65)
        let code: Int32
        if let magnet {
            code = at_add_magnet(session, magnet, downloadsFolder.path, 0, &id)
        } else if let torrent {
            code = torrent.withUnsafeBytes { bytes in
                at_add_torrent(session, bytes.bindMemory(to: UInt8.self).baseAddress, torrent.count, downloadsFolder.path, 0, &id)
            }
        } else {
            throw Failure(code: "TORRENT_BAD_INPUT")
        }
        guard code == 0 else { throw Failure(code: "TORRENT_BAD_INPUT") }
        let infoHash = String(cString: id)
        do {
            if try records().torrent(infoHash) == nil {
                // Held from the start: nothing but its file list until the files are chosen.
                _ = at_hold(session, infoHash, 1)
                try records().putTorrent(WatchLibrary.Torrent(infoHash: infoHash, name: "", wanted: [], destination: "public",
                                                              addedAt: Int64(Date().timeIntervalSince1970 * 1000)))
                lock.withLock { _ = holdUntilChosen.insert(infoHash) }
            }
        } catch {
            // Never a torrent in the engine without its record: it would download unseen.
            _ = at_remove(session, infoHash, 1)
            throw error
        }
        return infoHash
    }

    public struct File: Sendable, Identifiable {
        public var index: Int
        public var path: String
        public var size: Int64
        public var id: Int { index }
    }

    /// A torrent's files once its metadata is here; nil while it is fetched.
    public func files(_ id: String) throws -> [File]? {
        guard let list = json(at_files(try handle(), id)) as? [[String: Any]] else { return nil }
        return list.compactMap { f in
            guard let index = (f["index"] as? NSNumber)?.intValue, let path = f["path"] as? String else { return nil }
            return File(index: index, path: path, size: (f["size"] as? NSNumber)?.int64Value ?? 0)
        }
    }

    private func setPriorities(_ session: OpaquePointer, _ id: String, count: Int, _ priority: (Int) -> UInt8) {
        let values = (0..<count).map(priority)
        _ = at_set_priorities(session, id, values, Int32(values.count))
    }

    /// Held once the files are known, until they are chosen; whether they are known. Held,
    /// not paused, nor every file skipped: either of those drops the peers that just sent the
    /// file list, and the download would wait for them to be found again.
    private func holdBack(_ session: OpaquePointer, _ id: String) -> Bool {
        guard (try? files(id)) != nil else { return false }
        _ = at_hold(session, id, 1)
        return true
    }

    /// The user's choice: which files, and whether they go into the vault.
    public func choose(_ id: String, wanted: [Int], destination: String) throws {
        let session = try handle()
        guard let files = try files(id) else { throw Failure(code: "TORRENT_NO_METADATA") }
        _ = lock.withLock { holdUntilChosen.remove(id) }
        let chosen = Set(wanted)
        setPriorities(session, id, count: (files.map(\.index).max() ?? -1) + 1) { chosen.contains($0) ? 4 : 0 }
        _ = at_hold(session, id, 0)
        _ = at_resume(session, id)
        var record = try records().torrent(id)
            ?? WatchLibrary.Torrent(infoHash: id, name: "", wanted: [], destination: destination, addedAt: Int64(Date().timeIntervalSince1970 * 1000))
        record.name = engineState(id)?["name"] as? String ?? record.name
        record.wanted = wanted
        record.destination = destination
        record.state = "downloading"
        try records().putTorrent(record)
    }

    public func pause(_ id: String) throws { _ = at_pause(try handle(), id) }
    public func resume(_ id: String) throws { _ = at_resume(try handle(), id) }

    /// Removes a download; with `deleteFiles`, what it fetched too (filed copies stay).
    public func remove(_ id: String, deleteFiles: Bool) throws {
        _ = at_remove(try handle(), id, deleteFiles ? 1 : 0)
        try records().removeTorrent(id)
    }

    private func engineState(_ id: String) -> [String: Any]? {
        (try? status())?.first { $0["id"] as? String == id }
    }

    /// Downloads with their records and what the engine says about each now.
    public func downloads() throws -> [(record: WatchLibrary.Torrent, engine: [String: Any]?)] {
        let records = try records().torrents()
        let engine = records.isEmpty ? [] : (try? status()) ?? []
        return records.sorted { $0.addedAt > $1.addedAt }.map { record in
            (record, engine.first { $0["id"] as? String == record.infoHash })
        }
    }

    /// One pass of the filing work: hold back what is still to be chosen, file what has
    /// finished, let go of what is done. The app calls it every couple of seconds.
    public func work() {
        guard let records = try? records().torrents(), !records.isEmpty, let session = try? handle(),
              let taker = lock.withLock({ taker }) else { return }
        for record in records { step(session, record, taker) }
    }

    /// A torrent in the downloads folder with no record (an add that failed halfway, a crash
    /// between the two) would download unseen: it goes, with what it fetched. Run as a
    /// session starts, which is when such a torrent comes back from its resume data.
    private func removeUnrecorded(_ session: OpaquePointer, _ library: WatchLibrary?) {
        guard let library, let records = try? library.torrents(),
              let all = json(at_status(session)) as? [[String: Any]] else { return }
        let known = Set(records.map(\.infoHash))
        for torrent in all {
            guard let id = torrent["id"] as? String, !known.contains(id),
                  torrent["savePath"] as? String == downloadsFolder.path else { continue }
            _ = at_remove(session, id, 1)
        }
    }

    private func step(_ session: OpaquePointer, _ record: WatchLibrary.Torrent, _ taker: Taker) {
        let id = record.infoHash
        if record.state == "choosing" {
            if lock.withLock({ holdUntilChosen.contains(id) }), holdBack(session, id), record.name.isEmpty,
               let name = engineState(id)?["name"] as? String, !name.isEmpty {
                var named = record
                named.name = name
                try? records().putTorrent(named)
            }
            return
        }
        guard let progress = json(at_file_progress(session, id)) as? [[String: Any]] else { return }
        var current = record
        let count = (progress.compactMap { ($0["index"] as? NSNumber)?.intValue }.max() ?? -1) + 1
        for file in progress {
            guard let index = (file["index"] as? NSNumber)?.intValue, record.wanted.contains(index),
                  !current.taken.contains(index),
                  ((file["done"] as? NSNumber)?.int64Value ?? 0) >= ((file["size"] as? NSNumber)?.int64Value ?? 1),
                  let raw = at_file_path(session, id, Int32(index)) else { continue }
            let path = String(cString: raw)
            at_free(raw)
            let source = URL(fileURLWithPath: path)
            let relative = String(path.dropFirst(downloadsFolder.path.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let filed = record.destination == "private" ? taker.intoVault(source, source.lastPathComponent)
                                                        : taker.filePublic(source, relative)
            guard filed else { continue }
            current.taken.insert(index)
            if record.destination == "private" {
                // In the vault now: the plaintext goes, and the torrent stops serving it.
                let remaining = Set(record.wanted).subtracting(current.taken)
                setPriorities(session, id, count: count) { remaining.contains($0) ? 4 : 0 }
                try? FileManager.default.removeItem(at: source)
            }
            try? records().putTorrent(current)
        }
        let allTaken = !record.wanted.isEmpty && current.taken.isSuperset(of: record.wanted)
        if allTaken, current.state != "done" {
            current.state = "done"
            try? records().putTorrent(current)
        }
        // Done and no longer seeding: what was fetched goes; the filed copies stay.
        if allTaken, record.destination == "private" || engineState(id)?["paused"] as? Bool == true {
            _ = at_remove(session, id, 1)
        }
    }

    // MARK: - The heads-up

    /// Whether a VPN appears to be up: a `utun` interface carrying the default route
    /// (CONTRACT.md). It can be wrong either way, which is why the notice says "appears".
    public static func vpnAppearsActive() -> Bool {
        guard let store = SCDynamicStoreCreate(nil, "Arsivinyo" as CFString, nil, nil) else { return false }
        for key in ["State:/Network/Global/IPv4", "State:/Network/Global/IPv6"] {
            if let value = SCDynamicStoreCopyValue(store, key as CFString) as? [String: Any],
               let primary = value["PrimaryInterface"] as? String, primary.hasPrefix("utun") {
                return true
            }
        }
        return false
    }
}

private extension Optional where Wrapped == String {
    func withOptionalCString<T>(_ body: (UnsafePointer<CChar>?) -> T) -> T {
        switch self {
        case .some(let text): return text.withCString(body)
        case .none: return body(nil)
        }
    }
}
