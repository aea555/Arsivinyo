import ArsivinyoCore
import Foundation
import SwiftUI

/// One track going through one preset.
@MainActor
@Observable
final class RenderJob: Identifiable {
    enum State: Equatable {
        case waiting
        case rendering
        case done
        case failed(String)
        case cancelled
    }

    let id = UUID()
    let track: MusicLibrary.Track
    let preset: AudioPreset
    var state: State = .waiting
    var progress: Double = 0
    /// Its appearance stops the render; the C++ polls for it.
    let cancelFlag: URL

    init(track: MusicLibrary.Track, preset: AudioPreset, scratch: URL) {
        self.track = track
        self.preset = preset
        cancelFlag = scratch.appendingPathComponent("cancel-\(UUID().uuidString)")
    }

    var isActive: Bool { state == .waiting || state == .rendering }
}

extension AppModel {
    /// A built-in's name in the reader's language; the user's own as they wrote it.
    static func displayName(of preset: AudioPreset) -> String {
        switch preset.id {
        case "slowed-reverb": return String(localized: "Slowed + Reverb")
        case "nightcore": return String(localized: "Nightcore")
        case "bass-boost": return String(localized: "Bass Boost")
        default: return preset.name
        }
    }

    func refreshPresets() {
        presetList = presets.all()
        autoPresets = presets.autoApply
    }

    /// Queues renders of each track through `preset`. They run one at a time: a render
    /// uses a whole core for DSP and two ffmpeg processes besides, and several at once only
    /// makes every one of them slower.
    func applyPreset(_ preset: AudioPreset, to trackIds: [String]) {
        let tracks = trackIds.compactMap { id in self.tracks.first { $0.id == id } }
        renderJobs.append(contentsOf: tracks.map { RenderJob(track: $0, preset: preset, scratch: renderScratch) })
        pumpRenders()
    }

    func cancelRender(_ job: RenderJob) {
        guard job.isActive else { return }
        if job.state == .rendering { FileManager.default.createFile(atPath: job.cancelFlag.path, contents: nil) }
        job.state = .cancelled
        pumpRenders()
    }

    func clearFinishedRenders() {
        renderJobs.removeAll { !$0.isActive }
    }

    func pumpRenders() {
        guard !renderJobs.contains(where: { $0.state == .rendering }),
              let next = renderJobs.first(where: { $0.state == .waiting }) else { return }
        next.state = .rendering
        Task {
            do {
                let jobId = next.id
                // The model lives as long as the app; the Task around this holds it anyway.
                let report: @Sendable (Double) -> Void = { fraction in
                    Task { @MainActor in self.renderJobs.first { $0.id == jobId }?.progress = fraction }
                }
                _ = try await render(next.track, through: next.preset,
                                     progress: report, cancelFlag: next.cancelFlag)
                next.state = .done
            } catch {
                if next.state != .cancelled { next.state = .failed(String(describing: error)) }
            }
            try? FileManager.default.removeItem(at: next.cancelFlag)
            pumpRenders()
        }
    }

    /// Renders one track and adds the result to the library beside it.
    @discardableResult
    func render(_ track: MusicLibrary.Track, through preset: AudioPreset,
                progress: (@Sendable (Double) -> Void)? = nil, cancelFlag: URL? = nil) async throws -> MusicLibrary.Track {
        guard let ffmpeg = engine.ffmpegURL else {
            throw PresetRenderer.Failure(description: String(localized: "Presets need ffmpeg. Install it with: brew install ffmpeg"))
        }
        let ffprobe = ffmpeg.deletingLastPathComponent().appendingPathComponent("ffprobe")
        try FileManager.default.createDirectory(at: renderScratch, withIntermediateDirectories: true)
        let title = track.title + preset.titleSuffix
        let output = try await PresetRenderer.render(
            input: library.fileURL(for: track), into: renderScratch,
            fileName: Self.safeFileName(title), preset: preset,
            title: title, artist: track.artist, ffmpeg: ffmpeg, ffprobe: ffprobe,
            cancelFlag: cancelFlag, progress: progress)
        let added = try await library.adopt(output, title: title, artist: track.artist,
                                            artwork: library.artworkURL(for: track),
                                            presetId: preset.id, sourceSongId: track.id)
        refreshMusic()
        return added
    }

    /// Runs a fresh audio download through the presets set to apply automatically.
    ///
    /// The original goes only when every render worked and the setting says not to keep
    /// it: a failed render must not cost the download.
    func autoApplyPresets(to track: MusicLibrary.Track) async {
        let config = presets.autoApply
        guard !config.presetIds.isEmpty else { return }
        var allWorked = true
        for id in config.presetIds {
            guard let preset = presets.preset(id) else { continue }
            do {
                try await render(track, through: preset)
            } catch {
                allWorked = false
                musicProblem = String(describing: error)
            }
        }
        if allWorked && !config.keepOriginal {
            removeTracks([track.id])
        }
    }

    /// A title as a file name: no path separators, and nothing Finder hides or refuses.
    static func safeFileName(_ title: String) -> String {
        let cleaned = title.map { "/:\\".contains($0) ? "-" : $0 }
        let name = String(cleaned).trimmingCharacters(in: .whitespacesAndNewlines.union(.init(charactersIn: ".")))
        return name.isEmpty ? "Track" : String(name.prefix(180))
    }
}
