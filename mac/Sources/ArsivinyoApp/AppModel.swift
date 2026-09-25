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
@Observable
final class AppModel {
    var section: AppSection = .download

    /// Bound to the real key box once the vault lands; the menu item reads it today so the
    /// command and the view can never disagree about which way round it is.
    var vaultUnlocked = false

    /// What the user last pasted or dropped, waiting to be downloaded.
    var pendingURL: String = ""

    func pasteAndDownload() {
        section = .download
        if let text = NSPasteboard.general.string(forType: .string) {
            pendingURL = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    func toggleVaultLock() {
        section = .vault
        vaultUnlocked.toggle()
    }
}
