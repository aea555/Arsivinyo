import ArsivinyoCore
import SwiftUI

/// The presets, one Form like every other Settings tab: the preset chosen from a menu at the
/// top, its controls below. It used to be a list beside a Form, the only two-column tab, and
/// the toolbar above it treated the two columns differently: the tabs above the list lost
/// their hover. One column has nothing to split.
struct PresetSettings: View {
    @Environment(AppModel.self) private var model
    @State private var selectedId: String?
    @State private var naming = false

    var body: some View {
        Group {
            if let preset = selected {
                PresetEditor(preset: preset, selectedId: $selectedId, naming: $naming).id(preset.id)
            } else {
                ContentUnavailableView("Choose a preset", systemImage: "slider.horizontal.3",
                                       description: Text("Right-click songs in Music to apply one."))
            }
        }
        .onAppear { if selectedId == nil { selectedId = model.presetList.first?.id } }
        .sheet(isPresented: $naming) {
            NameSheet(title: "New Preset", initial: "") { name in
                selectedId = model.createPreset(named: name, from: selected?.params ?? PresetParams()).id
            }
        }
    }

    private var selected: AudioPreset? {
        model.presetList.first { $0.id == selectedId }
    }
}

private struct PresetEditor: View {
    @Environment(AppModel.self) private var model
    let preset: AudioPreset
    @Binding var selectedId: String?
    @Binding var naming: Bool
    @State private var params: PresetParams

    init(preset: AudioPreset, selectedId: Binding<String?>, naming: Binding<Bool>) {
        self.preset = preset
        _selectedId = selectedId
        _naming = naming
        _params = State(initialValue: preset.params)
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Preset") {
                    HStack(spacing: 8) {
                        Picker("Preset", selection: $selectedId) {
                            ForEach(model.presetList) { item in
                                Text(AppModel.displayName(of: item)).tag(Optional(item.id))
                            }
                        }
                        .labelsHidden()
                        .fixedSize()
                        Button { naming = true } label: { Image(systemName: "plus") }
                            .help("New preset, starting from this one")
                        Button {
                            model.deletePreset(preset.id)
                            selectedId = model.presetList.first?.id
                        } label: { Image(systemName: "minus") }
                            .disabled(preset.builtIn)
                            .help("Delete this preset")
                    }
                }
                Toggle("Apply to every audio download", isOn: Binding(
                    get: { model.autoPresets.presetIds.contains(preset.id) },
                    set: { model.setAutoApply(preset.id, $0) }))
                Toggle("Keep the original of a download", isOn: Binding(
                    get: { model.autoPresets.keepOriginal },
                    set: { model.setKeepOriginal($0) }))
                    .disabled(model.autoPresets.presetIds.isEmpty)
                    .help("With presets applied to every download, whether the unchanged download stays too.")
                if preset.builtIn && preset.modified == true {
                    Button("Restore Defaults") {
                        model.resetPreset(preset.id)
                        params = AudioPreset.builtIns.first { $0.id == preset.id }?.params ?? params
                    }
                }
            }
            Section("Speed") {
                slider("Rate", \.rate) { String(format: "%.2f×", $0) }
            }
            Section("Reverb") {
                slider("Amount", \.reverbMix) { "\(Int(($0 * 100).rounded()))%" }
                slider("Room size", \.reverbRoom) { "\(Int(($0 * 100).rounded()))%" }
                slider("Damping", \.reverbDamp) { "\(Int(($0 * 100).rounded()))%" }
                slider("Stereo width", \.reverbWidth) { "\(Int(($0 * 100).rounded()))%" }
                slider("Pre-delay", \.reverbPreDelayMs) { "\(Int($0)) ms" }
            }
            Section("Tone") {
                slider("Bass", \.bassGainDb) { Self.decibels($0) }
                slider("Bass frequency", \.bassFreqHz) { "\(Int($0)) Hz" }
                slider("Treble", \.trebleGainDb) { Self.decibels($0) }
                slider("Treble frequency", \.trebleFreqHz) { String(format: "%.1f kHz", $0 / 1000) }
            }
            Section("Output") {
                slider("Gain", \.outputGainDb) { Self.decibels($0) }
                Toggle("Limiter", isOn: $params.limiterEnabled)
                    .help("Stops a boosted track clipping. Best left on.")
                if params.limiterEnabled {
                    slider("Ceiling", \.limiterCeilingDb) { Self.decibels($0) }
                }
            }
        }
        .formStyle(.grouped)
        .onChange(of: params) { _, new in model.savePreset(preset.id, params: new) }
    }

    private func slider(_ title: LocalizedStringKey, _ key: WritableKeyPath<PresetParams, Double>,
                        format: @escaping (Double) -> String) -> some View {
        let range = PresetParams.ranges.first { $0.key == key }!
        return LabeledContent {
            HStack {
                // Rounded to the step here rather than given to the slider as `step:`, which
                // draws a tick for every step: hundreds of them on most of these.
                Slider(value: Binding(get: { params[keyPath: key] },
                                      set: { params[keyPath: key] = ($0 / range.step).rounded() * range.step }),
                       in: range.min...range.max)
                Text(format(params[keyPath: key]))
                    .monospacedDigit().foregroundStyle(.secondary)
                    .frame(width: 64, alignment: .trailing)
            }
        } label: {
            Text(title)
        }
    }

    private static func decibels(_ value: Double) -> String {
        String(format: "%@%.1f dB", value > 0 ? "+" : "", value)
    }
}

extension AppModel {
    func savePreset(_ id: String, params: PresetParams) {
        presets.save(id, params: params)
        refreshPresets()
    }

    func resetPreset(_ id: String) {
        presets.reset(id)
        refreshPresets()
    }

    @discardableResult
    func createPreset(named name: String, from params: PresetParams) -> AudioPreset {
        let preset = presets.create(named: name, params: params)
        refreshPresets()
        return preset
    }

    func deletePreset(_ id: String) {
        presets.delete(id)
        refreshPresets()
    }

    func setAutoApply(_ id: String, _ on: Bool) {
        var config = presets.autoApply
        config.presetIds.removeAll { $0 == id }
        if on { config.presetIds.append(id) }
        // Turning the last one off with the original not kept would keep nothing at all.
        if !config.isValid { config.keepOriginal = true }
        presets.setAutoApply(config)
        refreshPresets()
    }

    func setKeepOriginal(_ keep: Bool) {
        var config = presets.autoApply
        config.keepOriginal = keep
        presets.setAutoApply(config)
        refreshPresets()
    }
}
