import AVKit
import ArsivinyoCore
import SwiftUI
import UniformTypeIdentifiers

/// The meme collection: a grid, search, filters, and tagging.
struct MemesView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var filter = MemeLibrary.Filter()
    @State private var selection = Set<String>()
    @State private var opened: String?
    @State private var batch: Set<String>?
    @State private var reviewing = false
    @State private var managingTags = false
    @State private var showingFaces = false
    @State private var isDropTarget = false

    private var visible: [MemeLibrary.Item] {
        MemeLibrary.search(model.memeSnapshot, query: query, filter: filter)
    }

    private var untagged: Int { model.memeSnapshot.items.filter(\.isUntagged).count }

    var body: some View {
        Group {
            if model.memeSnapshot.items.isEmpty {
                ContentUnavailableView {
                    Label("No memes yet", systemImage: "theatermasks")
                } description: {
                    Text("Videos and images you download land here, with the post they came from. You can also drop files in, or import them.")
                } actions: {
                    Button("Import…", action: chooseFiles).buttonStyle(.borderedProminent)
                }
            } else {
                VStack(spacing: 0) {
                    FilterBar(filter: $filter)
                    if visible.isEmpty {
                        ContentUnavailableView.search(text: query)
                    } else {
                        grid
                    }
                }
            }
        }
        .navigationTitle(String(localized: "Memes"))
        .navigationSubtitle(untagged > 0 ? String(localized: "\(untagged) untagged") : "")
        .searchable(text: $query, placement: .toolbar, prompt: Text("Search tags, people, captions"))
        .toolbar {
            ToolbarItemGroup {
                Button { reviewing = true } label: { Label("Review Untagged", systemImage: "rectangle.stack") }
                    .disabled(untagged == 0)
                    .help("Go through the untagged memes one by one")
                Button { showingFaces = true } label: { Label("Faces", systemImage: "person.crop.square") }
                    .badge(MemeLibrary.asked(model.memeSnapshot).count)
                    .help("Name the faces in your memes, and answer what the app is unsure of")
                Button { managingTags = true } label: { Label("Tags", systemImage: "tag") }
                    .help("Rename tags, change their facets, or delete them")
                Button(action: chooseFiles) { Label("Import", systemImage: "plus") }
                    .help("Import videos and images")
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $isDropTarget, perform: drop)
        .overlay {
            if isDropTarget {
                RoundedRectangle(cornerRadius: 10).strokeBorder(Color.accentColor, lineWidth: 2).padding(6)
            }
        }
        .sheet(item: Binding(get: { opened.map(Opened.init) }, set: { opened = $0?.id })) { item in
            MemeSheet(ids: [item.id], mode: .detail)
        }
        .sheet(item: Binding(get: { batch.map(Batch.init) }, set: { batch = $0?.ids })) { request in
            MemeSheet(ids: request.ids, mode: .batch)
        }
        .sheet(isPresented: $reviewing) {
            MemeSheet(ids: Set(model.memeSnapshot.items.filter(\.isUntagged).map(\.id)), mode: .review)
        }
        .sheet(isPresented: $managingTags) { TagManager() }
        .sheet(isPresented: $showingFaces) { FacesView() }
        // `-openFaces YES` on the command line, for looking at the screen from a terminal,
        // as `-section` does for the sidebar.
        .onAppear { if UserDefaults.standard.bool(forKey: "openFaces") { showingFaces = true } }
    }

    private struct Opened: Identifiable { let id: String }
    private struct Batch: Identifiable { let ids: Set<String>; var id: String { ids.sorted().joined() } }

    private var grid: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 220), spacing: 12)], spacing: 12) {
                ForEach(visible) { item in
                    MemeTile(item: item, selected: selection.contains(item.id))
                        .onTapGesture(count: 2) { opened = item.id }
                        .simultaneousGesture(TapGesture().modifiers(.command).onEnded {
                            if selection.contains(item.id) { selection.remove(item.id) } else { selection.insert(item.id) }
                        })
                        .onTapGesture { selection = [item.id] }
                        .contextMenu { menu(for: selection.contains(item.id) ? selection : [item.id]) }
                }
            }
            .padding(14)
        }
        .onDeleteCommand { if !selection.isEmpty { model.removeMemes(selection); selection = [] } }
    }

    @ViewBuilder
    private func menu(for ids: Set<String>) -> some View {
        let chosen = model.memeSnapshot.items.filter { ids.contains($0.id) }
        if chosen.count == 1, let first = chosen.first {
            Button("Open") { opened = first.id }
        }
        Button(chosen.count == 1 ? LocalizedStringKey("Tag…") : LocalizedStringKey("Tag \(chosen.count) Memes…")) { batch = ids }
        Divider()
        if chosen.allSatisfy(\.isPrivate) {
            Button("Make Public") { model.setPrivate(ids, false) }
        } else if chosen.allSatisfy({ !$0.isPrivate }) {
            Button("Make Private") { model.setPrivate(ids, true) }
        }
        if let devices = model.devices, !devices.connected.isEmpty, chosen.allSatisfy({ !$0.isPrivate }) {
            Menu("Send to") {
                ForEach(devices.peers.filter { devices.connected.contains($0.fingerprint) }) { peer in
                    Button(peer.name.isEmpty ? String(localized: "Unnamed device") : peer.name) {
                        // One at a time, as the protocol has it: the first of a selection.
                        if let first = chosen.first { model.sendMeme(first.id, to: peer.fingerprint) }
                    }
                }
            }
        }
        if chosen.count == 1, let file = chosen.first?.fileURL {
            Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([file]) }
        }
        Divider()
        Button("Move to Trash", role: .destructive) {
            model.removeMemes(ids)
            selection.subtract(ids)
        }
    }

    private func chooseFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.allowedContentTypes = [.movie, .image]
        panel.prompt = String(localized: "Import")
        panel.message = String(localized: "The files are copied into the collection. The originals stay where they are.")
        if panel.runModal() == .OK { model.importMemes(panel.urls) }
    }

    private func drop(_ providers: [NSItemProvider]) -> Bool {
        let group = DispatchGroup()
        nonisolated(unsafe) var urls: [URL] = []
        let lock = NSLock()
        for provider in providers {
            group.enter()
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url, MemeLibrary.kind(of: url) != nil { lock.withLock { urls.append(url) } }
                group.leave()
            }
        }
        group.notify(queue: .main) { if !urls.isEmpty { model.importMemes(urls) } }
        return true
    }
}

/// One meme in the grid.
private struct MemeTile: View {
    @Environment(AppModel.self) private var model
    let item: MemeLibrary.Item
    let selected: Bool
    @State private var image: NSImage?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Rectangle().fill(.quaternary)
            if let image {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: item.isPrivate ? "lock.fill" : (item.isVideo ? "film" : "photo"))
                    .font(.title).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            HStack(spacing: 4) {
                if item.isVideo { Image(systemName: "play.fill") }
                if item.isPrivate { Image(systemName: "lock.fill") }
                if item.isUntagged { Circle().fill(Color.accentColor).frame(width: 7, height: 7) }
            }
            .font(.caption2)
            .padding(5)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(6)
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(selected ? Color.accentColor : .clear, lineWidth: 3))
        .contentShape(Rectangle())
        .help(item.source?.caption ?? "")
        .task(id: item.id) { image = await MemeThumbnails.image(for: item, support: model.supportFolder) }
    }
}

/// Facets, people, platform, private, untagged: the chips under the toolbar.
private struct FilterBar: View {
    @Environment(AppModel.self) private var model
    @Binding var filter: MemeLibrary.Filter

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(MemeLibrary.Facet.allCases) { facet in
                    Chip(title: Text(FacetLabel.title(facet)), on: filter.facets.contains(facet)) {
                        if filter.facets.contains(facet) { filter.facets.remove(facet) } else { filter.facets.insert(facet) }
                    }
                }
                Divider().frame(height: 16)
                Chip(title: Text("Untagged"), on: filter.onlyUntagged) { filter.onlyUntagged.toggle() }
                if model.vaultUnlocked {
                    Chip(title: Text("Private"), on: filter.onlyPrivate) { filter.onlyPrivate.toggle() }
                }
                if !model.memeSnapshot.people.isEmpty {
                    Menu {
                        ForEach(model.memeSnapshot.people.sorted { $0.name < $1.name }) { person in
                            Toggle(person.name, isOn: Binding(get: { filter.people.contains(person.id) }, set: {
                                if $0 { filter.people.insert(person.id) } else { filter.people.remove(person.id) }
                            }))
                        }
                    } label: {
                        Text(filter.people.isEmpty ? String(localized: "People") : String(localized: "People (\(filter.people.count))"))
                    }
                    .menuStyle(.borderlessButton).fixedSize()
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 8)
        }
        .background(.bar)
        .overlay(alignment: .bottom) { Divider() }
    }
}

struct Chip: View {
    let title: Text
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            title.font(.callout)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(on ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.12), in: Capsule())
                .overlay(Capsule().strokeBorder(on ? Color.accentColor : .clear))
        }
        .buttonStyle(.plain)
    }
}

enum FacetLabel {
    static func title(_ facet: MemeLibrary.Facet) -> LocalizedStringKey {
        switch facet {
        case .reaction: return "Reaction"
        case .vibe: return "Vibe"
        case .emotion: return "Emotion"
        case .action: return "Action"
        case .context: return "Context"
        }
    }
}
