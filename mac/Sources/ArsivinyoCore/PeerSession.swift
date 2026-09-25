import CryptoKit
import Foundation

/// Something a peer may fetch: a name, a size, and a way to read the bytes.
public struct ItemSource: Sendable {
    public let name: String
    public let sizeBytes: Int64
    public let file: URL
    /// The cover, sent along: both apps keep covers beside the files, not inside them.
    public let artwork: URL?

    public init(name: String, sizeBytes: Int64, file: URL, artwork: URL? = nil) {
        self.name = name
        self.sizeBytes = sizeBytes
        self.file = file
        self.artwork = artwork
    }
}

/// What a paired device may see and do here: the music library, a place for what it sends,
/// and a link it asks this Mac to download. Never the vault, which only moves by backup.
public protocol PeerContent: AnyObject, Sendable {
    /// Items of `kind` ("music") as protocol `listing` entries.
    func listing(kind: String) -> [[String: Any]]
    /// The bytes behind an id from `listing`, or nil if the peer may not have it.
    func openItem(id: String) -> ItemSource?
    /// Where an incoming file should be written. The sender's name is a hint, never a path.
    func destination(forName name: String, kind: String) -> URL?
    /// A verified file has landed, with the cover that came with it; take them in.
    func accepted(_ file: URL, kind: String, artwork: URL?)
    /// The peer asks this Mac to fetch a link. Shown to the user, never started unasked.
    func downloadRequested(url: String, mediaKind: String, from peerName: String)
}

/// The verbs, over one authenticated link. The phone's `PeerSession`.
///
/// One transfer at a time per connection. A received file is written to a `.part`, hashed
/// as it arrives, and moved into place only once the declared size and SHA-256 both match,
/// so a truncated transfer never reaches the library.
public final class PeerSession: @unchecked Sendable {
    public let link: PeerLink
    private let content: PeerContent

    public var onListing: ((String, [[String: Any]]) -> Void)?
    public var onProgress: ((Int64, Int64) -> Void)?
    public var onReceived: ((String) -> Void)?
    public var onSent: (() -> Void)?
    public var onTransferFailed: ((String) -> Void)?

    private let lock = NSLock()
    private var receiving: Receiving?
    private var sending: ItemSource?
    private var accepted = DispatchSemaphore(value: 0)
    private var cancelled = false

    private final class Receiving {
        let kind: String
        let finalURL: URL
        let partURL: URL
        let total: Int64
        let expected: Data
        /// The cover from the offer, held until the file itself verifies.
        let artwork: Data?
        let artworkExtension: String
        let handle: FileHandle
        var hasher = SHA256()
        var received: Int64 = 0

        init(kind: String, finalURL: URL, total: Int64, expected: Data, artwork: Data?, artworkExtension: String) throws {
            self.kind = kind
            self.finalURL = finalURL
            partURL = finalURL.appendingPathExtension("part")
            self.total = total
            self.expected = expected
            self.artwork = artwork
            self.artworkExtension = artworkExtension
            FileManager.default.createFile(atPath: partURL.path, contents: nil, attributes: [.posixPermissions: 0o600])
            handle = try FileHandle(forWritingTo: partURL)
        }
    }

    /// False just after this side confirmed a pairing the other has not: requests wait for
    /// its `pair-confirm`, since until then it ignores all but the ceremony.
    private var ready: Bool
    private var waiting: [() -> Void] = []

    init(link: PeerLink, content: PeerContent, peerReady: Bool = true) {
        self.link = link
        self.content = content
        ready = peerReady
        // The link's callbacks are the service's, set once before the link started. It
        // routes each message here or to the ceremony under its own lock, so there is no
        // moment where a message reaches neither. Swapping the link's handlers from another
        // thread, as the phone did, is both a data race and that moment.
    }

    /// The service hands this session everything the link says once pairing is done.
    func receive(control message: [String: Any]) { onControl(message) }
    func receive(bulk chunk: Data) { onBulk(chunk) }

    /// The link is gone: whatever was in flight stops.
    func linkEnded(_ reason: String) {
        abortReceiving(reason)
        abortSending(reason)
    }

    public var isTransferring: Bool { lock.withLock { receiving != nil || sending != nil } }

    /// Runs `request` now, or once the other device has confirmed the pairing.
    @discardableResult
    private func whenReady(_ request: @escaping () -> Bool) -> Bool {
        let now = lock.withLock { () -> Bool in
            if !ready { waiting.append { _ = request() } }
            return ready
        }
        return now ? request() : true
    }

    private func peerConfirmed() {
        let queued = lock.withLock { () -> [() -> Void] in
            ready = true
            defer { waiting = [] }
            return waiting
        }
        queued.forEach { $0() }
    }

    // MARK: Asking

    public func requestListing(kind: String = "music") -> Bool {
        whenReady { [link] in link.send(control: ["t": "list", "kind": kind]) }
    }

    public func requestItem(id: String) -> Bool {
        guard !isTransferring else { return false }
        return whenReady { [link] in link.send(control: ["t": "get", "id": id]) }
    }

    public func requestDownload(url: String, mediaKind: String) -> Bool {
        whenReady { [link] in link.send(control: ["t": "download", "url": url, "mediaKind": mediaKind]) }
    }

    /// Offers a file. Returns once the offer is under way, not when it is done.
    public func send(_ source: ItemSource, kind: String = "music") -> Bool {
        let started = lock.withLock { () -> Bool in
            guard receiving == nil, sending == nil else { return false }
            sending = source
            cancelled = false
            accepted = DispatchSemaphore(value: 0)
            return true
        }
        guard started else { return false }
        return whenReady { [self] in
            let thread = Thread { [self] in stream(source, kind: kind) }
            thread.name = "pairing-send"
            thread.start()
            return true
        }
    }

    private func stream(_ source: ItemSource, kind: String) {
        guard let digest = Self.sha256(of: source.file) else { return abortSending(String(localized: "could not read the item")) }
        var offer: [String: Any] = ["t": "put", "name": source.name, "kind": kind,
                                    "sizeBytes": source.sizeBytes, "sha256": digest.hexString]
        // The cover rides in the offer: small, optional, ignored by a receiver that does not
        // know it, and left out over the cap rather than making the offer huge.
        if let art = source.artwork, let data = try? Data(contentsOf: art), (1...Self.maxArtworkBytes).contains(data.count) {
            offer["artwork"] = data.base64EncodedString()
            offer["artworkName"] = art.lastPathComponent
        }
        guard link.send(control: offer) else {
            return abortSending(String(localized: "the connection went away"))
        }
        // A peer that never answers must not leave a send hanging for the life of the app.
        guard accepted.wait(timeout: .now() + 60) == .success else {
            return abortSending(String(localized: "the device did not answer"))
        }
        if lock.withLock({ cancelled || sending == nil }) { return }

        guard let handle = try? FileHandle(forReadingFrom: source.file) else {
            return abortSending(String(localized: "could not read the item"))
        }
        defer { try? handle.close() }
        var sent: Int64 = 0
        while sent < source.sizeBytes {
            if lock.withLock({ cancelled }) { return }
            guard let chunk = try? handle.read(upToCount: 256 * 1024), !chunk.isEmpty else {
                return abortSending(String(localized: "the file ended early"))
            }
            guard link.send(bulk: chunk) else { return abortSending(String(localized: "the connection went away")) }
            sent += Int64(chunk.count)
            onProgress?(sent, source.sizeBytes)
        }
        link.send(control: ["t": "complete", "sha256": digest.hexString])
        lock.withLock { sending = nil }
        onSent?()
    }

    public func cancel() {
        guard isTransferring else { return }
        link.send(control: ["t": "cancel"])
        abortReceiving(String(localized: "cancelled"))
        abortSending(String(localized: "cancelled"))
    }

    // MARK: Answering

    private func onControl(_ message: [String: Any]) {
        switch message["t"] as? String {
        case "pair-confirm":
            peerConfirmed()
        case "list":
            let kind = message["kind"] as? String ?? ""
            link.send(control: ["t": "listing", "kind": kind, "items": content.listing(kind: kind)])
        case "listing":
            onListing?(message["kind"] as? String ?? "", message["items"] as? [[String: Any]] ?? [])
        case "get":
            if isTransferring {
                link.send(control: ["t": "reject", "reason": "busy"])
            } else if let source = content.openItem(id: message["id"] as? String ?? ""), send(source) {
                break
            } else {
                link.send(control: ["t": "error", "code": "NOT_FOUND", "message": "no such item"])
            }
        case "put":
            handlePut(message)
        case "accept":
            accepted.signal()
        case "reject":
            abortSending(message["reason"] as? String ?? String(localized: "refused"))
        case "complete":
            handleComplete(message)
        case "cancel":
            abortReceiving(String(localized: "the other device cancelled"))
            abortSending(String(localized: "the other device cancelled"))
        case "download":
            content.downloadRequested(url: message["url"] as? String ?? "", mediaKind: message["mediaKind"] as? String ?? "video",
                                      from: link.peerName)
        case "error":
            onTransferFailed?(message["message"] as? String ?? message["code"] as? String ?? "")
        default:
            break
        }
    }

    private func handlePut(_ message: [String: Any]) {
        guard !isTransferring else {
            link.send(control: ["t": "reject", "reason": "busy"])
            return
        }
        let size = (message["sizeBytes"] as? NSNumber)?.int64Value ?? -1
        guard size >= 0, let expected = (message["sha256"] as? String).flatMap(Data.init(hexString:)), expected.count == 32 else {
            link.send(control: ["t": "reject", "reason": "malformed"])
            return
        }
        let kind = message["kind"] as? String ?? "music"
        let artwork = (message["artwork"] as? String).flatMap { Data(base64Encoded: $0) }
            .flatMap { (1...Self.maxArtworkBytes).contains($0.count) ? $0 : nil }
        // Only the extension is taken from the peer's name, and only a plain one.
        let proposed = ((message["artworkName"] as? String ?? "") as NSString).pathExtension.lowercased()
        let artworkExtension = (1...5).contains(proposed.count) && proposed.allSatisfy({ $0.isLetter || $0.isNumber }) ? proposed : "jpg"
        guard let destination = content.destination(forName: message["name"] as? String ?? "", kind: kind),
              let started = try? Receiving(kind: kind, finalURL: destination, total: size, expected: expected,
                                           artwork: artwork, artworkExtension: artworkExtension) else {
            link.send(control: ["t": "reject", "reason": "refused"])
            return
        }
        lock.withLock { receiving = started }
        link.send(control: ["t": "accept", "transferId": UUID().uuidString])
    }

    private func onBulk(_ chunk: Data) {
        guard let current = lock.withLock({ receiving }) else { return }
        // Past the declared size is refused rather than trusting the sender to stop.
        guard current.received + Int64(chunk.count) <= current.total else {
            return abortReceiving(String(localized: "the other device sent more than it said"))
        }
        do {
            try current.handle.write(contentsOf: chunk)
            current.hasher.update(data: chunk)
            current.received += Int64(chunk.count)
            onProgress?(current.received, current.total)
        } catch {
            abortReceiving(String(localized: "could not write the file"))
        }
    }

    private func handleComplete(_ message: [String: Any]) {
        guard let current = lock.withLock({ receiving }) else { return }
        try? current.handle.close()
        // Size and hash both: a truncated file hashes correctly to its own truncated bytes.
        let actual = Data(current.hasher.finalize())
        let declared = (message["sha256"] as? String).flatMap(Data.init(hexString:))
        guard current.received == current.total, actual == current.expected, actual == declared else {
            return abortReceiving(String(localized: "the transfer did not verify"))
        }
        do {
            try FileManager.default.moveItem(at: current.partURL, to: current.finalURL)
        } catch {
            return abortReceiving(String(localized: "could not store the file"))
        }
        lock.withLock { receiving = nil }
        var artworkURL: URL?
        if let artwork = current.artwork {
            let url = current.finalURL.appendingPathExtension("cover").appendingPathExtension(current.artworkExtension)
            if (try? artwork.write(to: url)) != nil { artworkURL = url }
        }
        content.accepted(current.finalURL, kind: current.kind, artwork: artworkURL)
        onReceived?(current.kind)
    }

    private func abortReceiving(_ reason: String) {
        guard let current = lock.withLock({ () -> Receiving? in
            defer { receiving = nil }
            return receiving
        }) else { return }
        try? current.handle.close()
        try? FileManager.default.removeItem(at: current.partURL)
        onTransferFailed?(reason)
    }

    private func abortSending(_ reason: String) {
        let was = lock.withLock { () -> Bool in
            let was = sending != nil
            sending = nil
            cancelled = true
            return was
        }
        guard was else { return }
        accepted.signal()
        onTransferFailed?(reason)
    }

    /// A cover is a few hundred kilobytes at most; anything bigger is not sent along.
    static let maxArtworkBytes = 1024 * 1024

    static func sha256(of url: URL) -> Data? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try? handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return Data(hasher.finalize())
    }
}
