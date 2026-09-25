import ArsivinyoCryptoC
import Foundation

/// The plaintext header of a `.avsbck`: enough to preview a backup and derive its key, and
/// nothing about what is in it beyond counts. Field names are the phone's `BackupFormat`.
public struct BackupHeader: Sendable {
    public struct Section: Sendable, Hashable {
        public let id: String
        public let keySlot: String
        /// Advisory, like `plaintextBytes`: plaintext and unauthenticated, so good for a
        /// preview and never trusted for anything else.
        public let itemCount: Int
        public let plaintextBytes: Int64
    }

    public struct Slot: Sendable {
        public let id: String
        public let salt: Data
        public let verifier: Data
        /// "passphrase" or "password", so the prompt can say which is wanted.
        public let secretKind: String
    }

    public var formatVersion = 1
    public var createdAt: Date
    public var producerApp = "arsivinyo"
    public var producerVersion: String
    public var producerVersionCode = 0
    public var kdf: Crypto.Argon2idParams
    public var slots: [Slot]
    public var sections: [Section]

    public func section(_ id: String) -> Section? { sections.first { $0.id == id } }

    public enum Failure: Error, CustomStringConvertible {
        case unreadable(String)
        public var description: String {
            switch self {
            case .unreadable(let why): return String(localized: "This backup cannot be read: \(why)")
            }
        }
    }

    /// Read back, with the phone's limits: a header asking for gigabytes of memory or
    /// thousands of passes is refused rather than run.
    public init(json: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw Failure.unreadable("the header is not JSON")
        }
        let version = root["formatVersion"] as? Int ?? -1
        guard version >= 1 else { throw Failure.unreadable("it has no format version") }
        guard version <= 1 else { throw Failure.unreadable("it was written by a newer version of the app") }
        formatVersion = version
        createdAt = Date(timeIntervalSince1970: (root["createdAt"] as? Double ?? 0) / 1000)
        let producer = root["producer"] as? [String: Any] ?? [:]
        producerApp = producer["app"] as? String ?? ""
        producerVersion = producer["version"] as? String ?? ""
        producerVersionCode = producer["versionCode"] as? Int ?? 0

        guard let kdf = root["kdf"] as? [String: Any] else { throw Failure.unreadable("it has no key derivation") }
        guard (kdf["id"] as? String ?? "argon2id") == "argon2id" else {
            throw Failure.unreadable("it uses a key derivation this app does not know")
        }
        let memory = kdf["memoryKiB"] as? Int ?? 0
        let iterations = kdf["iterations"] as? Int ?? 0
        let parallelism = kdf["parallelism"] as? Int ?? 0
        guard (8 * 1024...1024 * 1024).contains(memory), (1...16).contains(iterations),
              (1...16).contains(parallelism) else {
            throw Failure.unreadable("its key derivation settings are out of range")
        }
        self.kdf = Crypto.Argon2idParams(memoryKiB: UInt32(memory), iterations: UInt32(iterations),
                                         parallelism: UInt32(parallelism),
                                         version: UInt32(kdf["version"] as? Int ?? 0x13))

        slots = try (root["keySlots"] as? [[String: Any]] ?? []).map { slot in
            guard let id = (slot["id"] as? String)?.nonEmpty,
                  let salt = Data(base64Encoded: slot["salt"] as? String ?? ""), salt.count >= 16,
                  let verifier = Data(base64Encoded: slot["verifier"] as? String ?? "")
            else { throw Failure.unreadable("a key slot is damaged") }
            return Slot(id: id, salt: salt, verifier: verifier,
                        secretKind: (slot["secretKind"] as? String)?.nonEmpty ?? "passphrase")
        }
        guard !slots.isEmpty else { throw Failure.unreadable("it has no key slot") }
        let slotIds = Set(slots.map(\.id))
        sections = try (root["sections"] as? [[String: Any]] ?? []).compactMap { section in
            guard let id = (section["id"] as? String)?.nonEmpty else { return nil }
            let slot = (section["keySlot"] as? String)?.nonEmpty ?? "default"
            guard slotIds.contains(slot) else { throw Failure.unreadable("a section names a key slot that is not there") }
            return Section(id: id, keySlot: slot, itemCount: section["itemCount"] as? Int ?? 0,
                           plaintextBytes: (section["plaintextBytes"] as? NSNumber)?.int64Value ?? 0)
        }
        guard Set(sections.map(\.id)).count == sections.count else {
            throw Failure.unreadable("it declares a section twice")
        }
    }

    init(createdAt: Date, producerVersion: String, kdf: Crypto.Argon2idParams, slots: [Slot], sections: [Section]) {
        self.createdAt = createdAt
        self.producerVersion = producerVersion
        self.kdf = kdf
        self.slots = slots
        self.sections = sections
    }

    func json() throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "formatVersion": formatVersion,
            "createdAt": Int64(createdAt.timeIntervalSince1970 * 1000),
            "producer": ["app": producerApp, "version": producerVersion, "versionCode": producerVersionCode],
            "kdf": ["id": "argon2id", "version": Int(kdf.version), "memoryKiB": Int(kdf.memoryKiB),
                    "iterations": Int(kdf.iterations), "parallelism": Int(kdf.parallelism)],
            "keySlots": slots.map { ["id": $0.id, "salt": $0.salt.base64EncodedString(),
                                     "verifier": $0.verifier.base64EncodedString(), "secretKind": $0.secretKind] },
            "sections": sections.map { ["id": $0.id, "keySlot": $0.keySlot, "itemCount": $0.itemCount,
                                        "plaintextBytes": $0.plaintextBytes] },
        ], options: [.sortedKeys])
    }
}

/// One entry's header inside a section. The phone's `EntryHeader`.
public struct BackupEntry: @unchecked Sendable {
    public var name: String
    /// Advisory. The trailer's size is the real one.
    public var size: Int64
    public var kind: String
    public var meta: [String: Any]

    public init(name: String, size: Int64, kind: String, meta: [String: Any] = [:]) {
        self.name = name
        self.size = size
        self.kind = kind
        self.meta = meta
    }

    init(json: String) {
        let root = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
        name = root["name"] as? String ?? ""
        size = (root["size"] as? NSNumber)?.int64Value ?? -1
        kind = root["kind"] as? String ?? ""
        meta = root["meta"] as? [String: Any] ?? [:]
    }

    func json() throws -> String {
        let data = try JSONSerialization.data(withJSONObject: ["name": name, "size": size, "kind": kind, "meta": meta],
                                              options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }

    public func string(_ key: String) -> String? { (meta[key] as? String)?.nonEmpty }
}

/// The C writer, from Swift.
final class BackupFileWriter {
    private var handle: OpaquePointer?

    init(url: URL, header: BackupHeader) throws {
        let json = String(decoding: try header.json(), as: UTF8.self)
        handle = av_backup_writer_open(url.path, json)
        guard handle != nil else { throw Self.failure() }
    }

    deinit { if let handle { av_backup_writer_abort(handle) } }

    func beginSection(_ id: String, key: Data) throws {
        let ok = key.withUnsafeBytes { av_backup_writer_begin_section(handle, id, $0.bindMemory(to: UInt8.self).baseAddress, key.count) }
        guard ok == 1 else { throw Self.failure() }
    }

    func beginEntry(_ entry: BackupEntry) throws {
        guard av_backup_writer_begin_entry(handle, try entry.json()) == 1 else { throw Self.failure() }
    }

    func write(_ data: Data) throws {
        let ok = data.withUnsafeBytes { av_backup_writer_write(handle, $0.bindMemory(to: UInt8.self).baseAddress, data.count) }
        guard ok == 1 else { throw Self.failure() }
    }

    func endEntry(complete: Bool) throws {
        guard av_backup_writer_end_entry(handle, complete ? 1 : 0) == 1 else { throw Self.failure() }
    }

    func endSection() throws {
        guard av_backup_writer_end_section(handle) == 1 else { throw Self.failure() }
    }

    /// Flushes and closes. After this the file is a finished backup.
    func finish() throws {
        let closing = handle
        handle = nil
        guard av_backup_writer_close(closing) == 1 else { throw Self.failure() }
    }

    static func failure() -> Crypto.Failure {
        Crypto.Failure()
    }
}

/// The C reader, from Swift.
final class BackupFileReader {
    private let handle: OpaquePointer
    let header: BackupHeader

    init(url: URL) throws {
        var json: UnsafeMutablePointer<CChar>?
        guard let handle = av_backup_reader_open(url.path, &json), let json else {
            throw BackupHeader.Failure.unreadable(String(cString: av_last_error()))
        }
        defer { free(json) }
        self.handle = handle
        do {
            header = try BackupHeader(json: Data(String(cString: json).utf8))
        } catch {
            av_backup_reader_close(handle)
            throw error
        }
    }

    deinit { av_backup_reader_close(handle) }

    /// Reads a payload as it streams by.
    struct Payload {
        fileprivate let pointer: UnsafeMutableRawPointer

        /// Up to `count` bytes; empty once the payload is finished.
        func read(_ count: Int = 1 << 20) throws -> Data {
            var buffer = Data(count: count)
            var got = 0
            let ok = buffer.withUnsafeMutableBytes {
                av_backup_payload_read(pointer, $0.bindMemory(to: UInt8.self).baseAddress, count, &got)
            }
            guard ok == 1 else { throw BackupFileWriter.failure() }
            buffer.count = got
            return buffer
        }
    }

    private final class Callbacks {
        let onEntry: (BackupEntry, Payload) -> Bool
        let onVerdict: (Bool, String) -> Void
        init(onEntry: @escaping (BackupEntry, Payload) -> Bool, onVerdict: @escaping (Bool, String) -> Void) {
            self.onEntry = onEntry
            self.onVerdict = onVerdict
        }
    }

    /// Walks the next section. `onEntry` consumes the payload; `onVerdict` then says
    /// whether it was whole and matched its recorded hash.
    func readSection(_ id: String, key: Data, onEntry: @escaping (BackupEntry, Payload) -> Bool,
                     onVerdict: @escaping (Bool, String) -> Void) throws {
        let callbacks = Callbacks(onEntry: onEntry, onVerdict: onVerdict)
        let context = Unmanaged.passUnretained(callbacks).toOpaque()
        let entry: av_backup_entry_fn = { context, header, payload in
            let callbacks = Unmanaged<Callbacks>.fromOpaque(context!).takeUnretainedValue()
            return callbacks.onEntry(BackupEntry(json: String(cString: header!)), Payload(pointer: payload!)) ? 1 : 0
        }
        let verdict: av_backup_verdict_fn = { context, verified, why in
            let callbacks = Unmanaged<Callbacks>.fromOpaque(context!).takeUnretainedValue()
            callbacks.onVerdict(verified == 1, why.map { String(cString: $0) } ?? "")
        }
        let ok = withExtendedLifetime(callbacks) {
            key.withUnsafeBytes {
                av_backup_reader_read_section(handle, id, $0.bindMemory(to: UInt8.self).baseAddress, key.count,
                                              entry, verdict, context)
            }
        }
        guard ok == 1 else { throw BackupFileWriter.failure() }
    }

    func skipSection() throws {
        guard av_backup_reader_skip_section(handle) == 1 else { throw BackupFileWriter.failure() }
    }
}
