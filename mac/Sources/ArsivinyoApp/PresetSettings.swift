import ArsivinyoCore
import SwiftUI

/// The presets: a list on the left, the chosen one's controls on the right.
struct PresetSettings: View {
    @Environment(AppModel.self) private var model
    @State private var selectedId: String?
    @State private var naming = false

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                List(selection: $selectedId) {
                    ForEach(model.presetList) { preset in
                        HStack {
                            Text(AppModel.displayName(of: preset))
                            Spacer()
                            if model.autoPresets.presetIds.contains(preset.id) {
                                Image(systemName: "arrow.down.circle")
                                    .foregroundStyle(.secondary)
                                    .help("Applied to every audio download")
                            }
                        }
                        .tag(preset.id)
                    }
                }
                .listStyle(.bordered)
                HStack(spacing: 0) {
                    Button { naming = true } label: { Image(systemName: "plus").frame(width: 24, height: 20) }
                        .help("New preset, starting from the selected one")
                    Button {
                        if let id = selectedId { model.deletePreset(id); selectedId = nil }
                    } label: { Image(systemName: "minus").frame(width: 24, height: 20) }
                        .disabled(selected?.builtIn != false)
                        .help("Delete the selected preset")
                    Spacer()
                }
                .buttonStyle(.borderless)
                .padding(4)
                Toggle("Keep the original of a download", isOn: Binding(
                    get: { model.autoPresets.keepOriginal },
                    set: { model.setKeepOriginal($0) }))
                    .toggleStyle(.checkbox)
                    .disabled(model.autoPresets.presetIds.isEmpty)
                    .help("With presets applied to every download, whether the unchanged download stays too.")
                    .padding(.vertical, 6)
            }
            .frame(width: 210)
            .padding([.leading, .vertical], 16)

            if let preset = selected {
                PresetEditor(preset: preset).id(preset.id)
            } else {
                ContentUnavailableView("Choose a preset", systemImage: "slider.horizontal.3",
                                       description: Text("Right-click songs in Music to apply one."))
                    .frame(maxWidth: .infinity)
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
    @State private var params: PresetParams

    init(preset: AudioPreset) {
        self.preset = preset
        _params = State(initialValue: preset.params)
    }

    var body: some View {
        Form {
            Section {
                Toggle("Apply to every audio download", isOn: Binding(
                    get: { model.autoPresets.presetIds.contains(preset.id) },
                    set: { model.setAutoApply(preset.id, $0) }))
            } header: {
                HStack {
                    Text(AppModel.displayName(of: preset)).font(.headline)
                    Spacer()
                    if preset.builtIn && preset.modified == true {
                        Button("Restore Defaults") {
                            model.resetPreset(preset.id)
                            params = AudioPreset.builtIns.first { $0.id == preset.id }?.params ?? params
                        }
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
