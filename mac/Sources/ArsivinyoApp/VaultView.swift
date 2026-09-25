import AVKit
import ArsivinyoCore
import SwiftUI
import UniformTypeIdentifiers

/// The vault: private files, encrypted at rest, listed only when unlocked.
///
/// A sortable table, the way Finder shows files, rather than a list of cards: a vault is a
/// collection people search and sort, and a table is what a Mac user reaches for there.
struct VaultView: View {
    @Environment(AppModel.self) private var model
    @State private var selection = Set<Vault.Item.ID>()
    @State private var sortOrder = [KeyPathComparator(\Vault.Item.addedAt, order: .reverse)]
    @State private var playing: Vault.Item?
    @State private var renaming: Vault.Item?
    @State private var confirmingDelete: [Vault.Item] = []
    @State private var isDropTarget = false
    @State private var search = ""

    private var items: [Vault.Item] {
        let filtered = search.isEmpty
            ? model.vaultItems
            : model.vaultItems.filter { $0.title.localizedCaseInsensitiveContains(search) }
        return filtered.sorted(using: sortOrder)
    }

    var body: some View {
        Group {
            if !model.vaultUnlocked {
                locked
            } else if model.vault.isUnreadable {
                unreadable
            } else if model.vaultItems.isEmpty {
                empty
            } else {
                table
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $isDropTarget, perform: drop)
        .overlay {
            if isDropTarget && model.vaultUnlocked {
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(Color.accentColor, lineWidth: 2)
                    .padding(6)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let problem = model.vaultProblem {
                Label(problem, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(.orange)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.bar)
            }
        }
        .toolbar {
            if model.vaultUnlocked {
                ToolbarItem {
                    Button(action: chooseFiles) { Label("Add Files", systemImage: "plus") }
                        .help("Add files to the vault")
                }
            }
        }
        .searchable(text: $search, placement: .toolbar, prompt: "Search the vault")
        .sheet(item: $playing) { VaultPlayerSheet(item: $0) }
        .sheet(item: $renaming) { item in
            RenameSheet(title: item.title) { model.renameInVault(item, to: $0) }
        }
        .confirmationDialog(
            confirmingDelete.count == 1
                ? "Delete “\(confirmingDelete.first?.title ?? "")” from the vault?"
                : "Delete \(confirmingDelete.count) items from the vault?",
            isPresented: .init(get: { !confirmingDelete.isEmpty },
                               set: { if !$0 { confirmingDelete = [] } })
        ) {
            Button("Delete", role: .destructive) {
                confirmingDelete.forEach(model.removeFromVault)
                confirmingDelete = []
            }
        } message: {
            // Said plainly: there is no other copy, and no undo.
            Text("There is no other copy, and this cannot be undone.")
        }
    }

    // MARK: - States

    private var locked: some View {
        ContentUnavailableView {
            Label("Vault is locked", systemImage: "lock.shield")
        } description: {
            Text(model.vaultConfigured
                 ? "Everything here is encrypted, and so is the list of what it is."
                 : "Set a passphrase to start a vault. Files you add are encrypted with it.")
        } actions: {
            Button(model.vaultConfigured ? "Unlock…" : "Set a Passphrase…") {
                model.showUnlockSheet = true
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private var unreadable: some View {
        ContentUnavailableView {
            Label("The listing could not be read", systemImage: "exclamationmark.lock")
        } description: {
            // The instinct on seeing an empty vault is to add to it, and that is the one
            // thing that would make this permanent. So it says so.
            Text("Your files are still here and still encrypted. Nothing has been deleted, "
                 + "and nothing will be written until the listing can be read again.")
        }
    }

    private var empty: some View {
        ContentUnavailableView {
            Label("Vault is empty", systemImage: "lock.open")
        } description: {
            Text("Drop files here, or add them. They are encrypted as they come in.")
        } actions: {
            Button("Add Files…", action: chooseFiles).buttonStyle(.borderedProminent)
        }
    }

    private var table: some View {
        Table(items, selection: $selection, sortOrder: $sortOrder) {
            TableColumn("Name", value: \.title) { item in
                Label {
                    Text(item.title).lineLimit(1)
                } icon: {
                    Image(systemName: item.isVideo ? "film" : item.isAudio ? "music.note" : "doc")
                        .foregroundStyle(.secondary)
                }
            }
            TableColumn("Kind", value: \.fileExtension) { item in
                Text(item.fileExtension.uppercased()).foregroundStyle(.secondary)
            }
            .width(min: 50, ideal: 60, max: 90)
            TableColumn("Size", value: \.sizeBytes) { item in
                Text(ByteCountFormatter.string(fromByteCount: item.sizeBytes, countStyle: .file))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            .width(min: 60, ideal: 80, max: 110)
            TableColumn("Added", value: \.addedAt) { item in
                Text(item.addedAt, format: .dateTime.day().month().year())
                    .foregroundStyle(.secondary)
            }
            .width(min: 80, ideal: 110, max: 140)
        }
        .contextMenu(forSelectionType: Vault.Item.ID.self) { ids in
            let chosen = items.filter { ids.contains($0.id) }
            if chosen.count == 1, let item = chosen.first {
                Button("Play") { playing = item }
                    .disabled(!(item.isVideo || item.isAudio))
                Button("Rename…") { renaming = item }
                Button("Export…") { export(item) }
                Divider()
            }
            if !chosen.isEmpty {
                Button("Delete…", role: .destructive) { confirmingDelete = chosen }
            }
        } primaryAction: { ids in
            // Double-click plays, the way it opens a file anywhere else on the Mac.
            if let item = items.first(where: { ids.contains($0.id) }),
               item.isVideo || item.isAudio {
                playing = item
            }
        }
        .onDeleteCommand {
            confirmingDelete = items.filter { selection.contains($0.id) }
        }
    }

    // MARK: - Actions

    private func chooseFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Add to Vault"
        panel.message = "The files are encrypted as they are added. The originals stay where they are."
        if panel.runModal() == .OK { model.addToVault(panel.urls) }
    }

    private func drop(_ providers: [NSItemProvider]) -> Bool {
        guard model.vaultUnlocked else { return false }
        let group = DispatchGroup()
        nonisolated(unsafe) var urls: [URL] = []
        let lock = NSLock()
        for provider in providers {
            group.enter()
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url { lock.withLock { urls.append(url) } }
                group.leave()
            }
        }
        group.notify(queue: .main) { model.addToVault(urls) }
        return true
    }

    private func export(_ item: Vault.Item) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = item.fileExtension.isEmpty
            ? item.title : "\(item.title).\(item.fileExtension)"
        panel.message = "This writes a decrypted copy. It is not protected once it leaves the vault."
        if panel.runModal() == .OK, let url = panel.url { model.exportFromVault(item, to: url) }
    }
}

/// Plays one item straight out of its encrypted file.
private struct VaultPlayerSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let item: Vault.Item

    // The loader has to outlive the player: AVFoundation holds its delegate weakly, and a
    // loader that goes away mid-film stops the film with no error.
    @State private var loader: VaultAssetLoader?
    @State private var player: AVPlayer?
    @State private var problem: String?

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Color.black
                if let player {
                    PlayerView(player: player)
                } else if let problem {
                    Text(problem).foregroundStyle(.white)
                } else {
                    ProgressView()
                }
            }
            .frame(minWidth: 720, minHeight: 405)

            HStack {
                Text(item.title).lineLimit(1)
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            .padding(12)
        }
        .task {
            do {
                let reader = try model.vault.reader(for: item.id)
                let loader = VaultAssetLoader(reader: reader, contentType: item.contentType)
                self.loader = loader
                let player = AVPlayer(playerItem: AVPlayerItem(asset: loader.makeAsset(id: item.id)))
                self.player = player
                player.play()
            } catch {
                problem = String(describing: error)
            }
        }
        .onDisappear {
            player?.pause()
            player = nil
            loader = nil
        }
    }
}

private struct RenameSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var title: String
    let onSave: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Rename").font(.headline)
            TextField("Name", text: $title).frame(width: 320).onSubmit(save)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Rename", action: save)
                    .keyboardShortcut(.defaultAction)
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
    }

    private func save() {
        let trimmed = title.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        onSave(trimmed)
        dismiss()
    }
}

/// AppKit's player, the one QuickTime uses: its controls, full screen and Picture in Picture.
/// SwiftUI's VideoPlayer aborted the app as it was set up, inside the framework itself.
private struct PlayerView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.controlsStyle = .floating
        view.allowsPictureInPicturePlayback = true
        view.player = player
        return view
    }

    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
}
