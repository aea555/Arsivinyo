import AVFoundation
import AppKit
import ArsivinyoCore
import MediaPlayer
import SwiftUI

/// Plays the music library.
///
/// It registers with Now Playing and the remote-command centre, so the play/pause key on
/// the keyboard works, headphone buttons work, and what is playing shows in Control Center
/// with its artwork — the same place every other player on the Mac puts it. An app that
/// needs its own window in front to pause is a guest on the machine, not a resident.
@MainActor
@Observable
final class Player {

    enum Repeat { case off, all, one }

    private(set) var queue: [MusicLibrary.Track] = []
    private(set) var index: Int?
    private(set) var isPlaying = false
    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    var shuffle = false
    var repeatMode: Repeat = .off
    var volume: Float = 0.8 { didSet { player.volume = volume } }

    var current: MusicLibrary.Track? { index.flatMap { queue.indices.contains($0) ? queue[$0] : nil } }

    private let library: MusicLibrary
    private let player = AVPlayer()
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?

    init(library: MusicLibrary) {
        self.library = library
        player.volume = volume
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main
        ) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.position = time.seconds.isFinite ? time.seconds : 0
                if let d = self.player.currentItem?.duration.seconds, d.isFinite { self.duration = d }
                self.publishNowPlaying()
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main
        ) { [weak self] note in
            // Every player in the app posts this, the vault's films too. Only the end of our
            // own track is the end of a track.
            let ended = (note.object as AnyObject?).map(ObjectIdentifier.init)
            MainActor.assumeIsolated {
                guard let self, ended == self.player.currentItem.map(ObjectIdentifier.init) else { return }
                self.didFinishTrack()
            }
        }
        registerRemoteCommands()
    }

    // MARK: - Control

    /// Replaces the queue and starts at `start`. Double-clicking a row does this with the
    /// visible list, so the next track is the next row — what a Mac user expects.
    func play(_ tracks: [MusicLibrary.Track], startingAt start: MusicLibrary.Track) {
        guard let position = tracks.firstIndex(of: start) else { return }
        queue = tracks
        index = position
        load()
        resume()
    }

    func playNext(_ track: MusicLibrary.Track) {
        guard let index else { play([track], startingAt: track); return }
        queue.insert(track, at: index + 1)
    }

    func togglePlayPause() { isPlaying ? pause() : resume() }

    func resume() {
        guard current != nil else { return }
        player.play()
        isPlaying = true
        publishNowPlaying()
    }

    func pause() {
        player.pause()
        isPlaying = false
        publishNowPlaying()
    }

    func next() {
        guard let index, !queue.isEmpty else { return }
        if shuffle, queue.count > 1 {
            var pick = index
            while pick == index { pick = Int.random(in: 0..<queue.count) }
            self.index = pick
        } else if index + 1 < queue.count {
            self.index = index + 1
        } else if repeatMode == .all {
            self.index = 0
        } else {
            pause()
            return
        }
        load()
        resume()
    }

    /// Back to the start of the track if it has been playing a while; otherwise the one
    /// before. Every player on the Mac does this, and doing anything else feels broken.
    func previous() {
        guard let index else { return }
        if position > 3 || index == 0 {
            seek(to: 0)
        } else {
            self.index = index - 1
            load()
            resume()
        }
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        position = seconds
        publishNowPlaying()
    }

    func stop() {
        pause()
        player.replaceCurrentItem(with: nil)
        queue = []
        index = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        MPNowPlayingInfoCenter.default().playbackState = .stopped
    }

    private func load() {
        guard let track = current else { return }
        player.replaceCurrentItem(with: AVPlayerItem(url: library.fileURL(for: track)))
        position = 0
        duration = track.durationSeconds
    }

    private func didFinishTrack() {
        if repeatMode == .one {
            seek(to: 0)
            resume()
        } else {
            next()
        }
    }

    // MARK: - Now Playing

    private func publishNowPlaying() {
        guard let track = current else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let url = library.artworkURL(for: track), let image = NSImage(contentsOf: url) {
            info[MPMediaItemPropertyArtwork] = Self.artwork(image)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
    }

    /// MediaPlayer asks for the image on its own queue. A closure written inside this class
    /// belongs to the main actor, and Swift stops the app when it runs anywhere else, so the
    /// closure is made here, outside the actor.
    private nonisolated static func artwork(_ image: NSImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { @Sendable _ in image }
    }

    private func registerRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.resume() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.togglePlayPause() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.next() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.previous() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            MainActor.assumeIsolated { self?.seek(to: event.positionTime) }
            return .success
        }
    }
}
