import Foundation

/// Add-ons over the network: manifests, catalogs, metas and streams, each request with firm
/// timeouts so a slow add-on is reported as slow instead of holding anything up
/// (`shared/watch/CONTRACT.md`). An add-on's URL is only ever sent to the add-on itself.
public final class WatchService: @unchecked Sendable {

    public struct Failure: Error, CustomStringConvertible, Sendable {
        public let code: String
        public var description: String { code }
        public init(code: String) { self.code = code }
    }

    public let library: WatchLibrary
    private let session: URLSession

    public init(library: WatchLibrary) {
        self.library = library
        // Ephemeral: no cookies or cache on disk, which would hold what was browsed.
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 30
        configuration.httpAdditionalHeaders = ["User-Agent": "Arsivinyo", "Accept": "application/json"]
        session = URLSession(configuration: configuration)
    }

    /// Enabled add-ons, in order, with their manifests read.
    public func enabled() throws -> [(addon: WatchLibrary.Addon, manifest: Addons.Manifest)] {
        try library.addons().filter(\.enabled).compactMap { a in Addons.manifest(a.manifest).map { (a, $0) } }
    }

    public static func host(_ base: String) -> String { URL(string: base)?.host ?? "" }

    // MARK: - Requests

    func getJSON(_ address: String, timeout: TimeInterval = 12) async throws -> [String: Any] {
        guard let url = URL(string: address) else { throw Failure(code: "WATCH_BAD_URL") }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        let data: Data, response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError where error.code == .timedOut {
            throw Failure(code: "WATCH_TIMEOUT")
        } catch {
            throw Failure(code: "WATCH_NETWORK")
        }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw Failure(code: "WATCH_HTTP_\(http.statusCode)")
        }
        guard data.count <= 16 << 20 else { throw Failure(code: "WATCH_TOO_LARGE") }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure(code: "WATCH_NOT_JSON")
        }
        return json
    }

    /// Whether a stream's URL is a page rather than media, so it goes through yt-dlp first.
    /// When the server will not say, it is taken to be media and played.
    public func isPage(_ address: String, headers: [String: String]) async -> Bool {
        guard let url = URL(string: address) else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.timeoutInterval = 6
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        guard let (_, response) = try? await session.data(for: request) else { return false }
        return (response as? HTTPURLResponse)?.mimeType?.lowercased() == "text/html"
    }

    // MARK: - Add-ons

    /// Installs from whatever was pasted; the manifest, or a failure code.
    @discardableResult
    public func install(_ input: String) async throws -> Addons.Manifest {
        guard let base = Addons.base(input) else { throw Failure(code: "WATCH_BAD_URL") }
        let json = try await getJSON(base + "/manifest.json")
        guard let manifest = Addons.manifest(json) else { throw Failure(code: "WATCH_BAD_MANIFEST") }
        if manifest.configurationRequired { throw Failure(code: "WATCH_NEEDS_CONFIGURATION") }
        try library.install(base: base, manifest: json)
        return manifest
    }

    /// The recommended add-ons (`shared/watch/CONTRACT.md`, "The recommended add-ons"): enough to
    /// watch real films and series in one click. The phone has the same list; change both.
    public static let recommended: [(name: String, url: String)] = [
        ("Cinemeta", "https://v3-cinemeta.strem.io/manifest.json"),
        ("Streaming Catalogs", "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json"),
        ("Torrentio", "https://torrentio.strem.fun/manifest.json"),
        ("TorrentsDB", "https://torrentsdb.com/manifest.json"),
        ("ThePirateBay+", "https://thepiratebay-plus.strem.fun/manifest.json"),
        ("OpenSubtitles v3", "https://opensubtitles-v3.strem.io/manifest.json"),
    ]

    /// The recommended add-ons not installed yet. One from the same host counts as installed, so
    /// a configured Torrentio is kept as it is.
    public func missingRecommended() -> [(name: String, url: String)] {
        let hosts = Set(((try? library.addons()) ?? []).map { Self.host($0.base) })
        return Self.recommended.filter { !hosts.contains(Self.host($0.url)) }
    }

    /// Installs the missing recommended add-ons, in the list's order. Gives the names of those
    /// installed and of those that failed.
    public func installRecommended() async -> (installed: [String], failed: [String]) {
        var installed: [String] = [], failed: [String] = []
        for addon in missingRecommended() {
            do {
                _ = try await install(addon.url)
                installed.append(addon.name)
            } catch {
                failed.append(addon.name)
            }
        }
        return (installed, failed)
    }

    // MARK: - What the screens ask for

    public struct Row: Hashable, Sendable, Identifiable {
        public var addonBase: String
        public var addonName: String
        public var catalog: Addons.Catalog
        public var id: String { "\(addonBase)|\(catalog.type)|\(catalog.id)" }
    }

    /// Every listable catalog of every enabled add-on, in the add-ons' order.
    public func rows() throws -> [Row] {
        try enabled().flatMap { pair in
            pair.manifest.catalogs.filter(\.listable).map { Row(addonBase: pair.addon.base, addonName: pair.manifest.name, catalog: $0) }
        }
    }

    public func searchable() throws -> [Row] {
        try enabled().flatMap { pair in
            pair.manifest.catalogs.filter(\.searchable).map { Row(addonBase: pair.addon.base, addonName: pair.manifest.name, catalog: $0) }
        }
    }

    public func catalog(_ row: Row, extra: [(String, String)] = []) async throws -> [Addons.Preview] {
        Addons.previews(try await getJSON(Addons.resourceURL(base: row.addonBase, resource: "catalog",
                                                             type: row.catalog.type, id: row.catalog.id, extra: extra)))
    }

    /// The first add-on, in order, that gives a meta for this title.
    public func meta(type: String, id: String) async throws -> Addons.Meta {
        var last = Failure(code: "WATCH_NO_META")
        for pair in try enabled() where Addons.supports(pair.manifest, resource: "meta", type: type, id: id) {
            do {
                if let meta = Addons.meta(try await getJSON(Addons.resourceURL(base: pair.addon.base, resource: "meta", type: type, id: id))) {
                    return meta
                }
            } catch let failure as Failure {
                last = failure
            }
        }
        throw last
    }

    /// The lists of add-ons that installed add-ons publish, one per list: the "all" type where
    /// there is one, so a list is not shown once per type.
    public func offerLists() throws -> [Row] {
        try enabled().flatMap { pair -> [Row] in
            guard pair.manifest.resources.contains(where: { $0.name == "addon_catalog" }) else { return [] }
            var seen: [String: Addons.Catalog] = [:], order: [String] = []
            for catalog in pair.manifest.addonCatalogs {
                if seen[catalog.id] == nil { order.append(catalog.id) }
                if seen[catalog.id] == nil || catalog.type == "all" { seen[catalog.id] = catalog }
            }
            return order.compactMap { seen[$0] }.map { Row(addonBase: pair.addon.base, addonName: pair.manifest.name, catalog: $0) }
        }
    }

    public func offers(_ row: Row) async throws -> [Addons.Offer] {
        Addons.offers(try await getJSON(Addons.resourceURL(base: row.addonBase, resource: "addon_catalog",
                                                           type: row.catalog.type, id: row.catalog.id), timeout: 20))
    }

    public struct Source: Hashable, Sendable, Identifiable {
        public var base: String
        public var name: String
        public var id: String { base }
    }

    /// Add-ons that offer streams for this video, so each can be asked on its own.
    public func streamSources(type: String, id: String) throws -> [Source] {
        try enabled().filter { Addons.supports($0.manifest, resource: "stream", type: type, id: id) }
            .map { Source(base: $0.addon.base, name: $0.manifest.name) }
    }

    public func streams(from source: Source, type: String, id: String) async throws -> [Addons.Stream] {
        Addons.streams(try await getJSON(Addons.resourceURL(base: source.base, resource: "stream", type: type, id: id), timeout: 20))
    }

    /// Subtitles to offer for a video, best first: the stream's own, then those of every
    /// add-on that has them, in the preferred languages only. An add-on that fails or is slow
    /// is left out rather than holding up the others'.
    public func subtitles(type: String, id: String, filename: String?, own: [Addons.Subtitle]) async throws -> [Addons.Subtitle] {
        let extra = filename.map { [("filename", $0)] } ?? []
        var all = own
        for pair in try enabled() where Addons.supports(pair.manifest, resource: "subtitles", type: type, id: id) {
            let address = Addons.resourceURL(base: pair.addon.base, resource: "subtitles", type: type, id: id, extra: extra)
            if let json = try? await getJSON(address, timeout: 10) { all += Addons.subtitles(json) }
        }
        return Addons.rankSubtitles(all, preferred: try library.languages())
    }
}
