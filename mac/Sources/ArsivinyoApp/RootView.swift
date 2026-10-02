import ArsivinyoCore
import SwiftUI

/// The window: a source list on the left, the chosen section on the right.
///
/// `NavigationSplitView` rather than a row of tabs. Tabs are what the phone uses because a
/// phone has one column; copying them onto a 27-inch display is the shape the Qt app had
/// and the reason it read as a port.
struct RootView: View {
    @Environment(AppModel.self) private var model
    /// The theme's accent for the sidebar's icons; nil keeps the system's (Theme.swift).
    @Environment(\.themeAccent) private var themeAccent
    @State private var creatingPlaylist = false
    @State private var renamingPlaylist: MusicLibrary.Playlist?

    var body: some View {
        @Bindable var model = model

        NavigationSplitView {
            List(selection: $model.selection) {
                // The first group is unlabelled, the way a Mac source list usually opens.
                Section {
                    row(.download)
                    row(.library)
                    row(.watch)
                    row(.torrents)
                    row(.memes)
                    row(.vault)
                }
                // Places you go, so they sit where places are — as in Music.app.
                Section("Playlists") {
                    ForEach(model.playlists) { playlist in
                        Label(AppModel.displayName(of: playlist),
                              systemImage: playlist.isSystem ? "heart" : "music.note.list")
                            .listItemTint(themeAccent)
                            .tag(SidebarSelection.playlist(playlist.id))
                            .contextMenu {
                                if !playlist.isSystem {
                                    Button("Rename…") { renamingPlaylist = playlist }
                                    Button("Delete Playlist", role: .destructive) {
                                        model.deletePlaylist(playlist.id)
                                    }
                                }
                            }
                    }
                }
                .contextMenu {
                    Button("New Playlist…") { creatingPlaylist = true }
                }
                Section("Network") {
                    row(.devices)
                }
            }
            .listStyle(.sidebar)
            .themedSidebar()
            .navigationSplitViewColumnWidth(min: 180, ideal: 200, max: 260)
        } detail: {
            detail
                // A default; Music sets its own, and the innermost title wins.
                .navigationTitle(model.section.title)
                .toolbar { toolbar }
        }
        .sheet(isPresented: $model.showUnlockSheet) { UnlockSheet() }
        // A link from a paired device is offered wherever the window is, not only on Devices.
        .sheet(item: Binding(get: { model.devices?.linkRequest }, set: { model.devices?.linkRequest = $0 })) { request in
            LinkRequestSheet(request: request) { audio in
                model.queue.enqueue(url: request.url, audioOnly: audio)
                model.section = .download
            }
        }
        // A stremio:// link: asked about, then installed. Only its host is shown, since the
        // rest of it can carry an account key.
        .confirmationDialog("Install this add-on?",
                            isPresented: Binding(get: { model.addonLink != nil }, set: { if !$0 { model.addonLink = nil } })) {
            Button("Install") {
                guard let link = model.addonLink else { return }
                model.addonLink = nil
                Task {
                    do {
                        let manifest = try await model.watch.install(link.absoluteString)
                        model.watchNotice = String(localized: "Installed \(manifest.name).")
                    } catch {
                        model.watchNotice = String(localized: "Could not install it (\(String(describing: error))).")
                    }
                }
            }
        } message: {
            Text("A link asks to install an add-on from \(model.addonLink?.host ?? "").")
        }
        .sheet(isPresented: $creatingPlaylist) {
            NameSheet(title: "New Playlist", initial: "") { model.createPlaylist(named: $0) }
        }
        .sheet(item: $renamingPlaylist) { playlist in
            NameSheet(title: "Rename Playlist", initial: playlist.name) { model.renamePlaylist(playlist.id, to: $0) }
        }
        // The quick prompt for the meme that just downloaded, over whatever is showing.
        .sheet(item: Binding(get: { model.tagPrompts.first.map(PromptId.init) },
                             set: { if $0 == nil, !model.tagPrompts.isEmpty { model.tagPrompts.removeFirst() } })) { prompt in
            MemeSheet(ids: [prompt.id], mode: .prompt)
        }
        // Across the whole window, so the transport survives switching sections.
        .safeAreaInset(edge: .bottom, spacing: 0) { PlayerBar() }
    }

    private func row(_ section: AppSection) -> some View {
        Label(section.title, systemImage: section.symbol)
            .listItemTint(themeAccent)
            .tag(SidebarSelection.section(section))
    }

    @ViewBuilder
    private var detail: some View {
        switch model.section {
        case .download: DownloadView()
        case .library: MusicView()
        case .watch: WatchView()
        case .torrents: TorrentsView()
        case .memes: MemesView()
        case .vault: VaultView()
        case .devices: DevicesView()
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        // The lock belongs in the toolbar whatever section is showing: it is the one piece
        // of state that changes what the rest of the app will let you do.
        ToolbarItem(placement: .primaryAction) {
            Button {
                model.toggleVaultLock()
            } label: {
                // In words as well: an open padlock alone read as "locked", and the vault may
                // already be open at launch ("Remember on this Mac").
                Label(model.vaultUnlocked ? LocalizedStringKey("Vault Open") : LocalizedStringKey("Vault Locked"),
                      systemImage: model.vaultUnlocked ? "lock.open.fill" : "lock.fill")
                    .labelStyle(.titleAndIcon)
                    .foregroundStyle(model.vaultUnlocked ? Color.orange : Color.secondary)
            }
            .help(model.vaultUnlocked
                  ? "The vault is unlocked. Lock it now."
                  : "The vault is locked.")
        }
    }
}

private struct PromptId: Identifiable { let id: String }
