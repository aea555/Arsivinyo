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
}
