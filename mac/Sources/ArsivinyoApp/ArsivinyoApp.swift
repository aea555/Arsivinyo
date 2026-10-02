import SwiftUI

/// The app.
///
/// Deliberately built out of the things a Mac app is expected to have rather than a window
/// with controls in it: a source list, a unified toolbar, a Settings scene on ⌘, and real
/// menu commands. The Qt app it replaces had none of that, which is most of why it never
/// felt like it belonged on the machine.
@main
struct ArsivinyoApp: App {

    @State private var model = AppModel()

    var body: some Scene {
        // One main window, not a group: everything in it shares one model, and a link opened
        // from elsewhere (magnet:, stremio://, a .torrent) goes to this window, or reopens
        // it. As a group, every link made a second window showing the same thing.
        Window("Arsivinyo", id: "main") {
            RootView()
                .environment(model)
                .themed(model.theme)
                // Wide enough that the source list and a detail pane both have room; the
                // window is otherwise free to be resized and its size is restored.
                .frame(minWidth: 860, minHeight: 560)
                // A Stremio add-on's configure page installs through a stremio:// link.
                .onOpenURL { url in
                    switch url.scheme?.lowercased() {
                    case "stremio":
                        model.section = .watch
                        model.addonLink = url
                    case "magnet":
                        model.section = .torrents
                        model.torrentLink = url.absoluteString
                    case "file" where url.pathExtension.lowercased() == "torrent":
                        model.section = .torrents
                        model.torrentLink = url.path
                    default:
                        break
                    }
                }
        }
        .windowToolbarStyle(.unified)
        .commands { AppCommands(model: model) }

        // Films and episodes play in a window of their own, which full screen takes over.
        Window("Player", id: "watch-player") {
            WatchPlayerWindow()
                .environment(model)
                .themed(model.theme)
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1100, height: 640)

        // ⌘, opens a separate window, which is where a Mac keeps its preferences. The
        // phone puts settings in a tab; doing that here is the tell that an app was ported
        // rather than written for the platform.
        Settings {
            SettingsView()
                .environment(model)
                .themed(model.theme)
        }
    }
}

/// Menu-bar commands. What a Mac user reaches for before they look for a button.
struct AppCommands: Commands {
    let model: AppModel

    var body: some Commands {
        // Replace "New Window", which means nothing here.
        CommandGroup(replacing: .newItem) {
            Button("New Download…") { model.section = .download }
                .keyboardShortcut("n")
            Button("Paste Link and Download") { model.pasteAndDownload() }
                .keyboardShortcut("v", modifiers: [.command, .shift])
        }

        CommandMenu("Vault") {
            Button(model.vaultUnlocked ? LocalizedStringKey("Lock Vault") : LocalizedStringKey("Unlock Vault…")) {
                model.toggleVaultLock()
            }
            .keyboardShortcut("l", modifiers: [.command, .shift])
            Divider()
            Button("Add Files to Vault…") { model.section = .vault }
                .keyboardShortcut("i", modifiers: [.command, .shift])
        }

        CommandGroup(after: .sidebar) {
            Divider()
            ForEach(AppSection.allCases) { section in
                Button(section.title) { model.section = section }
                    .keyboardShortcut(section.shortcut, modifiers: .command)
            }
        }
    }
}
