import Foundation
import ArsivinyoCore

/// The meme collection, held to `shared/memes/CONTRACT.md`'s "done" list.
extension CoreChecks {

    mutating func checkMemes() throws {
        print("memes")
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("arsivinyo-memes-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        let folder = scratch.appendingPathComponent("Downloads")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let keybox = Keybox(directory: scratch, params: .fast, keychainService: "com.arsivinyo.mac.checks.\(UUID().uuidString)")
        try keybox.create(passphrase: "a correct horse battery staple")
        defer { keybox.deleteKeychainItem() }
        let vault = Vault(root: scratch.appendingPathComponent("vault"), keybox: keybox)
        let deviceKey = try Crypto.randomBytes(32)
        func library() -> MemeLibrary {
            MemeLibrary(support: scratch.appendingPathComponent("memes"), vault: vault, keybox: keybox, deviceKey: { deviceKey })
        }
        let memes = library()

        func video(_ name: String, _ seed: Int) throws -> URL {
            let url = folder.appendingPathComponent(name)
            try Self.pattern(40_000 + seed, seed).write(to: url)
            return url
        }

        // 1. Found by its caption, with no tagging at all.
        let arda = try memes.add(try video("arda.mp4", 3), source: .init(platform: "twitter", account: "futbolcaps",
                                                                      caption: "bizim laubalilik seviyesi"))
        var snapshot = try memes.load()
        check(MemeLibrary.search(snapshot, query: "laubali").map(\.id) == [arda.id],
              "a download is found by a word of its caption, untagged")
        check(arda.isUntagged, "and waits in the untagged inbox")

        // 2. Turkish folding, both ways.
        for query in ["laubalilik", "LAUBALİLİK", "laubalılık", "Laubalılık"] {
            check(MemeLibrary.search(snapshot, query: query).map(\.id) == [arda.id], "“\(query)” finds it")
        }
        check(MemeLibrary.search(snapshot, query: "LAUBALILIK").map(\.id) == [arda.id],
              "and a dotless I lowers to ı, which folds to i as well")

        // Tags: free, with any number of facets, merged by folded name.
        let rahat = try memes.tag(named: "Rahat", facets: [.vibe])
        let again = try memes.tag(named: "RAHAT", facets: [.emotion])
        check(again.id == rahat.id && Set(again.facets) == [.vibe, .emotion],
              "a tag is one tag whatever its case, and gathers facets")
        let terim = try memes.person(named: "Fatih Terim")
        let summer = try memes.add(try video("terim.mp4", 5), source: .init(platform: "instagram", account: "futbolcaps",
                                                                         caption: "yaz geldi"))
        try memes.label([summer.id], addTags: [rahat.id], addPeople: [terim.id])
        snapshot = try memes.load()
        check(MemeLibrary.search(snapshot, query: "fatih rahat").map(\.id) == [summer.id], "people and tags search together")
        var vibe = MemeLibrary.Filter()
        vibe.facets = [.vibe]
        check(MemeLibrary.search(snapshot, query: "", filter: vibe).map(\.id) == [summer.id], "and a facet filters")
        var inbox = MemeLibrary.Filter()
        inbox.onlyUntagged = true
        check(MemeLibrary.search(snapshot, query: "", filter: inbox).map(\.id) == [arda.id], "tagging empties the inbox")

        // Suggestions: the same account's tags, and caption words that are tags.
        let laubalilik = try memes.tag(named: "laubalilik", facets: [.action])
        snapshot = try memes.load()
        let suggested = MemeLibrary.suggestions(for: snapshot.items.first { $0.id == arda.id }!, in: snapshot).map(\.id)
        check(suggested.first == laubalilik.id && suggested.contains(rahat.id),
              "suggestions come from the caption and from the same account")

        // 3. Arriving from another device: labels merge by name.
        let incoming = try video("incoming.mp4", 7)
        let received = try memes.receive(incoming, source: .init(platform: "twitter", caption: "gelen"),
                                         tags: [("RAHAT", [.context]), ("yeni", [])], people: ["fatih terim"])
        snapshot = try memes.load()
        check(received.tags.contains(rahat.id) && received.people == [terim.id] && snapshot.tags.count == 3,
              "a meme from another device merges its labels into these by name")

        // 4. Private: into the vault, out of the index, back only when unlocked.
        let secret = try memes.tag(named: "gizli-etiket")
        try memes.label([arda.id], addTags: [secret.id])
        try memes.makePrivate(arda.id)
        check(!FileManager.default.fileExists(atPath: folder.appendingPathComponent("arda.mp4").path),
              "a private meme's file leaves the folder for the vault")
        keybox.lock()
        memes.forgetPrivate()
        let locked = try library().load()
        check(!locked.items.contains { $0.id == arda.id } && !locked.tags.contains { $0.id == secret.id },
              "locked, a private meme and its own labels do not exist")
        check(MemeLibrary.search(locked, query: "laubalilik").isEmpty, "and search does not find it")
        try keybox.unlock(passphrase: "a correct horse battery staple")
        let unlocked = try library().load()
        check(unlocked.items.first { $0.id == arda.id }?.isPrivate == true, "unlocked, it is back")

        // 6. Nothing readable in the files.
        let publicFile = try Data(contentsOf: scratch.appendingPathComponent("memes/index.enc"))
        let privateFile = try Data(contentsOf: scratch.appendingPathComponent("memes/private.enc"))
        let secrets = ["laubalilik", "yaz geldi", "Fatih", "Rahat", "futbolcaps", "gizli"]
        check(secrets.allSatisfy { publicFile.range(of: Data($0.utf8)) == nil && privateFile.range(of: Data($0.utf8)) == nil },
              "neither index holds a tag, a person, a caption or an account in plain text")

        // Round trip back out of the vault.
        try library().makePublic(arda.id, into: folder)
        let back = try library().load()
        check(back.items.first { $0.id == arda.id }.map { !$0.isPrivate && FileManager.default.fileExists(atPath: $0.path ?? "") } == true
              && back.tags.contains { $0.id == secret.id },
              "made public again, it returns to the folder with its labels")
    }
}
