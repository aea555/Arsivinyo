import ArsivinyoCore
import Foundation

/// Fills a vault in a scratch directory, remembered in a Keychain item of its own, so the app
/// launched against it opens unlocked with something to show.
///
///   ARSIVINYO_DATA_DIR=/tmp/x ARSIVINYO_KEYCHAIN_SERVICE=dev.seed swift run VaultSeed
///
/// Refuses to run without both, so it can never set a passphrase on the real vault or
/// replace the real Keychain item.
@main
struct VaultSeed {
    static func main() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let dir = environment["ARSIVINYO_DATA_DIR"],
              let service = environment["ARSIVINYO_KEYCHAIN_SERVICE"],
              service != "com.arsivinyo.mac.keybox"
        else {
            print("set ARSIVINYO_DATA_DIR and a non-default ARSIVINYO_KEYCHAIN_SERVICE first")
            exit(2)
        }
        let root = URL(fileURLWithPath: dir)
        let keybox = Keybox(directory: root, params: .fast, keychainService: service)
        if !keybox.isConfigured {
            try keybox.create(passphrase: "a correct horse battery staple")
        } else {
            try keybox.unlock(passphrase: "a correct horse battery staple")
        }
        try keybox.setRemembered(true)

        let vault = Vault(root: root.appendingPathComponent("vault"), keybox: keybox)
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("seed-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }

        let samples = [
            ("Holiday footage", "testsrc=size=640x360:rate=24:duration=4"),
            ("Interview take 3", "smptebars=size=640x360:rate=24:duration=3"),
            ("Old home video", "mandelbrot=size=640x360:rate=24"),
        ]
        for (title, source) in samples {
            let file = scratch.appendingPathComponent("\(title).mp4")
            let ffmpeg = Process()
            ffmpeg.executableURL = URL(fileURLWithPath: "/opt/homebrew/bin/ffmpeg")
            ffmpeg.arguments = ["-hide_banner", "-loglevel", "error", "-y",
                                "-f", "lavfi", "-i", source, "-t", "3",
                                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                                file.path]
            try ffmpeg.run()
            ffmpeg.waitUntilExit()
            try vault.add(file)
            print("in: \(title)")
        }
        print("passphrase: a correct horse battery staple")

        // A music library too, when a music folder is given, with tags, artwork, a
        // favourite and a playlist — so every part of the music screen has something in it.
        guard let musicDir = environment["ARSIVINYO_MUSIC_DIR"] else { return }
        let library = MusicLibrary(musicFolder: URL(fileURLWithPath: musicDir),
                                   supportFolder: root.appendingPathComponent("music"))
        let songs: [(String, String, Int, String)] = [
            ("Gnossienne No. 1", "Erik Satie", 214, "0x3a5f8c"),
            ("Clair de Lune", "Claude Debussy", 301, "0x6b3f8c"),
            ("Spiegel im Spiegel", "Arvo Pärt", 587, "0x2f7a5a"),
            ("Metamorphosis One", "Philip Glass", 356, "0x8c5a2f"),
            ("Nuvole Bianche", "Ludovico Einaudi", 342, "0x8c2f4a"),
        ]
        var ids: [String] = []
        for (index, (title, artist, seconds, colour)) in songs.enumerated() {
            let audio = scratch.appendingPathComponent("\(title).m4a")
            let art = scratch.appendingPathComponent("\(title).jpg")
            for (args, _) in [
                (["-f", "lavfi", "-i", "sine=frequency=\(220 + index * 55):duration=\(seconds)",
                  "-c:a", "aac", "-b:a", "64k", audio.path], 0),
                (["-f", "lavfi", "-i", "color=c=\(colour):s=300x300",
                  "-frames:v", "1", art.path], 1),
            ] {
                let p = Process()
                p.executableURL = URL(fileURLWithPath: "/opt/homebrew/bin/ffmpeg")
                p.arguments = ["-hide_banner", "-loglevel", "error", "-y"] + args
                try p.run()
                p.waitUntilExit()
            }
            let track = try await library.adopt(audio, title: title, artist: artist, artwork: art)
            ids.append(track.id)
            print("song: \(title)")
        }
        library.setFavorite(ids[1], true)
        library.setFavorite(ids[4], true)
        let playlist = library.createPlaylist(named: "Late Night")
        library.add([ids[0], ids[2], ids[3]], to: playlist.id)
    }
}
