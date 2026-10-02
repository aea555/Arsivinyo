import ArsivinyoCore
import SwiftUI

/// mpv's view, for SwiftUI. mpv starts when the view does and stops with it.
struct MPVVideoView: NSViewRepresentable {
    let player: MPVPlayer

    func makeNSView(context: Context) -> MPVLayerView {
        let view = MPVLayerView(frame: .zero)
        player.attach(view.metal)
        return view
    }

    func updateNSView(_ view: MPVLayerView, context: Context) {}

    static func dismantleNSView(_ view: MPVLayerView, coordinator: ()) {}
}

/// The player, for add-on streams and vault videos alike: mpv underneath, and Arsivinyo's
/// controls over it — play, seek, audio track, subtitle track, subtitle delay and speed.
struct VideoPlayerScreen<Overlay: View>: View {
    let player: MPVPlayer
    var title: String
    /// Subtitles from add-ons, best first; the best is loaded by itself when the file has
    /// nothing in a language preferred as much.
    var subtitles: [Addons.Subtitle] = []
    /// A torrent being streamed: its peers and speed are shown while the player waits.
    var torrentId: String? = nil
    @ViewBuilder var overlay: () -> Overlay

    @State private var controlsShown = true
    @State private var lastMove = Date()
    @State private var scrubMs: Double?
    @State private var loaded: Set<String> = []

    var body: some View {
        ZStack {
            Color.black
            MPVVideoView(player: player)
            if player.buffering, player.failed == nil {
                VStack(spacing: 10) {
                    ProgressView().controlSize(.large).tint(.white)
                    if let torrentId { TorrentLiveText(id: torrentId).font(.callout).foregroundStyle(.white) }
                }
            }
            if controlsShown || player.paused { controls.transition(.opacity) }
            if let failed = player.failed {
                VStack(spacing: 8) {
                    Text("This video could not be played.").font(.title3)
                    Text(failed).font(.caption).foregroundStyle(.secondary)
                }
                .padding(24)
                .background(.black.opacity(0.75), in: .rect(cornerRadius: 12))
                .foregroundStyle(.white)
            }
            overlay()
        }
        .onContinuousHover { phase in
            if case .active = phase { reveal() }
        }
        .task {
            // Fades the controls after a while without the pointer moving.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if Date().timeIntervalSince(lastMove) > 3, !player.paused {
                    withAnimation { controlsShown = false }
                }
            }
        }
        .onAppear {
            player.onTracksKnown = { tracks in chooseSubtitle(tracks) }
        }
        .onChange(of: subtitles) { _, _ in
            // Add-ons often answer after the file's tracks are known.
            if !player.tracks.isEmpty { chooseSubtitle(player.tracks) }
        }
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.space) { player.togglePause(); return .handled }
        .onKeyPress(.leftArrow) { player.seek(byMs: -10_000); return .handled }
        .onKeyPress(.rightArrow) { player.seek(byMs: 10_000); return .handled }
    }

    private func reveal() {
        lastMove = Date()
        if !controlsShown { withAnimation { controlsShown = true } }
    }

    @State private var chosen = false

    /// Once per video: an add-on's subtitle when the file has none in a language preferred
    /// as much.
    private func chooseSubtitle(_ tracks: [MPVPlayer.Track]) {
        guard !chosen, let best = subtitles.first else { return }
        chosen = true
        func rank(_ lang: String?) -> Int { lang.flatMap { player.languages.firstIndex(of: $0) } ?? Int.max }
        let own = tracks.filter { $0.kind == .subtitle }.map { rank($0.lang) }.min() ?? Int.max
        if rank(best.lang) < own { load(best, index: 0, select: true) }
    }

    private func label(_ subtitle: Addons.Subtitle, index: Int) -> String {
        String(localized: "\(languageName(subtitle.lang)) · from an add-on \(index + 1)")
    }

    private func load(_ subtitle: Addons.Subtitle, index: Int, select: Bool) {
        loaded.insert(subtitle.url)
        player.addSubtitle(url: subtitle.url, title: label(subtitle, index: index), lang: subtitle.lang, select: select)
    }

    private func trackLabel(_ track: MPVPlayer.Track, _ n: Int) -> String {
        [track.title ?? String(localized: "Track \(n)"), track.lang.map(languageName), track.codec?.uppercased()]
            .compactMap { $0 }.joined(separator: " · ")
    }

    private var controls: some View {
        VStack {
            HStack(spacing: 16) {
                Text(title).font(.headline).lineLimit(1)
                Spacer()
                audioMenu
                subtitleMenu
                speedMenu
            }
            .padding(12)
            .background(.black.opacity(0.45))

            Spacer()

            HStack(spacing: 48) {
                Button { player.seek(byMs: -10_000) } label: { Image(systemName: "gobackward.10").font(.title) }
                    .help("Back 10 seconds")
                Button { player.togglePause() } label: {
                    Image(systemName: player.paused ? "play.fill" : "pause.fill").font(.system(size: 44))
                }
                .help(player.paused ? "Play" : "Pause")
                Button { player.seek(byMs: 10_000) } label: { Image(systemName: "goforward.10").font(.title) }
                    .help("Forward 10 seconds")
            }
            .buttonStyle(.plain)

            Spacer()

            HStack(spacing: 12) {
                Text(clock(scrubMs ?? player.positionMs)).monospacedDigit()
                Slider(value: Binding(get: { scrubMs ?? player.positionMs }, set: { scrubMs = $0 }),
                       in: 0...max(1, player.durationMs)) { editing in
                    if !editing, let target = scrubMs {
                        player.seek(toMs: target)
                        scrubMs = nil
                    }
                }
                Text(clock(player.durationMs)).monospacedDigit()
            }
            .padding(12)
            .background(.black.opacity(0.45))
        }
        .foregroundStyle(.white)
    }

    private var audioMenu: some View {
        let audio = player.tracks.filter { $0.kind == .audio }
        return Menu {
            if audio.isEmpty { Text("None") }
            ForEach(Array(audio.enumerated()), id: \.element.id) { n, track in
                Toggle(trackLabel(track, n + 1), isOn: Binding(get: { track.selected },
                                                              set: { _ in player.select(track, kind: .audio) }))
            }
        } label: { Image(systemName: "speaker.wave.2") }
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Audio")
    }

    private var subtitleMenu: some View {
        let subs = player.tracks.filter { $0.kind == .subtitle }
        let offered = subtitles.enumerated().filter { !loaded.contains($0.element.url) }
        return Menu {
            Toggle("Off", isOn: Binding(get: { !subs.contains(where: \.selected) },
                                        set: { _ in player.select(nil, kind: .subtitle) }))
            ForEach(Array(subs.enumerated()), id: \.element.id) { n, track in
                Toggle(trackLabel(track, n + 1), isOn: Binding(get: { track.selected },
                                                              set: { _ in player.select(track, kind: .subtitle) }))
            }
            if !offered.isEmpty {
                Divider()
                ForEach(offered, id: \.element.url) { index, subtitle in
                    Button(label(subtitle, index: index)) { load(subtitle, index: index, select: true) }
                }
            }
            Divider()
            Text("Subtitle delay: \(String(format: "%.1f", player.subtitleDelayMs / 1000)) s")
            Button("Earlier by 0.1 s") { player.setSubtitleDelay(ms: player.subtitleDelayMs - 100) }
                .keyboardShortcut("z", modifiers: [])
            Button("Later by 0.1 s") { player.setSubtitleDelay(ms: player.subtitleDelayMs + 100) }
                .keyboardShortcut("x", modifiers: [])
            Button("Reset delay") { player.setSubtitleDelay(ms: 0) }
        } label: { Image(systemName: "captions.bubble") }
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Subtitles")
    }

    private var speedMenu: some View {
        Menu {
            ForEach(playbackSpeeds, id: \.self) { speed in
                Toggle("\(speed.formatted())×", isOn: Binding(get: { player.speed == speed },
                                                             set: { _ in player.setSpeed(speed) }))
            }
        } label: { Image(systemName: "gauge.with.dots.needle.67percent") }
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Speed")
    }
}

extension VideoPlayerScreen where Overlay == EmptyView {
    init(player: MPVPlayer, title: String, subtitles: [Addons.Subtitle] = []) {
        self.init(player: player, title: title, subtitles: subtitles) { EmptyView() }
    }
}

private let playbackSpeeds: [Double] = [0.5, 0.75, 1, 1.25, 1.5, 2]

/// A language's name in the app's language, from its ISO 639-2 code.
func languageName(_ code: String) -> String {
    Locale.current.localizedString(forLanguageCode: code) ?? code
}

private func clock(_ ms: Double) -> String {
    let total = max(0, Int(ms / 1000))
    let h = total / 3600, m = (total % 3600) / 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}
