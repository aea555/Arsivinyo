import ArsivinyoPairingC
import Foundation

/// One connection to one peer: TLS, identity, and framing. The phone's `PeerLink`.
///
/// Nothing but the peer's own `auth` message is read until it verifies: a signature, by the
/// key it claims, over a transcript naming both certificates of this connection. That is
/// what keeps a man in the middle out, since it would present different certificates on
/// each leg.
///
/// Reading happens on a thread of the link's own and every callback runs on it, in order.
/// Bulk frames have to stay in order, so nothing here hops elsewhere; callers that touch the
/// interface do that themselves.
public final class PeerLink: @unchecked Sendable {
    public let role: UInt8
    public private(set) var peerKey = Data()
    public private(set) var peerName = ""
    public private(set) var isAuthenticated = false
    public let peerAddress: String

    public var onAuthenticated: ((Data, String) -> Void)?
    public var onControl: (([String: Any]) -> Void)?
    public var onBulk: ((Data) -> Void)?
    /// Fatal. The connection is closed by the time it runs.
    public var onFailed: ((String) -> Void)?
    public var onClosed: (() -> Void)?

    private let connection: OpaquePointer
    private let identity: DeviceIdentity
    private let lock = NSLock()
    private var closed = false
    private var thread: Thread?

    init(connection: OpaquePointer, role: UInt8, identity: DeviceIdentity) {
        self.connection = connection
        self.role = role
        self.identity = identity
        var buffer = [CChar](repeating: 0, count: 128)
        peerAddress = av_tls_peer_address(connection, &buffer, buffer.count) == 1
            ? String(decoding: buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self) : ""
    }

    deinit { av_tls_free(connection) }

    public var peerFingerprint: String { peerKey.isEmpty ? "" : Pairing.fingerprint(of: peerKey) }

    /// Sends this device's `auth`, then reads until the connection ends.
    func start() {
        let thread = Thread { [self] in run() }
        thread.name = "pairing-link"
        self.thread = thread
        thread.start()
    }

    private func run() {
        guard let (server, client) = certificateHashes() else { return fail("the peer presented no certificate") }
        do {
            let transcript = Pairing.authTranscript(role: role, serverCertSha256: server, clientCertSha256: client)
            let auth: [String: Any] = [
                "t": "auth", "v": 2, "key": identity.publicKey.hexString,
                "name": identity.deviceName, "sig": try identity.sign(transcript).hexString,
            ]
            guard send(control: auth) else { return fail("could not send this device's identity") }
        } catch {
            return fail("could not sign the session transcript")
        }
        readLoop()
    }

    /// (server's, client's), in the transcript's order whichever end this is.
    private func certificateHashes() -> (Data, Data)? {
        var own = Data(count: 32)
        var peer = Data(count: 32)
        let ok = own.withUnsafeMutableBytes { o in av_tls_local_cert_sha256(connection, o.bindMemory(to: UInt8.self).baseAddress) } == 1
            && peer.withUnsafeMutableBytes { p in av_tls_peer_cert_sha256(connection, p.bindMemory(to: UInt8.self).baseAddress) } == 1
        guard ok else { return nil }
        return role == Pairing.roleServer ? (own, peer) : (peer, own)
    }

    private func readLoop() {
        var inbox = Data()
        var chunk = [UInt8](repeating: 0, count: 64 * 1024)
        while true {
            let read = av_tls_read(connection, &chunk, chunk.count)
            if read <= 0 { break }
            inbox.append(contentsOf: chunk[0..<read])
            while true {
                switch Pairing.decodeFrame(inbox) {
                case .incomplete:
                    break
                case .tooLarge:
                    return fail("the peer announced an oversized frame")
                case .badType:
                    return fail("the peer sent an unknown frame type")
                case .frame(let type, let payload, let consumed):
                    let body = Data(payload)
                    inbox = Data(inbox.dropFirst(consumed))
                    if type == Pairing.controlFrame {
                        if !handleControl(body) { return }
                    } else if !isAuthenticated {
                        // Bulk before the peer proved who it is would be an unknown device's
                        // bytes written to disk.
                        return fail("the peer sent data before authenticating")
                    } else {
                        onBulk?(body)
                    }
                    continue
                }
                break
            }
        }
        let wasClosed = lock.withLock { () -> Bool in
            let was = closed
            closed = true
            return was
        }
        if !wasClosed { onClosed?() }
    }

    private func handleControl(_ payload: Data) -> Bool {
        guard let message = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            fail("the peer sent a malformed control message")
            return false
        }
        guard isAuthenticated else {
            guard message["t"] as? String == "auth",
                  let key = (message["key"] as? String).flatMap(Data.init(hexString:)), key.count == 32,
                  let signature = (message["sig"] as? String).flatMap(Data.init(hexString:)),
                  let (server, client) = certificateHashes()
            else {
                fail("the peer could not prove its identity")
                return false
            }
            let peerRole = role == Pairing.roleServer ? Pairing.roleClient : Pairing.roleServer
            let transcript = Pairing.authTranscript(role: peerRole, serverCertSha256: server, clientCertSha256: client)
            guard Pairing.verify(publicKey: key, message: transcript, signature: signature) else {
                // Either it does not hold the key it claims, or something in the middle is
                // terminating TLS: the transcript names this connection's certificates.
                fail("the peer could not prove its identity")
                return false
            }
            peerKey = key
            // Shown, never matched against.
            peerName = String((message["name"] as? String ?? "").prefix(64))
            isAuthenticated = true
            onAuthenticated?(key, peerName)
            return true
        }
        onControl?(message)
        return true
    }

    @discardableResult
    public func send(control message: [String: Any]) -> Bool {
        guard let json = try? JSONSerialization.data(withJSONObject: message) else { return false }
        return send(frame: Pairing.controlFrame, json)
    }

    @discardableResult
    public func send(bulk chunk: Data) -> Bool { send(frame: Pairing.bulkFrame, chunk) }

    private func send(frame type: UInt8, _ payload: Data) -> Bool {
        guard let frame = Pairing.encodeFrame(type: type, payload: payload),
              !lock.withLock({ closed }) else { return false }
        return frame.withUnsafeBytes { av_tls_write(connection, $0.bindMemory(to: UInt8.self).baseAddress, frame.count) } == 1
    }

    private func fail(_ reason: String) {
        let wasClosed = lock.withLock { () -> Bool in
            let was = closed
            closed = true
            return was
        }
        guard !wasClosed else { return }
        av_tls_shutdown(connection)
        onFailed?(reason)
    }

    public func close() {
        let wasClosed = lock.withLock { () -> Bool in
            let was = closed
            closed = true
            return was
        }
        guard !wasClosed else { return }
        av_tls_shutdown(connection)
        onClosed?()
    }
}

extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
