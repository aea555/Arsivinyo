import ArsivinyoCore
import Foundation
import Libmpv

/// The player's done criterion on the Mac (`shared/watch/CONTRACT.md`, phase 2): the shared
/// sample, an MKV with HEVC video, AC3 and DTS audio and styled ASS subtitles, plays with the
/// preferred language's tracks picked by themselves, from a file and out of the vault.
///
/// Without a picture (vo=null): what is checked is that libmpv demuxes and decodes all of it
/// and keeps time. The phone plays the same file in MpvInstrumentedTest.
extension CoreChecks {

    mutating func checkPlayer() async throws {
        print("player")
        let sample = Self.repositoryRoot.appendingPathComponent("shared/watch/fixtures/hevc-ac3-dts-ass.mkv")

        check(play(sample.path, vault: nil), "an MKV with HEVC, AC3, DTS and styled ASS plays, in the preferred languages")

        // Out of the vault, through VaultStream: the same file, never in the clear on disk.
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("arsivinyo-player-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }
        let keybox = Keybox(directory: scratch, params: .fast, keychainService: "com.arsivinyo.mac.checks.\(UUID().uuidString)")
        try keybox.create(passphrase: "a correct horse battery staple")
        let vault = Vault(root: scratch.appendingPathComponent("vault"), keybox: keybox)
        let item = try vault.add(sample)
        let stream = VaultStream(try vault.reader(for: item.id))
        check(play(VaultStream.url(id: item.id), vault: stream), "and plays out of the vault")

        // The Matroska header, which every plaintext copy of the sample starts with.
        let header = Data([0x1A, 0x45, 0xDF, 0xA3])
        let files = FileManager.default.enumerator(at: scratch, includingPropertiesForKeys: nil)?.compactMap { $0 as? URL } ?? []
        check(!files.contains { (try? FileHandle(forReadingFrom: $0).read(upToCount: 4)) == header },
              "with nothing in the clear written")
    }

    /// Plays `url` until a second and a half has passed; whether every track was there and
    /// the Turkish ones were picked.
    private func play(_ url: String, vault: VaultStream?) -> Bool {
        guard let mpv = mpv_create() else { return false }
        defer { mpv_terminate_destroy(mpv) }
        for (name, value) in [("vo", "null"), ("ao", "null"), ("slang", "tr,tur,en,eng"), ("alang", "tr,tur,en,eng"),
                              ("keep-open", "yes")] {
            mpv_set_option_string(mpv, name, value)
        }
        guard mpv_initialize(mpv) >= 0 else { return false }
        vault?.register(with: mpv)
        var args: [UnsafeMutablePointer<CChar>?] = ["loadfile", url].map { strdup($0) } + [nil]
        defer { args.forEach { free($0) } }
        args.withUnsafeMutableBufferPointer { buffer in
            buffer.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buffer.count) {
                _ = mpv_command(mpv, $0)
            }
        }

        func double(_ name: String) -> Double {
            var value = 0.0
            return mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &value) >= 0 ? value : 0
        }
        func string(_ name: String) -> String? {
            guard let raw = mpv_get_property_string(mpv, name) else { return nil }
            defer { mpv_free(raw) }
            return String(cString: raw)
        }

        let deadline = Date().addingTimeInterval(10)
        while double("time-pos") < 1.5, Date() < deadline { Thread.sleep(forTimeInterval: 0.05) }
        guard double("time-pos") >= 1.5,
              let json = string("track-list"),
              let tracks = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]] else { return false }
        func of(_ type: String) -> [[String: Any]] { tracks.filter { $0["type"] as? String == type } }
        let audio = of("audio"), subs = of("sub")
        return of("video").map { $0["codec"] as? String } == ["hevc"]
            && Set(audio.compactMap { $0["codec"] as? String }) == ["ac3", "dts"]
            && audio.first { $0["selected"] as? Bool == true }?["codec"] as? String == "dts"
            && subs.count == 1 && subs[0]["codec"] as? String == "ass" && subs[0]["selected"] as? Bool == true
            && string("sub-text") == "Merhaba — altyazı"
            && (string("sub-text-ass") ?? "").contains("\\c&H00FF00&")
    }
}
