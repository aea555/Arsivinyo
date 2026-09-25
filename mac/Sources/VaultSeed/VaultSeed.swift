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
    static func main() throws {
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
    }
}
