import Foundation

/// The Stremio add-on protocol, as `shared/watch/CONTRACT.md` takes it: URLs, manifests and
/// the shapes add-ons answer with. No networking here, so CoreChecks holds it to
/// `shared/watch/VECTORS.json`, which the phone's Kotlin is held to as well.
public enum Addons {

    // MARK: - URLs

    /// An add-on's base from whatever was pasted: a manifest URL, a base with or without a
    /// trailing slash, or a `stremio://` link. Nil when it is not an add-on address at all.
    public static func base(_ input: String) -> String? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.lowercased().hasPrefix("stremio://") { text = "https://" + text.dropFirst("stremio://".count) }
        guard let components = URLComponents(string: text),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty else { return nil }
        if text.hasSuffix("/manifest.json") { text = String(text.dropLast("/manifest.json".count)) }
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }

    /// `{base}/{resource}/{type}/{id}[/{extra}].json`, each part encoded as a URI component.
    public static func resourceURL(base: String, resource: String, type: String, id: String,
                                   extra: [(String, String)] = []) -> String {
        var trimmed = base
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        var path = "\(trimmed)/\(component(resource))/\(component(type))/\(component(id))"
        if !extra.isEmpty {
            path += "/" + extra.map { "\(component($0.0))=\(component($0.1))" }.joined(separator: "&")
        }
        return path + ".json"
    }

    /// encodeURIComponent, as Stremio encodes path parts.
    public static func component(_ value: String) -> String {
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()".utf8)
        var out = ""
        for byte in value.utf8 {
            if allowed.contains(byte) { out.append(Character(UnicodeScalar(byte))) }
            else { out += String(format: "%%%02X", byte) }
        }
        return out
    }

    public static func isHTTP(_ url: String) -> Bool {
        let lower = url.lowercased()
        return lower.hasPrefix("https://") || lower.hasPrefix("http://")
    }

    // MARK: - Manifests

    public struct Catalog: Hashable, Sendable {
        public var type: String
        public var id: String
        public var name: String
        public var extra: [String]
        public var required: [String]
        public var searchable: Bool { extra.contains("search") }
        /// A catalog that cannot be listed without an argument is not a row.
        public var listable: Bool { required.isEmpty }
    }

    public struct Resource: Hashable, Sendable {
        public var name: String
        public var types: [String]?
        public var idPrefixes: [String]?
    }

    public struct Manifest: Sendable {
        public var id: String
        public var version: String
        public var name: String
        public var description: String
        public var logo: String?
        public var types: [String]
        public var idPrefixes: [String]?
        public var resources: [Resource]
        public var catalogs: [Catalog]
        /// Lists of other add-ons this one publishes: Cinemeta's official and community ones.
        public var addonCatalogs: [Catalog]
        /// It has a page of settings at `{base}/configure`.
        public var configurable: Bool
        public var configurationRequired: Bool
    }

    private static func strings(_ value: Any?) -> [String]? {
        (value as? [Any])?.compactMap { ($0 as? String).flatMap { $0.isEmpty ? nil : $0 } }
    }

    /// Leniently: what is missing is absent; what cannot be read at all is nil.
    public static func manifest(_ json: [String: Any]) -> Manifest? {
        guard let id = json["id"] as? String, !id.isEmpty else { return nil }
        var resources: [Resource] = []
        for raw in json["resources"] as? [Any] ?? [] {
            if let name = raw as? String {
                resources.append(Resource(name: name, types: nil, idPrefixes: nil))
            } else if let object = raw as? [String: Any], let name = object["name"] as? String, !name.isEmpty {
                resources.append(Resource(name: name, types: strings(object["types"]), idPrefixes: strings(object["idPrefixes"])))
            }
        }
        var catalogs: [Catalog] = []
        for raw in json["catalogs"] as? [[String: Any]] ?? [] {
            var extra: [String] = [], required: [String] = []
            for x in raw["extra"] as? [[String: Any]] ?? [] {
                guard let name = x["name"] as? String, !name.isEmpty else { continue }
                extra.append(name)
                if x["isRequired"] as? Bool == true { required.append(name) }
            }
            // The older form: names in extraSupported, the required ones in extraRequired.
            extra += (strings(raw["extraSupported"]) ?? []).filter { !extra.contains($0) }
            required += (strings(raw["extraRequired"]) ?? []).filter { !required.contains($0) }
            let catalogId = raw["id"] as? String ?? ""
            let name = (raw["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? catalogId
            catalogs.append(Catalog(type: raw["type"] as? String ?? "", id: catalogId, name: name, extra: extra, required: required))
        }
        let hints = json["behaviorHints"] as? [String: Any]
        return Manifest(
            id: id,
            version: json["version"] as? String ?? "",
            name: (json["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id,
            description: json["description"] as? String ?? "",
            logo: (json["logo"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            types: strings(json["types"]) ?? [],
            idPrefixes: strings(json["idPrefixes"]),
            resources: resources,
            catalogs: catalogs,
            addonCatalogs: (json["addonCatalogs"] as? [[String: Any]] ?? []).map {
                let id = $0["id"] as? String ?? ""
                return Catalog(type: $0["type"] as? String ?? "", id: id,
                               name: ($0["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id, extra: [], required: [])
            },
            configurable: hints?["configurable"] as? Bool ?? false,
            configurationRequired: hints?["configurationRequired"] as? Bool ?? false)
    }

    /// Whether an add-on answers `resource` for this type and id. A resource's own types and
    /// prefixes win over the manifest's; catalogs are named by their own ids, so prefixes do
    /// not apply to them.
    public static func supports(_ manifest: Manifest, resource: String, type: String, id: String) -> Bool {
        guard let r = manifest.resources.first(where: { $0.name == resource }) else { return false }
        guard (r.types ?? manifest.types).contains(type) else { return false }
        if resource == "catalog" { return true }
        guard let prefixes = r.idPrefixes ?? manifest.idPrefixes else { return true }
        return prefixes.contains { id.hasPrefix($0) }
    }

    // MARK: - What add-ons answer with

    public struct Preview: Hashable, Sendable, Identifiable {
        public var id: String
        public var type: String
        public var name: String
        public var poster: String?
        public var posterShape: String
        public var releaseInfo: String?
        public var description: String?
    }

    public struct Video: Hashable, Sendable, Identifiable {
        public var id: String
        public var title: String
        public var season: Int?
        public var episode: Int?
        public var released: String?
        public var thumbnail: String?
        public var overview: String?

        public init(id: String, title: String, season: Int? = nil, episode: Int? = nil, released: String? = nil,
                    thumbnail: String? = nil, overview: String? = nil) {
            self.id = id
            self.title = title
            self.season = season
            self.episode = episode
            self.released = released
            self.thumbnail = thumbnail
            self.overview = overview
        }
    }

    public struct Meta: Hashable, Sendable {
        public var id: String
        public var type: String
        public var name: String
        public var poster: String?
        public var background: String?
        public var logo: String?
        public var description: String?
        public var releaseInfo: String?
        public var runtime: String?
        public var genres: [String]
        public var imdbRating: String?
        public var videos: [Video]
        /// Trailers as streams: Cinemeta gives YouTube ids in trailerStreams.
        public var trailers: [Stream] = []
    }

    private static func text(_ value: Any?) -> String? {
        if let s = value as? String { return s.isEmpty ? nil : s }
        if let n = value as? NSNumber { return n.stringValue }
        return nil
    }

    private static func http(_ value: Any?) -> String? { text(value).flatMap { isHTTP($0) ? $0 : nil } }

    public static func previews(_ json: [String: Any]) -> [Preview] {
        (json["metas"] as? [[String: Any]] ?? []).compactMap { m in
            guard let id = text(m["id"]) else { return nil }
            return Preview(id: id, type: m["type"] as? String ?? "", name: m["name"] as? String ?? "",
                           poster: http(m["poster"]), posterShape: text(m["posterShape"]) ?? "poster",
                           releaseInfo: text(m["releaseInfo"]), description: text(m["description"]))
        }
    }

    public static func meta(_ json: [String: Any]) -> Meta? {
        guard let m = json["meta"] as? [String: Any], let id = text(m["id"]) else { return nil }
        let videos = (m["videos"] as? [[String: Any]] ?? []).compactMap { v -> Video? in
            guard let videoId = text(v["id"]) else { return nil }
            return Video(id: videoId, title: text(v["title"]) ?? text(v["name"]) ?? "",
                         season: (v["season"] as? NSNumber)?.intValue,
                         episode: (v["episode"] as? NSNumber)?.intValue ?? (v["number"] as? NSNumber)?.intValue,
                         released: text(v["released"]), thumbnail: http(v["thumbnail"]),
                         overview: text(v["overview"]) ?? text(v["description"]))
        }
        // Specials (season 0) last, the rest in order.
        let ordered = videos.sorted {
            let a = ($0.season ?? 0) == 0 ? 1 : 0, b = ($1.season ?? 0) == 0 ? 1 : 0
            if a != b { return a < b }
            if ($0.season ?? 0) != ($1.season ?? 0) { return ($0.season ?? 0) < ($1.season ?? 0) }
            return ($0.episode ?? 0) < ($1.episode ?? 0)
        }
        return Meta(id: id, type: m["type"] as? String ?? "", name: m["name"] as? String ?? "",
                    poster: http(m["poster"]), background: http(m["background"]), logo: http(m["logo"]),
                    description: text(m["description"]), releaseInfo: text(m["releaseInfo"]), runtime: text(m["runtime"]),
                    genres: strings(m["genres"]) ?? strings(m["genre"]) ?? [], imdbRating: text(m["imdbRating"]),
                    videos: ordered,
                    trailers: (m["trailerStreams"] as? [[String: Any]] ?? []).compactMap(stream))
    }

    public enum Kind: String, Sendable { case url, youtube, torrent, external }

    public struct Stream: Hashable, Sendable {
        public var kind: Kind
        /// What to open: a media or page URL, a YouTube watch URL, a magnet, an external link.
        public var target: String
        public var fileIdx: Int?
        /// The add-on's name line and its description, each flattened to one line.
        public var label: String
        public var detail: String
        public var bingeGroup: String?
        public var notWebReady: Bool
        public var headers: [String: String]
        public var filename: String?
        /// Subtitles the stream brings with it.
        public var subtitles: [Subtitle] = []
    }

    private static func oneLine(_ text: String) -> String {
        text.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// One stream object; nil when it gives nothing this app can open.
    public static func stream(_ s: [String: Any]) -> Stream? {
        let hints = s["behaviorHints"] as? [String: Any]
        let kind: Kind
        let target: String?
        if let url = text(s["url"]) {
            kind = .url
            target = isHTTP(url) ? url : nil
        } else if let yt = text(s["ytId"]) {
            kind = .youtube
            target = "https://www.youtube.com/watch?v=" + component(yt)
        } else if let hash = text(s["infoHash"]) {
            kind = .torrent
            let valid = hash.count == 40 && hash.allSatisfy(\.isHexDigit)
            target = valid ? magnet(hash, sources: s["sources"] as? [String] ?? []) : nil
        } else if let external = text(s["externalUrl"]) {
            kind = .external
            target = isHTTP(external) ? external : nil
        } else {
            return nil
        }
        guard let target else { return nil }
        var headers: [String: String] = [:]
        if let request = (hints?["proxyHeaders"] as? [String: Any])?["request"] as? [String: Any] {
            for (key, value) in request { if let v = value as? String, !v.isEmpty { headers[key] = v } }
        }
        return Stream(kind: kind, target: target,
                      fileIdx: kind == .torrent ? (s["fileIdx"] as? NSNumber)?.intValue : nil,
                      label: oneLine(s["name"] as? String ?? ""),
                      detail: oneLine(text(s["title"]) ?? text(s["description"]) ?? ""),
                      bingeGroup: text(hints?["bingeGroup"]),
                      notWebReady: hints?["notWebReady"] as? Bool ?? false,
                      headers: headers,
                      filename: text(hints?["filename"]),
                      subtitles: subtitles(s))
    }

    /// An add-on another add-on offers, ready to install from `base`.
    public struct Offer: Sendable, Identifiable {
        public var base: String
        public var manifest: Manifest
        public var id: String { base }
    }

    /// An `addon_catalog` answer. Left out: add-ons that run on the device Stremio is on (its
    /// local server), the old transport whose address is not a manifest URL, and anything
    /// whose manifest cannot be read.
    public static func offers(_ json: [String: Any]) -> [Offer] {
        (json["addons"] as? [[String: Any]] ?? []).compactMap { entry in
            guard let url = entry["transportUrl"] as? String, url.hasSuffix("/manifest.json"),
                  let base = base(url), let host = URL(string: base)?.host?.lowercased(),
                  !["127.0.0.1", "localhost", "::1"].contains(host),
                  let raw = entry["manifest"] as? [String: Any], let manifest = manifest(raw) else { return nil }
            return Offer(base: base, manifest: manifest)
        }
    }

    /// A magnet for an info hash, with the trackers among a stream's `sources` ("tracker:…").
    public static func magnet(_ hash: String, sources: [String]) -> String {
        let trackers = sources.filter { $0.hasPrefix("tracker:") }.map { String($0.dropFirst("tracker:".count)) }
        return "magnet:?xt=urn:btih:" + hash.lowercased() + trackers.map { "&tr=" + component($0) }.joined()
    }

    public static func streams(_ json: [String: Any]) -> [Stream] {
        (json["streams"] as? [[String: Any]] ?? []).compactMap(stream)
    }

    // MARK: - Subtitles and languages

    public struct Subtitle: Hashable, Sendable {
        public var id: String
        public var url: String
        public var lang: String
        /// The release it was made for, when the add-on says (OpenSubtitles' movieReleaseName,
        /// else its subtitleFileName): one made for the file being played is in time with it.
        public var release: String
        public init(id: String, url: String, lang: String, release: String = "") {
            self.id = id
            self.url = url
            self.lang = lang
            self.release = release
        }
    }

    /// A `subtitles` answer, or a stream's own `subtitles`.
    public static func subtitles(_ json: [String: Any]) -> [Subtitle] {
        (json["subtitles"] as? [[String: Any]] ?? []).compactMap { s in
            guard let url = s["url"] as? String, isHTTP(url) else { return nil }
            let release = (s["movieReleaseName"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? s["subtitleFileName"] as? String ?? ""
            return Subtitle(id: text(s["id"]) ?? url, url: url, lang: s["lang"] as? String ?? "", release: release)
        }
    }

    /// A language the user can prefer: its ISO 639-2 code, and every code add-ons use for it.
    public struct Language: Sendable {
        public var code: String
        public var codes: Set<String>
    }

    /// The languages offered for subtitles and audio, by ISO 639-2/B code.
    public static let languages: [Language] = [
        ("tur", "tr"), ("eng", "en"), ("ger/deu", "de"), ("fre/fra", "fr"), ("spa", "es"), ("ita", "it"),
        ("por", "pt"), ("dut/nld", "nl"), ("rus", "ru"), ("ara", "ar"), ("per/fas", "fa"), ("gre/ell", "el"),
        ("pol", "pl"), ("jpn", "ja"), ("kor", "ko"), ("chi/zho", "zh"), ("aze", "az"),
    ].map { three, two in
        let codes = three.split(separator: "/").map(String.init)
        return Language(code: codes[0], codes: Set(codes + [two]))
    }

    /// The 639-2 code for whatever an add-on or a file calls a language; nil when unknown.
    public static func language(_ lang: String) -> String? {
        let key = lang.trimmingCharacters(in: .whitespaces).lowercased()
            .split(separator: "-").first.map(String.init)?
            .split(separator: "_").first.map(String.init) ?? ""
        return languages.first { $0.codes.contains(key) }?.code
    }

    /// Subtitles to offer, best first: only the preferred languages, in the order they are
    /// preferred, each keeping the order it came in (the stream's own, then the add-ons' in
    /// theirs), at most `perLanguage` of each and none twice.
    public static func rankSubtitles(_ subtitles: [Subtitle], preferred: [String], perLanguage: Int = 5,
                                     filename: String? = nil) -> [Subtitle] {
        var seen = Set<String>()
        let file = filename.map(releaseWords) ?? []
        return preferred.flatMap { code in
            let ofLanguage = subtitles.filter { language($0.lang) == code && seen.insert($0.url).inserted }
            // Made for this release first: a subtitle for another cut or frame rate drifts.
            // Stable, so ties keep the add-ons' own order.
            let ranked = file.isEmpty ? ofLanguage : ofLanguage.enumerated().sorted { a, b in
                let x = releaseWords(a.element.release).intersection(file).count
                let y = releaseWords(b.element.release).intersection(file).count
                return x != y ? x > y : a.offset < b.offset
            }.map(\.element)
            return ranked.prefix(perLanguage)
        }
    }

    /// A release name's words: "Show.S01E01.1080p.WEB-DL" is show, s01e01, 1080p, web, dl.
    static func releaseWords(_ name: String) -> Set<String> {
        Set(name.lowercased().split { !($0.isASCII && ($0.isLetter || $0.isNumber)) }.map(String.init))
    }
}
