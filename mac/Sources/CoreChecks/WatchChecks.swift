import ArsivinyoCore
import Foundation

/// Watch, held to `shared/watch/VECTORS.json` (the phone's tests read it too) and to the
/// library's rules in `shared/watch/CONTRACT.md`.
extension CoreChecks {

    mutating func checkWatch() async throws {
        print("watch")
        let url = Self.repositoryRoot.appendingPathComponent("shared/watch/VECTORS.json")
        let vectors = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]

        for case let c as [String: Any] in vectors["bases"] as! [Any] {
            check(Addons.base(c["input"] as! String) == c["base"] as? String, "base: \(c["why"]!)")
        }
        for case let c as [String: Any] in vectors["resources"] as! [Any] {
            let extra = (c["extra"] as! [[String]]).map { ($0[0], $0[1]) }
            let made = Addons.resourceURL(base: c["base"] as! String, resource: c["resource"] as! String,
                                          type: c["type"] as! String, id: c["id"] as! String, extra: extra)
            check(made == c["url"] as! String, "resource URL: \(made)")
        }
        let supports = vectors["supports"] as! [String: Any]
        let manifest = Addons.manifest(supports["manifest"] as! [String: Any])!
        for case let c as [String: Any] in supports["cases"] as! [Any] {
            check(Addons.supports(manifest, resource: c["resource"] as! String, type: c["type"] as! String,
                                  id: c["id"] as! String) == c["expect"] as! Bool, "supports: \(c["why"]!)")
        }
        for case let c as [String: Any] in vectors["streams"] as! [Any] {
            let stream = Addons.stream(c["stream"] as! [String: Any])
            guard let kind = c["kind"] as? String else {
                check(stream == nil, "stream dropped: \(c["why"] ?? "")")
                continue
            }
            check(stream?.kind.rawValue == kind && stream?.target == c["target"] as? String
                  && stream?.label == c["label"] as? String && stream?.detail == c["detail"] as? String
                  && (c["fileIdx"] == nil || stream?.fileIdx == c["fileIdx"] as? Int),
                  "stream read as \(kind)")
        }

        for case let c as [String: Any] in vectors["metas"] as! [Any] {
            let meta = Addons.meta(["meta": c["meta"]!])
            check(meta?.videos.map(\.id) == c["order"] as? [String] && meta?.videos.map(\.title) == c["titles"] as? [String]
                  && meta?.trailers.map(\.target) == c["trailers"] as? [String], "meta: \(c["why"]!)")
        }

        // The library.
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("arsivinyo-watch-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        let key = try Crypto.randomBytes(32)
        func library() -> WatchLibrary { WatchLibrary(file: scratch.appendingPathComponent("library.enc"), key: { key }) }
        let lib = library()
        let show = WatchLibrary.Title(id: "tt0944947", type: "series", name: "Game of Thrones", poster: nil)
        try lib.recordProgress(show, videoId: "tt0944947:1:1", positionMs: 600_000, durationMs: 3_600_000,
                               addon: "https://addon", bingeGroup: "g1", now: 1)
        check(try lib.continueWatching().map(\.id) == [show.id], "a started episode is in continue watching")
        try lib.recordProgress(show, videoId: "tt0944947:1:1", positionMs: 3_400_000, durationMs: 3_600_000,
                               addon: nil, bingeGroup: nil, now: 2)
        check(try lib.continueWatching().isEmpty && lib.item(show.id)?.watched == ["tt0944947:1:1"],
              "near the end it counts as watched and leaves continue watching")
        check(try lib.item(show.id)?.bingeGroup == "g1", "and the source is remembered for the next episode")

        try lib.install(base: "https://a", manifest: ["id": "a", "version": "1"])
        try lib.install(base: "https://b", manifest: ["id": "b"])
        try lib.install(base: "https://torrent.example/token=SECRET123", manifest: ["id": "c"])
        try lib.move(base: "https://torrent.example/token=SECRET123", to: 0)
        check(try lib.addons().map(\.base) == ["https://torrent.example/token=SECRET123", "https://a", "https://b"],
              "add-ons keep the order they are put in")
        try lib.setEnabled(base: "https://b", false)
        let reopened = library()
        check(try reopened.addons().map(\.enabled) == [true, true, false] && reopened.item(show.id)?.watched.count == 1,
              "the library survives reopening")
        let bytes = String(decoding: try Data(contentsOf: scratch.appendingPathComponent("library.enc")), as: UTF8.self)
        check(!["SECRET123", "Game of Thrones", "tt0944947", "torrent.example"].contains { bytes.contains($0) },
              "nothing in it is readable on disk, an add-on's token included")
    }
}
