import CryptoKit
import Foundation
import Security
import UniformTypeIdentifiers

/// The meme collection: what is in it, what each one signifies, who is in it, and where it
/// came from. `shared/memes/CONTRACT.md` is the specification; field names and facet ids here
/// are its wire values.
///
/// Two indexes, both encrypted. Memes that are not private, with the labels they use, are
/// sealed under a device key kept in the Keychain, so searching them never asks for anything
/// but a copied disk reveals nothing. Private memes, and any label only they use, are sealed
/// under the vault's key and exist only while it is unlocked.
public final class MemeLibrary: @unchecked Sendable {

    // MARK: - The model

    public enum Facet: String, CaseIterable, Codable, Sendable, Identifiable {
        case reaction, vibe, emotion, action, context
        public var id: String { rawValue }
    }

    public struct Tag: Codable, Hashable, Sendable, Identifiable {
        public var id: String
        public var name: String
        public var facets: [Facet]
    }

    public struct Person: Codable, Hashable, Sendable, Identifiable {
        public var id: String
        public var name: String
        /// Up to eight face signatures, base64 half floats: how this person is recognised.
        public var signatures: [String]? = nil
    }

    /// One person's face in one meme (`CONTRACT.md`, "Faces").
    public struct Face: Codable, Hashable, Sendable, Identifiable {
        public enum State: String, Codable, Sendable {
            /// Sure enough to label on its own.
            case auto
            /// Named or confirmed by the user.
            case confirmed
            /// Close to `person`, waiting for a yes or no.
            case asked
            /// Nobody known.
            case unnamed
        }

        public var id: String
        /// 128 half floats, base64.
        public var signature: String
        /// Where to show it from: the video time, and the box in source pixels.
        public var frameMs: Int
        public var box: [Double]
        public var person: String?
        public var state: State
        /// People this face has been said not to be.
        public var rejected: [String]? = nil
        /// Whether this face put its person's label on the meme, and so may take it off.
        public var added: Bool? = nil
        /// Its unnamed group; nil once it has a person. Decided once, when it became unnamed.
        public var group: String? = nil
    }

    public struct Source: Codable, Hashable, Sendable {
        public var platform: String?
        public var account: String?
        public var accountName: String?
        public var caption: String?
        public var url: String?
        public var postedAt: Double?
        public var savedAt: Double

        public init(platform: String? = nil, account: String? = nil, accountName: String? = nil, caption: String? = nil,
                    url: String? = nil, postedAt: Double? = nil, savedAt: Double = Date().timeIntervalSince1970 * 1000) {
            self.platform = platform
            self.account = account
            self.accountName = accountName
            self.caption = caption
            self.url = url
            self.postedAt = postedAt
            self.savedAt = savedAt
        }
    }

    public struct Item: Codable, Hashable, Sendable, Identifiable {
        public var id: String
        public var kind: String
        public var isPrivate: Bool
        /// Where the file is, when it is not private.
        public var path: String?
        /// Its vault item, when it is.
        public var vaultId: String?
        public var sha256: String
        public var source: Source?
        public var tags: [String]
        public var people: [String]
        public var addedAt: Double
        /// 0 while the meme waits in the untagged inbox.
        public var taggedAt: Double
        public var faces: [Face]? = nil
        /// The faces pipeline that scanned it; nil or older: to be scanned.
        public var facesVersion: Int? = nil

        public init(id: String, kind: String, isPrivate: Bool, path: String?, vaultId: String?, sha256: String,
                    source: Source?, tags: [String], people: [String], addedAt: Double, taggedAt: Double) {
            self.id = id
            self.kind = kind
            self.isPrivate = isPrivate
            self.path = path
            self.vaultId = vaultId
            self.sha256 = sha256
            self.source = source
            self.tags = tags
            self.people = people
            self.addedAt = addedAt
            self.taggedAt = taggedAt
        }

        public var isVideo: Bool { kind == "video" }
        public var isUntagged: Bool { taggedAt == 0 }
        public var fileURL: URL? { path.map { URL(fileURLWithPath: $0) } }

        enum CodingKeys: String, CodingKey {
            case id, kind, isPrivate = "private", path, vaultId, sha256, source, tags, people, addedAt, taggedAt
            case faces, facesVersion
        }
    }

    struct Index: Codable {
        var version = 1
        var tags: [Tag] = []
        var people: [Person] = []
        var items: [Item] = []
    }

    public enum Failure: Error, CustomStringConvertible {
        case notMedia
        case notFound
        case locked
        case io(String)

        public var description: String {
            switch self {
            case .notMedia: return String(localized: "That is not a video or an image.")
            case .notFound: return String(localized: "That meme is not in the collection.")
            case .locked: return String(localized: "Unlock the vault first: that meme is private.")
            case .io(let why): return why
            }
        }
    }

    // MARK: - Storage

    private let supportFolder: URL
    private let deviceKey: () throws -> Data
    private let vault: Vault
    private let keybox: Keybox
    private let lock = NSLock()
    private var publicIndex: Index?
    private var privateIndex: Index?

    /// - Parameters:
    ///   - support: where the two index files live.
    ///   - deviceKey: the key for memes that are not private. The app passes the Keychain's;
    ///     the checks pass one of their own.
    public init(support: URL, vault: Vault, keybox: Keybox, deviceKey: @escaping () throws -> Data) {
        supportFolder = support
        self.vault = vault
        self.keybox = keybox
        self.deviceKey = deviceKey
    }

    private var publicURL: URL { supportFolder.appendingPathComponent("index.enc") }
    private var privateURL: URL { supportFolder.appendingPathComponent("private.enc") }
    private static let publicAssociatedData = "memes/index/v1"
    private static let privateAssociatedData = "memes/private-index/v1"

    private func readPublic() throws -> Index {
        if let publicIndex { return publicIndex }
        let index = try Self.open(publicURL, key: deviceKey(), associatedData: Self.publicAssociatedData)
        publicIndex = index
        return index
    }

    /// Nil while the vault is locked: private memes do not exist until then.
    private func readPrivate() throws -> Index? {
        guard keybox.isUnlocked else {
            privateIndex = nil
            return nil
        }
        if let privateIndex { return privateIndex }
        let index = try Self.open(privateURL, key: keybox.key(for: .vaultIndex), associatedData: Self.privateAssociatedData)
        privateIndex = index
        return index
    }

    private static func open(_ url: URL, key: Data, associatedData: String) throws -> Index {
        guard FileManager.default.fileExists(atPath: url.path) else { return Index() }
        let json = try Crypto.unpad(Crypto.open(Data(contentsOf: url), key: key, associatedData: associatedData))
        return try JSONDecoder().decode(Index.self, from: json)
    }

    private static func seal(_ index: Index, to url: URL, key: Data, associatedData: String) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        // Padded before sealing, so the file's size does not count the memes.
        let sealed = try Crypto.seal(Crypto.pad(encoder.encode(index)), key: key, associatedData: associatedData)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.writePrivately(sealed, to: url)
    }

    /// Everything visible now, merged: all public memes, and the private ones when unlocked.
    private func merged() throws -> Index {
        let open = try readPublic()
        guard let hidden = try readPrivate() else { return open }
        var all = open
        all.items += hidden.items
        let tagIds = Set(open.tags.map(\.id))
        all.tags += hidden.tags.filter { !tagIds.contains($0.id) }
        let personIds = Set(open.people.map(\.id))
        all.people += hidden.people.filter { !personIds.contains($0.id) }
        return all
    }

    /// Splits the merged view back into its two files. A label used only by private memes
    /// goes with them, so the public index never names it.
    private func save(_ all: Index) throws {
        let hiddenItems = all.items.filter(\.isPrivate)
        let openItems = all.items.filter { !$0.isPrivate }
        let openTags = Set(openItems.flatMap(\.tags))
        let hiddenTags = Set(hiddenItems.flatMap(\.tags)).subtracting(openTags)
        let openPeople = Set(openItems.flatMap(\.people))
        let hiddenPeople = Set(hiddenItems.flatMap(\.people)).subtracting(openPeople)

        var open = Index()
        open.items = openItems
        open.tags = all.tags.filter { !hiddenTags.contains($0.id) }
        open.people = all.people.filter { !hiddenPeople.contains($0.id) }
        try Self.seal(open, to: publicURL, key: deviceKey(), associatedData: Self.publicAssociatedData)
        publicIndex = open

        if keybox.isUnlocked {
            var hidden = Index()
            hidden.items = hiddenItems
            hidden.tags = all.tags.filter { hiddenTags.contains($0.id) }
            hidden.people = all.people.filter { hiddenPeople.contains($0.id) }
            try Self.seal(hidden, to: privateURL, key: keybox.key(for: .vaultIndex), associatedData: Self.privateAssociatedData)
            privateIndex = hidden
        } else if hiddenItems.isEmpty == false {
            throw Failure.locked
        }
    }

    func mutate<T>(_ change: (inout Index) throws -> T) throws -> T {
        try lock.withLock {
            let lockedBefore = !keybox.isUnlocked
            var all = try merged()
            let result = try change(&all)
            // Whatever changed, every unnamed face ends up in a group, and named ones leave theirs.
            Self.assignGroups(&all.items)
            // Locked, the private memes were never read, so they must not be written over.
            if lockedBefore, all.items.contains(where: \.isPrivate) { throw Failure.locked }
            try save(all)
            return result
        }
    }

    /// Drops the private half from memory, as the vault does when it locks.
    public func forgetPrivate() { lock.withLock { privateIndex = nil } }

    // MARK: - Reading

    public struct Snapshot: Sendable {
        public var items: [Item]
        public var tags: [Tag]
        public var people: [Person]

        public init(items: [Item], tags: [Tag], people: [Person]) {
            self.items = items
            self.tags = tags
            self.people = people
        }
    }

    /// Everything visible, newest first. Public memes whose file has gone are dropped.
    public func load() throws -> Snapshot {
        try lock.withLock {
            var all = try merged()
            let before = all.items.count
            all.items.removeAll { !$0.isPrivate && !FileManager.default.fileExists(atPath: $0.path ?? "") }
            if all.items.count != before { try save(all) }
            return Snapshot(items: all.items.sorted { $0.addedAt > $1.addedAt }, tags: all.tags, people: all.people)
        }
    }

    // MARK: - Adding

    public static func kind(of url: URL) -> String? {
        guard let type = UTType(filenameExtension: url.pathExtension) else { return nil }
        if type.conforms(to: .movie) || type.conforms(to: .video) { return "video" }
        if type.conforms(to: .image) { return "image" }
        return nil
    }

    /// Takes a file that is already where it should stay, a download, into the collection.
    @discardableResult
    public func add(_ file: URL, source: Source?, tagNames: [(String, [Facet])] = [], people personNames: [String] = [],
                    tagged: Bool = false) throws -> Item {
        guard let kind = Self.kind(of: file) else { throw Failure.notMedia }
        let hash = try Self.sha256(of: file)
        return try mutate { all in
            if let existing = all.items.first(where: { $0.sha256 == hash && !$0.isPrivate }) {
                return existing
            }
            let tagIds = tagNames.map { Self.resolveTag($0.0, facets: $0.1, in: &all) }
            let personIds = personNames.map { Self.resolvePerson($0, in: &all) }
            let now = Date().timeIntervalSince1970 * 1000
            let item = Item(id: Self.newId("m"), kind: kind, isPrivate: false, path: file.path, vaultId: nil, sha256: hash,
                            source: source ?? Source(platform: "import"), tags: Array(Set(tagIds)),
                            people: Array(Set(personIds)), addedAt: now, taggedAt: tagged ? now : 0)
            all.items.append(item)
            return item
        }
    }

    /// Copies files in from elsewhere; the originals stay where they are.
    public func importFiles(_ urls: [URL], into folder: URL) -> (added: [Item], failed: [String]) {
        var added: [Item] = []
        var failed: [String] = []
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for url in urls {
            do {
                guard Self.kind(of: url) != nil else { throw Failure.notMedia }
                let target = Self.unique(url.lastPathComponent, in: folder)
                try FileManager.default.copyItem(at: url, to: target)
                added.append(try add(target, source: Source(platform: "import")))
            } catch {
                failed.append(url.lastPathComponent)
            }
        }
        return (added, failed)
    }

    // MARK: - Labels

    /// A tag by name, made if new. Names match Turkish-folded, so "LAUBALİLİK" is "laubalilik".
    @discardableResult
    public func tag(named name: String, facets: [Facet] = []) throws -> Tag {
        try mutate { all in
            let id = Self.resolveTag(name, facets: facets, in: &all)
            return all.tags.first { $0.id == id }!
        }
    }

    @discardableResult
    public func person(named name: String) throws -> Person {
        try mutate { all in
            let id = Self.resolvePerson(name, in: &all)
            return all.people.first { $0.id == id }!
        }
    }

    public func setFacets(_ facets: [Facet], of tagId: String) throws {
        try mutate { all in
            guard let index = all.tags.firstIndex(where: { $0.id == tagId }) else { return }
            all.tags[index].facets = facets
        }
    }

    public func rename(tag tagId: String, to name: String) throws {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        try mutate { all in
            guard let index = all.tags.firstIndex(where: { $0.id == tagId }) else { return }
            all.tags[index].name = trimmed
        }
    }

    public func delete(tag tagId: String) throws {
        try mutate { all in
            all.tags.removeAll { $0.id == tagId }
            for index in all.items.indices { all.items[index].tags.removeAll { $0 == tagId } }
        }
    }

    /// Adds and removes labels on many memes at once. Marks them tagged, which takes them
    /// out of the inbox, even when nothing was added: "Save" on an empty sheet means done.
    public func label(_ itemIds: Set<String>, addTags: Set<String> = [], removeTags: Set<String> = [],
                      addPeople: Set<String> = [], removePeople: Set<String> = [], markTagged: Bool = true) throws {
        try mutate { all in
            let now = Date().timeIntervalSince1970 * 1000
            for index in all.items.indices where itemIds.contains(all.items[index].id) {
                var tags = Set(all.items[index].tags).union(addTags).subtracting(removeTags)
                tags.formIntersection(Set(all.tags.map(\.id)))
                all.items[index].tags = Array(tags)
                var people = Set(all.items[index].people).union(addPeople).subtracting(removePeople)
                people.formIntersection(Set(all.people.map(\.id)))
                all.items[index].people = Array(people)
                if markTagged { all.items[index].taggedAt = now }
                // Taking a person off by hand is a "no" to the faces that put them there.
                if !removePeople.isEmpty { Self.rejectFaces(of: removePeople, in: &all.items[index]) }
            }
            if !removePeople.isEmpty { Self.reevaluate(&all) }
        }
    }

    // MARK: - Private and back

    /// Moves a meme into the vault, with its labels into the private index.
    public func makePrivate(_ itemId: String) throws {
        guard keybox.isUnlocked else { throw Failure.locked }
        let snapshot = try load()
        guard let item = snapshot.items.first(where: { $0.id == itemId }), !item.isPrivate, let file = item.fileURL else {
            throw Failure.notFound
        }
        let title = item.source?.caption.flatMap { $0.isEmpty ? nil : String($0.prefix(80)) }
            ?? file.deletingPathExtension().lastPathComponent
        let stored = try vault.add(file, title: title, removeOriginal: true)
        try mutate { all in
            guard let index = all.items.firstIndex(where: { $0.id == itemId }) else { return }
            all.items[index].isPrivate = true
            all.items[index].path = nil
            all.items[index].vaultId = stored.id
        }
    }

    /// Brings a private meme back out of the vault into `folder`.
    public func makePublic(_ itemId: String, into folder: URL) throws {
        guard keybox.isUnlocked else { throw Failure.locked }
        let snapshot = try load()
        guard let item = snapshot.items.first(where: { $0.id == itemId }), item.isPrivate, let vaultId = item.vaultId,
              let vaultItem = try vault.items().first(where: { $0.id == vaultId }) else { throw Failure.notFound }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = (item.source?.caption.flatMap { Self.safeName($0) } ?? "Meme") + "." + vaultItem.fileExtension
        let target = Self.unique(name, in: folder)
        try vault.export(vaultId, to: target)
        try mutate { all in
            guard let index = all.items.firstIndex(where: { $0.id == itemId }) else { return }
            all.items[index].isPrivate = false
            all.items[index].path = target.path
            all.items[index].vaultId = nil
        }
        try vault.remove(vaultId)
    }

    /// Takes a meme out of the collection. A public one's file goes to the Trash; a private
    /// one's leaves the vault.
    public func remove(_ itemId: String) throws {
        let snapshot = try load()
        guard let item = snapshot.items.first(where: { $0.id == itemId }) else { return }
        if item.isPrivate {
            guard keybox.isUnlocked, let vaultId = item.vaultId else { throw Failure.locked }
            try mutate { all in all.items.removeAll { $0.id == itemId } }
            try? vault.remove(vaultId)
        } else {
            try mutate { all in all.items.removeAll { $0.id == itemId } }
            if let file = item.fileURL { try? FileManager.default.trashItem(at: file, resultingItemURL: nil) }
        }
    }

    // MARK: - Search

    public struct Filter: Hashable, Sendable {
        public var facets: Set<Facet> = []
        public var people: Set<String> = []
        public var platform: String?
        public var onlyPrivate = false
        public var onlyUntagged = false
        public init() {}
    }

    /// Every word required, each matching by prefix a tag, a person, a word of the caption,
    /// or the account. Turkish-folded on both sides.
    public static func search(_ snapshot: Snapshot, query: String, filter: Filter = Filter()) -> [Item] {
        let words = tokens(query)
        let tagsById = Dictionary(uniqueKeysWithValues: snapshot.tags.map { ($0.id, $0) })
        let peopleById = Dictionary(uniqueKeysWithValues: snapshot.people.map { ($0.id, $0) })
        return snapshot.items.filter { item in
            if filter.onlyPrivate && !item.isPrivate { return false }
            if filter.onlyUntagged && !item.isUntagged { return false }
            if let platform = filter.platform, item.source?.platform != platform { return false }
            if !filter.people.isSubset(of: Set(item.people)) { return false }
            let tags = item.tags.compactMap { tagsById[$0] }
            if !filter.facets.isEmpty {
                let present = Set(tags.flatMap(\.facets))
                if !filter.facets.isSubset(of: present) { return false }
            }
            guard !words.isEmpty else { return true }
            var haystack: [String] = []
            haystack += tags.flatMap { tokens($0.name) }
            haystack += item.people.compactMap { peopleById[$0] }.flatMap { tokens($0.name) }
            haystack += tokens(item.source?.caption ?? "")
            haystack += tokens(item.source?.account ?? "") + tokens(item.source?.accountName ?? "")
            return words.allSatisfy { word in haystack.contains { $0.hasPrefix(word) } }
        }
    }

    /// Tags worth offering for a meme: caption words that are tags already, tags used on
    /// memes from the same account, then recent tags. No model, only the user's own labels.
    public static func suggestions(for item: Item, in snapshot: Snapshot, limit: Int = 12) -> [Tag] {
        var scored: [String: Double] = [:]
        let captionWords = Set(tokens(item.source?.caption ?? ""))
        for tag in snapshot.tags {
            let words = tokens(tag.name)
            if !words.isEmpty && words.allSatisfy({ word in captionWords.contains { $0.hasPrefix(word) } }) {
                scored[tag.id, default: 0] += 10
            }
        }
        if let account = item.source?.account, !account.isEmpty {
            for other in snapshot.items where other.id != item.id && other.source?.account == account {
                for tagId in other.tags { scored[tagId, default: 0] += 3 }
            }
        }
        let recent = snapshot.items.filter { $0.taggedAt > 0 }.sorted { $0.taggedAt > $1.taggedAt }.prefix(20)
        for (rank, other) in recent.enumerated() {
            for tagId in other.tags { scored[tagId, default: 0] += 1 / Double(rank + 2) }
        }
        let byId = Dictionary(uniqueKeysWithValues: snapshot.tags.map { ($0.id, $0) })
        return scored.filter { !item.tags.contains($0.key) }
            .sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }
            .prefix(limit).compactMap { byId[$0.key] }
    }

    // MARK: - Exchange

    /// A meme arriving from another device or a backup: filed, with its labels merged into
    /// this collection's by name.
    @discardableResult
    public func receive(_ file: URL, source: Source?, tags: [(String, [Facet])], people: [String], taggedAt: Double? = nil,
                        signatures: [String: [String]] = [:]) throws -> Item {
        let item = try add(file, source: source, tagNames: tags, people: people, tagged: !tags.isEmpty || !people.isEmpty)
        if let taggedAt, taggedAt > 0 { try label([item.id], markTagged: true) }
        try absorb(signatures: signatures)
        return item
    }

    /// Adds labels by name to a meme already here: a duplicate arriving again still brings
    /// whatever labels it carries.
    public func merge(labels tags: [(String, [Facet])], people: [String], into itemId: String,
                      signatures: [String: [String]] = [:]) throws {
        try mutate { all in
            if !signatures.isEmpty {
                Self.absorb(signatures, into: &all)
                Self.reevaluate(&all)
            }
            let tagIds = tags.map { Self.resolveTag($0.0, facets: $0.1, in: &all) }
            let personIds = people.map { Self.resolvePerson($0, in: &all) }
            guard let index = all.items.firstIndex(where: { $0.id == itemId }) else { return }
            all.items[index].tags = Array(Set(all.items[index].tags).union(tagIds))
            all.items[index].people = Array(Set(all.items[index].people).union(personIds))
            if !tagIds.isEmpty || !personIds.isEmpty, all.items[index].taggedAt == 0 {
                all.items[index].taggedAt = Date().timeIntervalSince1970 * 1000
            }
        }
    }

    /// Makes sure these tags and people exist, with at least these facets.
    public func ensure(tags: [(String, [Facet])], people: [String], signatures: [String: [String]] = [:]) throws {
        try mutate { all in
            tags.forEach { _ = Self.resolveTag($0.0, facets: $0.1, in: &all) }
            people.forEach { _ = Self.resolvePerson($0, in: &all) }
            if !signatures.isEmpty {
                Self.absorb(signatures, into: &all)
                Self.reevaluate(&all)
            }
        }
    }

    /// Records a vault item as a private meme, for a restore. The vault item already exists.
    @discardableResult
    public func registerPrivate(vaultId: String, kind: String, sha256: String, source: Source?,
                                tags: [(String, [Facet])], people: [String],
                                signatures: [String: [String]] = [:]) throws -> Item {
        guard keybox.isUnlocked else { throw Failure.locked }
        return try mutate { all in
            if !signatures.isEmpty { Self.absorb(signatures, into: &all) }
            let tagIds = tags.map { Self.resolveTag($0.0, facets: $0.1, in: &all) }
            let personIds = people.map { Self.resolvePerson($0, in: &all) }
            let now = Date().timeIntervalSince1970 * 1000
            let item = Item(id: Self.newId("m"), kind: kind, isPrivate: true, path: nil, vaultId: vaultId, sha256: sha256,
                            source: source, tags: Array(Set(tagIds)), people: Array(Set(personIds)), addedAt: now,
                            taggedAt: tagIds.isEmpty && personIds.isEmpty ? 0 : now)
            all.items.append(item)
            return item
        }
    }

    /// Labels by name, for sending: ids mean nothing on another device.
    public static func labels(of item: Item, in snapshot: Snapshot) -> (tags: [(String, [Facet])], people: [String]) {
        let tags = item.tags.compactMap { id in snapshot.tags.first { $0.id == id } }.map { ($0.name, $0.facets) }
        let people = item.people.compactMap { id in snapshot.people.first { $0.id == id }?.name }
        return (tags, people)
    }

    // MARK: - Turkish folding

    /// Lower case the Turkish way (I→ı, İ→i), then plain letters: ı→i, ş→s, ğ→g, ç→c, ö→o,
    /// ü→u. "LAUBALİLİK", "laubalılık" and "laubalilik" all come out the same.
    public static func fold(_ text: String) -> String {
        text.lowercased(with: Locale(identifier: "tr"))
            .replacingOccurrences(of: "ı", with: "i")
            .folding(options: [.diacriticInsensitive, .widthInsensitive], locale: Locale(identifier: "tr"))
    }

    public static func tokens(_ text: String) -> [String] {
        fold(text).split { !$0.isLetter && !$0.isNumber }.map(String.init)
    }

    // MARK: - Helpers

    private static func resolveTag(_ name: String, facets: [Facet], in all: inout Index) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = fold(trimmed)
        if let index = all.tags.firstIndex(where: { fold($0.name) == key }) {
            // An incoming facet is added, never taken away: labels only accumulate.
            let merged = all.tags[index].facets + facets.filter { !all.tags[index].facets.contains($0) }
            all.tags[index].facets = merged
            return all.tags[index].id
        }
        let tag = Tag(id: newId("t"), name: trimmed, facets: facets)
        all.tags.append(tag)
        return tag.id
    }

    static func resolvePerson(_ name: String, in all: inout Index) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = fold(trimmed)
        if let existing = all.people.first(where: { fold($0.name) == key }) { return existing.id }
        let person = Person(id: newId("p"), name: trimmed)
        all.people.append(person)
        return person.id
    }

    static func newId(_ prefix: String) -> String {
        prefix + ((try? Crypto.randomBytes(12))?.map { String(format: "%02x", $0) }.joined() ?? UUID().uuidString)
    }

    static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    public static func unique(_ name: String, in folder: URL) -> URL {
        var candidate = folder.appendingPathComponent(name)
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var counter = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = folder.appendingPathComponent(ext.isEmpty ? "\(base) (\(counter))" : "\(base) (\(counter)).\(ext)")
            counter += 1
        }
        return candidate
    }

    static func safeName(_ text: String) -> String? {
        let cleaned = String(text.prefix(60)).map { "/:\\\n".contains($0) ? " " : $0 }
        let name = String(cleaned).trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        return name.isEmpty ? nil : name
    }
}

/// The device key for memes that are not private: 32 random bytes in the login Keychain,
/// made on first use. Readable without a prompt, which is the point: search never asks.
public enum MemeDeviceKey {
    public static func load(service: String) throws -> Data {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "memes-index",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data, data.count == 32 {
            return data
        }
        let key = try Crypto.randomBytes(32)
        query.removeValue(forKey: kSecReturnData as String)
        query.removeValue(forKey: kSecMatchLimit as String)
        SecItemDelete(query as CFDictionary)
        query[kSecValueData as String] = key
        // This Mac only, and only while it is unlocked: never synced to other devices.
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw MemeLibrary.Failure.io(String(localized: "The Keychain refused (\(status)).")) }
        return key
    }

    public static func delete(service: String) {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                       kSecAttrAccount as String: "memes-index"] as CFDictionary)
    }
}

/// A meme's `meme` object in a pairing offer (`shared/memes/CONTRACT.md`, "Between devices").
/// Labels travel by name: ids mean nothing on another device.
public enum MemeTransfer {
    public static func encode(item: MemeLibrary.Item, tags: [(String, [MemeLibrary.Facet])], people: [String],
                              signatures: [String: [String]] = [:]) -> Data? {
        var source: [String: Any] = ["savedAt": item.source?.savedAt ?? item.addedAt]
        if let value = item.source?.platform { source["platform"] = value }
        if let value = item.source?.account { source["account"] = value }
        if let value = item.source?.accountName { source["accountName"] = value }
        if let value = item.source?.caption { source["caption"] = value }
        if let value = item.source?.url { source["url"] = value }
        if let value = item.source?.postedAt { source["postedAt"] = value }
        return try? JSONSerialization.data(withJSONObject: [
            "kind": item.kind,
            "source": source,
            "tags": tags.map { ["name": $0.0, "facets": $0.1.map(\.rawValue)] },
            // A person's face signatures travel with them, so the other device recognises
            // them without being taught.
            "people": people.map { name -> [String: Any] in
                var person: [String: Any] = ["name": name]
                if let set = signatures[name], !set.isEmpty { person["signatures"] = set }
                return person
            },
        ])
    }

    /// The face signatures that came with people, by name. Anything that is not a valid
    /// signature is dropped.
    public static func signatures(_ object: [String: Any]) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for person in object["people"] as? [[String: Any]] ?? [] {
            guard let name = (person["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty
            else { continue }
            let set = (person["signatures"] as? [String] ?? []).prefix(8).filter { FaceMath.decode($0) != nil }
            if !set.isEmpty { out[String(name.prefix(80))] = Array(set) }
        }
        return out
    }

    /// Reads one back, leniently: an unknown facet is dropped, a missing field is absent.
    public static func decode(_ object: [String: Any]) -> (source: MemeLibrary.Source?, tags: [(String, [MemeLibrary.Facet])], people: [String]) {
        let s = object["source"] as? [String: Any]
        let source = s.map {
            MemeLibrary.Source(platform: $0["platform"] as? String, account: $0["account"] as? String,
                               accountName: $0["accountName"] as? String, caption: $0["caption"] as? String,
                               url: $0["url"] as? String, postedAt: ($0["postedAt"] as? NSNumber)?.doubleValue,
                               savedAt: ($0["savedAt"] as? NSNumber)?.doubleValue ?? Date().timeIntervalSince1970 * 1000)
        }
        let tags = (object["tags"] as? [[String: Any]] ?? []).compactMap { tag -> (String, [MemeLibrary.Facet])? in
            guard let name = (tag["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
            return (String(name.prefix(80)), (tag["facets"] as? [String] ?? []).compactMap(MemeLibrary.Facet.init(rawValue:)))
        }
        let people = (object["people"] as? [[String: Any]] ?? []).compactMap {
            ($0["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter { !$0.isEmpty }.map { String($0.prefix(80)) }
        return (source, tags, people)
    }
}
