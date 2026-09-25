import Foundation
import Security

/// The key box: which secrets can open the vault.
///
/// A keystore is a container, not a source of secrecy. The master key is 32 random bytes,
/// generated once and never derived from anything a person types; each way in is a *slot*
/// that wraps that same key under its own key-encryption key. That is what makes changing a
/// passphrase or adding a way in cheap — both re-wrap 32 bytes and touch no content — and it
/// is why removing the last slot has to be refused: nothing would be left that could open it.
///
///   passphrase   Argon2id over what you type. The real protection.
///   keychain     "Remember on this Mac": a random key held in the login Keychain, which is
///                encrypted under your login password. Better than the Qt app's file beside
///                the data, but still convenience — anyone logged in as you can use it.
///   recovery     a key file you keep somewhere else, for when the passphrase is gone.
///
/// The on-disk shape matches the Qt app's `keybox.json`, and the wrap is the shared C++, so
/// it is one design across all three apps.
public final class Keybox: @unchecked Sendable {

    public enum Failure: Error, Equatable, CustomStringConvertible {
        case alreadyConfigured
        case notConfigured
        case locked
        case wrongPassphrase
        case tooShort
        case damaged(String)
        case lastSlot
        case keychain(OSStatus)

        public var description: String {
            switch self {
            case .alreadyConfigured: return String(localized: "A passphrase is already set.")
            case .notConfigured: return String(localized: "No passphrase has been set yet.")
            case .locked: return String(localized: "Unlock first.")
            case .wrongPassphrase: return String(localized: "That passphrase is not correct.")
            case .tooShort: return String(localized: "Use at least 8 characters. Length matters more than symbols.")
            case .damaged(let why): return String(localized: "The stored key is damaged: \(why)")
            case .lastSlot: return String(localized: "That is the only way in. Removing it would lose everything.")
            case .keychain(let status): return String(localized: "The Keychain refused (\(status)).")
            }
        }
    }

    private enum Kind: String {
        case passphrase
        case keychain = "platform-keystore"
        case recovery
    }

    private struct Slot {
        var id: String
        var kind: Kind
        var salt: Data
        var verifier: Data
        var wrapped: Data
        var kdf: Crypto.Argon2idParams?
    }

    private static let passphraseSlot = "passphrase"
    private static let keychainSlot = "keychain"
    private static let recoverySlot = "recovery"

    private let directory: URL
    private let params: Crypto.Argon2idParams
    /// Injectable so the checks use their own item and cannot clobber the app's real one.
    private let keychainService: String
    private let guardLock = NSLock()
    private var masterKey: Data?

    /// `params` is injectable so the checks are not twenty seconds of key stretching. The app
    /// always uses the shipped profile; there is no setting that reaches `.fast`.
    public init(directory: URL, params: Crypto.Argon2idParams = .shipped,
                keychainService: String = "com.arsivinyo.mac.keybox") {
        self.directory = directory
        self.params = params
        self.keychainService = keychainService
    }

    deinit { wipe() }

    private var fileURL: URL { directory.appendingPathComponent("keybox.json") }

    public var isConfigured: Bool { FileManager.default.fileExists(atPath: fileURL.path) }
    public var isUnlocked: Bool { guardLock.withLock { masterKey != nil } }
    public var isRemembered: Bool { (try? load().contains { $0.kind == .keychain }) ?? false }
    public var hasRecoveryKey: Bool { (try? load().contains { $0.kind == .recovery }) ?? false }

    // MARK: - Opening

    public func create(passphrase: String) throws {
        guard !isConfigured else { throw Failure.alreadyConfigured }
        guard passphrase.count >= 8 else { throw Failure.tooShort }

        let master = try Crypto.randomBytes(32)
        let slot = try passphraseSlot(for: passphrase, master: master)
        try save([slot])
        guardLock.withLock { masterKey = master }
    }

    public func unlock(passphrase: String) throws {
        guard isConfigured else { throw Failure.notConfigured }
        for slot in try load() where slot.kind == .passphrase {
            let kek = try Crypto.argon2id(secret: Data(passphrase.utf8), salt: slot.salt,
                                          params: slot.kdf ?? params)
            switch Crypto.unwrapMasterKey(kek: kek, slotId: slot.id,
                                          verifier: slot.verifier, wrapped: slot.wrapped) {
            case .unwrapped(let master):
                guardLock.withLock { masterKey = master }
                return
            case .wrongSecret:
                continue
            case .damaged(let why):
                throw Failure.damaged(why)
            }
        }
        throw Failure.wrongPassphrase
    }

    /// Opens without asking, if this Mac was told to remember. Called at launch.
    @discardableResult
    public func unlockFromKeychain() -> Bool {
        guard let slot = try? load().first(where: { $0.kind == .keychain }) else { return false }
        let secret: Data
        do {
            secret = try readKeychain()
        } catch Failure.keychain(errSecItemNotFound) {
            // The item is gone, deleted in Keychain Access or never on this Mac. The slot can
            // never open anything again, and keeping it would show the Mac as remembering
            // while it asks every time. Other errors, a locked Keychain or a refusal, leave
            // it alone: those pass.
            try? remove(kind: .keychain)
            return false
        } catch {
            return false
        }
        guard let kek = try? Crypto.keyfileKEK(keyfile: secret, salt: slot.salt),
              case .unwrapped(let master) = Crypto.unwrapMasterKey(
                  kek: kek, slotId: slot.id, verifier: slot.verifier, wrapped: slot.wrapped)
        else { return false }
        guardLock.withLock { masterKey = master }
        return true
    }

    public func unlock(recoveryKey: Data) throws {
        for slot in try load() where slot.kind == .recovery {
            let kek = try Crypto.keyfileKEK(keyfile: recoveryKey, salt: slot.salt)
            if case .unwrapped(let master) = Crypto.unwrapMasterKey(
                kek: kek, slotId: slot.id, verifier: slot.verifier, wrapped: slot.wrapped) {
                guardLock.withLock { masterKey = master }
                return
            }
        }
        throw Failure.wrongPassphrase
    }

    public func lock() { wipe() }

    private func wipe() {
        guardLock.withLock {
            if var key = masterKey {
                key.resetBytes(in: 0..<key.count)
                masterKey = nil
            }
        }
    }

    // MARK: - Using

    /// A key for one purpose. A leaked cookie key must not open the vault.
    public func key(for purpose: Crypto.Purpose) throws -> Data {
        guard let master = guardLock.withLock({ masterKey }) else { throw Failure.locked }
        return try Crypto.purposeKey(master: master, purpose: purpose)
    }

    // MARK: - Changing the ways in

    public func changePassphrase(from old: String, to new: String) throws {
        guard new.count >= 8 else { throw Failure.tooShort }
        // The old one is required even when unlocked, or anyone at an unlocked window could
        // lock the owner out.
        try unlock(passphrase: old)
        guard let master = guardLock.withLock({ masterKey }) else { throw Failure.locked }
        var slots = try load().filter { $0.kind != .passphrase }
        slots.append(try passphraseSlot(for: new, master: master))
        try save(slots)
    }

    public func setRemembered(_ remember: Bool) throws {
        if !remember {
            try remove(kind: .keychain)
            deleteKeychainItem()
            return
        }
        guard let master = guardLock.withLock({ masterKey }) else { throw Failure.locked }
        let secret = try Crypto.randomBytes(32)
        let salt = try Crypto.randomBytes(16)
        let kek = try Crypto.keyfileKEK(keyfile: secret, salt: salt)
        let wrapped = try Crypto.wrapMasterKey(master, kek: kek, slotId: Self.keychainSlot)
        try writeKeychain(secret)
        var slots = try load().filter { $0.kind != .keychain }
        slots.append(Slot(id: Self.keychainSlot, kind: .keychain, salt: salt,
                          verifier: wrapped.verifier, wrapped: wrapped.wrapped, kdf: nil))
        try save(slots)
    }

    /// Writes a recovery key somewhere of the user's choosing and adds its slot.
    public func exportRecoveryKey(to url: URL) throws {
        guard let master = guardLock.withLock({ masterKey }) else { throw Failure.locked }
        let secret = try Crypto.randomBytes(32)
        let salt = try Crypto.randomBytes(16)
        let kek = try Crypto.keyfileKEK(keyfile: secret, salt: salt)
        let wrapped = try Crypto.wrapMasterKey(master, kek: kek, slotId: Self.recoverySlot)
        try secret.write(to: url, options: [.atomic, .completeFileProtection])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        var slots = try load().filter { $0.kind != .recovery }
        slots.append(Slot(id: Self.recoverySlot, kind: .recovery, salt: salt,
                          verifier: wrapped.verifier, wrapped: wrapped.wrapped, kdf: nil))
        try save(slots)
    }

    private func remove(kind: Kind) throws {
        let remaining = try load().filter { $0.kind != kind }
        // Refused in code, not only in the interface: nothing would be left that can
        // unwrap the master key, and every encrypted file would be lost.
        guard !remaining.isEmpty else { throw Failure.lastSlot }
        try save(remaining)
    }

    private func passphraseSlot(for passphrase: String, master: Data) throws -> Slot {
        let salt = try Crypto.randomBytes(16)
        let kek = try Crypto.argon2id(secret: Data(passphrase.utf8), salt: salt, params: params)
        let wrapped = try Crypto.wrapMasterKey(master, kek: kek, slotId: Self.passphraseSlot)
        return Slot(id: Self.passphraseSlot, kind: .passphrase, salt: salt,
                    verifier: wrapped.verifier, wrapped: wrapped.wrapped, kdf: params)
    }

    // MARK: - On disk

    private func load() throws -> [Slot] {
        let data = try Data(contentsOf: fileURL)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["formatVersion"] as? Int == 1,
              let list = root["slots"] as? [[String: Any]]
        else { throw Failure.damaged("unrecognised key box") }

        return list.compactMap { entry in
            guard let id = entry["id"] as? String,
                  let kind = (entry["kind"] as? String).flatMap(Kind.init(rawValue:)),
                  let salt = (entry["salt"] as? String).flatMap({ Data(base64Encoded: $0) }),
                  let verifier = (entry["verifier"] as? String).flatMap({ Data(base64Encoded: $0) }),
                  let wrapped = (entry["wrapped"] as? String).flatMap({ Data(base64Encoded: $0) })
            else { return nil }
            var kdf: Crypto.Argon2idParams?
            if let k = entry["kdf"] as? [String: Any] {
                kdf = .init(memoryKiB: UInt32(k["memoryKiB"] as? Int ?? 0),
                            iterations: UInt32(k["iterations"] as? Int ?? 0),
                            parallelism: UInt32(k["parallelism"] as? Int ?? 0),
                            version: UInt32(k["version"] as? Int ?? 0))
            }
            return Slot(id: id, kind: kind, salt: salt, verifier: verifier, wrapped: wrapped,
                        kdf: kdf)
        }
    }

    private func save(_ slots: [Slot]) throws {
        let list: [[String: Any]] = slots.map { slot in
            var entry: [String: Any] = [
                "id": slot.id, "kind": slot.kind.rawValue,
                "salt": slot.salt.base64EncodedString(),
                "verifier": slot.verifier.base64EncodedString(),
                "wrapped": slot.wrapped.base64EncodedString(),
            ]
            if let k = slot.kdf {
                entry["kdf"] = ["id": "argon2id", "version": Int(k.version),
                                "memoryKiB": Int(k.memoryKiB), "iterations": Int(k.iterations),
                                "parallelism": Int(k.parallelism)]
            }
            return entry
        }
        let data = try JSONSerialization.data(
            withJSONObject: ["formatVersion": 1, "slots": list],
            options: [.prettyPrinted, .sortedKeys])
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: fileURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
    }

    // MARK: - The Keychain

    private var keychainQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: keychainService,
         kSecAttrAccount as String: "remember"]
    }

    private func writeKeychain(_ secret: Data) throws {
        deleteKeychainItem()
        var query = keychainQuery
        query[kSecValueData as String] = secret
        // Only while the Mac is unlocked, and never synced to other devices: this is a key
        // for this machine, not one to carry through iCloud.
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw Failure.keychain(status) }
    }

    private func readKeychain() throws -> Data {
        var query = keychainQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else {
            throw Failure.keychain(status)
        }
        return data
    }

    public func deleteKeychainItem() {
        SecItemDelete(keychainQuery as CFDictionary)
    }
}
