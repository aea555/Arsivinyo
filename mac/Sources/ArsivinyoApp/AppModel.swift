import ArsivinyoCore
import Foundation
import SwiftUI

/// Which part of the app the source list is showing.
///
/// Settings is absent on purpose: on a Mac it is a window behind ⌘, not a place you
/// navigate to.
enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case download
    case library
    case vault
    case devices

    var id: String { rawValue }

    var title: String {
        switch self {
        case .download: return "Download"
        case .library: return "Music"
        case .vault: return "Vault"
        case .devices: return "Devices"
        }
    }

    /// SF Symbols, so the source list looks like every other Mac app's.
    var symbol: String {
        switch self {
        case .download: return "arrow.down.circle"
        case .library: return "music.note.list"
        case .vault: return "lock.shield"
        case .devices: return "laptopcomputer.and.iphone"
        }
    }

    var shortcut: KeyEquivalent {
        switch self {
        case .download: return "1"
        case .library: return "2"
        case .vault: return "3"
        case .devices: return "4"
        }
    }
}

/// App-wide state.
///
/// Observable rather than a pile of singletons so the views can read it directly and the
/// menu commands can drive the same state the sidebar does.
@MainActor
@Observable
final class AppModel {
    /// Reopens where you left off, as a Mac app does.
    var section: AppSection = AppSection(
        rawValue: UserDefaults.standard.string(forKey: "section") ?? "") ?? .download {
        didSet { UserDefaults.standard.set(section.rawValue, forKey: "section") }
    }

    /// The engine and the queue live here rather than in the download view, so switching
    /// to another section does not throw away what is running.
    let engine: EngineClient
    let queue: DownloadQueue

    /// Why the engine is not usable, when it is not. Shown rather than swallowed: a
    /// download button that quietly does nothing is the worst of both.
    private(set) var engineProblem: String?

    var downloadDirectory: URL {
        didSet { UserDefaults.standard.set(downloadDirectory.path, forKey: "downloadDirectory") }
    }

    init() {
        // Overridable, so a seeded vault can be looked at without touching the real one or
        // the real Keychain item. The Qt app had the same escape hatch.
        let environment = ProcessInfo.processInfo.environment
        let support = environment["ARSIVINYO_DATA_DIR"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("Arsivinyo", isDirectory: true)
        keybox = Keybox(
            directory: support,
            keychainService: environment["ARSIVINYO_KEYCHAIN_SERVICE"] ?? "com.arsivinyo.mac.keybox")
        vault = Vault(root: support.appendingPathComponent("vault"), keybox: keybox)

        let stored = UserDefaults.standard.string(forKey: "downloadDirectory")
        let destination = stored.map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Downloads/Arsivinyo")
        downloadDirectory = destination

        // While the app runs from the repository this finds the fetched engine; a bundled
        // build will carry its own copy and this is where that choice will be made.
        let layout = EngineClient.Layout.developmentFromSource()
        engine = EngineClient(layout: layout ?? .init(
            python: URL(fileURLWithPath: "/usr/bin/python3"),
            engine: URL(fileURLWithPath: "/nonexistent"),
            ytDlp: URL(fileURLWithPath: "/nonexistent")))
        queue = DownloadQueue(engine: engine, destination: destination)

        if layout == nil {
            engineProblem = "The download engine is not set up. Run mac/scripts/fetch-engine.sh."
        } else {
            do {
                try engine.start()
            } catch {
                engineProblem = String(describing: error)
            }
        }

        // "Remember on this Mac" means never being asked.
        if keybox.unlockFromKeychain() { vaultDidUnlock() }
    }

    // MARK: - The vault

    let keybox: Keybox
    let vault: Vault

    /// Mirrors the key box, which is not observable itself. Every change goes through this
    /// model, so the toolbar, the menu and the vault pane cannot disagree about it.
    private(set) var vaultUnlocked = false
    private(set) var vaultItems: [Vault.Item] = []
    private(set) var vaultProblem: String?
    var showUnlockSheet = false

    var vaultConfigured: Bool { keybox.isConfigured }

    /// What the user last pasted or dropped, waiting to be downloaded.
    var pendingURL: String = ""

    func pasteAndDownload() {
        section = .download
        if let text = NSPasteboard.general.string(forType: .string) {
            pendingURL = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    func toggleVaultLock() {
        if vaultUnlocked {
            lockVault()
        } else {
            section = .vault
            showUnlockSheet = true
        }
    }

    // MARK: - Vault actions

    /// Returns a message to show, or nil on success.
    func unlockVault(passphrase: String) -> String? {
        do {
            if keybox.isConfigured {
                try keybox.unlock(passphrase: passphrase)
            } else {
                try keybox.create(passphrase: passphrase)
            }
            vaultDidUnlock()
            return nil
        } catch {
            return String(describing: error)
        }
    }

    func lockVault() {
        keybox.lock()
        vault.forget()
        vaultUnlocked = false
        vaultItems = []
        vaultProblem = nil
    }

    private func vaultDidUnlock() {
        vaultUnlocked = true
        showUnlockSheet = false
        refreshVault()
    }

    func refreshVault() {
        do {
            vaultItems = try vault.items()
            vaultProblem = nil
        } catch {
            vaultItems = []
            vaultProblem = String(describing: error)
        }
    }

    /// Encrypting is slow for video, so it runs off the main actor.
    func addToVault(_ urls: [URL], removeOriginals: Bool = false) {
        guard vaultUnlocked else { showUnlockSheet = true; return }
        let vault = vault
        Task.detached {
            var failure: String?
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    try vault.add(url, removeOriginal: removeOriginals)
                } catch {
                    failure = String(describing: error)
                }
            }
            await MainActor.run {
                self.refreshVault()
                if let failure { self.vaultProblem = failure }
            }
        }
    }

    func removeFromVault(_ item: Vault.Item) {
        do {
            try vault.remove(item.id)
            refreshVault()
        } catch {
            vaultProblem = String(describing: error)
        }
    }

    func renameInVault(_ item: Vault.Item, to title: String) {
        do {
            try vault.rename(item.id, to: title)
            refreshVault()
        } catch {
            vaultProblem = String(describing: error)
        }
    }

    func exportFromVault(_ item: Vault.Item, to url: URL) {
        let vault = vault
        Task.detached {
            do {
                try vault.export(item.id, to: url)
            } catch {
                await MainActor.run { self.vaultProblem = String(describing: error) }
            }
        }
    }
}
