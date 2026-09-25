import ArsivinyoCore
import SwiftUI
import UniformTypeIdentifiers

/// The music library, or one playlist from it.
///
/// A sortable table with a heart column, the way Music.app lays out songs. Double-clicking
/// plays from that row with the rest of the visible list queued behind it, so what plays
/// next is what is under it on screen.
struct MusicView: View {
    @Environment(AppModel.self) private var model
    @State private var selection = Set<MusicLibrary.Track.ID>()
    @State private var sortOrder: [KeyPathComparator<MusicLibrary.Track>] = []
    @State private var search = ""
    @State private var naming: NamingRequest?
    @State private var isDropTarget = false

    private struct NamingRequest: Identifiable {
        let id = UUID()
        var title: String
        var initial: String
        var trackIds: [String]
        var renaming: String?
    }

    private var playlist: MusicLibrary.Playlist? {
        model.selectedPlaylistId.flatMap { id in model.playlists.first { $0.id == id } }
    }

    private var visible: [MusicLibrary.Track] {
        var tracks: [MusicLibrary.Track]
        if let playlist {
            // A playlist keeps its own order until a column is clicked.
            let byId = Dictionary(uniqueKeysWithValues: model.tracks.map { ($0.id, $0) })
            tracks = playlist.trackIds.compactMap { byId[$0] }
        } else {
            tracks = model.tracks
        }
        if !search.isEmpty {
            tracks = tracks.filter {
                $0.title.localizedCaseInsensitiveContains(search)
                    || $0.artist.localizedCaseInsensitiveContains(search)
            }
        }
        return sortOrder.isEmpty ? tracks : tracks.sorted(using: sortOrder)
    }

    var body: some View {
        Group {
            if model.tracks.isEmpty {
                ContentUnavailableView {
                    Label("No music yet", systemImage: "music.note.list")
                } description: {
                    Text("Audio you download lands here. You can also drop files in, or import them.")
                } actions: {
                    Button("Import…", action: chooseFiles).buttonStyle(.borderedProminent)
                }
            } else if let playlist, playlist.trackIds.isEmpty {
                ContentUnavailableView {
                    Label(playlist.isSystem ? "No favourites yet" : "Empty playlist",
                          systemImage: playlist.isSystem ? "heart" : "music.note.list")
                } description: {
                    Text(playlist.isSystem
                         ? "Click the heart beside a song to keep it here."
                         : "Add songs from the library with the right-click menu.")
                }
            } else {
                table
            }
        }
        .navigationTitle(playlist?.name ?? "Music")
        .navigationSubtitle(subtitle)
        .searchable(text: $search, placement: .toolbar, prompt: "Search music")
        .toolbar {
            ToolbarItem {
                Button(action: chooseFiles) { Label("Import", systemImage: "plus") }
                    .help("Import audio files into the library")
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $isDropTarget, perform: drop)
        .overlay {
            if isDropTarget {
                RoundedRectangle(cornerRadius: 10).strokeBorder(Color.accentColor, lineWidth: 2).padding(6)
            }
        }
        .sheet(item: $naming) { request in
            NameSheet(title: request.title, initial: request.initial) { name in
                if let id = request.renaming {
                    model.renamePlaylist(id, to: name)
                } else {
                    model.createPlaylist(named: name, with: request.trackIds)
                }
            }
        }
    }

    private var subtitle: String {
        let tracks = visible
        let seconds = tracks.reduce(0) { $0 + $1.durationSeconds }
        let count = tracks.count == 1 ? "1 song" : "\(tracks.count) songs"
        return tracks.isEmpty ? "" : "\(count), \(Self.longDuration(seconds))"
    }

    private var table: some View {
        Table(visible, selection: $selection, sortOrder: $sortOrder) {
            TableColumn("") { track in
                let favourite = model.favorites.contains(track.id)
                Button { model.toggleFavorite(track) } label: {
                    Image(systemName: favourite ? "heart.fill" : "heart")
                        .foregroundStyle(favourite ? Color.pink : Color.secondary.opacity(0.5))
                }
                .buttonStyle(.plain)
                .help(favourite ? "Remove from Favorites" : "Add to Favorites")
            }
            .width(22)

            TableColumn("Title", value: \.title) { track in
                HStack(spacing: 6) {
                    if model.player.current?.id == track.id {
                        Image(systemName: model.player.isPlaying ? "speaker.wave.2.fill" : "speaker.fill")
                            .foregroundStyle(Color.accentColor)
                            .font(.caption)
                    }
                    Text(track.title).lineLimit(1)
                        .fontWeight(model.player.current?.id == track.id ? .semibold : .regular)
                }
            }
            TableColumn("Artist", value: \.artist) { track in
                Text(track.artist).foregroundStyle(.secondary).lineLimit(1)
            }
            TableColumn("Time", value: \.durationSeconds) { track in
                Text(Self.shortDuration(track.durationSeconds))
                    .foregroundStyle(.secondary).monospacedDigit()
            }
            .width(min: 44, ideal: 54, max: 70)
        }
        .contextMenu(forSelectionType: MusicLibrary.Track.ID.self) { ids in
            menu(for: ids)
        } primaryAction: { ids in
            if let first = visible.first(where: { ids.contains($0.id) }) {
                model.player.play(visible, startingAt: first)
            }
        }
        .onDeleteCommand {
            let ids = Array(selection)
            if let playlist, !playlist.isSystem {
                model.removeTracks(ids, fromPlaylist: playlist.id)
            } else if let playlist, playlist.isSystem {
                ids.compactMap { id in model.tracks.first { $0.id == id } }.forEach(model.toggleFavorite)
            } else {
                model.removeTracks(ids)
            }
        }
    }

    @ViewBuilder
    private func menu(for ids: Set<MusicLibrary.Track.ID>) -> some View {
        let chosen = visible.filter { ids.contains($0.id) }
        if let first = chosen.first {
            Button("Play") { model.player.play(visible, startingAt: first) }
            Button("Play Next") { chosen.reversed().forEach(model.player.playNext) }
            Divider()
            Menu("Add to Playlist") {
                Button("New Playlist…") {
                    naming = .init(title: "New Playlist", initial: "", trackIds: chosen.map(\.id))
                }
                let targets = model.playlists.filter { !$0.isSystem && $0.id != playlist?.id }
                if !targets.isEmpty { Divider() }
                ForEach(targets) { target in
                    Button(target.name) { model.addTracks(chosen.map(\.id), toPlaylist: target.id) }
                }
            }
            let allFavourite = chosen.allSatisfy { model.favorites.contains($0.id) }
            Button(allFavourite ? "Remove from Favorites" : "Add to Favorites") {
                chosen.filter { model.favorites.contains($0.id) == allFavourite }.forEach(model.toggleFavorite)
            }
            if chosen.count == 1 {
                Button("Show in Finder") {
                    NSWorkspace.shared.activateFileViewerSelecting([model.library.fileURL(for: first)])
                }
            }
            Divider()
            if let playlist, !playlist.isSystem {
                Button("Remove from “\(playlist.name)”") {
                    model.removeTracks(chosen.map(\.id), fromPlaylist: playlist.id)
                }
            }
            Button("Move to Trash", role: .destructive) { model.removeTracks(chosen.map(\.id)) }
        }
    }

    private func chooseFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.allowedContentTypes = [.audio]
        panel.prompt = "Import"
        panel.message = "The files are copied into the library. The originals stay where they are."
        if panel.runModal() == .OK { model.importMusic(panel.urls) }
    }

    private func drop(_ providers: [NSItemProvider]) -> Bool {
        let group = DispatchGroup()
        nonisolated(unsafe) var urls: [URL] = []
        let lock = NSLock()
        for provider in providers {
            group.enter()
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url, UTType(filenameExtension: url.pathExtension)?.conforms(to: .audio) == true {
                    lock.withLock { urls.append(url) }
                }
                group.leave()
            }
        }
        group.notify(queue: .main) { if !urls.isEmpty { model.importMusic(urls) } }
        return true
    }

    static func shortDuration(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds > 0 else { return "–" }
        let total = Int(seconds.rounded())
        return total >= 3600
            ? String(format: "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
            : String(format: "%d:%02d", total / 60, total % 60)
    }

    static func longDuration(_ seconds: Double) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute] : [.minute]
        formatter.unitsStyle = .full
        return formatter.string(from: seconds) ?? ""
    }
}

/// Asks for a name, for a new playlist or a renamed one.
struct NameSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    @State var name: String
    let onSave: (String) -> Void

    init(title: String, initial: String, onSave: @escaping (String) -> Void) {
        self.title = title
        self._name = State(initialValue: initial)
        self.onSave = onSave
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.headline)
            TextField("Name", text: $name).frame(width: 300).onSubmit(save)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Save", action: save).keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        onSave(trimmed)
        dismiss()
    }
}
