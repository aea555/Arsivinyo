import ArsivinyoCore
import ArsivinyoTorrentC
import Foundation

/// Torrent downloads end to end on the Mac (`shared/watch/CONTRACT.md`, phase 3): a magnet
/// whose files are picked, a download that survives the app stopping halfway, and a private
/// one that lands in the vault with no plaintext left behind. A seeder in this process
/// serves shared/torrent/fixtures/sample.torrent's payload on loopback.
extension CoreChecks {

    /// shared/torrent/test's pattern: an LCG, one byte from bits 16..23 of each step.
    private static func pattern(_ size: Int, seed: UInt32) -> Data {
        var x = seed
        var out = Data(count: size)
        out.withUnsafeMutableBytes { buffer in
            for i in 0..<size {
                x = x &* 1103515245 &+ 12345
                buffer[i] = UInt8(truncatingIfNeeded: x >> 16)
            }
        }
        return out
    }

    private static func wait(_ seconds: Double, _ done: () -> Bool) -> Bool {
        let until = Date().addingTimeInterval(seconds)
        while Date() < until {
            if done() { return true }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return done()
    }

    mutating func checkTorrents() throws {
        print("torrents")
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("arsivinyo-torrents-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        let fm = FileManager.default
        let payload = scratch.appendingPathComponent("seed/show")
        try fm.createDirectory(at: payload, withIntermediateDirectories: true)
        let movie = Self.pattern(8 << 20, seed: 7)
        try movie.write(to: payload.appendingPathComponent("movie.mkv"))
        try Self.pattern(512 << 10, seed: 9).write(to: payload.appendingPathComponent("extra.nfo"))
        let torrent = try Data(contentsOf: Self.repositoryRoot.appendingPathComponent("shared/torrent/fixtures/sample.torrent"))

        // The seeder: the engine's C API, held to 256 KB/s so a download can be stopped halfway.
        let seedState = scratch.appendingPathComponent("seed-state").path
        var seedSettings = seedState.withCString { at_settings(state_dir: strdup($0), listen_port: 0, seed_ratio: 100,
                                                               upload_allowed: 1, discovery: 0, upload_limit: 256 << 10, download_limit: 0) }
        defer { free(UnsafeMutableRawPointer(mutating: seedSettings.state_dir)) }
        var error = [CChar](repeating: 0, count: 256)
        guard let seeder = at_session_create(&seedSettings, &error) else {
            check(false, "a seeder starts")
            return
        }
        defer { at_session_destroy(seeder) }
        var idBytes = [CChar](repeating: 0, count: 65)
        _ = torrent.withUnsafeBytes { at_add_torrent(seeder, $0.bindMemory(to: UInt8.self).baseAddress, torrent.count,
                                                     scratch.appendingPathComponent("seed").path, 0, &idBytes) }
        let hash = String(cString: idBytes)
        let magnet = "magnet:?xt=urn:btih:\(hash)&x.pe=127.0.0.1:\(at_session_port(seeder))"
        check(Self.wait(20) {
            guard let raw = at_status(seeder) else { return false }
            defer { at_free(raw) }
            return String(cString: raw).contains("\"seeding\"")
        }, "the seeder has the whole torrent")

        let key = try Crypto.randomBytes(32)
        let library = WatchLibrary(file: scratch.appendingPathComponent("library.enc"), key: { key })
        let keybox = Keybox(directory: scratch.appendingPathComponent("vault-keys"), params: .fast,
                            keychainService: "com.arsivinyo.mac.checks.\(UUID().uuidString)")
        try fm.createDirectory(at: scratch.appendingPathComponent("vault-keys"), withIntermediateDirectories: true)
        try keybox.create(passphrase: "a correct horse battery staple")
        let vault = Vault(root: scratch.appendingPathComponent("vault"), keybox: keybox)
        let publicFolder = scratch.appendingPathComponent("Downloads")
        let taker = TorrentEngine.Taker(
            filePublic: { file, relative in
                let target = publicFolder.appendingPathComponent(relative)
                try? FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                return (try? FileManager.default.copyItem(at: file, to: target)) != nil
            },
            intoVault: { file, name in (try? vault.add(file, title: name)) != nil })
        let root = scratch.appendingPathComponent("torrents")
        func engine() -> TorrentEngine {
            let made = TorrentEngine(root: root)
            made.attach(library: library, taker: taker)
            return made
        }

        // Private: the movie only, stopped halfway, then carried on by a new engine.
        var first: TorrentEngine? = engine()
        let id = try first!.add(magnet: magnet)
        var files: [TorrentEngine.File]?
        check(Self.wait(30) { files = try? first!.files(id); return files != nil }, "a magnet's files arrive from a peer")
        let movieIndex = files?.first { $0.path.hasSuffix("movie.mkv") }?.index ?? 0
        Thread.sleep(forTimeInterval: 3)
        first!.work()
        check(((try? first!.downloads())?.first?.engine?["done"] as? NSNumber)?.int64Value ?? -1 == 0,
              "nothing is fetched before the files are chosen, even with the file list here")
        try first!.choose(id, wanted: [movieIndex], destination: "private")
        check(Self.wait(30) {
            first!.work()
            let done = ((try? first!.downloads())?.first?.engine?["done"] as? NSNumber)?.int64Value ?? 0
            return done > 1 << 20
        }, "it downloads only what was picked")
        first!.stop()
        first = nil
        let halfway = try library.torrent(id)
        check(halfway?.state == "downloading" && halfway?.taken.isEmpty == true, "stopped halfway, it is still to finish")

        let second = engine()
        // The seeder was known from the magnet alone; real torrents carry trackers, which the
        // resume data keeps, and a restarted download finds its peers through them.
        // Once its resume data has been checked, which libtorrent does before anything else.
        var resumedFrom: Int64 = 0
        check(Self.wait(20) {
            let engine = (try? second.downloads())?.first?.engine
            resumedFrom = (engine?["done"] as? NSNumber)?.int64Value ?? 0
            return engine?["state"] as? String == "downloading" && resumedFrom > 0
        }, "it starts again from what it had, not from nothing")
        _ = at_connect_peer(try second.handle(), id, "127.0.0.1", at_session_port(seeder))
        check(Self.wait(90) {
            second.work()
            return (try? library.torrent(id))?.state == "done"
        }, "a new engine carries on where the old one stopped, and finishes")
        let item = try vault.items().first { $0.title.contains("movie") }
        let decrypted = try item.map { try vault.reader(for: $0.id) }.map { try $0.read(offset: 0, length: Int($0.size)) }
        check(decrypted == movie, "the file is in the vault, whole")
        let leftovers = (fm.enumerator(at: root, includingPropertiesForKeys: nil)?.compactMap { $0 as? URL } ?? [])
            .filter { $0.lastPathComponent == "movie.mkv" }
        check(leftovers.isEmpty, "with no plaintext left behind")
        check(!(fm.enumerator(at: root, includingPropertiesForKeys: nil)?.compactMap { $0 as? URL } ?? [])
            .contains { $0.lastPathComponent == "extra.nfo" && ((try? Data(contentsOf: $0))?.count ?? 0) == 512 << 10 },
              "and the file not picked was never fetched")
        second.stop()

        // Public: a download of its own, every file, into the download folder.
        let publicLibrary = WatchLibrary(file: scratch.appendingPathComponent("public-library.enc"), key: { key })
        let publicEngine = TorrentEngine(root: scratch.appendingPathComponent("public-torrents"))
        publicEngine.attach(library: publicLibrary, taker: taker)
        let all = try publicEngine.add(magnet: magnet)
        _ = Self.wait(30) { (try? publicEngine.files(all)) != nil }
        try publicEngine.choose(all, wanted: (try publicEngine.files(all) ?? []).map(\.index), destination: "public")
        check(Self.wait(90) {
            publicEngine.work()
            return (try? publicLibrary.torrent(all))?.state == "done"
        }, "a public download finishes")
        check((try? Data(contentsOf: publicFolder.appendingPathComponent("show/movie.mkv"))) == movie,
              "and its files are in the download folder")
        publicEngine.stop()

        // A torrent in the downloads folder with no record of it, as an add that failed
        // halfway leaves: the next engine to start removes it, rather than let it run unseen.
        let orphanRoot = scratch.appendingPathComponent("orphan-torrents")
        let orphanLibrary = WatchLibrary(file: scratch.appendingPathComponent("orphan-library.enc"), key: { key })
        do {
            let raw = TorrentEngine(root: orphanRoot)
            raw.attach(library: orphanLibrary, taker: taker)
            var orphanId = [CChar](repeating: 0, count: 65)
            _ = at_add_magnet(try raw.handle(), magnet, orphanRoot.appendingPathComponent("downloads").path, 0, &orphanId)
            check(((try? raw.status()) ?? []).count == 1, "a torrent can be in the engine with no record")
            raw.stop()
        }
        let restarted = TorrentEngine(root: orphanRoot)
        restarted.attach(library: orphanLibrary, taker: taker)
        check(Self.wait(10) { ((try? restarted.status()) ?? []).isEmpty }, "and the next engine removes it")
        restarted.stop()
    }
}
