import Foundation
import ArsivinyoCore

/// Pairing, held to `shared/pairing/VECTORS.json`: the same file the phone's tests read, so
/// the two ends agree on every byte rather than each only with itself.
extension CoreChecks {

    mutating func checkPairingVectors() throws {
        print("pairing vectors")
        let url = Self.repositoryRoot.appendingPathComponent("shared/pairing/VECTORS.json")
        let vectors = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        func list(_ name: String) -> [[String: Any]] { vectors[name] as! [[String: Any]] }
        func hex(_ value: Any?) -> Data { Data(hexString: value as! String)! }
        func string(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

        for frame in list("frames") {
            let encoded = Pairing.encodeFrame(type: UInt8(frame["type"] as! Int), payload: hex(frame["payload"]))
            check(encoded == hex(frame["encoded"]), "frame: \(frame["why"]!)")
            if case .frame(let type, let payload, let consumed) = Pairing.decodeFrame(hex(frame["encoded"])) {
                check(type == UInt8(frame["type"] as! Int) && payload == hex(frame["payload"]) && consumed == encoded?.count,
                      "and it decodes back")
            } else {
                check(false, "and it decodes back")
            }
        }
        for bad in list("decode_errors") {
            let result = Pairing.decodeFrame(hex(bad["input"]))
            let expected: Pairing.Decoded = switch bad["expect"] as! String {
                case "TooLarge": .tooLarge
                case "BadType": .badType
                default: .incomplete
            }
            check(result == expected, "refused: \(bad["why"]!)")
        }
        for pair in list("code_input") {
            check(Pairing.codeInput(hex(pair["keyA"]), hex(pair["keyB"])) == hex(pair["sorted"]), "code input: \(pair["why"]!)")
        }
        for code in list("pairing_code") {
            check(Pairing.code(fromDigest: hex(code["digest"])) == code["code"] as? String, "code: \(code["why"]!)")
        }
        for key in list("ed25519") {
            let publicKey = try Pairing.publicKey(seed: hex(key["seed"]))
            check(publicKey == hex(key["publicKey"]), "Ed25519 key from its seed, as RFC 8032 has it")
            check(Pairing.fingerprint(of: publicKey) == key["fingerprint"] as? String, "and its fingerprint")
            let signature = try Pairing.sign(seed: hex(key["seed"]), message: hex(key["message"]))
            check(signature == hex(key["signature"]), "a signature made here is the phone's, byte for byte")
            check(Pairing.verify(publicKey: publicKey, message: hex(key["message"]), signature: signature), "and verifies")
            var forged = signature
            forged[forged.startIndex] ^= 1
            check(!Pairing.verify(publicKey: publicKey, message: hex(key["message"]), signature: forged), "and a changed one does not")
        }
        let seed = hex(vectors["auth_transcript_seed"])
        for auth in list("auth_transcript") {
            let role = (auth["role"] as! String) == "server" ? Pairing.roleServer : Pairing.roleClient
            let transcript = Pairing.authTranscript(role: role, serverCertSha256: hex(auth["serverCertSha256"]),
                                                    clientCertSha256: hex(auth["clientCertSha256"]))
            check(transcript == hex(auth["transcript"]), "transcript: \(auth["why"]!)")
            check(try Pairing.sign(seed: seed, message: transcript) == hex(auth["signature"]), "and its signature")
        }
        for v2 in list("pairing_v2") {
            let clientNonce = hex(v2["clientNonce"])
            let serverNonce = hex(v2["serverNonce"])
            check(string(Pairing.commitment(clientNonce: clientNonce)) == v2["commitment"] as? String,
                  "v2 commitment: \(v2["why"]!)")
            check(Pairing.codeV2(hex(v2["keyA"]), hex(v2["keyB"]), clientNonce: clientNonce, serverNonce: serverNonce)
                  == v2["code"] as? String, "and the code")
        }
    }
}

/// What one side of a loopback pairing serves and receives.
final class LoopbackContent: PeerContent, @unchecked Sendable {
    let folder: URL
    let offered: URL?
    let offeredArtwork: URL?
    private let lock = NSLock()
    private var landed: [URL] = []
    private var covers: [Data] = []
    private var memeObjects: [[String: Any]] = []
    private var lists: [PeerPlaylist] = []
    private var reuses: [(String, PeerPlaylist)] = []
    var receivedPlaylists: [PeerPlaylist] { lock.withLock { lists } }
    var reused: [(String, PeerPlaylist)] { lock.withLock { reuses } }
    var receivedMemes: [[String: Any]] { lock.withLock { memeObjects } }
    var received: [URL] { lock.withLock { landed } }
    var receivedArtwork: [Data] { lock.withLock { covers } }

    init(folder: URL, offering file: URL? = nil, artwork: URL? = nil) {
        self.folder = folder
        offered = file
        offeredArtwork = artwork
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    func listing(kind: String) -> [[String: Any]] {
        guard let offered else { return [] }
        return [["id": "one", "title": "Offered", "sizeBytes": (try? offered.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0]]
    }

    func openItem(id: String) -> ItemSource? {
        guard id == "one", let offered else { return nil }
        let size = Int64((try? offered.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        return ItemSource(name: offered.lastPathComponent, sizeBytes: size, file: offered, artwork: offeredArtwork)
    }

    func destination(forName name: String, kind: String) -> URL? {
        folder.appendingPathComponent("incoming-" + (name as NSString).lastPathComponent)
    }

    func accepted(_ file: URL, kind: String, artwork: URL?, meme: [String: Any]?, playlist: PeerPlaylist?,
                  title: String?, artist: String?) {
        let cover = artwork.flatMap { try? Data(contentsOf: $0) }
        lock.withLock {
            landed.append(file)
            if let cover { covers.append(cover) }
            if let meme { memeObjects.append(meme) }
            if let playlist { lists.append(playlist) }
        }
    }

    /// A landed file with these bytes, by its path.
    func existing(sizeBytes: Int64, sha256: Data, kind: String) -> String? {
        received.first { (try? Data(contentsOf: $0)).map { Data(SHA256.hash(data: $0)) == sha256 } ?? false }?.path
    }

    private var asked: [String] = []
    var requestedPlaylists: [String] { lock.withLock { asked } }

    func sendPlaylist(_ id: String, to fingerprint: String) -> Bool {
        lock.withLock { asked.append(id) }
        return true
    }

    func reuse(_ id: String, playlist: PeerPlaylist?) {
        guard let playlist else { return }
        lock.withLock { reuses.append((id, playlist)) }
    }
    func downloadRequested(url: String, mediaKind: String, from peerName: String) {}
}

extension CoreChecks {

    static func waitFor(_ seconds: Double = 15, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while !condition() && Date() < deadline { Thread.sleep(forTimeInterval: 0.02) }
        return condition()
    }

    mutating func checkPairingLoopback() throws {
        print("pairing, two Macs on loopback")
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("arsivinyo-pairing-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        let track = scratch.appendingPathComponent("Track.m4a")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        try Self.pattern(700_000, 29).write(to: track)
        let cover = scratch.appendingPathComponent("cover.jpg")
        try Self.pattern(5_000, 11).write(to: cover)

        func service(_ name: String, offering file: URL? = nil, artwork: URL? = nil) throws -> (PairingService, LoopbackContent) {
            let dir = scratch.appendingPathComponent(name)
            let identity = try DeviceIdentity(directory: dir.appendingPathComponent("pairing"))
            identity.deviceName = name
            let content = LoopbackContent(folder: dir.appendingPathComponent("files"), offering: file, artwork: artwork)
            return (PairingService(identity: identity, registry: PeerRegistry(url: dir.appendingPathComponent("peers.json")),
                                   content: content), content)
        }

        let (alice, aliceFiles) = try service("Alice")
        let (bob, bobContent) = try service("Bob", offering: track, artwork: cover)
        try alice.start()
        try bob.start()
        defer { alice.stop(); bob.stop() }

        let (mallory, _) = try service("Mallory")
        try mallory.start()
        mallory.connect(host: "127.0.0.1", port: alice.port)
        Thread.sleep(forTimeInterval: 1)
        check(alice.sessions.isEmpty && alice.pendingCode.isEmpty, "a device that is not paired is turned away")
        mallory.stop()

        alice.beginPairing()
        bob.beginPairing()
        bob.connect(host: "127.0.0.1", port: alice.port)
        check(Self.waitFor { !alice.pendingCode.isEmpty && !bob.pendingCode.isEmpty },
              "both devices reach a code")
        check(alice.pendingCode == bob.pendingCode && alice.pendingCode.count == 6, "the same six digits on both")
        check(alice.pendingCode != Pairing.code(fromDigest: Data(SHA256Hash.of(Pairing.codeInput(alice.identity.publicKey, bob.identity.publicKey)))),
              "and not the keys-only code a man in the middle could aim at")
        check(bob.confirmPairing() && alice.confirmPairing(), "confirming pairs them")
        check(Self.waitFor { alice.sessions.count == 1 && bob.sessions.count == 1 }, "each has a connection to the other")

        // Alice asks for Bob's track; Bob offers it; it lands verified.
        var listed: [[String: Any]] = []
        alice.sessions.first?.onListing = { _, items in listed = items }
        _ = alice.sessions.first?.requestListing()
        check(Self.waitFor { listed.count == 1 }, "a listing crosses")
        _ = alice.sessions.first?.requestItem(id: "one")
        check(Self.waitFor(30) { aliceFiles.received.count == 1 }, "a requested track crosses")
        check(aliceFiles.received.first.flatMap { try? Data(contentsOf: $0) } == Self.pattern(700_000, 29),
              "whole, and verified before it was kept")
        check(aliceFiles.receivedArtwork.first == Self.pattern(5_000, 11), "with its cover, which lives beside the file")

        // A meme, with its labels by name and its source.
        let item = MemeLibrary.Item(id: "m1", kind: "video", isPrivate: false, path: track.path, vaultId: nil, sha256: "",
                                    source: .init(platform: "twitter", account: "futbolcaps", caption: "bizim laubalilik seviyesi"),
                                    tags: [], people: [], addedAt: 0, taggedAt: 1)
        let meme = MemeTransfer.encode(item: item, tags: [("laubalilik", [.action, .vibe])], people: ["Arda Turan"])
        var memeSent = false
        bob.sessions.first?.onSent = { memeSent = true }
        _ = bob.sessions.first?.send(ItemSource(name: "arda.mp4", sizeBytes: 700_000, file: track, meme: meme), kind: "meme")
        check(Self.waitFor(30) { memeSent && aliceFiles.receivedMemes.count == 1 }, "a meme crosses")
        let decoded = aliceFiles.receivedMemes.first.map(MemeTransfer.decode)
        check(decoded?.tags.first?.0 == "laubalilik" && decoded?.tags.first?.1 == [.action, .vibe]
              && decoded?.people == ["Arda Turan"] && decoded?.source?.caption == "bizim laubalilik seviyesi",
              "with its tags, facets, people and caption")

        // A track sent as part of a playlist lands with it; the same bytes sent again are not
        // sent twice, and join the playlist instead.
        let song = scratch.appendingPathComponent("road.mp3")
        try Self.pattern(300_000, 7).write(to: song)
        var songsSent = 0
        bob.sessions.first?.onSent = { songsSent += 1 }
        let landedBefore = aliceFiles.received.count
        _ = bob.sessions.first?.send(ItemSource(name: "road.mp3", sizeBytes: 300_000, file: song,
                                                playlist: PeerPlaylist(name: "Road trip")))
        check(Self.waitFor(30) { songsSent == 1 && aliceFiles.receivedPlaylists.last == PeerPlaylist(name: "Road trip") },
              "a track sent in a playlist arrives with its playlist's name")
        _ = bob.sessions.first?.send(ItemSource(name: "road.mp3", sizeBytes: 300_000, file: song,
                                                playlist: PeerPlaylist(name: "", favorites: true)))
        check(Self.waitFor(30) { songsSent == 2 }
              && aliceFiles.received.count == landedBefore + 1
              && aliceFiles.reused.last?.1 == PeerPlaylist(name: "", favorites: true),
              "one already there is not sent again: it joins the playlist, here Favorites")

        // Alice asks Bob for a whole playlist: Bob's content is asked to send it.
        let bobFiles = bobContent
        _ = alice.sessions.first?.requestPlaylist(id: "road-trip")
        check(Self.waitFor { bobFiles.requestedPlaylists == ["road-trip"] }, "a playlist asked for reaches the other device")

        // A restart of Bob: same identity, a fresh session certificate, no ceremony.
        bob.stop()
        check(Self.waitFor { alice.sessions.isEmpty }, "a device that goes away is no longer listed")
        let (bobAgain, _) = try service("Bob", offering: track)
        try bobAgain.start()
        defer { bobAgain.stop() }
        bobAgain.connect(host: "127.0.0.1", port: alice.port)
        check(Self.waitFor { alice.sessions.count == 1 && bobAgain.sessions.count == 1 } && alice.pendingCode.isEmpty,
              "a paired device reconnects without being asked to pair again")

        // Both reach for each other at once.
        alice.connect(host: "127.0.0.1", port: bobAgain.port)
        bobAgain.connect(host: "127.0.0.1", port: alice.port)
        Thread.sleep(forTimeInterval: 1.5)
        // Waited for as a whole: on the way there each side can briefly hold a different one.
        check(Self.waitFor { alice.sessions.count == 1 && bobAgain.sessions.count == 1
                             && alice.sessions.first?.link.role != bobAgain.sessions.first?.link.role },
              "two connections at once settle on one, the same one at both ends")
    }
}

import CryptoKit
enum SHA256Hash {
    static func of(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }
}

extension CoreChecks {
    /// The Mac's side of `scripts/check-pairing-interop.sh`: pairs with the phone's own
    /// Kotlin PairingService, running on the JVM here, and trades with it.
    static func runInterop(_ dir: URL) async -> Int32 {
        var runner = CoreChecks()
        print("pairing with the phone's own code")
        func file(_ name: String) -> URL { dir.appendingPathComponent(name) }
        func text(_ name: String) -> String? { try? String(contentsOf: file(name), encoding: .utf8) }

        guard waitFor(120, { text("phone-ready") != nil }), let port = text("phone-ready").flatMap({ UInt16($0) }) else {
            print("  FAIL  the phone side never started")
            return 1
        }
        do {
            let work = dir.appendingPathComponent("mac")
            let identity = try DeviceIdentity(directory: work.appendingPathComponent("pairing"))
            identity.deviceName = "Mac"
            let track = work.appendingPathComponent("Mac Track.m4a")
            try pattern(234_567, 37).write(to: track)
            let macCover = work.appendingPathComponent("mac-cover.jpg")
            try pattern(3_000, 5).write(to: macCover)
            let content = LoopbackContent(folder: work.appendingPathComponent("files"), offering: track, artwork: macCover)
            let mac = PairingService(identity: identity, registry: PeerRegistry(url: work.appendingPathComponent("peers.json")),
                                     content: content)
            try mac.start()
            defer { mac.stop() }
            mac.beginPairing()
            mac.connect(host: "127.0.0.1", port: port)

            runner.check(waitFor(30) { !mac.pendingCode.isEmpty && text("phone-code") != nil },
                         "both reach a code through the phone's own ceremony")
            runner.check(mac.pendingCode == text("phone-code"), "the Mac and the phone show the same six digits (\(mac.pendingCode))")
            try mac.pendingCode.write(to: file("mac-code"), atomically: true, encoding: .utf8)
            runner.check(mac.confirmPairing(), "and confirming pairs them")
            runner.check(waitFor { mac.sessions.count == 1 }, "the phone lets the paired Mac in")

            var listing: [[String: Any]] = []
            mac.sessions.first?.onListing = { _, items in listing = items }
            _ = mac.sessions.first?.requestListing()
            runner.check(waitFor { listing.first?["id"] as? String == "p1" }, "the phone's listing reads here")
            _ = mac.sessions.first?.requestItem(id: "p1")
            runner.check(waitFor(60) { content.received.count == 1 }
                         && content.received.first.flatMap { try? Data(contentsOf: $0) } == pattern(345_678, 31),
                         "a track from the phone arrives whole")
            runner.check(content.receivedArtwork.first == pattern(4_000, 7), "with the phone's cover")

            var sent = false
            mac.sessions.first?.onSent = { sent = true }
            let source = ItemSource(name: track.lastPathComponent, sizeBytes: 234_567, file: track, artwork: macCover)
            runner.check(mac.sessions.first?.send(source) == true && waitFor(60) { sent }, "a track goes to the phone")
            let expected = SHA256Hash.of(pattern(234_567, 37)).map { String(format: "%02x", $0) }.joined()
            runner.check(waitFor(30) { text("phone-received-sha256") == expected }, "and the phone verified it")
            let coverHash = SHA256Hash.of(pattern(3_000, 5)).map { String(format: "%02x", $0) }.joined()
            runner.check(waitFor(10) { text("phone-received-artwork-sha256") == coverHash }, "with the Mac's cover")

            _ = mac.sessions.first?.requestDownload(url: "https://example.com/song", mediaKind: "audio")
            runner.check(waitFor(30) { text("phone-link") == "audio https://example.com/song" }, "a link reaches the phone, as audio")

            // A meme, both ways, with its labels by name.
            let arda = work.appendingPathComponent("arda.mp4")
            try pattern(20_000, 19).write(to: arda)
            let labels = try JSONSerialization.data(withJSONObject: [
                "kind": "video",
                "source": ["platform": "twitter", "caption": "bizim laubalilik seviyesi", "savedAt": 0],
                "tags": [["name": "laubalılık", "facets": ["vibe", "action"]]],
                "people": [["name": "Arda Turan",
                            "signatures": [FaceMath.encode((0..<FaceMath.size).map { Float($0 % 7) / 7 })]]],
            ] as [String: Any])
            sent = false
            runner.check(mac.sessions.first?.send(ItemSource(name: "arda.mp4", sizeBytes: 20_000, file: arda, meme: labels),
                                                  kind: "meme") == true && waitFor(60) { sent },
                         "a meme goes to the phone")
            runner.check(waitFor(30) { text("phone-meme") == "video|laubalılık:vibe,action|Arda Turan|bizim laubalilik seviyesi|faces:1" },
                         "and arrives there as a meme, tags, facets, people, caption and a face signature intact")
            try "".write(to: file("mac-wants-meme"), atomically: true, encoding: .utf8)
            runner.check(waitFor(60) { content.receivedMemes.count == 1 }, "a meme from the phone arrives as a meme")
            let back = content.receivedMemes.first.map(MemeTransfer.decode)
            runner.check(back?.tags.first?.0 == "rahat" && back?.tags.first?.1 == [.vibe] && back?.people == ["Fatih Terim"]
                         && content.received.last.flatMap { try? Data(contentsOf: $0) } == pattern(12_000, 29),
                         "whole, with the phone's tags and people")
        } catch {
            runner.check(false, "threw: \(error)")
        }
        try? (runner.failures == 0 ? "" : "\(runner.failures) failed").write(to: file("mac-failures"), atomically: true, encoding: .utf8)
        try? "".write(to: file("mac-done"), atomically: true, encoding: .utf8)
        return runner.failures == 0 ? 0 : 1
    }
}
