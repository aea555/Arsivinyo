import ArsivinyoPairingC
import CryptoKit
import Foundation

/// The pairing wire format and its cryptography, over shared/pairing and OpenSSL. See
/// `shared/pairing/PROTOCOL.md`; `CoreChecks` holds all of this to `VECTORS.json`.
public enum Pairing {
    public static let serviceType = "_arsivinyo._tcp"
    public static let controlFrame: UInt8 = 0
    public static let bulkFrame: UInt8 = 1
    public static let maxFrameBytes = 8 * 1024 * 1024
    public static let roleServer = UInt8(ascii: "S")
    public static let roleClient = UInt8(ascii: "C")
    public static let nonceBytes = 32

    public struct Failure: Error, CustomStringConvertible {
        public let description: String
        init(_ description: String? = nil) {
            self.description = description ?? String(cString: av_pair_last_error())
        }
    }

    // MARK: Frames

    public static func encodeFrame(type: UInt8, payload: Data) -> Data? {
        var out = Data(count: payload.count + 5)
        let written = out.withUnsafeMutableBytes { outRaw in
            payload.withUnsafeBytes { raw in
                av_pair_encode_frame(type, raw.bindMemory(to: UInt8.self).baseAddress, payload.count,
                                     outRaw.bindMemory(to: UInt8.self).baseAddress)
            }
        }
        return written == 0 ? nil : out.prefix(written)
    }

    public enum Decoded: Equatable {
        case frame(type: UInt8, payload: Data, consumed: Int)
        case incomplete
        case tooLarge
        case badType
    }

    public static func decodeFrame(_ buffer: Data) -> Decoded {
        var type: UInt8 = 0
        var consumed = 0
        let result = buffer.withUnsafeBytes { raw in
            av_pair_decode_frame(raw.bindMemory(to: UInt8.self).baseAddress, buffer.count, &type, &consumed)
        }
        switch result {
        case 1:
            let start = buffer.startIndex
            return .frame(type: type, payload: buffer[(start + 5)..<(start + consumed)], consumed: consumed)
        case 0: return .incomplete
        case -1: return .tooLarge
        default: return .badType
        }
    }

    // MARK: Codes

    public static func codeInput(_ keyA: Data, _ keyB: Data) -> Data {
        var out = Data(count: 64)
        out.withUnsafeMutableBytes { outRaw in
            keyA.withUnsafeBytes { a in
                keyB.withUnsafeBytes { b in
                    av_pair_code_input(a.bindMemory(to: UInt8.self).baseAddress, b.bindMemory(to: UInt8.self).baseAddress,
                                       outRaw.bindMemory(to: UInt8.self).baseAddress)
                }
            }
        }
        return out
    }

    public static func code(fromDigest digest: Data) -> String {
        var out = [CChar](repeating: 0, count: 7)
        digest.withUnsafeBytes { av_pair_code($0.bindMemory(to: UInt8.self).baseAddress, digest.count, &out) }
        return String(decoding: out.prefix(6).map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }

    public static func commitment(clientNonce: Data) -> Data {
        var input = Data(count: 64 + nonceBytes)
        let length = input.withUnsafeMutableBytes { out in
            clientNonce.withUnsafeBytes { n in
                av_pair_commitment_input(n.bindMemory(to: UInt8.self).baseAddress, out.bindMemory(to: UInt8.self).baseAddress)
            }
        }
        return Data(SHA256.hash(data: input.prefix(length)))
    }

    public static func codeInputV2(_ keyA: Data, _ keyB: Data, clientNonce: Data, serverNonce: Data) -> Data {
        var input = Data(count: 256)
        let length = input.withUnsafeMutableBytes { out in
            keyA.withUnsafeBytes { a in
                keyB.withUnsafeBytes { b in
                    clientNonce.withUnsafeBytes { c in
                        serverNonce.withUnsafeBytes { s in
                            av_pair_code_input_v2(a.bindMemory(to: UInt8.self).baseAddress, b.bindMemory(to: UInt8.self).baseAddress,
                                                  c.bindMemory(to: UInt8.self).baseAddress, s.bindMemory(to: UInt8.self).baseAddress,
                                                  out.bindMemory(to: UInt8.self).baseAddress)
                        }
                    }
                }
            }
        }
        return input.prefix(length)
    }

    /// The six digits both devices show: v2, over both keys and both nonces.
    public static func codeV2(_ keyA: Data, _ keyB: Data, clientNonce: Data, serverNonce: Data) -> String {
        code(fromDigest: Data(SHA256.hash(data: codeInputV2(keyA, keyB, clientNonce: clientNonce, serverNonce: serverNonce))))
    }

    // MARK: Identity

    public static func fingerprint(of publicKey: Data) -> String {
        SHA256.hash(data: publicKey).map { String(format: "%02x", $0) }.joined()
    }

    public static func authTranscript(role: UInt8, serverCertSha256: Data, clientCertSha256: Data) -> Data {
        var out = Data(count: 128)
        let length = out.withUnsafeMutableBytes { outRaw in
            serverCertSha256.withUnsafeBytes { s in
                clientCertSha256.withUnsafeBytes { c in
                    av_pair_auth_transcript(role, s.bindMemory(to: UInt8.self).baseAddress, c.bindMemory(to: UInt8.self).baseAddress,
                                            outRaw.bindMemory(to: UInt8.self).baseAddress)
                }
            }
        }
        return out.prefix(length)
    }

    public static func publicKey(seed: Data) throws -> Data {
        var out = Data(count: 32)
        let ok = out.withUnsafeMutableBytes { o in
            seed.withUnsafeBytes { av_ed25519_public_key($0.bindMemory(to: UInt8.self).baseAddress, o.bindMemory(to: UInt8.self).baseAddress) }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    public static func sign(seed: Data, message: Data) throws -> Data {
        var out = Data(count: 64)
        let ok = out.withUnsafeMutableBytes { o in
            seed.withUnsafeBytes { s in
                message.withUnsafeBytes { m in
                    av_ed25519_sign(s.bindMemory(to: UInt8.self).baseAddress, m.bindMemory(to: UInt8.self).baseAddress, message.count,
                                    o.bindMemory(to: UInt8.self).baseAddress)
                }
            }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    public static func verify(publicKey: Data, message: Data, signature: Data) -> Bool {
        guard publicKey.count == 32, signature.count == 64 else { return false }
        return publicKey.withUnsafeBytes { k in
            message.withUnsafeBytes { m in
                signature.withUnsafeBytes { s in
                    av_ed25519_verify(k.bindMemory(to: UInt8.self).baseAddress, m.bindMemory(to: UInt8.self).baseAddress, message.count,
                                      s.bindMemory(to: UInt8.self).baseAddress)
                }
            }
        } == 1
    }
}

extension Data {
    public init?(hexString: String) {
        guard hexString.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hexString.count / 2)
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}

/// This Mac, as other devices see it: an Ed25519 key for life, and a name.
///
/// The seed is a 32-byte file readable only by this user, which is what the protocol settles
/// on for a desktop until the Keychain holds it. A device that loses it is a new device and
/// has to pair again, which is intended.
public final class DeviceIdentity: @unchecked Sendable {
    public let publicKey: Data
    public let fingerprint: String
    private let seed: Data
    private let nameURL: URL

    public init(directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let seedURL = directory.appendingPathComponent("identity.key")
        if let stored = try? Data(contentsOf: seedURL), stored.count == 32 {
            seed = stored
        } else {
            seed = try Crypto.randomBytes(32)
            try FileManager.default.writePrivately(seed, to: seedURL)
        }
        publicKey = try Pairing.publicKey(seed: seed)
        fingerprint = Pairing.fingerprint(of: publicKey)
        nameURL = directory.appendingPathComponent("name")
    }

    /// Shown to the other device, and never trusted by it. The Mac's own name by default.
    public var deviceName: String {
        get {
            (try? String(contentsOf: nameURL, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
                ?? Host.current().localizedName ?? "Mac"
        }
        set {
            let trimmed = String(newValue.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
            try? Data(trimmed.utf8).write(to: nameURL, options: .atomic)
        }
    }

    public func sign(_ message: Data) throws -> Data { try Pairing.sign(seed: seed, message: message) }
}

/// The devices this Mac has paired with.
public final class PeerRegistry: @unchecked Sendable {
    public struct Peer: Codable, Hashable, Sendable, Identifiable {
        public var fingerprint: String
        public var publicKey: String
        public var name: String
        public var address: String
        public var pairedAt: Double
        public var id: String { fingerprint }
    }

    private let url: URL
    private let lock = NSLock()

    public init(url: URL) { self.url = url }

    public func all() -> [Peer] {
        lock.withLock {
            (try? JSONDecoder().decode([Peer].self, from: Data(contentsOf: url))) ?? []
        }
    }

    public func peer(forKey key: Data) -> Peer? {
        let fingerprint = Pairing.fingerprint(of: key)
        return all().first { $0.fingerprint == fingerprint }
    }

    public func remember(key: Data, name: String, address: String) throws {
        try mutate { peers in
            let fingerprint = Pairing.fingerprint(of: key)
            peers.removeAll { $0.fingerprint == fingerprint }
            peers.append(Peer(fingerprint: fingerprint, publicKey: key.map { String(format: "%02x", $0) }.joined(),
                              name: name, address: address, pairedAt: Date().timeIntervalSince1970 * 1000))
        }
    }

    public func noteAddress(key: Data, address: String) {
        try? mutate { peers in
            let fingerprint = Pairing.fingerprint(of: key)
            if let index = peers.firstIndex(where: { $0.fingerprint == fingerprint }) { peers[index].address = address }
        }
    }

    public func forget(_ fingerprint: String) {
        try? mutate { $0.removeAll { $0.fingerprint == fingerprint } }
    }

    private func mutate(_ change: (inout [Peer]) -> Void) throws {
        try lock.withLock {
            var peers = (try? JSONDecoder().decode([Peer].self, from: Data(contentsOf: url))) ?? []
            change(&peers)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try FileManager.default.writePrivately(try JSONEncoder().encode(peers), to: url)
        }
    }
}
