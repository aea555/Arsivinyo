import ArsivinyoPairingC
import Foundation

/// Listens for peers, connects to them, and runs the pairing ceremony. The phone's
/// `PairingService`, with the same rules:
///
/// - A device whose key has not been paired is closed after the handshake, unless the user
///   has opened a pairing window, which closes on its own after two minutes.
/// - The six digits come from the commit-reveal exchange of protocol v2, so a man in the
///   middle cannot aim them.
/// - Two connections between the same pair settle on the one opened by the device with the
///   smaller fingerprint, on both ends, without talking about it.
///
/// Callbacks come from connection threads. `onChange` is the one to redraw on.
public final class PairingService: @unchecked Sendable {
    public let identity: DeviceIdentity
    public let registry: PeerRegistry
    private let content: PeerContent

    /// Something a screen shows has changed.
    public var onChange: (() -> Void)?
    /// A connection was refused or lost, or a pairing failed. Never carries a file name.
    public var onMessage: ((String) -> Void)?
    /// A session appeared, for the caller to attach its own callbacks.
    public var onSession: ((PeerSession) -> Void)?

    public private(set) var port: UInt16 = 0
    public var pairingMode: Bool { lock.withLock { pairingUntil.map { $0 > Date() } ?? false } }
    public var pendingCode: String { lock.withLock { pending?.code ?? "" } }
    public var pendingName: String { lock.withLock { pending?.link.peerName ?? "" } }

    private let lock = NSLock()
    private var context: OpaquePointer?
    private var listener: Int32 = -1
    private var sessionList: [PeerSession] = []
    private var pairingUntil: Date?
    private var pending: Ceremony?

    private final class Ceremony {
        let link: PeerLink
        let key: Data
        var clientNonce: Data?
        var serverNonce: Data?
        var commitment: Data?
        var code: String?
        /// The other device's user has confirmed; its side of the session is live.
        var peerConfirmed = false
        init(link: PeerLink, key: Data) {
            self.link = link
            self.key = key
        }
    }

    public init(identity: DeviceIdentity, registry: PeerRegistry, content: PeerContent) {
        self.identity = identity
        self.registry = registry
        self.content = content
    }

    public var sessions: [PeerSession] { lock.withLock { sessionList } }

    public func session(for fingerprint: String) -> PeerSession? {
        sessions.first { $0.link.peerFingerprint == fingerprint }
    }

    // MARK: Listening

    /// Starts listening. The session certificate is made here, for this run only.
    public func start(port requested: UInt16 = 0) throws {
        try lock.withLock {
            guard listener < 0 else { return }
            guard let made = av_tls_context_new() else { throw Pairing.Failure() }
            var bound: UInt16 = 0
            let socket = av_tls_listen(requested, &bound)
            guard socket >= 0 else {
                av_tls_context_free(made)
                throw Pairing.Failure()
            }
            context = made
            listener = socket
            port = bound
        }
        let thread = Thread { [weak self] in self?.acceptLoop() }
        thread.name = "pairing-accept"
        thread.start()
    }

    public func stop() {
        let (socket, open) = lock.withLock { () -> (Int32, [PeerSession]) in
            let socket = listener
            listener = -1
            return (socket, sessionList)
        }
        if socket >= 0 { av_tls_close_listener(socket) }
        cancelPairing()
        open.forEach { $0.link.close() }
    }

    private func acceptLoop() {
        while true {
            let (socket, context) = lock.withLock { (listener, self.context) }
            guard socket >= 0, let context else { return }
            guard let connection = av_tls_accept(context, socket) else {
                if lock.withLock({ listener < 0 }) { return }
                continue
            }
            adopt(PeerLink(connection: connection, role: Pairing.roleServer, identity: identity))
        }
    }

    /// Connects to a device at an address it announced. Returns at once; the outcome is
    /// reported through the callbacks.
    public func connect(host: String, port: UInt16) {
        guard lock.withLock({ self.context != nil }) else { return }
        let thread = Thread { [weak self] in
            guard let self, let context = self.lock.withLock({ self.context }) else { return }
            guard let connection = av_tls_connect(context, host, port, 10) else {
                self.onMessage?(String(localized: "Could not reach that device."))
                return
            }
            self.adopt(PeerLink(connection: connection, role: Pairing.roleClient, identity: self.identity))
        }
        thread.name = "pairing-connect"
        thread.start()
    }

    /// Every callback is set here, once, before the link starts, and never swapped. Messages
    /// go through `route`, which decides under the lock whether they belong to the ceremony
    /// or the session, so confirming switches them over without a gap.
    private func adopt(_ link: PeerLink) {
        link.onAuthenticated = { [weak self, unowned link] key, name in self?.authenticated(link, key: key, name: name) }
        link.onControl = { [weak self, unowned link] in self?.route(link, $0) }
        link.onBulk = { [weak self, unowned link] chunk in self?.sessionOn(link)?.receive(bulk: chunk) }
        link.onFailed = { [weak self, unowned link] reason in
            self?.sessionOn(link)?.linkEnded(reason)
            self?.onMessage?(reason)
            self?.drop(link)
        }
        link.onClosed = { [weak self, unowned link] in
            self?.sessionOn(link)?.linkEnded(String(localized: "the connection closed"))
            self?.drop(link)
        }
        link.start()
    }

    private func sessionOn(_ link: PeerLink) -> PeerSession? {
        lock.withLock { sessionList.first { $0.link === link } }
    }

    private func route(_ link: PeerLink, _ message: [String: Any]) {
        enum Target { case session(PeerSession), ceremony, none }
        let target = lock.withLock { () -> Target in
            if let session = sessionList.first(where: { $0.link === link }) { return .session(session) }
            return pending?.link === link ? .ceremony : .none
        }
        switch target {
        case .session(let session): session.receive(control: message)
        case .ceremony: ceremony(link, message)
        case .none: break
        }
    }

    // MARK: Who is this

    private func authenticated(_ link: PeerLink, key: Data, name: String) {
        let fingerprint = Pairing.fingerprint(of: key)
        guard registry.peer(forKey: key) != nil else {
            guard pairingMode else {
                // It proved it holds a key, and this Mac never agreed to trust that key.
                onMessage?(String(localized: "A device that is not paired tried to connect."))
                link.close()
                return
            }
            let busy = lock.withLock { () -> Bool in
                if let pending, pending.link !== link { return true }
                pending = Ceremony(link: link, key: key)
                return false
            }
            guard !busy else {
                // One ceremony at a time: two codes at once is how the wrong one gets confirmed.
                onMessage?(String(localized: "Another device is already pairing."))
                link.close()
                return
            }
            if link.role == Pairing.roleClient, let nonce = try? Crypto.randomBytes(Pairing.nonceBytes) {
                lock.withLock { pending?.clientNonce = nonce }
                link.send(control: ["t": "pair-commit", "c": Pairing.commitment(clientNonce: nonce).hexString])
            }
            onChange?()
            return
        }

        // Both devices may reach each other at once. Both keep the connection opened by the
        // device with the smaller fingerprint.
        if let existing = session(for: fingerprint), existing.link !== link {
            let openedBy = link.role == Pairing.roleClient ? identity.fingerprint : fingerprint
            if openedBy == min(identity.fingerprint, fingerprint) {
                existing.link.close()
                drop(existing.link)
            } else {
                link.close()
                return
            }
        }
        registry.noteAddress(key: key, address: link.peerAddress)
        let session = PeerSession(link: link, content: content)
        lock.withLock { sessionList.append(session) }
        onSession?(session)
        onChange?()
    }

    /// The commit-reveal exchange that produces the six digits.
    private func ceremony(_ link: PeerLink, _ message: [String: Any]) {
        guard let current = lock.withLock({ pending?.link === link ? pending : nil }) else { return }
        let kind = message["t"] as? String ?? ""
        if kind == "pair-confirm" {
            lock.withLock { current.peerConfirmed = true }
            return
        }
        let value = (message[kind == "pair-commit" ? "c" : "n"] as? String).flatMap(Data.init(hexString:)) ?? Data()
        let server = link.role == Pairing.roleServer

        if server, kind == "pair-commit", current.commitment == nil {
            guard value.count == 32, let nonce = try? Crypto.randomBytes(Pairing.nonceBytes) else {
                return failCeremony(link, String(localized: "The other device sent a malformed commitment."))
            }
            lock.withLock {
                current.commitment = value
                current.serverNonce = nonce
            }
            link.send(control: ["t": "pair-nonce", "n": nonce.hexString])
        } else if !server, kind == "pair-nonce", current.serverNonce == nil, let clientNonce = current.clientNonce {
            guard value.count == Pairing.nonceBytes else {
                return failCeremony(link, String(localized: "The other device sent a malformed nonce."))
            }
            link.send(control: ["t": "pair-reveal", "n": clientNonce.hexString])
            show(current, clientNonce: clientNonce, serverNonce: value)
        } else if server, kind == "pair-reveal", current.clientNonce == nil,
                  let commitment = current.commitment, let serverNonce = current.serverNonce {
            guard value.count == Pairing.nonceBytes,
                  Crypto.constantTimeEquals(Pairing.commitment(clientNonce: value), commitment) else {
                // The nonce revealed is not the one committed to: a broken device, or an
                // attempt to steer the code. Neither gets a code to confirm.
                return failCeremony(link, String(localized: "The other device did not keep to its commitment."))
            }
            show(current, clientNonce: value, serverNonce: serverNonce)
        }
        // Anything else is ignored: no verb is served until the pairing is confirmed.
    }

    private func show(_ ceremony: Ceremony, clientNonce: Data, serverNonce: Data) {
        let code = Pairing.codeV2(identity.publicKey, ceremony.key, clientNonce: clientNonce, serverNonce: serverNonce)
        lock.withLock {
            ceremony.clientNonce = clientNonce
            ceremony.serverNonce = serverNonce
            ceremony.code = code
        }
        onChange?()
    }

    private func failCeremony(_ link: PeerLink, _ reason: String) {
        onMessage?(reason)
        link.close()
    }

    // MARK: Pairing, by the user

    /// Opens a two-minute window in which an unknown device may present itself.
    public func beginPairing(seconds: TimeInterval = 120) {
        lock.withLock { pairingUntil = Date().addingTimeInterval(seconds) }
        onChange?()
        DispatchQueue.global().asyncAfter(deadline: .now() + seconds + 0.5) { [weak self] in
            guard let self, !self.pairingMode, self.pendingCode.isEmpty else { return }
            self.cancelPairing()
        }
    }

    /// The user confirmed the six digits match. False if there is nothing to confirm yet.
    @discardableResult
    public func confirmPairing() -> Bool {
        guard let ceremony = lock.withLock({ () -> Ceremony? in
            guard let pending, pending.code != nil else { return nil }
            return pending
        }) else { return false }
        do {
            try registry.remember(key: ceremony.key, name: ceremony.link.peerName, address: ceremony.link.peerAddress)
        } catch {
            onMessage?(String(localized: "The pairing could not be saved."))
            ceremony.link.close()
            return false
        }
        // The ceremony ends and the session begins in one step under the lock, which is
        // also where `route` decides: no message falls between them. The other device may
        // still be showing its code and ignores all but the ceremony until its user
        // confirms, so this side's requests wait for its `pair-confirm`.
        let session = lock.withLock { () -> PeerSession in
            let session = PeerSession(link: ceremony.link, content: content, peerReady: ceremony.peerConfirmed)
            sessionList.append(session)
            if pending === ceremony { pending = nil }
            pairingUntil = nil
            return session
        }
        ceremony.link.send(control: ["t": "pair-confirm"])
        onSession?(session)
        onMessage?(String(localized: "Paired with \(ceremony.link.peerName)."))
        onChange?()
        return true
    }

    public func cancelPairing() {
        let link = lock.withLock { () -> PeerLink? in
            let link = pending?.link
            pending = nil
            pairingUntil = nil
            return link
        }
        link?.close()
        onChange?()
    }

    public func forget(_ fingerprint: String) {
        registry.forget(fingerprint)
        session(for: fingerprint)?.link.close()
        onChange?()
    }

    private func drop(_ link: PeerLink) {
        let changed = lock.withLock { () -> Bool in
            let before = sessionList.count
            sessionList.removeAll { $0.link === link }
            var changed = sessionList.count != before
            if pending?.link === link {
                pending = nil
                changed = true
            }
            return changed
        }
        if changed { onChange?() }
    }
}
