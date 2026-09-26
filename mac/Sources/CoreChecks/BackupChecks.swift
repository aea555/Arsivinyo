import Foundation
import ArsivinyoCore

/// Backups: a whole library out and back, the ways one can go wrong, and the files the
/// phone and this Mac have to be able to read from each other.
extension CoreChecks {

    /// A library to back up: two vault items, two tracks (one a preset render of the other)
    /// with artwork, a playlist, a favourite, a custom preset applied to every download, and
    /// a cookie profile.
    struct BackupWorld {
        let root: URL
        let keybox: Keybox
        let vault: Vault
        let library: MusicLibrary
        let presets: PresetStore
        let cookies: CookieStore
        let memes: MemeLibrary
        let memeFolder: URL

        var sources: Backup.Sources {
            .init(vault: vault, library: library, presets: presets, cookies: cookies, memes: memes, memeFolder: memeFolder)
        }

        init(_ root: URL, service: String) throws {
            self.root = root
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            keybox = Keybox(directory: root, params: .fast, keychainService: service)
            try keybox.create(passphrase: "a correct horse battery staple")
            vault = Vault(root: root.appendingPathComponent("vault"), keybox: keybox)
            library = MusicLibrary(musicFolder: root.appendingPathComponent("Music"),
                                   supportFolder: root.appendingPathComponent("music"))
            presets = PresetStore(directory: root)
            cookies = CookieStore(directory: root, keybox: keybox)
            memeFolder = root.appendingPathComponent("Downloads")
            let deviceKey = try Crypto.randomBytes(32)
            memes = MemeLibrary(support: root.appendingPathComponent("memes"), vault: vault, keybox: keybox, deviceKey: { deviceKey })
        }
    }

    static func pattern(_ count: Int, _ step: Int) -> Data {
        Data((0..<count).map { UInt8(truncatingIfNeeded: $0 &* step &+ 11) })
    }

    /// Fills a world with fixed content, so a backup of it can be checked byte for byte on
    /// the other side. The phone's fixture test expects exactly this.
    static func fill(_ world: BackupWorld) async throws {
        let scratch = world.root.appendingPathComponent("incoming")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        let clip = scratch.appendingPathComponent("clip.mp4")
        try pattern(300_000, 7).write(to: clip)
        try world.vault.add(clip, title: "Holiday clip", removeOriginal: true)

        let song = scratch.appendingPathComponent("Song One.m4a")
        try pattern(50_000, 13).write(to: song)
        let art = scratch.appendingPathComponent("cover.jpg")
        try pattern(1_000, 3).write(to: art)
        let one = try await world.library.adopt(song, title: "Song One", artist: "Artist", artwork: art)
        let slowed = scratch.appendingPathComponent("Song One (Slowed).m4a")
        try pattern(60_000, 17).write(to: slowed)
        _ = try await world.library.adopt(slowed, title: "Song One (Slowed)", artist: "Artist",
                                          presetId: "slowed-reverb", sourceSongId: one.id)
        world.library.setFavorite(one.id, true)
        let mix = world.library.createPlaylist(named: "Mix")
        world.library.add([one.id], to: mix.id)

        var mine = PresetParams()
        mine.rate = 0.9
        let custom = world.presets.create(named: "Mine", params: mine)
        world.presets.setAutoApply(AutoPresetConfig(keepOriginal: true, presetIds: [custom.id]))

        let jar = scratch.appendingPathComponent("cookies.txt")
        try Data(".youtube.com\tTRUE\t/\tTRUE\t2000000000\tSID\tfixture-session\n".utf8).write(to: jar)
        try world.cookies.importFile(jar, into: .platform("youtube"), name: "main")

        try FileManager.default.createDirectory(at: world.memeFolder, withIntermediateDirectories: true)
        let meme = world.memeFolder.appendingPathComponent("arda.mp4")
        try pattern(20_000, 19).write(to: meme)
        let arda = try world.memes.add(meme, source: .init(platform: "twitter", account: "futbolcaps",
                                                           caption: "bizim laubalilik seviyesi"),
                                       tagNames: [("laubalilik", [.action])], people: ["Arda Turan"], tagged: true)
        _ = arda
        _ = try world.memes.tag(named: "rahat", facets: [.vibe])
        let hidden = world.memeFolder.appendingPathComponent("gizli.mp4")
        try pattern(15_000, 23).write(to: hidden)
        let secret = try world.memes.add(hidden, source: nil, tagNames: [("gizli-etiket", [.emotion])], tagged: true)
        try world.memes.makePrivate(secret.id)
    }

    static let fixturePassphrase = "cross platform fixture passphrase"
    static var fixtures: URL { repositoryRoot.appendingPathComponent("shared/crypto/fixtures") }

    static func writeBackupFixture() async throws {
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-fixture-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        let world = try BackupWorld(scratch, service: "com.arsivinyo.mac.checks.\(UUID().uuidString)")
        defer { world.keybox.deleteKeychainItem() }
        try await fill(world)
        try FileManager.default.createDirectory(at: fixtures, withIntermediateDirectories: true)
        let out = fixtures.appendingPathComponent("mac-written.avsbck")
        _ = try Backup.create(at: out, secret: fixturePassphrase, sections: BackupSection.allCases,
                              from: world.sources, appVersion: "fixture")
        print(out.path)
    }

    /// The phone wrote this through its own collectors (CrossPlatformBackupTest). Restoring
    /// it here has to produce the same library a backup made here does.
    mutating func checkPhoneBackup() async throws {
        print("backup from the phone")
        let file = Self.fixtures.appendingPathComponent("phone-written.avsbck")
        guard FileManager.default.fileExists(atPath: file.path) else {
            check(false, "shared/crypto/fixtures/phone-written.avsbck is there")
            return
        }
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-phone-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        let world = try BackupWorld(scratch.appendingPathComponent("world"), service: "com.arsivinyo.mac.checks.\(UUID().uuidString)")
        defer { world.keybox.deleteKeychainItem() }
        let report = try await Backup.restore(from: file, secret: Self.fixturePassphrase, sections: Set(BackupSection.allCases),
                                              into: world.sources, staging: scratch.appendingPathComponent("staging"))
        check(report.restored == 5 && report.failed == 0,
              "a backup the phone wrote restores here (\(report.restored) added, \(report.failed) failed)")
        try Self.checkRestored(world, into: &self)
        try Self.checkRestoredMemes(world, into: &self)
        check(!world.presets.settingsBlob().keys.contains("@arsivinyo_theme"),
              "and the phone's own settings, its theme, stay on the phone")
    }

    mutating func checkBackup() async throws {
        print("backup")
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-backup-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        let service = "com.arsivinyo.mac.checks.\(UUID().uuidString)"

        let source = try BackupWorld(scratch.appendingPathComponent("source"), service: service + ".a")
        try await Self.fill(source)
        let file = scratch.appendingPathComponent("library.avsbck")
        let passphrase = "correct horse battery staple backup"
        let header = try Backup.create(at: file, secret: passphrase, sections: BackupSection.allCases,
                                       from: source.sources, appVersion: "checks")
        check(header.sections.map(\.id) == ["vault", "music", "memes", "settings", "cookies"],
              "all five sections are written, memes after music")

        let bytes = try Data(contentsOf: file)
        check(["Holiday clip", "Song One", "fixture-session", "Mine", "laubalilik", "Arda Turan", "gizli-etiket"]
                .allSatisfy { bytes.range(of: Data($0.utf8)) == nil },
              "no title, name or cookie is readable in the file")
        let preview = try Backup.preview(file)
        check(preview.section("vault")?.itemCount == 2 && preview.section("memes")?.itemCount == 2
              && preview.section("cookies")?.itemCount == 1,
              "the preview counts what is in it without the passphrase")

        let target = try BackupWorld(scratch.appendingPathComponent("target"), service: service + ".b")
        do {
            _ = try await Backup.restore(from: file, secret: "not the passphrase", sections: Set(BackupSection.allCases),
                                         into: target.sources, staging: scratch.appendingPathComponent("staging"))
            check(false, "a wrong passphrase is refused")
        } catch Backup.Failure.wrongSecret {
            check(true, "a wrong passphrase is refused, as a wrong passphrase")
        }

        let report = try await Backup.restore(from: file, secret: passphrase, sections: Set(BackupSection.allCases),
                                              into: target.sources, staging: scratch.appendingPathComponent("staging"))
        if report.failed > 0 { print("        reasons: \(report.reasons)") }
        check(report.restored == 5 && report.failed == 0,
              "a restore adds the vault items, both tracks and the meme (\(report.restored) added, \(report.failed) failed)")
        try Self.checkRestored(target, into: &self)
        try Self.checkRestoredMemes(target, into: &self)

        let again = try await Backup.restore(from: file, secret: passphrase, sections: Set(BackupSection.allCases),
                                             into: target.sources, staging: scratch.appendingPathComponent("staging"))
        check(again.restored == 0 && again.duplicates == 5 && again.existing == 1,
              "restoring the same backup again adds nothing (\(again.duplicates) duplicates, \(again.existing) existing)")
        let playlists = target.library.load().playlists
        check(playlists.filter { $0.name == "Mix" }.count == 1 && playlists.first { $0.name == "Mix" }?.trackIds.count == 1,
              "and does not make a second of each playlist")

        // Only the chosen sections come back.
        let partial = try BackupWorld(scratch.appendingPathComponent("partial"), service: service + ".c")
        _ = try await Backup.restore(from: file, secret: passphrase, sections: [.cookies],
                                     into: partial.sources, staging: scratch.appendingPathComponent("staging"))
        let partialVault = try partial.vault.items()
        check(partial.library.load().tracks.isEmpty && partialVault.isEmpty && partial.cookies.profiles().count == 1,
              "a partial restore takes only the sections asked for")

        // Altered anywhere in a section, the section fails; it does not restore something else.
        var tampered = bytes
        tampered[tampered.count / 3] ^= 0x01
        let damaged = scratch.appendingPathComponent("damaged.avsbck")
        try tampered.write(to: damaged)
        let victim = try BackupWorld(scratch.appendingPathComponent("victim"), service: service + ".d")
        let outcome = try? await Backup.restore(from: damaged, secret: passphrase, sections: Set(BackupSection.allCases),
                                                into: victim.sources, staging: scratch.appendingPathComponent("staging"))
        check(outcome == nil || outcome!.failed > 0, "a byte changed in the file is caught, not restored")

        // A source that cannot be read in full is written as incomplete and refused on the
        // way back, while the rest restores.
        let broken = try BackupWorld(scratch.appendingPathComponent("broken"), service: service + ".e")
        try await Self.fill(broken)
        let unreadable = broken.library.load().tracks.first { $0.presetId != nil }!
        let path = broken.library.fileURL(for: unreadable).path
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: path)
        let brokenFile = scratch.appendingPathComponent("broken.avsbck")
        _ = try Backup.create(at: brokenFile, secret: passphrase, sections: [.music], from: broken.sources, appVersion: "checks")
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: path)
        let rescue = try BackupWorld(scratch.appendingPathComponent("rescue"), service: service + ".f")
        let rescued = try await Backup.restore(from: brokenFile, secret: passphrase, sections: [.music],
                                               into: rescue.sources, staging: scratch.appendingPathComponent("staging"))
        check(rescued.failed == 1 && rescued.restored == 1,
              "an item that could not be read is refused, and the rest of the section still restores")

        for world in [source, target, partial, victim, broken, rescue] { world.keybox.deleteKeychainItem() }
    }

    /// The memes of `fill`: the public one found by its caption and tags, the private one back
    /// in the vault with its labels, the unused tag still there.
    static func checkRestoredMemes(_ world: BackupWorld, into runner: inout CoreChecks) throws {
        let snapshot = try world.memes.load()
        let arda = MemeLibrary.search(snapshot, query: "laubalilik arda")
        runner.check(arda.count == 1 && arda.first.flatMap { $0.fileURL }.map { (try? Data(contentsOf: $0)) == pattern(20_000, 19) } == true,
                     "a meme comes back whole, found by its tag and person")
        runner.check(arda.first?.source?.caption == "bizim laubalilik seviyesi" && arda.first?.isUntagged == false,
                     "with its source and still tagged")
        runner.check(snapshot.tags.first { $0.name == "rahat" }?.facets == [.vibe], "a tag no meme uses yet survives, facets and all")
        let secret = MemeLibrary.search(snapshot, query: "gizli")
        runner.check(secret.count == 1 && secret.first?.isPrivate == true
                     && (try? world.vault.items())?.contains { $0.id == secret.first?.vaultId } == true,
                     "a private meme comes back into the vault, with its labels")
    }

    /// What a restore of `fill` must have produced. Used for this Mac's own backups and for
    /// the one the phone wrote.
    static func checkRestored(_ world: BackupWorld, into runner: inout CoreChecks) throws {
        let items = try world.vault.items()
        let clip = items.first { $0.title == "Holiday clip" }
        let clipBytes = try clip.map { item -> Data in
            let reader = try world.vault.reader(for: item.id)
            return try reader.read(offset: 0, length: Int(reader.size))
        }
        runner.check(clipBytes == pattern(300_000, 7), "the vault item comes back whole, under its title")

        let (tracks, playlists) = world.library.load()
        let one = tracks.first { $0.title == "Song One" }
        let slowed = tracks.first { $0.title == "Song One (Slowed)" }
        runner.check(one.map { (try? Data(contentsOf: world.library.fileURL(for: $0))) == pattern(50_000, 13) } == true,
                     "a track comes back whole")
        runner.check(one.flatMap { world.library.artworkURL(for: $0) }.map { (try? Data(contentsOf: $0)) == pattern(1_000, 3) } == true,
                     "with its artwork")
        runner.check(slowed?.presetId == "slowed-reverb" && slowed?.sourceSongId == one?.id,
                     "a preset render still points at its source, by the new id")
        runner.check(playlists.first { $0.name == "Mix" }?.trackIds == one.map { [$0.id] },
                     "a playlist names the restored track")
        runner.check(playlists.first { $0.id == MusicLibrary.favoritesId }?.trackIds == one.map { [$0.id] },
                     "and so does Favorites")

        let mine = world.presets.all().first { $0.name == "Mine" }
        runner.check(mine?.params.rate == 0.9, "a custom preset comes back")
        runner.check(mine.map { world.presets.autoApply.presetIds.contains($0.id) } == true,
                     "still applied to every download, though it arrived after the setting that names it")

        let profile = world.cookies.profiles().first
        runner.check(profile?.scope == .platform("youtube") && profile?.name == "main" && profile?.isDefault == true
                     && (try? world.cookies.plaintext(.platform("youtube"), name: "main"))?.range(of: Data("fixture-session".utf8)) != nil,
                     "the cookie profile comes back, as the site's default, encrypted here")
    }
}
