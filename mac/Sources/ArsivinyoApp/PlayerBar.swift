import ArsivinyoCore
import SwiftUI

/// What is playing, across the bottom of the window.
///
/// Present whatever section is showing, so switching to the vault or a download does not
/// take the transport away. Hidden when nothing has been played, rather than showing an
/// empty strip that looks like a broken control.
struct PlayerBar: View {
    @Environment(AppModel.self) private var model
    @State private var scrubbing: Double?

    var body: some View {
        let player = model.player
        if let track = player.current {
            HStack(spacing: 14) {
                artwork(for: track)

                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title).font(.callout.weight(.medium)).lineLimit(1)
                    Text(track.artist.isEmpty ? " " : track.artist)
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(width: 190, alignment: .leading)

                HStack(spacing: 18) {
                    Button(action: player.previous) { Image(systemName: "backward.fill") }
                        .keyboardShortcut(.leftArrow, modifiers: .command)
                    Button(action: player.togglePlayPause) {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.title2)
                            .frame(width: 22)
                    }
                    Button(action: player.next) { Image(systemName: "forward.fill") }
                        .keyboardShortcut(.rightArrow, modifiers: .command)
                }
                .buttonStyle(.plain)

                HStack(spacing: 8) {
                    Text(MusicView.shortDuration(scrubbing ?? player.position))
                        .font(.caption).monospacedDigit().foregroundStyle(.secondary)
                        .frame(width: 44, alignment: .trailing)
                    Slider(value: Binding(
                        get: { scrubbing ?? player.position },
                        set: { scrubbing = $0 }
                    ), in: 0...max(player.duration, 1)) { editing in
                        // Seek on release, not on every pixel of the drag.
                        if !editing, let target = scrubbing {
                            player.seek(to: target)
                            scrubbing = nil
                        }
                    }
                    .controlSize(.small)
                    Text(MusicView.shortDuration(player.duration))
                        .font(.caption).monospacedDigit().foregroundStyle(.secondary)
                        .frame(width: 44, alignment: .leading)
                }

                HStack(spacing: 12) {
                    Button { model.player.shuffle.toggle() } label: {
                        Image(systemName: "shuffle")
                            .foregroundStyle(player.shuffle ? Color.accentColor : Color.secondary)
                    }
                    .help(player.shuffle ? "Shuffle is on" : "Shuffle is off")
                    Button { cycleRepeat() } label: {
                        Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                            .foregroundStyle(player.repeatMode == .off ? Color.secondary : Color.accentColor)
                    }
                    .help("Repeat: \(repeatLabel)")
                    let favourite = model.favorites.contains(track.id)
                    Button { model.toggleFavorite(track) } label: {
                        Image(systemName: favourite ? "heart.fill" : "heart")
                            .foregroundStyle(favourite ? Color.pink : Color.secondary)
                    }
                    Image(systemName: "speaker.fill").foregroundStyle(.secondary).font(.caption)
                    Slider(value: Binding(get: { Double(player.volume) },
                                          set: { model.player.volume = Float($0) }), in: 0...1)
                        .frame(width: 80)
                        .controlSize(.small)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.bar)
            .overlay(alignment: .top) { Divider() }
        }
    }

    private var repeatLabel: String {
        switch model.player.repeatMode {
        case .off: return "off"
        case .all: return "all"
        case .one: return "one"
        }
    }

    private func cycleRepeat() {
        switch model.player.repeatMode {
        case .off: model.player.repeatMode = .all
        case .all: model.player.repeatMode = .one
        case .one: model.player.repeatMode = .off
        }
    }

    @ViewBuilder
    private func artwork(for track: MusicLibrary.Track) -> some View {
        Group {
            if let url = model.library.artworkURL(for: track), let image = NSImage(contentsOf: url) {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                ZStack {
                    Color.secondary.opacity(0.15)
                    Image(systemName: "music.note").foregroundStyle(.secondary)
                }
            }
        }
        .frame(width: 40, height: 40)
        .clipShape(RoundedRectangle(cornerRadius: 5))
    }
}
