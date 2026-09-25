import Foundation
import ArsivinyoCore

/// The music library: moving files in, reading their tags, playlists, and reconciliation.
///
/// The index has to keep the phone's field names, because a phone backup's music-index blob
/// is restored straight into it. That is checked by reading the file, not the Swift model.
extension CoreChecks {

    mutating func checkMusic() async throws {
        print("music library")
        guard let ffmpeg = ["/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"]
            .first(where: { FileManager.default.isExecutableFile(atPath: $0) })
        else {
            print("  skip  no ffmpeg to make tagged audio with")
            return
        }

        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-music-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }

        func makeAudio(_ name: String, title: String, artist: String, seconds: Int) throws -> URL {
            let url = scratch.appendingPathComponent(name)
            let p = Process()
            p.executableURL = URL(fileURLWithPath: ffmpeg)
            p.arguments = ["-hide_banner", "-loglevel", "error", "-y",
                           "-f", "lavfi", "-i", "sine=frequency=330:duration=\(seconds)",
                           "-c:a", "aac", "-metadata", "title=\(title)",
                           "-metadata", "artist=\(artist)", url.path]
            try p.run()
            p.waitUntilExit()
            return url
        }

        // Favouriting before anything has loaded — what the seed did, and what silently did
        // nothing, because Favorites only existed once load() had written it.
        do {
            let early = MusicLibrary(musicFolder: scratch.appendingPathComponent("Early"),
                                     supportFolder: scratch.appendingPathComponent("early-support"))
            let track = try await early.adopt(
                try makeAudio("early.m4a", title: "Early", artist: "A", seconds: 1))
            early.setFavorite(track.id, true)
            check(early.load().playlists.first { $0.id == MusicLibrary.favoritesId }?.trackIds == [track.id],
                  "favouriting works before the library has ever been loaded")
        }

        let musicFolder = scratch.appendingPathComponent("Music")
        let support = scratch.appendingPathComponent("support")
        let library = MusicLibrary(musicFolder: musicFolder, supportFolder: support)

        var (tracks, playlists) = library.load()
        check(tracks.isEmpty, "a fresh library is empty")
        check(playlists.contains { $0.id == MusicLibrary.favoritesId },
              "and Favorites already exists, so nothing has to handle its absence")

        let tagged = try makeAudio("download.m4a", title: "From The Tags", artist: "Some Artist", seconds: 2)
        let first = try await library.adopt(tagged)
        check(first.title == "From The Tags" && first.artist == "Some Artist", "tags are read from the file")
        check(first.durationSeconds > 1.5 && first.durationSeconds < 2.5,
              String(format: "and the duration (%.2f)", first.durationSeconds))
        check(!FileManager.default.fileExists(atPath: tagged.path), "adopting moves the download in")
        check(FileManager.default.fileExists(atPath: library.fileURL(for: first).path),
              "and it is in the music folder")

        // Sites tag badly; what the downloader knew wins.
        let second = try await library.adopt(
            try makeAudio("download.m4a", title: "wrong", artist: "wrong", seconds: 1),
            title: "The Real Title", artist: "The Real Artist")
        check(second.title == "The Real Title", "the downloader's title wins over the file's")
        check(second.fileName != first.fileName, "a clashing name is not overwritten (\(second.fileName))")

        library.setFavorite(first.id, true)
        let mix = library.createPlaylist(named: "Mix")
        library.add([first.id, second.id], to: mix.id)
        (tracks, playlists) = library.load()
        check(playlists.first { $0.id == MusicLibrary.favoritesId }?.trackIds == [first.id],
              "a track can be favourited")
        check(playlists.first { $0.id == mix.id }?.trackIds == [first.id, second.id],
              "and added to a playlist, in order")
        check((try? library.deletePlaylist(MusicLibrary.favoritesId)) == nil, "Favorites cannot be deleted")
        check((try? library.renamePlaylist(MusicLibrary.favoritesId, to: "x")) == nil,
              "or renamed")

        // What the phone would read back.
        let index = try JSONSerialization.jsonObject(
            with: Data(contentsOf: support.appendingPathComponent("index.json"))) as? [String: Any] ?? [:]
        let songs = index["songs"] as? [[String: Any]] ?? []
        let lists = index["playlists"] as? [[String: Any]] ?? []
        check(index["version"] as? Int == 1 && songs.count == 2, "the index has the phone's shape")
        check(songs.allSatisfy { $0["fileName"] != nil && $0["durationSec"] != nil },
              "with the phone's field names")
        check(lists.contains { ($0["songIds"] as? [String])?.isEmpty == false },
              "and playlists as songIds, as the phone writes them")
        check((songs.first?["createdAt"] as? Double ?? 0) > 1_000_000_000_000,
              "with timestamps in milliseconds, as the phone writes them")

        // A file deleted in Finder drops out, and takes its playlist entries with it.
        try FileManager.default.removeItem(at: library.fileURL(for: second))
        (tracks, playlists) = library.load()
        check(tracks.map(\.id) == [first.id], "a file removed outside the app drops out")
        check(playlists.first { $0.id == mix.id }?.trackIds == [first.id],
              "and its playlist entries go with it")

        try library.remove(first.id)
        (tracks, playlists) = library.load()
        check(tracks.isEmpty, "a track can be removed")
        check(playlists.allSatisfy { !$0.trackIds.contains(first.id) }, "from every playlist too")
        check(!FileManager.default.fileExists(atPath: library.fileURL(for: first).path),
              "its file goes to the Trash")
    }
}
