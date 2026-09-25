import CryptoKit
import Foundation
import ArsivinyoCore

/// The Mac against `shared/crypto/VECTORS.json` — the same file the Android app reads.
///
/// The Mac app has to open a vault and a backup the phone wrote. Compiling the same C++ is
/// not evidence of that; reproducing the recorded bytes is. Where the vectors carry a
/// ciphertext Tink produced, this seals with the recorded header and compares byte for byte,
/// which checks both directions rather than only that a round trip closes.
///
/// An executable rather than an XCTest suite, because XCTest ships with Xcode and the core
/// has to stay verifiable without it. Same shape as the C++ tests it replaces: a line per
/// check, a non-zero exit if any failed.
@main
struct CoreChecks {

    // MARK: - Harness

    var failures = 0

    mutating func check(_ ok: Bool, _ what: String) {
        print(ok ? "  ok    \(what)" : "  FAIL  \(what)")
        if !ok { failures += 1 }
    }

    private static func unhex(_ s: String) -> Data {
        var out = Data(capacity: s.count / 2)
        var index = s.startIndex
        while index < s.endIndex {
            let next = s.index(index, offsetBy: 2)
            out.append(UInt8(s[index..<next], radix: 16)!)
            index = next
        }
        return out
    }

    private static func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    private static func sha256(_ d: Data) -> String { hex(Data(SHA256.hash(data: d))) }

    /// `java.util.Random`, so a megabyte of plaintext need not live in the vectors file.
    private struct JavaRandom {
        private var seed: UInt64

        init(_ s: Int64) { seed = (UInt64(bitPattern: s) ^ 0x5DEECE66D) & ((1 << 48) - 1) }

        private mutating func next(_ bits: Int) -> Int32 {
            seed = (seed &* 0x5DEECE66D &+ 0xB) & ((1 << 48) - 1)
            return Int32(truncatingIfNeeded: Int64(bitPattern: seed >> (48 - UInt64(bits))))
        }

        mutating func bytes(_ count: Int) -> Data {
            var out = Data(count: count)
            var i = 0
            while i < count {
                var value = next(32)
                var n = min(count - i, 4)
                while n > 0 {
                    out[i] = UInt8(truncatingIfNeeded: value)
                    value >>= 8
                    i += 1
                    n -= 1
                }
            }
            return out
        }
    }

    /// The repository, found by walking up from this file.
    static var repositoryRoot: URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: dir.appendingPathComponent("shared/crypto/VECTORS.json").path) {
            let parent = dir.deletingLastPathComponent()
            if parent == dir { return dir }
            dir = parent
        }
        return dir
    }

    /// Walk up for the repository root, the way the Kotlin suite does.
    private static func loadVectors() -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(
            atPath: dir.appendingPathComponent("shared/crypto/VECTORS.json").path
        ) {
            let parent = dir.deletingLastPathComponent()
            if parent == dir {
                FileHandle.standardError.write(
                    Data("cannot find shared/crypto/VECTORS.json above \(#filePath)\n".utf8))
                exit(2)
            }
            dir = parent
        }
        let url = dir.appendingPathComponent("shared/crypto/VECTORS.json")
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }

    // MARK: - Checks

    static func main() async {
        // Writes shared/crypto/fixtures/mac-written.avsbck, which the phone's
        // CrossPlatformBackupTest reads. Committed; written again only when asked.
        if CommandLine.arguments.contains("--write-backup-fixture") {
            do {
                try await writeBackupFixture()
                exit(0)
            } catch {
                FileHandle.standardError.write(Data("could not write the fixture: \(error)\n".utf8))
                exit(1)
            }
        }

        if let flag = CommandLine.arguments.firstIndex(of: "--interop"), flag + 1 < CommandLine.arguments.count {
            exit(await runInterop(URL(fileURLWithPath: CommandLine.arguments[flag + 1])))
        }

        var runner = CoreChecks()
        do {
            try runner.run()
            try runner.checkKeyboxAndVault()
            try await runner.checkPlayback()
            try await runner.checkMusic()
            try runner.checkCookies()
            try await runner.checkPresets()
            try await runner.checkBackup()
            try await runner.checkPhoneBackup()
            try runner.checkPairingVectors()
            try runner.checkPairingLoopback()
            try runner.checkMemes()
            await runner.checkEngine()
        } catch {
            print("  FAIL  threw: \(error)")
            runner.failures += 1
        }
        print("\n\(runner.failures == 0 ? "the pinned vectors hold on this Mac" : "FAILURES")")
        exit(runner.failures == 0 ? 0 : 1)
    }

    private mutating func run() throws {
        let vectors = Self.loadVectors()
        func list(_ name: String) -> [[String: Any]] { vectors[name] as! [[String: Any]] }
        func map(_ name: String) -> [String: Any] { vectors[name] as! [String: Any] }

        print("argon2id")
        for c in list("argon2id") {
            let params = Crypto.Argon2idParams(
                memoryKiB: UInt32(c["memoryKiB"] as! Int),
                iterations: UInt32(c["iterations"] as! Int),
                parallelism: UInt32(c["parallelism"] as! Int),
                version: UInt32(c["version"] as! Int))
            // The recorded UTF-8 bytes, not a re-encoded string: re-encoding here could
            // make the same mistake twice and hide an encoding difference.
            let out = try Crypto.argon2id(
                secret: Self.unhex(c["passwordUtf8"] as! String),
                salt: Self.unhex(c["salt"] as! String),
                params: params,
                outputCount: c["outLength"] as! Int)
            check(Self.hex(out) == c["out"] as! String, c["why"] as! String)
        }

        print("hkdf-sha256")
        for c in list("hkdf_sha256") {
            let salt = c["salt"] as? String
            let out = try Crypto.hkdfSHA256(
                ikm: Self.unhex(c["ikm"] as! String),
                salt: salt.map(Self.unhex),
                info: Data((c["info"] as! String).utf8),
                outputCount: c["outLength"] as! Int)
            check(Self.hex(out) == c["out"] as! String, c["why"] as! String)
        }

        print("avsbck key hierarchy")
        let subkeys = map("avsbck_subkeys")
        let master = Self.unhex(subkeys["masterKey"] as! String)
        check(Self.hex(try Crypto.backupVerifier(master: master)) == subkeys["verifier"] as! String,
              "the verifier label")
        for (sectionId, expected) in (subkeys["sections"] as! [String: String])
            .sorted(by: { $0.key < $1.key }) {
            check(Self.hex(try Crypto.backupSectionKey(master: master, sectionId: sectionId))
                    == expected, "section key: \(sectionId)")
        }

        print("streaming aead, against Tink's own output")
        for c in list("aead_stream") {
            let key = Self.unhex(c["key"] as! String)
            let aad = c["associatedData"] as! String
            let spec = c["plaintext"] as! [String: Any]
            let length = spec["length"] as! Int
            var random = JavaRandom(Int64(spec["seed"] as! Int))
            let plaintext = random.bytes(length)

            let sealed = try Crypto.sealWithRecordedHeader(
                plaintext, key: key, associatedData: aad,
                headerSalt: Self.unhex(c["headerSalt"] as! String),
                noncePrefix: Self.unhex(c["noncePrefix"] as! String))

            check(sealed.count == c["ciphertextLength"] as! Int
                    && Self.sha256(sealed) == c["ciphertextSha256"] as! String,
                  "\(length) bytes: \(c["why"] as! String)")
            check(try Crypto.open(sealed, key: key, associatedData: aad) == plaintext,
                  "\(length) bytes: decrypts back")
        }

        print("vault index")
        let index = map("vault_index")
        let dek = Self.unhex(index["dek"] as! String)
        let indexKey = try Crypto.purposeKey(master: dek, purpose: .vaultIndex)
        check(Self.hex(indexKey) == index["indexKey"] as! String,
              "the key label matches the phone's")

        var paddingOk = true
        for c in index["padding"] as! [[String: Any]] {
            let length = c["length"] as! Int
            var random = JavaRandom(Int64(length))
            let content = random.bytes(length)
            let padded = Crypto.pad(content)
            if padded.count != c["paddedLength"] as! Int { paddingOk = false }
            if Self.sha256(padded) != c["paddedSha256"] as! String { paddingOk = false }
            if try Crypto.unpad(padded) != content { paddingOk = false }
        }
        check(paddingOk, "padding matches, and unpads back")

        let sealedListing = Data(base64Encoded: index["sealed"] as! String)!
        let padded = try Crypto.open(sealedListing, key: indexKey,
                                     associatedData: index["associatedData"] as! String)
        let listing = String(data: try Crypto.unpad(padded), encoding: .utf8)
        check(listing == index["listing"] as? String,
              "a vault listing sealed by the phone opens on the Mac")

        print("refusals")
        let key = try Crypto.randomBytes(32)
        let sealed = try Crypto.seal(Data("something private".utf8), key: key,
                                     associatedData: "vault")
        var caught = 0
        // A wrong key, the wrong associated data, and a flipped bit.
        if (try? Crypto.open(sealed, key: Crypto.randomBytes(32), associatedData: "vault")) == nil {
            caught += 1
        }
        if (try? Crypto.open(sealed, key: key, associatedData: "music")) == nil { caught += 1 }
        var bent = sealed
        bent[bent.count - 1] ^= 1
        if (try? Crypto.open(bent, key: key, associatedData: "vault")) == nil { caught += 1 }
        check(caught == 3, "a wrong key, the wrong associated data and a flipped bit are refused")
    }

    /// The download engine, which is a Python child process rather than anything Swift.
    ///
    /// Skipped rather than failed when yt-dlp has not been fetched: the vectors above are
    /// the contract, and this is an integration check that needs a working directory set up.
    private mutating func checkEngine() async {
        print("engine")
        guard let layout = EngineClient.Layout.developmentFromSource() else {
            print("  skip  yt-dlp is not fetched; run mac/scripts/fetch-engine.sh")
            return
        }

        let client = await EngineClient(layout: layout)
        do {
            try await client.start()
        } catch {
            check(false, "the engine starts: \(error)")
            return
        }

        var version: String?
        var impersonation: Bool?
        for await event in await client.perform("version").events {
            if case .finished(.success(let payload)) = event { version = payload["ytDlp"]?.string }
        }
        for await event in await client.perform("diagnostics").events {
            if case .finished(.success(let payload)) = event {
                impersonation = payload["impersonationRuntimeAvailable"]?.bool
            }
        }
        let source = await client.ytDlpSource
        await client.stop()

        check(version != nil, "the engine answers with a yt-dlp version (\(version ?? "none"))")
        // Only the bootstrap reports this. Started any other way, a downloaded yt-dlp is
        // never put on the path, and updating would do nothing at all.
        check(source != nil, "it started through the bootstrap, so an update can take effect (\(source ?? "no report"))")

        // Every key the app sends has to be one host.py actually reads, because it silently
        // ignores the rest. This is what caught "Audio" downloading video.
        let hostSource = (try? String(contentsOf: layout.engine.appendingPathComponent("host.py"),
                                      encoding: .utf8)) ?? ""
        let arguments = EngineClient.downloadArguments(
            url: "https://example.com/x", outputDirectory: URL(fileURLWithPath: "/tmp"),
            audioOnly: true, cookiesDirectory: URL(fileURLWithPath: "/tmp"), cookieProfile: "main")
        let unread = arguments.keys.filter { !hostSource.contains("req.get(\"\($0)\")")
                                             && !hostSource.contains("req[\"\($0)\"]") }
        check(unread.isEmpty, "every download key is one the host reads (unread: \(unread.sorted()))")
        check(arguments["audioOnly"] as? Bool == true, "and audio is asked for as audioOnly")
        // What stops sites refusing a downloader outright. The phone ships wheels for it;
        // on this Mac it is a pip install, and without it the app is the lesser one.
        check(impersonation == true, "impersonation is available, as it is on the phone")
    }
}
