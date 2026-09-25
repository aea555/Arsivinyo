import Foundation
import UniformTypeIdentifiers

/// Private files, encrypted at rest, listed from an encrypted index.
///
/// The layout and both formats match the phone and the Qt app, and are pinned by the same
/// vectors:
///
///   vault/index.enc          padded, sealed under the vault-index key, AAD "vault/index/v1"
///   vault/objects/<id>.enc   the streaming cipher, AAD = the id
///
/// The listing is encrypted as well as the files. The phone's used to be plain JSON, so every
/// title, tag and size was readable without touching the crypto; that is fixed there and not
/// repeated here. Ids are random rather than derived from names, or the names would leak back
/// out through the object files.
public final class Vault: @unchecked Sendable {

    public struct Item: Identifiable, Hashable, Sendable {
        public let id: String
        public var title: String
        public var fileExtension: String
        public var contentType: String
        public var sizeBytes: Int64
        public var addedAt: Date
        public var tags: [String]
        public var folderId: String?

        public var isVideo: Bool { UTType(contentType)?.conforms(to: .movie) ?? false }
        public var isAudio: Bool { UTType(contentType)?.conforms(to: .audio) ?? false }
    }

    public enum Failure: Error, CustomStringConvertible {
        case locked
        /// The index exists and will not open. Every write is refused in this state.
        case unreadableIndex
        case notFound
        case io(String)

        public var description: String {
            switch self {
            case .locked: return "Unlock first."
            case .unreadableIndex:
                return "The vault listing could not be read. Your files are still there and "
                    + "still encrypted, and nothing will be written until it can be read."
            case .notFound: return "That item is not in the vault."
            case .io(let why): return why
            }
        }
    }

    private static let indexAssociatedData = "vault/index/v1"
    private static let formatVersion = 1

    private let root: URL
    private let keybox: Keybox
    private let guardLock = NSLock()
    private var cache: [Item]?
    private var unreadable = false

    public init(root: URL, keybox: Keybox) {
        self.root = root
        self.keybox = keybox
    }

    private var indexURL: URL { root.appendingPathComponent("index.enc") }
    private var objectsURL: URL { root.appendingPathComponent("objects") }
    public func objectURL(for id: String) -> URL {
        objectsURL.appendingPathComponent("\(id).enc")
    }

    /// True when the listing exists but will not open. Never collapses into "empty".
    public var isUnreadable: Bool { guardLock.withLock { unreadable } }

    /// Forgets the decrypted listing, for when the key box locks.
    public func forget() { guardLock.withLock { cache = nil; unreadable = false } }

    // MARK: - Reading

    public func items() throws -> [Item] {
        if let cached = guardLock.withLock({ cache }) { return cached }
        let loaded = try load()
        guardLock.withLock { cache = loaded }
        return loaded
    }

    private func load() throws -> [Item] {
        guard FileManager.default.fileExists(atPath: indexURL.path) else { return [] }
        let key = try keybox.key(for: .vaultIndex)
        do {
            let sealed = try Data(contentsOf: indexURL)
            let padded = try Crypto.open(sealed, key: key,
                                         associatedData: Self.indexAssociatedData)
            let json = try Crypto.unpad(padded)
            guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
                  root["formatVersion"] as? Int == Self.formatVersion,
                  let list = root["items"] as? [[String: Any]]
            else { throw Failure.unreadableIndex }
            return list.compactMap(Self.item(from:))
        } catch Keybox.Failure.locked {
            throw Failure.locked
        } catch {
            // The objects are still on disk. Answering "empty" here would let the next write
            // replace the real listing with an empty one and orphan every file in it — the
            // exact bug the phone had. Refuse instead, loudly.
            guardLock.withLock { unreadable = true }
            throw Failure.unreadableIndex
        }
    }

    private func store(_ items: [Item]) throws {
        // Belt and braces, and unreachable through the public surface today: `unreadable`
        // is only set by a failed load, a failed load never fills the cache, so `items()`
        // throws before anything gets here. The protection that matters is in `load()`, and
        // that is the one the checks mutate. This stays so a future path that skips
        // `items()` cannot write an empty listing over a damaged one.
        guard !isUnreadable else { throw Failure.unreadableIndex }
        let key = try keybox.key(for: .vaultIndex)
        let json = try JSONSerialization.data(withJSONObject: [
            "formatVersion": Self.formatVersion,
            "items": items.map(Self.dictionary(from:)),
        ], options: [.sortedKeys])
        // Padded before sealing, so the file's size does not count the items.
        let sealed = try Crypto.seal(Crypto.pad(json), key: key,
                                     associatedData: Self.indexAssociatedData)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try sealed.write(to: indexURL, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600],
                                               ofItemAtPath: indexURL.path)
        guardLock.withLock { cache = items }
    }

    // MARK: - Changing

    /// Encrypts a file into the vault. Streams it, so a large video is never resident.
    @discardableResult
    public func add(_ source: URL, title: String? = nil, removeOriginal: Bool = false) throws
        -> Item
    {
        var current = try items()
        let key = try keybox.key(for: .vault)

        let id = try Crypto.randomBytes(16).map { String(format: "%02x", $0) }.joined()
        try FileManager.default.createDirectory(at: objectsURL, withIntermediateDirectories: true)
        let target = objectURL(for: id)
        let partial = target.appendingPathExtension("partial")

        do {
            // The id is the associated data, so one object file swapped for another fails.
            try Crypto.encryptFile(source, to: partial, key: key, associatedData: id)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600],
                                                   ofItemAtPath: partial.path)
            try FileManager.default.moveItem(at: partial, to: target)
        } catch {
            try? FileManager.default.removeItem(at: partial)
            throw Failure.io("Could not write into the vault: \(error)")
        }

        let attributes = try? FileManager.default.attributesOfItem(atPath: source.path)
        let type = UTType(filenameExtension: source.pathExtension) ?? .data
        let item = Item(
            id: id,
            title: title ?? source.deletingPathExtension().lastPathComponent,
            fileExtension: source.pathExtension,
            contentType: type.identifier,
            sizeBytes: (attributes?[.size] as? NSNumber)?.int64Value ?? 0,
            addedAt: Date(), tags: [], folderId: nil)

        // The object is complete before the listing names it, so a crash leaves an orphan
        // rather than an entry pointing at nothing.
        current.insert(item, at: 0)
        do {
            try store(current)
        } catch {
            try? FileManager.default.removeItem(at: target)
            throw error
        }
        if removeOriginal { try? FileManager.default.removeItem(at: source) }
        return item
    }

    public func remove(_ id: String) throws {
        var current = try items()
        guard current.contains(where: { $0.id == id }) else { throw Failure.notFound }
        current.removeAll { $0.id == id }
        // The listing loses it first. The other order leaves an entry pointing at a file
        // that is already gone, which reads as corruption rather than a finished delete.
        try store(current)
        try? FileManager.default.removeItem(at: objectURL(for: id))
    }

    public func rename(_ id: String, to title: String) throws {
        var current = try items()
        guard let index = current.firstIndex(where: { $0.id == id }) else { throw Failure.notFound }
        current[index].title = title
        try store(current)
    }

    /// Decrypts an item back out to a chosen location.
    public func export(_ id: String, to destination: URL) throws {
        let key = try keybox.key(for: .vault)
        try Crypto.decryptFile(objectURL(for: id), to: destination, key: key, associatedData: id)
    }

    /// A reader for playback. Nothing is decrypted to disk.
    public func reader(for id: String) throws -> EncryptedReader {
        let key = try keybox.key(for: .vault)
        return try EncryptedReader(url: objectURL(for: id), key: key, associatedData: id)
    }

    // MARK: - Encoding

    private static func dictionary(from item: Item) -> [String: Any] {
        var out: [String: Any] = [
            "id": item.id, "title": item.title, "extension": item.fileExtension,
            "contentType": item.contentType, "sizeBytes": item.sizeBytes,
            "addedAt": item.addedAt.timeIntervalSince1970, "tags": item.tags,
        ]
        if let folder = item.folderId { out["folderId"] = folder }
        return out
    }

    private static func item(from entry: [String: Any]) -> Item? {
        guard let id = entry["id"] as? String, !id.isEmpty else { return nil }
        return Item(
            id: id,
            title: entry["title"] as? String ?? "",
            fileExtension: entry["extension"] as? String ?? "",
            contentType: entry["contentType"] as? String ?? UTType.data.identifier,
            sizeBytes: (entry["sizeBytes"] as? NSNumber)?.int64Value ?? 0,
            addedAt: Date(timeIntervalSince1970: entry["addedAt"] as? Double ?? 0),
            tags: entry["tags"] as? [String] ?? [],
            folderId: entry["folderId"] as? String)
    }
}
