import ArsivinyoDSPC
import Foundation

/// The parameters the DSP chain takes. Mirrors `PresetParams` in `shared/dsp/preset_params.h`
/// and `AudioPresetParams` in the phone's `core.ts`.
public struct PresetParams: Codable, Equatable, Hashable, Sendable {
    /// Playback rate. Resampling with no pitch correction, so tempo and pitch move together:
    /// that is what makes "slowed" sound slowed rather than stretched.
    public var rate = 1.0
    public var reverbMix = 0.0
    public var reverbRoom = 0.5
    public var reverbDamp = 0.5
    public var reverbWidth = 1.0
    public var reverbPreDelayMs = 0.0
    public var bassGainDb = 0.0
    public var bassFreqHz = 100.0
    public var trebleGainDb = 0.0
    public var trebleFreqHz = 6000.0
    public var outputGainDb = 0.0
    public var limiterEnabled = true
    public var limiterCeilingDb = -0.3

    public init() {}

    /// A missing or mistyped field keeps its default, so parameters written by another
    /// build, with fields this one lacks or lacking ones it has, still read.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func value(_ key: CodingKeys, _ fallback: Double) -> Double {
            (try? c.decode(Double.self, forKey: key)) ?? fallback
        }
        rate = value(.rate, 1)
        reverbMix = value(.reverbMix, 0)
        reverbRoom = value(.reverbRoom, 0.5)
        reverbDamp = value(.reverbDamp, 0.5)
        reverbWidth = value(.reverbWidth, 1)
        reverbPreDelayMs = value(.reverbPreDelayMs, 0)
        bassGainDb = value(.bassGainDb, 0)
        bassFreqHz = value(.bassFreqHz, 100)
        trebleGainDb = value(.trebleGainDb, 0)
        trebleFreqHz = value(.trebleFreqHz, 6000)
        outputGainDb = value(.outputGainDb, 0)
        limiterEnabled = (try? c.decode(Bool.self, forKey: .limiterEnabled)) ?? true
        limiterCeilingDb = value(.limiterCeilingDb, -0.3)
    }

    /// Each numeric parameter, its range and step, in the phone's order. The order matters
    /// only for the spec string, which then reads the same on both.
    ///
    /// These must stay in step with `PresetParams::Clamp()` in preset_params.cpp: the C++
    /// clamps to the same bounds, and a mismatch is a slider whose top end does nothing.
    ///
    /// `nonisolated(unsafe)` because key paths are not marked Sendable, though a `let` of
    /// them can never change.
    nonisolated(unsafe) public static let ranges: [(key: WritableKeyPath<PresetParams, Double>, name: String,
                                min: Double, max: Double, step: Double)] = [
        (\.rate, "rate", 0.5, 2, 0.01),
        (\.reverbMix, "reverbMix", 0, 1, 0.01),
        (\.reverbRoom, "reverbRoom", 0, 0.97, 0.01),
        (\.reverbDamp, "reverbDamp", 0, 1, 0.01),
        (\.reverbWidth, "reverbWidth", 0, 1, 0.01),
        (\.reverbPreDelayMs, "reverbPreDelayMs", 0, 200, 1),
        (\.bassGainDb, "bassGainDb", -24, 24, 0.5),
        (\.bassFreqHz, "bassFreqHz", 20, 1000, 5),
        (\.trebleGainDb, "trebleGainDb", -24, 24, 0.5),
        (\.trebleFreqHz, "trebleFreqHz", 1000, 16000, 100),
        (\.outputGainDb, "outputGainDb", -24, 24, 0.5),
        (\.limiterCeilingDb, "limiterCeilingDb", -12, 0, 0.1),
    ]

    /// Every value pulled into its range; anything not a number falls back to its default.
    public var sanitized: PresetParams {
        var out = self
        let defaults = PresetParams()
        for range in Self.ranges {
            let value = self[keyPath: range.key]
            out[keyPath: range.key] = value.isFinite
                ? Swift.min(range.max, Swift.max(range.min, value))
                : defaults[keyPath: range.key]
        }
        return out
    }

    /// The `key=value;` string the renderer takes. Only what differs from the defaults is
    /// written, exactly as the phone's `buildParamsSpec` does.
    public var spec: String {
        let clean = sanitized
        let defaults = PresetParams()
        var parts: [String] = []
        for range in Self.ranges where clean[keyPath: range.key] != defaults[keyPath: range.key] {
            parts.append("\(range.name)=\(Self.number(clean[keyPath: range.key]))")
        }
        if clean.limiterEnabled != defaults.limiterEnabled {
            parts.append("limiterEnabled=\(clean.limiterEnabled)")
        }
        return parts.joined(separator: ";")
    }

    /// As JavaScript prints a number: 2, not 2.0.
    static func number(_ value: Double) -> String {
        value == value.rounded() && abs(value) < 1e15 ? String(Int(value)) : String(value)
    }
}

public struct AudioPreset: Codable, Identifiable, Hashable, Sendable {
    public var id: String
    /// For a built-in this is the English name; what is shown comes from the string table.
    public var name: String
    public var builtIn: Bool
    /// Appended to the source title, as " (Slowed + Reverb)".
    public var titleSuffix: String
    public var params: PresetParams
    /// A built-in whose parameters were changed from what ships. Never set on a user's own.
    public var modified: Bool?
    public var createdAt: Double?
    public var updatedAt: Double?

    /// The ones that ship, with the phone's ids and values. Ids are wire values: a backup
    /// and the auto-apply setting both name presets by them.
    public static let builtIns: [AudioPreset] = {
        var slowed = PresetParams()
        slowed.rate = 0.85
        slowed.reverbMix = 0.28
        slowed.reverbRoom = 0.72
        slowed.reverbDamp = 0.42
        slowed.reverbWidth = 1
        slowed.reverbPreDelayMs = 20
        slowed.bassGainDb = 2
        slowed.bassFreqHz = 120

        var nightcore = PresetParams()
        nightcore.rate = 1.25
        nightcore.reverbMix = 0.06
        nightcore.reverbRoom = 0.4
        nightcore.reverbDamp = 0.5
        nightcore.trebleGainDb = 1.5

        var bass = PresetParams()
        bass.bassGainDb = 6
        bass.bassFreqHz = 90
        bass.outputGainDb = -1

        return [
            AudioPreset(id: "slowed-reverb", name: "Slowed + Reverb", builtIn: true,
                        titleSuffix: " (Slowed + Reverb)", params: slowed),
            AudioPreset(id: "nightcore", name: "Nightcore", builtIn: true,
                        titleSuffix: " (Nightcore)", params: nightcore),
            AudioPreset(id: "bass-boost", name: "Bass Boost", builtIn: true,
                        titleSuffix: " (Bass Boost)", params: bass),
        ]
    }()
}

/// Which presets a new audio download is rendered through, and whether the original stays.
public struct AutoPresetConfig: Codable, Equatable, Sendable {
    public var keepOriginal = true
    public var presetIds: [String] = []

    public init(keepOriginal: Bool = true, presetIds: [String] = []) {
        self.keepOriginal = keepOriginal
        self.presetIds = presetIds
    }

    /// Selecting nothing would throw the download away, so it is not a legal state.
    public var isValid: Bool { keepOriginal || !presetIds.isEmpty }
}

/// The user's presets, the changes made to the built-ins, and the auto-apply choice.
///
/// One JSON file in the app's data folder. The phone keeps the same three things apart,
/// in its settings store; what crosses in a backup is the auto-apply blob, in the phone's
/// shape.
public final class PresetStore: @unchecked Sendable {
    private struct Stored: Codable {
        var custom: [AudioPreset] = []
        var builtInOverrides: [String: PresetParams] = [:]
        var autoApply = AutoPresetConfig()
    }

    private let url: URL
    private let lock = NSLock()

    public init(directory: URL) {
        url = directory.appendingPathComponent("presets.json")
    }

    // MARK: - Reading

    /// Built-ins first, with any changes applied, then the user's own by name.
    public func all() -> [AudioPreset] {
        let stored = read()
        let builtIns = AudioPreset.builtIns.map { preset -> AudioPreset in
            var preset = preset
            if let override = stored.builtInOverrides[preset.id] {
                preset.params = override
                preset.modified = true
            } else {
                preset.modified = false
            }
            return preset
        }
        let custom = stored.custom.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        return builtIns + custom
    }

    public func preset(_ id: String) -> AudioPreset? { all().first { $0.id == id } }

    public var autoApply: AutoPresetConfig {
        // Presets deleted since are dropped, so nothing renders under a name that is gone.
        let known = Set(all().map(\.id))
        var config = read().autoApply
        config.presetIds = config.presetIds.filter(known.contains)
        return config.isValid ? config : AutoPresetConfig()
    }

    // MARK: - Changing

    @discardableResult
    public func create(named name: String, params: PresetParams) -> AudioPreset {
        let now = Date().timeIntervalSince1970 * 1000
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let preset = AudioPreset(id: "custom-" + UUID().uuidString.lowercased(), name: trimmed,
                                 builtIn: false, titleSuffix: " (\(trimmed))",
                                 params: params.sanitized, createdAt: now, updatedAt: now)
        mutate { $0.custom.append(preset) }
        return preset
    }

    /// For a built-in this records a change, which `reset` can undo; for the user's own it
    /// replaces the values.
    public func save(_ id: String, params: PresetParams) {
        let clean = params.sanitized
        mutate { stored in
            if let builtIn = AudioPreset.builtIns.first(where: { $0.id == id }) {
                if clean == builtIn.params {
                    stored.builtInOverrides[id] = nil
                } else {
                    stored.builtInOverrides[id] = clean
                }
            } else if let index = stored.custom.firstIndex(where: { $0.id == id }) {
                stored.custom[index].params = clean
                stored.custom[index].updatedAt = Date().timeIntervalSince1970 * 1000
            }
        }
    }

    public func rename(_ id: String, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        mutate { stored in
            guard let index = stored.custom.firstIndex(where: { $0.id == id }) else { return }
            stored.custom[index].name = trimmed
            stored.custom[index].titleSuffix = " (\(trimmed))"
        }
    }

    public func reset(_ id: String) {
        mutate { $0.builtInOverrides[id] = nil }
    }

    /// Deleting one takes it out of auto-apply as well; if that would leave nothing
    /// selected, the original is kept instead of the download being thrown away.
    public func delete(_ id: String) {
        mutate { stored in
            stored.custom.removeAll { $0.id == id }
            stored.autoApply.presetIds.removeAll { $0 == id }
            if !stored.autoApply.isValid { stored.autoApply.keepOriginal = true }
        }
    }

    public func setAutoApply(_ config: AutoPresetConfig) {
        mutate { $0.autoApply = config.isValid ? config : AutoPresetConfig() }
    }

    // MARK: - For a backup

    /// The auto-apply setting in the shape the phone stores and backs up:
    /// `{keepOriginal, presets: [{id, paramsSpec, titleSuffix}]}`.
    public func autoApplyBlob() -> [String: Any] {
        let config = autoApply
        let byId = Dictionary(uniqueKeysWithValues: all().map { ($0.id, $0) })
        return [
            "keepOriginal": config.keepOriginal,
            "presets": config.presetIds.compactMap { byId[$0] }.map {
                ["id": $0.id, "paramsSpec": $0.params.spec, "titleSuffix": $0.titleSuffix]
            },
        ]
    }

    /// Reads the phone's blob back. Presets it names that do not exist here are dropped.
    public func restoreAutoApply(from blob: [String: Any]) {
        let ids = (blob["presets"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String }
        let known = Set(all().map(\.id))
        setAutoApply(AutoPresetConfig(keepOriginal: blob["keepOriginal"] as? Bool != false,
                                      presetIds: ids.filter(known.contains)))
    }

    /// The user's presets and the changes to the built-ins, as the phone keeps them in its
    /// settings: `@arsivinyo_audio_presets_custom_v1` is a JSON array of presets and
    /// `@arsivinyo_audio_presets_builtin_overrides_v1` an object of parameters by id, each
    /// stored as a string. A backup's settings section carries exactly these.
    public func settingsBlob() -> [String: String] {
        let stored = read()
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        var out: [String: String] = [:]
        if let custom = try? encoder.encode(stored.custom) {
            out[Self.customKey] = String(decoding: custom, as: UTF8.self)
        }
        if let overrides = try? encoder.encode(stored.builtInOverrides) {
            out[Self.overridesKey] = String(decoding: overrides, as: UTF8.self)
        }
        return out
    }

    /// Takes presets from a backup's settings. Additive, as every restore is: a preset whose
    /// id is already here is left as it is; changes to built-ins are taken as they come.
    public func restoreSettings(from blob: [String: Any]) {
        let decoder = JSONDecoder()
        let incoming = (blob[Self.customKey] as? String).flatMap {
            try? decoder.decode([LenientPreset].self, from: Data($0.utf8))
        } ?? []
        let overrides = (blob[Self.overridesKey] as? String).flatMap {
            try? decoder.decode([String: PresetParams].self, from: Data($0.utf8))
        } ?? [:]
        mutate { stored in
            let have = Set(stored.custom.map(\.id))
            for preset in incoming.compactMap(\.preset) where !have.contains(preset.id) {
                stored.custom.append(preset)
            }
            for (id, params) in overrides where AudioPreset.builtIns.contains(where: { $0.id == id }) {
                stored.builtInOverrides[id] = params.sanitized
            }
        }
    }

    static let customKey = "@arsivinyo_audio_presets_custom_v1"
    static let overridesKey = "@arsivinyo_audio_presets_builtin_overrides_v1"

    /// A preset as another build may have written it: anything missing takes the phone's
    /// default, and one without an id or a name is dropped rather than failing the rest.
    private struct LenientPreset: Decodable {
        let preset: AudioPreset?

        private enum Keys: String, CodingKey { case id, name, titleSuffix, params, createdAt, updatedAt }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: Keys.self)
            let id = ((try? c.decode(String.self, forKey: .id)) ?? "").trimmingCharacters(in: .whitespaces)
            let name = ((try? c.decode(String.self, forKey: .name)) ?? "").trimmingCharacters(in: .whitespaces)
            guard !id.isEmpty, !name.isEmpty else { preset = nil; return }
            let suffix = (try? c.decode(String.self, forKey: .titleSuffix)).flatMap { $0.isEmpty ? nil : $0 }
            preset = AudioPreset(id: id, name: name, builtIn: false, titleSuffix: suffix ?? " (\(name))",
                                 params: ((try? c.decode(PresetParams.self, forKey: .params)) ?? PresetParams()).sanitized,
                                 createdAt: try? c.decode(Double.self, forKey: .createdAt),
                                 updatedAt: try? c.decode(Double.self, forKey: .updatedAt))
        }
    }

    // MARK: - Private

    private func read() -> Stored {
        lock.withLock {
            guard let data = try? Data(contentsOf: url),
                  let stored = try? JSONDecoder().decode(Stored.self, from: data) else { return Stored() }
            return stored
        }
    }

    private func mutate(_ change: (inout Stored) -> Void) {
        lock.withLock {
            var stored = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(Stored.self, from: $0) }
                ?? Stored()
            change(&stored)
            guard let data = try? JSONEncoder().encode(stored) else { return }
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            try? data.write(to: url, options: .atomic)
        }
    }
}

/// Runs a render through the shared C++.
public enum PresetRenderer {
    public struct Failure: Error, CustomStringConvertible {
        public let description: String
        public init(description: String) { self.description = description }
    }

    /// Lossless in, lossless out; otherwise AAC. Re-encoding a lossy track to FLAC would
    /// triple its size and recover nothing — the phone's rule.
    public static func outputFormat(forSource url: URL) -> String {
        ["flac", "wav", "aiff", "aif", "alac"].contains(url.pathExtension.lowercased()) ? "flac" : "m4a"
    }

    /// Renders `input` into a new file in `directory`. Blocking work runs off the caller.
    ///
    /// - Parameters:
    ///   - progress: 0…1, called on an arbitrary thread.
    ///   - cancelFlag: a file whose appearance stops the render.
    public static func render(input: URL, into directory: URL, fileName: String, preset: AudioPreset,
                              title: String, artist: String, ffmpeg: URL, ffprobe: URL,
                              cancelFlag: URL? = nil,
                              progress: (@Sendable (Double) -> Void)? = nil) async throws -> URL {
        let format = outputFormat(forSource: input)
        let output = directory.appendingPathComponent(fileName).appendingPathExtension(format)
        let progressFile = directory.appendingPathComponent(".progress-\(UUID().uuidString).json")
        let spec = preset.params.spec

        // The render blocks until it is done. A watcher reads the progress file meanwhile.
        let watcher = Task.detached {
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(300))
                if let data = try? Data(contentsOf: progressFile),
                   let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let percent = object["percent"] as? Double {
                    progress?(percent / 100)
                }
            }
        }
        defer {
            watcher.cancel()
            try? FileManager.default.removeItem(at: progressFile)
        }

        let error = await Task.detached { () -> String? in
            var buffer = [CChar](repeating: 0, count: 1024)
            let ok = av_render_preset(ffmpeg.path, ffprobe.path, input.path, output.path, spec, format,
                                      title, artist, progressFile.path, cancelFlag?.path,
                                      &buffer, UInt(buffer.count))
            let message = buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }
            return ok == 1 ? nil : String(decoding: message, as: UTF8.self)
        }.value
        if let error {
            try? FileManager.default.removeItem(at: output)
            throw Failure(description: error)
        }
        return output
    }
}
