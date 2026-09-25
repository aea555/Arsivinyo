import SwiftUI

/// The four sections, as they stand before their machinery is wired in.
///
/// Each says what it will do rather than showing an empty pane, so the shape of the app is
/// legible while it is being built.

struct LibraryView: View {
    var body: some View {
        ContentUnavailableView(
            "No music yet",
            systemImage: "music.note.list",
            description: Text("Audio you download lands here, with playlists and presets."))
    }
}

struct VaultView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ContentUnavailableView {
            Label(model.vaultUnlocked ? "Vault is empty" : "Vault is locked",
                  systemImage: model.vaultUnlocked ? "lock.open" : "lock.shield")
        } description: {
            Text(model.vaultUnlocked
                 ? "Files you add are encrypted, and so is the list of what they are."
                 : "Everything here is encrypted. Unlock to see what is in it.")
        } actions: {
            Button(model.vaultUnlocked ? "Add Files…" : "Unlock") { model.toggleVaultLock() }
                .buttonStyle(.borderedProminent)
        }
    }
}

struct DevicesView: View {
    var body: some View {
        ContentUnavailableView(
            "No devices paired",
            systemImage: "laptopcomputer.and.iphone",
            description: Text("Pair your phone to move files between it and this Mac."))
    }
}

struct SettingsView: View {
    var body: some View {
        TabView {
            Text("Folders, cookies and the engine will live here.")
                .padding(40)
                .tabItem { Label("General", systemImage: "gearshape") }
            Text("Passphrase, recovery key, and how this Mac unlocks.")
                .padding(40)
                .tabItem { Label("Security", systemImage: "lock") }
        }
        .frame(width: 520, height: 320)
    }
}
