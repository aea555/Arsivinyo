import ArsivinyoCore
import Foundation
import SwiftUI

/// What a paired device may reach on this Mac: the music library to browse and take from, a
/// place for what it sends, and a way to ask for a download. Never the vault.
final class LibraryContent: PeerContent, @unchecked Sendable {
    private let library: MusicLibrary
    private let incoming: URL
    /// Where a received backup is left, for the user to restore deliberately.
    var backupsFolder: () -> URL
    var onTrackArrived: ((URL) -> Void)?
    var onLinkRequested: ((String, String, String) -> Void)?

    init(library: MusicLibrary, incoming: URL, backupsFolder: @escaping () -> URL) {
        self.library = library
        self.incoming = incoming
        self.backupsFolder = backupsFolder
        try? FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o700])
    }

    func listing(kind: String) -> [[String: Any]] {
        guard kind == "music" else { return [] }
        return library.load().tracks.map {
            ["id": $0.id, "title": $0.title, "artist": $0.artist, "durationSec": $0.durationSeconds, "sizeBytes": $0.sizeBytes]
        }
    }

    func openItem(id: String) -> ItemSource? {
        // Matched against the library's own entries, never turned into a path.
        guard let track = library.load().tracks.first(where: { $0.id == id }) else { return nil }
        let file = library.fileURL(for: track)
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? track.sizeBytes
        guard size > 0 else { return nil }
        return ItemSource(name: track.fileName, sizeBytes: size, file: file)
    }

    func destination(forName name: String, kind: String) -> URL? {
        // A name from a peer is data, not a path: its last component only, no hiding dot.
        var bare = (name as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "-")
        while bare.hasPrefix(".") { bare.removeFirst() }
        bare = bare.trimmingCharacters(in: .whitespaces)
        guard !bare.isEmpty else { return nil }
        var candidate = incoming.appendingPathComponent(bare)
        var attempt = 2
        while FileManager.default.fileExists(atPath: candidate.path) || FileManager.default.fileExists(atPath: candidate.path + ".part") {
            let base = (bare as NSString).deletingPathExtension
            let ext = (bare as NSString).pathExtension
            candidate = incoming.appendingPathComponent(ext.isEmpty ? "\(base) (\(attempt))" : "\(base) (\(attempt)).\(ext)")
            attempt += 1
        }
        return candidate
    }

    func accepted(_ file: URL, kind: String) {
        if kind == "backups" {
            // Not a library item: it waits in Downloads for a restore, with its passphrase.
            let folder = backupsFolder()
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try? FileManager.default.moveItem(at: file, to: folder.appendingPathComponent(file.lastPathComponent))
            return
        }
        onTrackArrived?(file)
    }

    func downloadRequested(url: String, mediaKind: String, from peerName: String) {
        onLinkRequested?(url, mediaKind, peerName)
    }
}

/// The Devices section's state, from the pairing service and discovery.
@MainActor
@Observable
final class DevicesModel {
    struct Listing {
        let fingerprint: String
        let deviceName: String
        var items: [Item]
        struct Item: Identifiable {
            let id: String
            let title: String
            let artist: String
            let seconds: Double
            let sizeBytes: Int64
        }
    }

    struct LinkRequest: Identifiable {
        let id = UUID()
        let url: String
        let audio: Bool
        let from: String
    }

    let service: PairingService
    let content: LibraryContent
    private let discovery = Discovery()

    private(set) var peers: [PeerRegistry.Peer] = []
    private(set) var nearby: [Discovery.Found] = []
    private(set) var connected: Set<String> = []
    private(set) var pendingCode = ""
    private(set) var pendingName = ""
    private(set) var pairingMode = false
    private(set) var listening = false
    var message: String?
    var listing: Listing?
    var transfer: (done: Int64, total: Int64)?
    var linkRequest: LinkRequest?

    private var lastAttempt: [String: Date] = [:]
    private var timer: Timer?

    init(support: URL, library: MusicLibrary, backupsFolder: @escaping () -> URL) throws {
        let folder = support.appendingPathComponent("pairing", isDirectory: true)
        let identity = try DeviceIdentity(directory: folder)
        content = LibraryContent(library: library, incoming: folder.appendingPathComponent("incoming"),
                                 backupsFolder: backupsFolder)
        service = PairingService(identity: identity, registry: PeerRegistry(url: folder.appendingPathComponent("peers.json")),
                                 content: content)
        service.onChange = { [weak self] in Task { @MainActor in self?.refresh() } }
        service.onMessage = { [weak self] text in Task { @MainActor in self?.message = text } }
        service.onSession = { [weak self] session in Task { @MainActor in self?.attach(session) } }
        discovery.onChange = { [weak self] in Task { @MainActor in self?.refresh(); self?.reconnect() } }
        content.onLinkRequested = { [weak self] url, kind, from in
            Task { @MainActor in self?.linkRequest = LinkRequest(url: url, audio: kind == "audio", from: from) }
        }
    }

    var deviceName: String {
        get { service.identity.deviceName }
        set {
            service.identity.deviceName = newValue
            if listening { discovery.start(fingerprint: service.identity.fingerprint, name: newValue, port: service.port) }
        }
    }

    var fingerprint: String { service.identity.fingerprint }

    /// Listens and announces. Safe to call again.
    func start() {
        guard !listening else { return }
        do {
            try service.start()
            discovery.start(fingerprint: service.identity.fingerprint, name: service.identity.deviceName, port: service.port)
            listening = true
            // A paired device that dropped is tried again while it is still being announced.
            timer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.reconnect() }
            }
        } catch {
            message = String(describing: error)
        }
        refresh()
    }

    func refresh() {
        peers = service.registry.all().sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        let paired = Set(peers.map(\.fingerprint))
        // Found and not paired: offered for pairing. Paired ones are listed with the paired.
        nearby = discovery.peers.filter { !paired.contains($0.fingerprint) }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        connected = Set(service.sessions.map(\.link.peerFingerprint))
        pendingCode = service.pendingCode
        pendingName = service.pendingName
        pairingMode = service.pairingMode
    }

    func isNearby(_ fingerprint: String) -> Bool { discovery.peers.contains { $0.fingerprint == fingerprint } }

    private func reconnect() {
        let paired = Set(service.registry.all().map(\.fingerprint))
        for found in discovery.peers where paired.contains(found.fingerprint) && service.session(for: found.fingerprint) == nil {
            if let last = lastAttempt[found.fingerprint], Date().timeIntervalSince(last) < 20 { continue }
            lastAttempt[found.fingerprint] = Date()
            service.connect(host: found.host, port: found.port)
        }
    }

    private func attach(_ session: PeerSession) {
        let fingerprint = session.link.peerFingerprint
        session.onListing = { [weak self] _, items in
            Task { @MainActor in
                guard let self else { return }
                self.listing = Listing(
                    fingerprint: fingerprint, deviceName: session.link.peerName,
                    items: items.compactMap { item in
                        guard let id = item["id"] as? String else { return nil }
                        return .init(id: id, title: item["title"] as? String ?? "", artist: item["artist"] as? String ?? "",
                                     seconds: item["durationSec"] as? Double ?? 0,
                                     sizeBytes: (item["sizeBytes"] as? NSNumber)?.int64Value ?? 0)
                    })
            }
        }
        session.onProgress = { [weak self] done, total in Task { @MainActor in self?.transfer = (done, total) } }
        session.onReceived = { [weak self] _ in
            // What arrived is private: it is in the library, not named here.
            Task { @MainActor in
                self?.transfer = nil
                self?.message = String(localized: "A track arrived.")
            }
        }
        session.onSent = { [weak self] in
            Task { @MainActor in
                self?.transfer = nil
                self?.message = String(localized: "Sent.")
            }
        }
        session.onTransferFailed = { [weak self] reason in
            Task { @MainActor in
                self?.transfer = nil
                self?.message = reason
            }
        }
        refresh()
    }

    // MARK: Actions

    /// Pairs with a device found on the network: opens the window here and connects to it.
    /// The other device has to have its own window open, as the phone's "Add device" does.
    func pair(with found: Discovery.Found) {
        service.beginPairing()
        service.connect(host: found.host, port: found.port)
        refresh()
    }

    func togglePairingWindow() {
        if service.pairingMode { service.cancelPairing() } else { service.beginPairing() }
        refresh()
    }

    func confirm() {
        _ = service.confirmPairing()
        refresh()
    }

    func reject() {
        service.cancelPairing()
        refresh()
    }

    func forget(_ fingerprint: String) {
        service.forget(fingerprint)
        refresh()
    }

    func browse(_ fingerprint: String) {
        listing = nil
        _ = service.session(for: fingerprint)?.requestListing()
    }

    func fetch(_ id: String, from fingerprint: String) {
        guard let session = service.session(for: fingerprint) else { return }
        if !session.requestItem(id: id) { message = String(localized: "Wait for the transfer in progress to finish.") }
    }

    func send(_ track: MusicLibrary.Track, from library: MusicLibrary, to fingerprint: String) {
        guard let session = service.session(for: fingerprint) else { return }
        let file = library.fileURL(for: track)
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? track.sizeBytes
        if !session.send(ItemSource(name: track.fileName, sizeBytes: size, file: file)) {
            message = String(localized: "Wait for the transfer in progress to finish.")
        }
    }

    func sendLink(_ url: String, audio: Bool, to fingerprint: String) {
        _ = service.session(for: fingerprint)?.requestDownload(url: url, mediaKind: audio ? "audio" : "video")
    }

    func cancelTransfer() {
        service.sessions.forEach { $0.cancel() }
        transfer = nil
    }
}
