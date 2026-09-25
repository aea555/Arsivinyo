import SwiftUI

/// The window: a source list on the left, the chosen section on the right.
///
/// `NavigationSplitView` rather than a row of tabs. Tabs are what the phone uses because a
/// phone has one column; copying them onto a 27-inch display is the shape the Qt app had
/// and the reason it read as a port.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model

        NavigationSplitView {
            List(selection: $model.section) {
                // The first group is unlabelled, the way a Mac source list usually opens.
                // A section called Library containing an item called Library read as a bug.
                Section {
                    row(.download)
                    row(.library)
                    row(.vault)
                }
                Section("Network") {
                    row(.devices)
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 180, ideal: 200, max: 260)
        } detail: {
            detail
                .navigationTitle(model.section.title)
                .toolbar { toolbar }
        }
    }

    private func row(_ section: AppSection) -> some View {
        Label(section.title, systemImage: section.symbol)
            .tag(section)
    }

    @ViewBuilder
    private var detail: some View {
        switch model.section {
        case .download: DownloadView()
        case .library: LibraryView()
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
                Label(model.vaultUnlocked ? "Lock" : "Unlock",
                      systemImage: model.vaultUnlocked ? "lock.open" : "lock")
            }
            .help(model.vaultUnlocked
                  ? "The vault is unlocked. Lock it now."
                  : "The vault is locked.")
        }
    }
}
