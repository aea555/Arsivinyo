import AVFoundation
import Foundation
import ArsivinyoCore

/// Audio presets: the same parameter strings the phone sends, and a render that really
/// changes the sound.
extension CoreChecks {

    mutating func checkPresets() async throws {
        print("audio presets")

        // What the phone's own buildParamsSpec prints for these, taken by running core.ts.
        // Byte for byte, so a preset set up on one renders the same on the other.
        let phone = [
            "slowed-reverb": "rate=0.85;reverbMix=0.28;reverbRoom=0.72;reverbDamp=0.42;reverbPreDelayMs=20;bassGainDb=2;bassFreqHz=120",
            "nightcore": "rate=1.25;reverbMix=0.06;reverbRoom=0.4;trebleGainDb=1.5",
            "bass-boost": "bassGainDb=6;bassFreqHz=90;outputGainDb=-1",
        ]
        for preset in AudioPreset.builtIns {
            check(preset.params.spec == phone[preset.id],
                  "\(preset.id) is sent as the phone sends it (\(preset.params.spec))")
        }
        var custom = PresetParams()
        custom.rate = 2
        custom.limiterEnabled = false
        custom.trebleFreqHz = 12000.5
        custom.bassGainDb = -3.5
        check(custom.spec == "rate=2;bassGainDb=-3.5;trebleFreqHz=12000.5;limiterEnabled=false",
              "whole numbers, fractions and the limiter switch are written as the phone writes them")
        var wild = PresetParams()
        wild.rate = 40
        wild.reverbMix = .nan
        check(wild.sanitized.rate == 2 && wild.sanitized.reverbMix == 0,
              "a value out of range is pulled in, and one that is not a number falls back")

        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-presets-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }

        let store = PresetStore(directory: scratch)
        var slower = AudioPreset.builtIns[0].params
        slower.rate = 0.8
        store.save("slowed-reverb", params: slower)
        check(store.preset("slowed-reverb")?.modified == true, "a changed built-in is marked changed")
        store.reset("slowed-reverb")
        check(store.preset("slowed-reverb") == AudioPreset.builtIns[0].with(modified: false),
              "and resetting brings back exactly what ships")

        let mine = store.create(named: "Mine", params: custom)
        store.setAutoApply(AutoPresetConfig(keepOriginal: false, presetIds: [mine.id]))
        check(store.autoApply.presetIds == [mine.id], "a preset can be applied to every download")
        store.delete(mine.id)
        check(store.autoApply == AutoPresetConfig(keepOriginal: true, presetIds: []),
              "deleting it keeps the original rather than throwing downloads away")
        store.setAutoApply(AutoPresetConfig(keepOriginal: false, presetIds: []))
        check(store.autoApply.keepOriginal, "a setting that keeps nothing is refused")

        store.setAutoApply(AutoPresetConfig(keepOriginal: true, presetIds: ["nightcore"]))
        let blob = store.autoApplyBlob()
        let entries = blob["presets"] as? [[String: Any]] ?? []
        check(blob["keepOriginal"] as? Bool == true && entries.first?["paramsSpec"] as? String == phone["nightcore"],
              "the auto-apply blob has the phone's shape, for a backup")
        let other = PresetStore(directory: scratch.appendingPathComponent("other"))
        other.restoreAutoApply(from: ["keepOriginal": false, "presets": [["id": "nightcore"], ["id": "gone"]]])
        check(other.autoApply == AutoPresetConfig(keepOriginal: false, presetIds: ["nightcore"]),
              "and reads back, without presets that do not exist here")

        // A real render. Rate alone, because a preset with reverb rightly gains the tail of
        // the echo at the end, which the C++ flushes rather than cut off: at 1.25x, two
        // seconds come out as 1.6.
        guard let ffmpeg = ["/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"]
                .first(where: { FileManager.default.isExecutableFile(atPath: $0) }),
              let ffprobe = ["/opt/homebrew/bin/ffprobe", "/usr/local/bin/ffprobe"]
                .first(where: { FileManager.default.isExecutableFile(atPath: $0) })
        else {
            print("  skip  no ffmpeg to render with")
            return
        }
        let source = scratch.appendingPathComponent("tone.m4a")
        let make = Process()
        make.executableURL = URL(fileURLWithPath: ffmpeg)
        make.arguments = ["-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                          "-i", "sine=frequency=440:duration=2", "-ac", "2", "-c:a", "aac", source.path]
        try make.run()
        make.waitUntilExit()

        let nightcore = AudioPreset.builtIns[1]
        var faster = nightcore
        faster.params = PresetParams()
        faster.params.rate = 1.25
        let output = try await PresetRenderer.render(
            input: source, into: scratch, fileName: "tone (Faster)", preset: faster,
            title: "Tone (Faster)", artist: "Checks",
            ffmpeg: URL(fileURLWithPath: ffmpeg), ffprobe: URL(fileURLWithPath: ffprobe))
        let seconds = try await AVURLAsset(url: output).load(.duration).seconds
        check(output.pathExtension == "m4a", "a lossy source renders to AAC, not a bloated FLAC")
        check(abs(seconds - 1.6) < 0.1, String(format: "and a faster rate really plays faster (%.2f s from 2)", seconds))

        let cancel = scratch.appendingPathComponent("cancel.flag")
        try Data().write(to: cancel)
        let cancelled = try? await PresetRenderer.render(
            input: source, into: scratch, fileName: "never", preset: nightcore, title: "", artist: "",
            ffmpeg: URL(fileURLWithPath: ffmpeg), ffprobe: URL(fileURLWithPath: ffprobe), cancelFlag: cancel)
        check(cancelled == nil && !FileManager.default.fileExists(atPath: scratch.appendingPathComponent("never.m4a").path),
              "a cancelled render stops and leaves nothing behind")
    }
}

private extension AudioPreset {
    func with(modified: Bool) -> AudioPreset {
        var copy = self
        copy.modified = modified
        return copy
    }
}
