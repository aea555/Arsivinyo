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
    var onTrackArrived: ((URL, URL?, PeerPlaylist?, String?, String?) -> Void)?
    var onMemeArrived: ((URL, [String: Any]) -> Void)?
    var onLinkRequested: ((String, String, String) -> Void)?

    init(library: MusicLibrary, incoming: URL, backupsFolder: @escaping () -> URL) {
        self.library = library
        self.incoming = incoming
        self.backupsFolder = backupsFolder
        try? FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o700])
    }

    func listing(kind: String) -> [[String: Any]] {
        let index = library.load()
        switch kind {
        case "music":
            return index.tracks.map {
                ["id": $0.id, "title": $0.title, "artist": $0.artist, "durationSec": $0.durationSeconds, "sizeBytes": $0.sizeBytes]
            }
        case "playlists":
            // Only ones with something in them: an empty playlist has nothing to send.
            return index.playlists.filter { !$0.trackIds.isEmpty }.map {
                ["id": $0.id, "name": $0.isSystem ? "" : $0.name, "favorites": $0.isSystem, "count": $0.trackIds.count]
            }
        default:
            return []
        }
    }

    /// Set by the Devices model: sends a playlist through its queue, as Send to does.
    var onPlaylistRequested: ((String, String) -> Void)?

    func sendPlaylist(_ id: String, to fingerprint: String) -> Bool {
        guard library.load().playlists.contains(where: { $0.id == id && !$0.trackIds.isEmpty }) else { return false }
        onPlaylistRequested?(id, fingerprint)
        return true
    }

    func openItem(id: String) -> ItemSource? {
        // Matched against the library's own entries, never turned into a path.
        guard let track = library.load().tracks.first(where: { $0.id == id }) else { return nil }
        let file = library.fileURL(for: track)
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? track.sizeBytes
        guard size > 0 else { return nil }
        return ItemSource(name: track.fileName, sizeBytes: size, file: file, artwork: library.artworkURL(for: track),
                          title: track.title, artist: track.artist)
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

    func existing(sizeBytes: Int64, sha256: Data, kind: String) -> String? {
        kind == "music" ? library.track(sizeBytes: sizeBytes, sha256: sha256)?.id : nil
    }

    var onTrackReused: ((String, PeerPlaylist) -> Void)?

    func reuse(_ id: String, playlist: PeerPlaylist?) {
        guard let playlist else { return }
        onTrackReused?(id, playlist)
    }

    func accepted(_ file: URL, kind: String, artwork: URL?, meme: [String: Any]?, playlist: PeerPlaylist?,
                  title: String?, artist: String?) {
        if kind == "meme" {
            try? artwork.map { try FileManager.default.removeItem(at: $0) }
            onMemeArrived?(file, meme ?? [:])
            return
        }
        if kind == "backups" {
            // Not a library item: it waits in Downloads for a restore, with its passphrase.
            let folder = backupsFolder()
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try? FileManager.default.moveItem(at: file, to: folder.appendingPathComponent(file.lastPathComponent))
            return
        }
        onTrackArrived?(file, artwork, playlist, title, artist)
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
        /// Its playlists, from a second listing; empty from a device too old to have them.
        var playlists: [Playlist] = []
        struct Item: Identifiable {
            let id: String
            let title: String
            let artist: String
            let seconds: Double
            let sizeBytes: Int64
        }
        struct Playlist: Identifiable {
            let id: String
            let name: String
            let favorites: Bool
            let count: Int
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
    /// The transfer in flight, for the bar shown over every section: which way, with whom, and
    /// its place in the sender's batch.
    private(set) var transferIncoming = false
    private(set) var transferPeer = ""
    private(set) var transferBatch: (index: Int, count: Int)?
    var linkRequest: LinkRequest?
    /// Opt-in: a link from a paired device is downloaded without asking.
    var autoDownloadLinks: Bool = UserDefaults.standard.bool(forKey: "autoDownloadPeerLinks") {
        didSet { UserDefaults.standard.set(autoDownloadLinks, forKey: "autoDownloadPeerLinks") }
    }
    /// Starts a download the user opted to take without being asked.
    var onAutoDownload: ((String, Bool) -> Void)?

    /// Tracks waiting to be sent, in order: the protocol moves one file at a time.
    private var outbox: [(track: MusicLibrary.Track, fingerprint: String, playlist: PeerPlaylist?)] = []
    /// Where a batch of sends is: the one being sent, of how many.
    private(set) var sending: (index: Int, count: Int)?
    private let library: MusicLibrary

    private var lastAttempt: [String: Date] = [:]
    private var timer: Timer?

    init(support: URL, library: MusicLibrary, backupsFolder: @escaping () -> URL) throws {
        let folder = support.appendingPathComponent("pairing", isDirectory: true)
        let identity = try DeviceIdentity(directory: folder)
        self.library = library
        content = LibraryContent(library: library, incoming: folder.appendingPathComponent("incoming"),
                                 backupsFolder: backupsFolder)
        service = PairingService(identity: identity, registry: PeerRegistry(url: folder.appendingPathComponent("peers.json")),
                                 content: content)
        service.onChange = { [weak self] in Task { @MainActor in self?.refresh() } }
        service.onMessage = { [weak self] text in Task { @MainActor in self?.message = text } }
        service.onSession = { [weak self] session in Task { @MainActor in self?.attach(session) } }
        discovery.onChange = { [weak self] in Task { @MainActor in self?.refresh(); self?.reconnect() } }
        content.onPlaylistRequested = { [weak self] id, fingerprint in
            Task { @MainActor in self?.sendPlaylist(id, to: fingerprint) }
        }
        content.onLinkRequested = { [weak self] url, kind, from in
            Task { @MainActor in
                guard let self else { return }
                if self.autoDownloadLinks {
                    self.onAutoDownload?(url, kind == "audio")
                } else {
                    self.linkRequest = LinkRequest(url: url, audio: kind == "audio", from: from)
                }
            }
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
        session.onListing = { [weak self] kind, items in
            Task { @MainActor in
                guard let self else { return }
                if kind == "playlists" {
                    let playlists = items.compactMap { item -> Listing.Playlist? in
                        guard let id = item["id"] as? String else { return nil }
                        return .init(id: id, name: item["name"] as? String ?? "", favorites: item["favorites"] as? Bool ?? false,
                                     count: (item["count"] as? NSNumber)?.intValue ?? 0)
                    }
                    if self.listing?.fingerprint == fingerprint {
                        self.listing?.playlists = playlists
                    } else {
                        self.listing = Listing(fingerprint: fingerprint, deviceName: session.link.peerName, items: [],
                                               playlists: playlists)
                    }
                    return
                }
                let kept = self.listing?.fingerprint == fingerprint ? self.listing?.playlists ?? [] : []
                self.listing = Listing(
                    fingerprint: fingerprint, deviceName: session.link.peerName,
                    items: items.compactMap { item in
                        guard let id = item["id"] as? String else { return nil }
                        return .init(id: id, title: item["title"] as? String ?? "", artist: item["artist"] as? String ?? "",
                                     seconds: item["durationSec"] as? Double ?? 0,
                                     sizeBytes: (item["sizeBytes"] as? NSNumber)?.int64Value ?? 0)
                    },
                    playlists: kept)
            }
        }
        session.onProgress = { [weak self] done, total in
            let incoming = session.incomingBatch
            Task { @MainActor in
                guard let self else { return }
                self.transfer = (done, total)
                // Not sending a batch from here: it is coming in.
                self.transferIncoming = self.sending == nil
                self.transferPeer = session.link.peerName
                self.transferBatch = self.transferIncoming ? incoming
                    : self.sending.flatMap { $0.count > 1 ? (index: $0.index, count: $0.count) : nil }
                // Coming in as part of a batch: how far, as the sender counts.
                if let incoming, self.sending == nil {
                    self.message = String(localized: "Receiving \(incoming.index) of \(incoming.count)…")
                }
            }
        }
        session.onReceived = { [weak self] _ in
            // What arrived is private: it is in the library, not named here.
            Task { @MainActor in
                self?.transfer = nil
                self?.message = String(localized: "A track arrived.")
            }
        }
        session.onSent = { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.transfer = nil
                if self.outbox.isEmpty {
                    self.message = String(localized: "Sent.")
                    self.sending = nil
                } else {
                    self.sendNext()
                }
            }
        }
        session.onTransferFailed = { [weak self] reason in
            Task { @MainActor in
                // The rest of a batch is not sent after a failure: what failed is said once.
                self?.outbox.removeAll()
                self?.sending = nil
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
        _ = service.session(for: fingerprint)?.requestListing(kind: "playlists")
    }

    /// Asks a paired device for a whole playlist; it sends the tracks and this Mac makes it.
    func fetchPlaylist(_ id: String, from fingerprint: String) {
        guard let session = service.session(for: fingerprint) else { return }
        if !session.requestPlaylist(id: id) { message = String(localized: "Wait for the transfer in progress to finish.") }
    }

    /// One of this Mac's playlists to a paired device, which makes it too.
    func sendPlaylist(_ playlist: MusicLibrary.Playlist, to fingerprint: String) {
        sendPlaylist(playlist.id, to: fingerprint)
    }

    /// A paired device asked for one of this Mac's playlists: sent as Send to sends it.
    private func sendPlaylist(_ id: String, to fingerprint: String) {
        let index = library.load()
        guard let playlist = index.playlists.first(where: { $0.id == id }) else { return }
        let byId = Dictionary(index.tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let ordered = playlist.trackIds.compactMap { byId[$0] }
        // Favorites are newest first here and travel oldest first (PROTOCOL.md, "Playlists").
        send(playlist.isSystem ? ordered.reversed() : ordered, to: fingerprint,
             playlist: playlist.isSystem ? PeerPlaylist(name: "", favorites: true) : PeerPlaylist(name: playlist.name))
    }

    func fetch(_ id: String, from fingerprint: String) {
        guard let session = service.session(for: fingerprint) else { return }
        if !session.requestItem(id: id) { message = String(localized: "Wait for the transfer in progress to finish.") }
    }

    /// Sends tracks to a paired device, one after another, as part of `playlist` when there is
    /// one: the other device puts them in its playlist of that name. More sent while a batch is
    /// going join its end.
    func send(_ tracks: [MusicLibrary.Track], to fingerprint: String, playlist: PeerPlaylist? = nil) {
        guard !tracks.isEmpty, service.session(for: fingerprint) != nil else { return }
        let idle = sending == nil
        outbox += tracks.map { ($0, fingerprint, playlist) }
        sending = (sending?.index ?? 0, (sending?.count ?? 0) + tracks.count)
        if idle { sendNext() }
    }

    private func sendNext() {
        guard !outbox.isEmpty else { sending = nil; return }
        let (track, fingerprint, playlist) = outbox.removeFirst()
        guard let session = service.session(for: fingerprint) else {
            outbox.removeAll()
            sending = nil
            message = String(localized: "The device disconnected; the rest was not sent.")
            return
        }
        sending = sending.map { ($0.index + 1, $0.count) }
        message = sending.map { String(localized: "Sending \($0.index) of \($0.count)…") }
        let file = library.fileURL(for: track)
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? track.sizeBytes
        let batch = sending.flatMap { $0.count > 1 ? (index: $0.index, count: $0.count) : nil }
        if !session.send(ItemSource(name: track.fileName, sizeBytes: size, file: file, artwork: library.artworkURL(for: track),
                                    playlist: playlist, batch: batch, title: track.title, artist: track.artist)) {
            outbox.removeAll()
            sending = nil
            message = String(localized: "Wait for the transfer in progress to finish.")
        }
    }

    func sendLink(_ url: String, audio: Bool, to fingerprint: String) {
        _ = service.session(for: fingerprint)?.requestDownload(url: url, mediaKind: audio ? "audio" : "video")
    }

    func cancelTransfer() {
        outbox.removeAll()
        sending = nil
        service.sessions.forEach { $0.cancel() }
        transfer = nil
    }
}
