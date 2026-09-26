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

        // A meme collection, when a meme folder is given: clips and images with the kind of
        // captions, tags and people the real ones carry, one untagged, one private.
        if let memeDir = environment["ARSIVINYO_MEME_DIR"] {
            let folder = URL(fileURLWithPath: memeDir)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let memes = MemeLibrary(support: root.appendingPathComponent("memes"), vault: vault, keybox: keybox) {
                try MemeDeviceKey.load(service: service + ".memes")
            }
            let seeds: [(String, String, String, String?, [(String, [MemeLibrary.Facet])], [String], Bool)] = [
                ("arda.mp4", "testsrc2=size=640x640:rate=25", "bizim laubalilik seviyesi", "futbolcaps",
                 [("laubalilik", [.vibe, .action])], ["Arda Turan"], false),
                ("avci.mp4", "mandelbrot=size=640x640:rate=25", "hocam bu ne", "tffcaps",
                 [("iştah", [.reaction]), ("beğeni", [.reaction])], ["Abdullah Avcı"], false),
                ("terim.mp4", "gradients=size=640x640:rate=25:c0=orange:c1=yellow", "imparator tatilde", "futbolcaps",
                 [("rahat", [.vibe]), ("yaz", [.context])], ["Fatih Terim"], false),
                ("kocaman.mp4", "gradients=size=640x640:rate=25:c0=navy:c1=gray", "o bakış", "fenercaps",
                 [("hüzün", [.emotion])], ["Aykut Kocaman"], false),
                ("carpma.mp4", "life=size=640x640:rate=25:mold=10", "adam bisikletliyi indirdi", "trafikcaps",
                 [], [], false),
                ("gizli.mp4", "cellauto=size=640x640:rate=25", "sadece bende kalsın", "gizlicaps",
                 [("gizli", [.context])], [], true),
            ]
            for (name, source, caption, account, tags, people, hidden) in seeds {
                let file = folder.appendingPathComponent(name)
                let p = Process()
                p.executableURL = URL(fileURLWithPath: "/opt/homebrew/bin/ffmpeg")
                p.arguments = ["-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", source, "-t", "3",
                               "-c:v", "libx264", "-pix_fmt", "yuv420p", file.path]
                try p.run()
                p.waitUntilExit()
                let item = try memes.add(file, source: .init(platform: "twitter", account: account, caption: caption,
                                                             url: "https://x.com/\(account ?? "x")/status/1"),
                                         tagNames: tags, people: people, tagged: !tags.isEmpty)
                if hidden { try memes.makePrivate(item.id) }
                print("meme: \(name)")
            }

            // Real faces, for the faces screen: the public-domain fixture frames as images,
            // and one as a short video, so both paths are scanned.
            var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            while !FileManager.default.fileExists(atPath: dir.appendingPathComponent("shared/faces/fixtures").path) {
                dir = dir.deletingLastPathComponent()
            }
            for name in ["crew", "armstrong", "aldrin"] {
                let png = folder.appendingPathComponent("\(name).png")
                for args in [["-i", dir.appendingPathComponent("shared/faces/fixtures/\(name).ppm").path, png.path]]
                    + (name == "armstrong" ? [["-loop", "1", "-i", png.path, "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                                               "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", folder.appendingPathComponent("neil.mp4").path]] : []) {
                    let p = Process()
                    p.executableURL = URL(fileURLWithPath: "/opt/homebrew/bin/ffmpeg")
                    p.arguments = ["-hide_banner", "-loglevel", "error", "-y"] + args
                    try p.run()
                    p.waitUntilExit()
                }
                try memes.add(png, source: .init(platform: "import"))
                print("meme: \(name).png")
            }
            try memes.add(folder.appendingPathComponent("neil.mp4"), source: .init(platform: "import"))
        }

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
