import Foundation

/// The watch library: installed add-ons, and what has been watched and where it stopped
/// (`shared/watch/CONTRACT.md`, "The library"). The same shape as the phone's.
///
/// One file, sealed under a device key from the Keychain, as the memes index is. What someone
/// watches is as private as what they save, and an add-on's URL often carries an account
/// token, so nothing in here is ever written in the clear or logged.
public final class WatchLibrary: @unchecked Sendable {

    public struct Addon: @unchecked Sendable {
        public var base: String
        public var manifest: [String: Any]
        public var enabled: Bool
    }

    public struct Progress: Hashable, Sendable {
        public var videoId: String
        public var positionMs: Int64
        public var durationMs: Int64
        public var at: Int64
    }

    public struct Item: Hashable, Sendable, Identifiable {
        /// The add-on's id for the title.
        public var id: String
        public var type: String
        public var name: String
        public var poster: String?
        public var addedAt: Int64
        /// Videos finished; for a film, its own id.
        public var watched: Set<String> = []
        public var progress: Progress?
        /// The add-on and binge group last played from, to pick the same source next time.
        public var addon: String?
        public var bingeGroup: String?
        /// Added by hand, not only by being watched.
        public var saved = false
    }

    /// A title as it should be remembered: from its meta or a catalog preview.
    public struct Title: Hashable, Sendable {
        public var id: String
        public var type: String
        public var name: String
        public var poster: String?

        public init(id: String, type: String, name: String, poster: String?) {
            self.id = id
            self.type = type
            self.name = name
            self.poster = poster
        }
    }

    /// At or past this share of a video, it counts as watched.
    public static let finishedAt = 0.92

    /// Turkish, then English, until the user says otherwise (CONTRACT.md).
    public static let defaultLanguages = ["tur", "eng"]

    private let url: URL
    private let key: () throws -> Data
    private let lock = NSLock()
    private typealias State = (addons: [Addon], items: [Item], languages: [String])
    private var cache: State?
    private static let associatedData = "watch/library/v1"

    public init(file: URL, key: @escaping () throws -> Data) {
        url = file
        self.key = key
    }

    // MARK: - Reading

    public func addons() throws -> [Addon] { try lock.withLock { try read().addons } }

    /// Subtitle and audio languages, most preferred first, as ISO 639-2 codes.
    public func languages() throws -> [String] { try lock.withLock { try read().languages } }

    public func setLanguages(_ codes: [String]) throws {
        try write { state in
            var seen = Set<String>()
            let known = codes.compactMap(Addons.language).filter { seen.insert($0).inserted }
            state.languages = known.isEmpty ? Self.defaultLanguages : known
        }
    }
    public func items() throws -> [Item] { try lock.withLock { try read().items } }
    public func item(_ id: String) throws -> Item? { try items().first { $0.id == id } }

    /// Started and not finished, newest first.
    public func continueWatching() throws -> [Item] {
        try items().filter { $0.progress != nil }.sorted { $0.progress!.at > $1.progress!.at }
    }

    // MARK: - Add-ons

    /// Installs an add-on, or refreshes its manifest if it is already there, keeping its place.
    public func install(base: String, manifest: [String: Any]) throws {
        try write { state in
            if let at = state.addons.firstIndex(where: { $0.base == base }) {
                state.addons[at].manifest = manifest
            } else {
                state.addons.append(Addon(base: base, manifest: manifest, enabled: true))
            }
        }
    }

    public func uninstall(base: String) throws { try write { $0.addons.removeAll { $0.base == base } } }

    public func setEnabled(base: String, _ enabled: Bool) throws {
        try write { state in
            if let at = state.addons.firstIndex(where: { $0.base == base }) { state.addons[at].enabled = enabled }
        }
    }

    /// Catalogs show, and streams are listed, in this order.
    public func move(base: String, to position: Int) throws {
        try write { state in
            guard let at = state.addons.firstIndex(where: { $0.base == base }) else { return }
            let addon = state.addons.remove(at: at)
            state.addons.insert(addon, at: min(max(position, 0), state.addons.count))
        }
    }

    // MARK: - The library

    /// Where playback is. Near the end counts as watched: the video joins `watched` and the
    /// title's progress is cleared, so it leaves "continue watching" until the next one starts.
    public func recordProgress(_ title: Title, videoId: String, positionMs: Int64, durationMs: Int64,
                               addon: String?, bingeGroup: String?,
                               now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws {
        try write { state in
            var item = state.items.first { $0.id == title.id }
                ?? Item(id: title.id, type: title.type, name: title.name, poster: title.poster, addedAt: now)
            let finished = durationMs > 0 && Double(positionMs) >= Double(durationMs) * Self.finishedAt
            if !title.name.isEmpty { item.name = title.name }
            item.poster = title.poster ?? item.poster
            if finished {
                item.watched.insert(videoId)
                item.progress = nil
            } else {
                item.progress = Progress(videoId: videoId, positionMs: positionMs, durationMs: durationMs, at: now)
            }
            item.addon = addon ?? item.addon
            item.bingeGroup = bingeGroup ?? item.bingeGroup
            Self.put(item, into: &state.items)
        }
    }

    public func setWatched(_ title: Title, videoId: String, _ watched: Bool,
                           now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws {
        try write { state in
            var item = state.items.first { $0.id == title.id }
                ?? Item(id: title.id, type: title.type, name: title.name, poster: title.poster, addedAt: now)
            if watched {
                item.watched.insert(videoId)
                if item.progress?.videoId == videoId { item.progress = nil }
            } else {
                item.watched.remove(videoId)
            }
            Self.put(item, into: &state.items)
        }
    }

    public func setSaved(_ title: Title, _ saved: Bool, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws {
        try write { state in
            if var item = state.items.first(where: { $0.id == title.id }) {
                item.saved = saved
                Self.put(item, into: &state.items)
            } else if saved {
                var item = Item(id: title.id, type: title.type, name: title.name, poster: title.poster, addedAt: now)
                item.saved = true
                state.items.append(item)
            }
        }
    }

    /// Off "continue watching", without marking anything watched.
    public func dismissProgress(_ id: String) throws {
        try write { state in
            if let at = state.items.firstIndex(where: { $0.id == id }) { state.items[at].progress = nil }
        }
    }

    public func remove(_ id: String) throws { try write { $0.items.removeAll { $0.id == id } } }

    private static func put(_ item: Item, into items: inout [Item]) {
        if let at = items.firstIndex(where: { $0.id == item.id }) { items[at] = item } else { items.append(item) }
    }

    // MARK: - Storage

    private func write(_ change: (inout State) throws -> Void) throws {
        try lock.withLock {
            var state = try read()
            try change(&state)
            let json = try JSONSerialization.data(withJSONObject: Self.encode(state), options: [.sortedKeys])
            // Padded before sealing, so the file's size does not count what was watched.
            let sealed = try Crypto.seal(Crypto.pad(json), key: key(), associatedData: Self.associatedData)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try FileManager.default.writePrivately(sealed, to: url)
            cache = state
        }
    }

    private func read() throws -> State {
        if let cache { return cache }
        guard FileManager.default.fileExists(atPath: url.path) else {
            cache = ([], [], Self.defaultLanguages)
            return ([], [], Self.defaultLanguages)
        }
        let json = try Crypto.unpad(Crypto.open(Data(contentsOf: url), key: key(), associatedData: Self.associatedData))
        let state = Self.decode(try JSONSerialization.jsonObject(with: json) as? [String: Any] ?? [:])
        cache = state
        return state
    }

    private static func encode(_ state: State) -> [String: Any] {
        [
            "version": 1,
            "addons": state.addons.map { ["url": $0.base, "manifest": $0.manifest, "enabled": $0.enabled] as [String: Any] },
            "items": state.items.map { item -> [String: Any] in
                var out: [String: Any] = [
                    "id": item.id, "type": item.type, "name": item.name, "poster": (item.poster as Any?) ?? NSNull(),
                    "addedAt": item.addedAt, "watched": item.watched.sorted(), "saved": item.saved,
                    "stream": ["addon": (item.addon as Any?) ?? NSNull(), "bingeGroup": (item.bingeGroup as Any?) ?? NSNull()],
                ]
                out["progress"] = item.progress.map {
                    ["videoId": $0.videoId, "positionMs": $0.positionMs, "durationMs": $0.durationMs, "at": $0.at] as [String: Any]
                } ?? NSNull()
                return out
            },
            "languages": state.languages,
        ]
    }

    private static func decode(_ json: [String: Any]) -> State {
        let addons = (json["addons"] as? [[String: Any]] ?? []).compactMap { a -> Addon? in
            guard let base = a["url"] as? String else { return nil }
            return Addon(base: base, manifest: a["manifest"] as? [String: Any] ?? [:], enabled: a["enabled"] as? Bool ?? true)
        }
        let items = (json["items"] as? [[String: Any]] ?? []).compactMap { o -> Item? in
            guard let id = o["id"] as? String else { return nil }
            var item = Item(id: id, type: o["type"] as? String ?? "", name: o["name"] as? String ?? "",
                            poster: o["poster"] as? String, addedAt: (o["addedAt"] as? NSNumber)?.int64Value ?? 0)
            item.watched = Set(o["watched"] as? [String] ?? [])
            if let p = o["progress"] as? [String: Any], let videoId = p["videoId"] as? String {
                item.progress = Progress(videoId: videoId, positionMs: (p["positionMs"] as? NSNumber)?.int64Value ?? 0,
                                         durationMs: (p["durationMs"] as? NSNumber)?.int64Value ?? 0,
                                         at: (p["at"] as? NSNumber)?.int64Value ?? 0)
            }
            let stream = o["stream"] as? [String: Any]
            item.addon = stream?["addon"] as? String
            item.bingeGroup = stream?["bingeGroup"] as? String
            item.saved = o["saved"] as? Bool ?? false
            return item
        }
        let languages = (json["languages"] as? [String] ?? []).filter { !$0.isEmpty }
        return (addons, items, languages.isEmpty ? defaultLanguages : languages)
    }
}
