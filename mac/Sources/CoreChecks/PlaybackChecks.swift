import AVFoundation
import Foundation
import ArsivinyoCore

/// Playing an encrypted vault item without writing the plaintext down.
///
/// This decides the design. If AVFoundation will read through the resource loader, the Mac
/// needs no loopback server and no decrypted temp file. It is the same check that decided
/// the Qt app's design, against a real h264 file rather than bytes that only look like one.
extension CoreChecks {

    mutating func checkPlayback() async throws {
        print("playback")
        let ffmpeg = ["/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"]
            .first { FileManager.default.isExecutableFile(atPath: $0) }
        guard let ffmpeg else {
            print("  skip  no ffmpeg to make a sample with")
            return
        }

        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-playback-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }

        // Three seconds of colour bars and a tone: a real container to parse.
        let sample = scratch.appendingPathComponent("sample.mp4")
        let make = Process()
        make.executableURL = URL(fileURLWithPath: ffmpeg)
        make.arguments = ["-hide_banner", "-loglevel", "error", "-y",
                          "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15:duration=3",
                          "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                          "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                          "-c:a", "aac", "-shortest", sample.path]
        try make.run()
        make.waitUntilExit()
        guard make.terminationStatus == 0 else {
            check(false, "ffmpeg made a sample")
            return
        }

        let keybox = Keybox(directory: scratch, params: .fast,
                            keychainService: "com.arsivinyo.mac.checks.\(UUID().uuidString)")
        try keybox.create(passphrase: "a correct horse battery staple")
        let vault = Vault(root: scratch.appendingPathComponent("vault"), keybox: keybox)
        let item = try vault.add(sample)

        let loader = VaultAssetLoader(reader: try vault.reader(for: item.id),
                                      contentType: item.contentType)
        let asset = loader.makeAsset(id: item.id)

        do {
            let duration = try await asset.load(.duration).seconds
            let video = try await asset.loadTracks(withMediaType: .video)
            let audio = try await asset.loadTracks(withMediaType: .audio)
            let playable = try await asset.load(.isPlayable)

            check(playable, "AVFoundation reads it through the loader")
            check(duration > 2.5 && duration < 3.5,
                  String(format: "and reports about three seconds (%.2f)", duration))
            check(!video.isEmpty, "with a video track")
            check(!audio.isEmpty, "and an audio track")
        } catch {
            check(false, "the asset loads: \(error)")
        }

        // The only place the plaintext exists is the source it came from.
        let leftovers = FileManager.default.enumerator(atPath: scratch.path)?
            .compactMap { $0 as? String }
            .filter { $0.hasSuffix(".mp4") && $0 != "sample.mp4" } ?? []
        check(leftovers.isEmpty, "and no decrypted copy was written anywhere")
    }
}
