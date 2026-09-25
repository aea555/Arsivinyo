import Foundation

/// Cookie files, for downloads that need to be signed in.
///
/// Most sites rate-limit or refuse an anonymous downloader: a 403 on the first try, or a ban
/// after a handful. A cookie file is a signed-in session, so it is kept encrypted under a key
/// from the key box and only ever decrypted for the one download that uses it.
///
/// The model is the phone's. A site has any number of named profiles, one of them the
/// default, and a domain the engine does not know can have profiles of its own. A backup
/// carries profiles by site and name, so the two apps can exchange them.
///
/// The format is Netscape cookies.txt, which every browser extension exports.
public final class CookieStore: @unchecked Sendable {

    /// The sites the engine can match a link to. Mirrors `COOKIE_PLATFORMS` in
    /// `shared/engine/local_downloader.py`; the ids are wire values a backup records.
    public static let platforms: [(id: String, label: String, domains: [String])] = [
        ("youtube", "YouTube", ["youtube.com", "youtu.be"]),
        ("twitter", "X / Twitter", ["twitter.com", "x.com"]),
        ("instagram", "Instagram", ["instagram.com"]),
        ("facebook", "Facebook", ["facebook.com", "fb.watch"]),
        ("reddit", "Reddit", ["reddit.com", "v.redd.it"]),
        ("tiktok", "TikTok", ["tiktok.com", "vm.tiktok.com"]),
    ]

    /// Sites where a refused download is not retried without cookies: signed out, they
    /// answer with a login wall that reads as success and saves the wrong thing. The phone
    /// keeps the same list.
    public static let strictPlatforms: Set<String> = ["instagram", "facebook", "tiktok", "reddit"]

    /// What a profile belongs to: a known site, or a domain the user named.
    ///
    /// Stored as one folder name. Site ids have no dots and domains always do, so the two
    /// cannot collide.
    public enum Scope: Hashable, Sendable, Identifiable {
        case platform(String)
        case domain(String)

        public var id: String { folderName }

        var folderName: String {
            switch self {
            case .platform(let id): return id
            case .domain(let domain): return domain
            }
        }

        init?(folderName: String) {
            if CookieStore.platforms.contains(where: { $0.id == folderName }) {
                self = .platform(folderName)
            } else if folderName.contains("."), let domain = CookieStore.canonicalDomain(folderName) {
                self = .domain(domain)
            } else {
                return nil
            }
        }

        public var label: String {
            switch self {
            case .platform(let id): return CookieStore.platforms.first { $0.id == id }?.label ?? id
            case .domain(let domain): return domain
            }
        }
    }

    public struct Profile: Hashable, Sendable, Identifiable {
        public let scope: Scope
        public let name: String
        public let isDefault: Bool
        public let importedAt: Date

        public var id: String { scope.folderName + "/" + name }
    }

    public enum Failure: Error, Equatable, CustomStringConvertible {
        case notCookies
        case badName
        case badDomain
        case noDomainFound
        case locked
        case missing

        public var description: String {
            switch self {
            case .notCookies:
                return String(localized: "That is not a cookies.txt file. Export one in Netscape format.")
            case .badName:
                return String(localized: "Use letters, digits, spaces, dashes or underscores, up to 40 of them.")
            case .badDomain:
                return String(localized: "That is not a domain. Write it like example.com.")
            case .noDomainFound:
                return String(localized: "The file names no domain. Say which site it is for.")
            case .locked:
                return String(localized: "Unlock first, so the cookie file can be encrypted.")
            case .missing:
                return String(localized: "That cookie profile is gone.")
            }
        }
    }

    private let root: URL
    private let runtimeRoot: URL
    private let keybox: Keybox

    /// - Parameters:
    ///   - directory: The app's data folder. Profiles go in `cookies/`, and the decrypted
    ///     copies a download uses in `cookie_runtime/`, as on the phone.
    public init(directory: URL, keybox: Keybox) {
        root = directory.appendingPathComponent("cookies", isDirectory: true)
        runtimeRoot = directory.appendingPathComponent("cookie_runtime", isDirectory: true)
        self.keybox = keybox
        // Anything a crash left behind is a signed-in session in plain text.
        sweepRuntime()
    }

    // MARK: - Listing

    /// Needs no key: names, the default and a date are all on disk in the clear. The
    /// contents are what is encrypted.
    public func profiles() -> [Profile] {
        let fm = FileManager.default
        guard let folders = try? fm.contentsOfDirectory(atPath: root.path) else { return [] }
        var result: [Profile] = []
        for folder in folders {
            guard let scope = Scope(folderName: folder) else { continue }
            let dir = root.appendingPathComponent(folder, isDirectory: true)
            let chosen = defaultName(in: dir)
            let files = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
            for file in files where file.pathExtension == "enc" {
                let name = file.deletingPathExtension().lastPathComponent
                let date = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?
                    .contentModificationDate ?? .distantPast
                result.append(Profile(scope: scope, name: name, isDefault: name == chosen, importedAt: date))
            }
        }
        return result.sorted { ($0.scope.folderName, $0.name) < ($1.scope.folderName, $1.name) }
    }

    public func exists(_ scope: Scope, name: String) -> Bool {
        FileManager.default.fileExists(atPath: file(scope, name).path)
    }

    // MARK: - Changing

    /// Encrypts a cookies.txt into place.
    ///
    /// A file that is not in Netscape format is refused here rather than at download time,
    /// where it would surface as a sign-in that silently did not happen.
    public func importFile(_ url: URL, into scope: Scope, name: String) throws {
        let data = try Data(contentsOf: url)
        guard Self.cookieCount(in: data) > 0 else { throw Failure.notCookies }
        try store(scope, name: name, plaintext: data, makeDefault: nil)
    }

    /// The domains a cookie file is for, most-mentioned first, so a file for a site the
    /// engine does not know can be filed under it without asking.
    public static func domains(in data: Data) -> [String] {
        var counts: [String: Int] = [:]
        for line in lines(of: data) {
            let text = line.hasPrefix("#HttpOnly_") ? line.dropFirst(10) : line
            let fields = text.split(separator: "\t", omittingEmptySubsequences: false)
            guard !text.hasPrefix("#"), fields.count >= 7,
                  let domain = canonicalDomain(String(fields[0])) else { continue }
            counts[domain, default: 0] += 1
        }
        return counts.sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }.map(\.key)
    }

    /// Where a file's cookies belong: the known site they are for, else their main domain.
    public static func scope(forCookies data: Data) -> Scope? {
        let domains = domains(in: data)
        for domain in domains {
            if let platform = platform(forHost: domain) { return .platform(platform) }
        }
        return domains.first.map(Scope.domain)
    }

    /// Stores decrypted cookies under a name. Used by import and by a restore.
    ///
    /// - Parameter makeDefault: true or false to set it; nil makes it the default only when
    ///   it is the first profile for its scope, which is what the phone does.
    public func store(_ scope: Scope, name: String, plaintext: Data, makeDefault: Bool?) throws {
        guard Self.isValidName(name) else { throw Failure.badName }
        guard keybox.isUnlocked else { throw Failure.locked }
        let key = try keybox.key(for: .cookies)
        let sealed = try Crypto.seal(plaintext, key: key, associatedData: Self.associatedData(scope, name))

        let dir = root.appendingPathComponent(scope.folderName, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let firstHere = profiles().allSatisfy { $0.scope != scope }
        try FileManager.default.writePrivately(sealed, to: file(scope, name))

        if makeDefault ?? firstHere { try setDefault(scope, name: name) }
    }

    public func setDefault(_ scope: Scope, name: String) throws {
        guard exists(scope, name: name) else { throw Failure.missing }
        let marker = root.appendingPathComponent(scope.folderName).appendingPathComponent("default")
        try Data(name.utf8).write(to: marker, options: .atomic)
    }

    /// Removes a profile. Removing the default hands it to the newest one left, so a site
    /// with profiles always has one that is used.
    public func remove(_ scope: Scope, name: String) throws {
        try FileManager.default.removeItem(at: file(scope, name))
        let dir = root.appendingPathComponent(scope.folderName, isDirectory: true)
        let left = profiles().filter { $0.scope == scope }
        if left.isEmpty {
            try? FileManager.default.removeItem(at: dir)
        } else if defaultName(in: dir) == name {
            let newest = left.max { $0.importedAt < $1.importedAt }!
            try setDefault(scope, name: newest.name)
        }
    }

    /// The decrypted cookie file, for a backup.
    public func plaintext(_ scope: Scope, name: String) throws -> Data {
        guard exists(scope, name: name) else { throw Failure.missing }
        guard keybox.isUnlocked else { throw Failure.locked }
        let key = try keybox.key(for: .cookies)
        return try Crypto.open(Data(contentsOf: file(scope, name)), key: key,
                               associatedData: Self.associatedData(scope, name))
    }

    // MARK: - For a download

    /// A decrypted copy of the profile a link should use, in a fresh private folder.
    ///
    /// Returns nil when there is nothing to use, including when the vault is locked: a
    /// download then runs signed out rather than failing, which is what the phone does too.
    /// The caller deletes the folder once the download ends, through `discardRuntime`.
    ///
    /// - Parameter requested: a profile name the user picked for this download, if any.
    public func runtimeCookies(for url: String, requested: String? = nil) throws -> (file: URL, platform: String?)? {
        guard keybox.isUnlocked, let host = Self.host(of: url) else { return nil }
        let candidates = profiles()

        let platform = Self.platform(forHost: host)
        let scope: Scope
        if let platform {
            scope = .platform(platform)
        } else {
            // The longest matching domain wins, so a profile for music.example.com beats one
            // for example.com on its own subdomain.
            let matching = Set(candidates.compactMap { profile -> String? in
                guard case .domain(let domain) = profile.scope,
                      host == domain || host.hasSuffix("." + domain) else { return nil }
                return domain
            })
            guard let domain = matching.max(by: { $0.count < $1.count }) else { return nil }
            scope = .domain(domain)
        }

        let here = candidates.filter { $0.scope == scope }
        let chosen: Profile?
        if let requested {
            chosen = here.first { $0.name.caseInsensitiveCompare(requested) == .orderedSame }
            if chosen == nil { throw Failure.missing }
        } else {
            chosen = here.first(where: \.isDefault) ?? here.max { $0.importedAt < $1.importedAt }
        }
        guard let chosen else { return nil }

        let plain = try plaintext(chosen.scope, name: chosen.name)
        let dir = runtimeRoot.appendingPathComponent(try Crypto.randomBytes(8).hex, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let out = dir.appendingPathComponent("cookie.txt")
        try FileManager.default.writePrivately(plain, to: out)
        return (out, platform)
    }

    /// Deletes one download's decrypted copy.
    public func discardRuntime(_ file: URL) {
        let dir = file.deletingLastPathComponent()
        guard dir.deletingLastPathComponent().standardizedFileURL == runtimeRoot.standardizedFileURL else { return }
        try? FileManager.default.removeItem(at: dir)
    }

    /// Deletes every decrypted copy.
    public func sweepRuntime() {
        try? FileManager.default.removeItem(at: runtimeRoot)
    }

    /// Whether a download refused while signed in is worth one more try signed out.
    ///
    /// The phone's rule. An expired session fails where no session would not, so a generic
    /// failure is retried. Not on strict sites, where signed out is a login wall that looks
    /// like success, and not when the engine has already said what went wrong.
    public static func shouldRetrySignedOut(code: String?, message: String, platform: String?) -> Bool {
        if let platform, strictPlatforms.contains(platform) { return false }
        switch code {
        case "DOWNLOAD_CANCELLED", "FILE_TOO_LARGE", "COOKIE_STALE_OR_INVALID":
            return false
        case "PREFLIGHT_FAILED", "DOWNLOAD_FAILED", "INTERNAL_ERROR":
            return true
        default:
            let text = message.lowercased()
            return text.contains("cookie") || text.contains("sign in") || text.contains("login")
        }
    }

    // MARK: - Matching

    public static func platform(forHost host: String) -> String? {
        platforms.first { platform in
            platform.domains.contains { host == $0 || host.hasSuffix("." + $0) }
        }?.id
    }

    static func host(of url: String) -> String? {
        let raw = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty,
              let host = URLComponents(string: raw.contains("://") ? raw : "https://" + raw)?.host?.lowercased()
        else { return nil }
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    /// The phone's rule: lower case, no leading dot or www, letters, digits, dots and dashes.
    public static func canonicalDomain(_ value: String) -> String? {
        var text = value.trimmingCharacters(in: .whitespaces).lowercased()
        while text.hasPrefix(".") { text.removeFirst() }
        guard !text.isEmpty, !text.contains("://"), !text.contains("/"),
              !text.contains("?"), !text.contains("#") else { return nil }
        if text.hasPrefix("www.") { text.removeFirst(4) }
        text = text.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !text.isEmpty, !text.contains(".."),
              text.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-") })
        else { return nil }
        return text
    }

    /// A name from elsewhere, a backup from the phone, made into one this store accepts:
    /// anything else becomes a dash, and an empty result becomes "main".
    public static func sanitizedName(_ name: String) -> String {
        let mapped = String(name.map { $0.isLetter || $0.isNumber || $0 == " " || $0 == "-" || $0 == "_" ? $0 : "-" })
            .trimmingCharacters(in: .whitespaces)
        let cut = String(mapped.prefix(40)).trimmingCharacters(in: .whitespaces)
        return cut.isEmpty ? "main" : cut
    }

    public static func isValidName(_ name: String) -> Bool {
        (1...40).contains(name.count) && name.trimmingCharacters(in: .whitespaces) == name
            && name.allSatisfy { $0.isLetter || $0.isNumber || $0 == " " || $0 == "-" || $0 == "_" }
    }

    // MARK: - Private

    private func file(_ scope: Scope, _ name: String) -> URL {
        root.appendingPathComponent(scope.folderName, isDirectory: true)
            .appendingPathComponent(name + ".enc")
    }

    private func defaultName(in dir: URL) -> String? {
        (try? String(contentsOf: dir.appendingPathComponent("default"), encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The scope and name are bound in, so one profile's file renamed into another's place
    /// fails to open instead of signing in as the wrong account.
    private static func associatedData(_ scope: Scope, _ name: String) -> String {
        "cookies/" + scope.folderName + "/" + name
    }

    private static func lines(of data: Data) -> [Substring] {
        String(decoding: data, as: UTF8.self).split(whereSeparator: \.isNewline)
    }

    /// A Netscape cookies.txt is tab-separated with seven fields a line. Browser exports mark
    /// HttpOnly cookies with a `#HttpOnly_` prefix, which is a cookie, not a comment.
    static func cookieCount(in data: Data) -> Int {
        lines(of: data).prefix(5000).filter { line in
            let text = line.hasPrefix("#HttpOnly_") ? line.dropFirst(10) : line
            return !text.hasPrefix("#") && text.split(separator: "\t", omittingEmptySubsequences: false).count >= 7
        }.count
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

public extension FileManager {
    /// Writes a file created with owner-only permissions, so it is never briefly readable by
    /// others, and moved into place whole.
    ///
    /// No data-protection class: "complete" protection makes a file unreadable while the
    /// Mac is locked, which a backup of it, or a copy carried to another machine, cannot
    /// live with.
    public func writePrivately(_ data: Data, to url: URL) throws {
        let temporary = url.deletingLastPathComponent()
            .appendingPathComponent(".\(UUID().uuidString).tmp")
        guard createFile(atPath: temporary.path, contents: data, attributes: [.posixPermissions: 0o600]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        do {
            if fileExists(atPath: url.path) {
                _ = try replaceItemAt(url, withItemAt: temporary)
            } else {
                try moveItem(at: temporary, to: url)
            }
        } catch {
            try? removeItem(at: temporary)
            throw error
        }
    }
}
