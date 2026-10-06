import AppKit
import ArsivinyoCore
import Libmpv
import Observation
import QuartzCore

/// The player (`shared/watch/CONTRACT.md`, "The player"): libmpv drawing into a Metal layer,
/// so MKV, HEVC, AC3/DTS and styled subtitles all play, which AVFoundation cannot do.
///
/// The screen reads what is playing from here and tells it what to do; mpv itself lives in
/// a `Core` that only ever runs on its own queue. Nothing about what plays is logged.
@MainActor @Observable
final class MPVPlayer {
    enum Kind: String { case audio, subtitle = "sub" }

    struct Track: Identifiable, Hashable {
        var kind: Kind
        var number: Int
        var title: String?
        /// Its ISO 639-2 code where it is a language the app knows, as the preferences are.
        var lang: String?
        var codec: String?
        var external: Bool
        var selected: Bool
        var id: String { "\(kind.rawValue)\(number)" }
    }

    struct Source: Equatable {
        var url: String
        /// The audio as a separate file, played with the video.
        var audioURL: String? = nil
        var headers: [String: String] = [:]
        var startMs: Int64 = 0
    }

    private(set) var positionMs: Double = 0
    private(set) var durationMs: Double = 0
    private(set) var paused = false
    private(set) var buffering = true
    private(set) var tracks: [Track] = []
    private(set) var failed: String?
    private(set) var ended = false
    private(set) var speed: Double = 1
    private(set) var subtitleDelayMs: Double = 0

    /// Subtitle and audio languages, most preferred first, as ISO 639-2 codes.
    @ObservationIgnored var languages: [String] = []
    /// Called once per video when its tracks are first known.
    @ObservationIgnored var onTracksKnown: (([Track]) -> Void)?
    @ObservationIgnored var onEnded: (() -> Void)?

    @ObservationIgnored private var core: Core?
    @ObservationIgnored private var pending: Source?
    @ObservationIgnored private var loaded: Source?
    @ObservationIgnored private var tracksKnown = false
    /// From asking for a file until its first frame; mpv reports "not waiting" before any of it.
    @ObservationIgnored private var starting = false
    @ObservationIgnored private var cacheWait = false
    @ObservationIgnored private var seeking = false
    /// What `arsivinyo-vault://` URLs read from: a vault item, decrypted as mpv asks.
    @ObservationIgnored private let vault: EncryptedReader?
    @ObservationIgnored private var refit: Task<Void, Never>?
    /// The layer it draws in, kept so it can start again after the window closed: SwiftUI keeps
    /// a Window scene's view, and this player with it, when the window closes and opens again.
    @ObservationIgnored private weak var layer: CAMetalLayer?
    /// The mpv a closed window stopped, until it is gone: a new one on the same layer waits for
    /// it, as Vulkan allows one surface per layer.
    @ObservationIgnored private var retired: Core?

    init(vault: EncryptedReader? = nil) {
        self.vault = vault
    }

    // MARK: - The view

    /// Starts mpv on the view's layer. Before this, a source waits.
    func attach(_ layer: CAMetalLayer) {
        self.layer = layer
        guard core == nil else { return }
        retired?.waitUntilGone()
        retired = nil
        do {
            core = try Core(layer: layer, vault: vault.map(VaultStream.init)) { [weak self] event in
                Task { @MainActor in self?.apply(event) }
            }
            loadPending()
        } catch {
            failed = "PLAYER_UNAVAILABLE"
        }
    }

    /// The view's size changed. MPVKit's MoltenVK context sizes mpv's picture only when the
    /// video's parameters change (its control hook does nothing), so a bigger window showed the
    /// picture at its old size in a corner. Once the size settles, the video is refitted
    /// (`Core.refit`). Measured with the sample in a window grown from 640×360: 1280×720 drawn
    /// into a 3200×1736 layer before, 3200×1736 after, decoded in hardware or not.
    func layerResized(to size: CGSize) {
        guard loaded != nil else { return }
        refit?.cancel()
        refit = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            self?.core?.refit(to: size)
        }
    }

    /// Stops mpv: the window is closing. What it was playing is forgotten, and the next `load`
    /// starts mpv again on the same layer. It once stayed stopped: the window, reopened for
    /// another episode, kept showing the last frame of the one before and answered nothing.
    func detach() {
        refit?.cancel()
        core?.destroy()
        retired = core
        core = nil
        loaded = nil
        ended = false
        paused = false
        buffering = true
        positionMs = 0
        durationMs = 0
        tracks = []
    }

    // MARK: - What the screen tells it

    func load(_ source: Source) {
        guard source != loaded else { return }
        pending = source
        if core == nil, let layer {
            attach(layer)
            return
        }
        loadPending()
    }

    private func loadPending() {
        guard let core, let source = pending else { return }
        pending = nil
        loaded = source
        failed = nil
        ended = false
        // The last video's place and length are not this one's.
        positionMs = 0
        durationMs = 0
        tracksKnown = false
        tracks = []
        starting = true
        buffering = true
        subtitleDelayMs = 0
        // Each preferred language by every code a file may use for it.
        let codes = languages.flatMap { code in
            (Addons.languages.first { $0.code == code }?.codes ?? []).sorted { a, _ in a == code }
        }.joined(separator: ",")
        core.setString("slang", codes)
        core.setString("alang", codes)
        core.command(["change-list", "http-header-fields", "clr", ""])
        for (name, value) in source.headers { core.command(["change-list", "http-header-fields", "append", "\(name): \(value)"]) }
        var options: [String] = []
        if source.startMs > 0 { options.append("start=\(Double(source.startMs) / 1000)") }
        // Length-quoted, so a comma in the URL cannot end the option.
        if let audio = source.audioURL { options.append("audio-file=%\(audio.utf8.count)%\(audio)") }
        // A new video plays. mpv pauses itself at the end of one (keep-open), and a pause, the
        // user's or that one, would otherwise carry over to the next episode.
        core.setFlag("pause", false)
        var args = ["loadfile", source.url, "replace", "-1"]
        if !options.isEmpty { args.append(options.joined(separator: ",")) }
        core.command(args)
    }

    func setPaused(_ value: Bool) { core?.setFlag("pause", value) }
    func togglePause() { setPaused(!paused) }
    func seek(toMs ms: Double) { core?.command(["seek", String(ms / 1000), "absolute"]) }
    func seek(byMs ms: Double) { core?.command(["seek", String(ms / 1000), "relative"]) }

    /// A track to show, or nil to turn subtitles off.
    func select(_ track: Track?, kind: Kind) {
        core?.setString(kind == .audio ? "aid" : "sid", track.map { String($0.number) } ?? "no")
    }

    /// An add-on's subtitle, added and, when `select`, shown at once.
    func addSubtitle(url: String, title: String, lang: String, select: Bool) {
        core?.command(["sub-add", url, select ? "select" : "auto", title, lang])
    }

    func setSubtitleDelay(ms: Double) {
        subtitleDelayMs = ms
        core?.setDouble("sub-delay", ms / 1000)
    }

    func setSpeed(_ value: Double) {
        speed = value
        core?.setDouble("speed", value)
    }

    // MARK: - What mpv says

    fileprivate enum Event: Sendable {
        case position(Double), duration(Double), paused(Bool), buffering(Bool), seeking(Bool), started, endReached(Bool)
        case tracks(String), failed(String)
    }

    private func apply(_ event: Event) {
        switch event {
        case .position(let seconds): positionMs = seconds * 1000
        case .duration(let seconds): durationMs = seconds * 1000
        case .paused(let value): paused = value
        case .buffering(let value):
            cacheWait = value
            buffering = starting || cacheWait || seeking
        case .seeking(let value):
            seeking = value
            buffering = starting || cacheWait || seeking
        case .started:
            starting = false
            buffering = cacheWait || seeking
        case .endReached(let value):
            if value, !ended {
                ended = true
                onEnded?()
            }
        case .failed(let code):
            if loaded != nil { failed = code }
        case .tracks(let json):
            tracks = Self.tracks(json)
            if !tracksKnown, !tracks.isEmpty {
                tracksKnown = true
                onTracksKnown?(tracks)
            }
        }
    }

    private static func tracks(_ json: String) -> [Track] {
        guard let list = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]] else { return [] }
        return list.compactMap { t in
            guard let type = t["type"] as? String, let kind = Kind(rawValue: type),
                  let number = (t["id"] as? NSNumber)?.intValue else { return nil }
            let lang = (t["lang"] as? String).flatMap { $0.isEmpty ? nil : (Addons.language($0) ?? $0) }
            return Track(kind: kind, number: number, title: (t["title"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                         lang: lang, codec: t["codec"] as? String, external: t["external"] as? Bool ?? false,
                         selected: t["selected"] as? Bool ?? false)
        }
    }

    // MARK: - mpv

    /// The mpv handle. Every call into it after setup, and every event out of it, happens on
    /// `queue`, so the handle is never used after it is destroyed.
    private final class Core: @unchecked Sendable {
        private let handle: OpaquePointer
        private let queue = DispatchQueue(label: "arsivinyo.mpv", qos: .userInitiated)
        private var alive = true
        private let deliver: @Sendable (Event) -> Void
        private let vault: VaultStream?

        init(layer: CAMetalLayer, vault: VaultStream?, deliver: @escaping @Sendable (Event) -> Void) throws {
            guard let handle = mpv_create() else { throw CocoaError(.featureUnsupported) }
            self.handle = handle
            self.deliver = deliver
            self.vault = vault

            var wid = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
            mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &wid)
            for (name, value) in [
                ("vo", "gpu-next"), ("gpu-api", "vulkan"), ("gpu-context", "moltenvk"),
                ("hwdec", "videotoolbox"), ("ytdl", "no"),
                ("keep-open", "yes"), ("idle", "yes"), ("sub-auto", "fuzzy"),
                // Streams: read ahead generously, so a seek within what has come does not wait.
                ("cache", "yes"), ("demuxer-max-bytes", "\(128 * 1024 * 1024)"), ("demuxer-max-back-bytes", "\(64 * 1024 * 1024)"),
                // The keyboard and mouse belong to the app's controls.
                ("input-default-bindings", "no"), ("input-vo-keyboard", "no"),
            ] {
                mpv_set_option_string(handle, name, value)
            }
            guard mpv_initialize(handle) >= 0 else {
                mpv_terminate_destroy(handle)
                throw CocoaError(.featureUnsupported)
            }
            for (name, format) in [
                ("time-pos", MPV_FORMAT_DOUBLE), ("duration", MPV_FORMAT_DOUBLE), ("pause", MPV_FORMAT_FLAG),
                ("paused-for-cache", MPV_FORMAT_FLAG), ("seeking", MPV_FORMAT_FLAG), ("eof-reached", MPV_FORMAT_FLAG), ("track-list", MPV_FORMAT_NONE),
                ("sid", MPV_FORMAT_STRING), ("aid", MPV_FORMAT_STRING),
            ] {
                mpv_observe_property(handle, 0, name, format)
            }
            if let vault {
                vault.register(with: handle)
            }
            mpv_set_wakeup_callback(handle, { context in
                guard let context else { return }
                Unmanaged<Core>.fromOpaque(context).takeUnretainedValue().wake()
            }, Unmanaged.passUnretained(self).toOpaque())
        }

        private func wake() {
            queue.async { [self] in
                guard alive else { return }
                while let event = mpv_wait_event(handle, 0)?.pointee, event.event_id != MPV_EVENT_NONE {
                    handle(event)
                }
            }
        }

        private func handle(_ event: mpv_event) {
            switch event.event_id {
            case MPV_EVENT_PROPERTY_CHANGE:
                guard let property = event.data?.assumingMemoryBound(to: mpv_event_property.self).pointee else { return }
                let name = String(cString: property.name)
                switch (name, property.format) {
                case ("time-pos", MPV_FORMAT_DOUBLE): deliver(.position(property.data.load(as: Double.self)))
                case ("duration", MPV_FORMAT_DOUBLE): deliver(.duration(property.data.load(as: Double.self)))
                case ("pause", MPV_FORMAT_FLAG): deliver(.paused(property.data.load(as: Int32.self) != 0))
                case ("paused-for-cache", MPV_FORMAT_FLAG): deliver(.buffering(property.data.load(as: Int32.self) != 0))
                case ("seeking", MPV_FORMAT_FLAG): deliver(.seeking(property.data.load(as: Int32.self) != 0))
                case ("eof-reached", MPV_FORMAT_FLAG): deliver(.endReached(property.data.load(as: Int32.self) != 0))
                case ("track-list", _), ("sid", _), ("aid", _):
                    if let raw = mpv_get_property_string(handle, "track-list") {
                        deliver(.tracks(String(cString: raw)))
                        mpv_free(raw)
                    }
                default: break
                }
            case MPV_EVENT_PLAYBACK_RESTART:
                deliver(.started)
            case MPV_EVENT_END_FILE:
                guard let end = event.data?.assumingMemoryBound(to: mpv_event_end_file.self).pointee else { return }
                if end.reason == MPV_END_FILE_REASON_ERROR {
                    deliver(.failed(String(cString: mpv_error_string(end.error))))
                }
            default:
                break
            }
        }

        /// Makes mpv draw at its layer's size: a filter converting the picture to a pixel format
        /// it is not in is put in and, a frame later, taken out, so the video's parameters change
        /// and the output is made again. Always a different format: a conversion to the one the
        /// frames already have (yuv420p, decoded in software) changes nothing and refits nothing.
        func refit(to size: CGSize) {
            queue.async { [self] in
                // Only when mpv draws at another size: a refit makes it decode again from the last
                // keyframe, which a torrent may not have yet.
                var width: Int64 = 0, height: Int64 = 0
                guard alive else { return }
                mpv_get_property(handle, "osd-width", MPV_FORMAT_INT64, &width)
                mpv_get_property(handle, "osd-height", MPV_FORMAT_INT64, &height)
                guard width > 0, Int(width) != Int(size.width) || Int(height) != Int(size.height),
                      let raw = mpv_get_property_string(handle, "video-params/pixelformat") else { return }
                let format = String(cString: raw)
                mpv_free(raw)
                command(["vf", "add", "@arsivinyo-refit:format=fmt=\(format == "yuv420p" ? "nv12" : "yuv420p")"])
                queue.asyncAfter(deadline: .now() + 0.3) { [self] in
                    guard alive else { return }
                    command(["vf", "remove", "@arsivinyo-refit"])
                }
            }
        }

        func command(_ args: [String]) {
            queue.async { [self] in
                guard alive else { return }
                var cargs: [UnsafeMutablePointer<CChar>?] = args.map { strdup($0) } + [nil]
                defer { cargs.forEach { free($0) } }
                cargs.withUnsafeMutableBufferPointer { buffer in
                    buffer.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buffer.count) {
                        _ = mpv_command(handle, $0)
                    }
                }
            }
        }

        func setString(_ name: String, _ value: String) {
            queue.async { [self] in if alive { mpv_set_property_string(handle, name, value) } }
        }

        func setFlag(_ name: String, _ value: Bool) {
            queue.async { [self] in
                guard alive else { return }
                var flag: Int32 = value ? 1 : 0
                mpv_set_property(handle, name, MPV_FORMAT_FLAG, &flag)
            }
        }

        func setDouble(_ name: String, _ value: Double) {
            queue.async { [self] in
                guard alive else { return }
                var number = value
                mpv_set_property(handle, name, MPV_FORMAT_DOUBLE, &number)
            }
        }

        func destroy() {
            queue.async { [self] in
                guard alive else { return }
                alive = false
                mpv_set_wakeup_callback(handle, nil, nil)
                mpv_terminate_destroy(handle)
            }
        }

        /// Returns once `destroy` has run: its queue is serial, so this waits for it.
        func waitUntilGone() {
            queue.sync {}
        }
    }
}

/// The layer mpv draws into.
final class MPVLayer: CAMetalLayer {
    // MoltenVK sets the drawable to 1×1 to force a presentation through; kept from sticking,
    // which flickers (mpv-player/mpv#13651).
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set { if Int(newValue.width) > 1, Int(newValue.height) > 1 { super.drawableSize = newValue } }
    }
}

/// The view a player draws in: a Metal layer, sized with the view.
final class MPVLayerView: NSView {
    let metal = MPVLayer()
    /// Told the new size in pixels when it changes, so mpv is made to draw at it.
    var resized: ((CGSize) -> Void)?
    /// The last size reported: AppKit may already have set the drawable's size by itself.
    private var reported = CGSize.zero

    override init(frame: NSRect) {
        super.init(frame: frame)
        metal.framebufferOnly = true
        metal.backgroundColor = NSColor.black.cgColor
        layer = metal
        wantsLayer = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        let scale = window?.backingScaleFactor ?? 2
        metal.contentsScale = scale
        let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        metal.drawableSize = size
        guard size != reported else { return }
        reported = size
        resized?(size)
    }
}
