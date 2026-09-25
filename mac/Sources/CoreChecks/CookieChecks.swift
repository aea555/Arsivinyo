import Foundation
import ArsivinyoCore

/// The cookie store: encrypted at rest, chosen by the link, and never left in the clear.
extension CoreChecks {

    mutating func checkCookies() throws {
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-checks-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }
        let keybox = Keybox(directory: scratch, params: .fast,
                            keychainService: "com.arsivinyo.mac.checks.\(UUID().uuidString)")
        try keybox.create(passphrase: "a correct horse battery staple")

        print("cookies")
        let session = "SID-the-signed-in-session"
        let jar = Data("""
            # Netscape HTTP Cookie File
            .youtube.com\tTRUE\t/\tTRUE\t2000000000\tSID\t\(session)
            #HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t2000000000\tHSID\tx

            """.utf8)
        let source = scratch.appendingPathComponent("cookies.txt")
        try jar.write(to: source)
        let notJar = scratch.appendingPathComponent("notes.txt")
        try Data("just some notes\n".utf8).write(to: notJar)

        let store = CookieStore(directory: scratch, keybox: keybox)
        check(CookieStore.scope(forCookies: jar) == .platform("youtube"),
              "a file is filed under the site its cookies are for")
        check((try? store.importFile(notJar, into: .platform("youtube"), name: "main")) == nil,
              "a file that is not cookies.txt is refused at import")
        check((try? store.importFile(source, into: .platform("youtube"), name: "../escape")) == nil,
              "a name that could leave the folder is refused")

        try store.importFile(source, into: .platform("youtube"), name: "main")
        let stored = scratch.appendingPathComponent("cookies/youtube/main.enc")
        let onDisk = try Data(contentsOf: stored)
        check(onDisk.range(of: Data(session.utf8)) == nil, "the stored profile does not contain the session")
        let mode = (try FileManager.default.attributesOfItem(atPath: stored.path)[.posixPermissions]
                    as? NSNumber)?.intValue ?? 0
        check(mode & 0o077 == 0, "and is readable only by its owner")
        check(store.profiles().first?.isDefault == true, "the first profile for a site is its default")

        // Renamed into another profile's place, it must not open as that profile.
        try store.importFile(source, into: .platform("youtube"), name: "second")
        let second = scratch.appendingPathComponent("cookies/youtube/second.enc")
        try FileManager.default.removeItem(at: second)
        try FileManager.default.copyItem(at: stored, to: second)
        check((try? store.plaintext(.platform("youtube"), name: "second")) == nil,
              "a profile's file moved into another's place does not open")
        try store.remove(.platform("youtube"), name: "second")

        guard let runtime = try store.runtimeCookies(for: "https://www.youtube.com/watch?v=x") else {
            check(false, "a YouTube link gets the YouTube profile")
            return
        }
        check(try Data(contentsOf: runtime.file) == jar && runtime.platform == "youtube",
              "a YouTube link gets the YouTube profile, decrypted")
        check(try store.runtimeCookies(for: "https://example.org/x") == nil,
              "a link to another site gets nothing")
        store.discardRuntime(runtime.file)
        check(!FileManager.default.fileExists(atPath: runtime.file.path),
              "the decrypted copy is gone once the download is done")

        // A domain the engine does not know, matched by the longest suffix.
        try store.importFile(source, into: .domain("example.com"), name: "main")
        try store.importFile(source, into: .domain("music.example.com"), name: "main")
        let custom = try store.runtimeCookies(for: "https://a.music.example.com/track")
        check(custom != nil && custom?.platform == nil, "a custom domain's profile is used for its links")
        if let custom { store.discardRuntime(custom.file) }

        let leftover = try store.runtimeCookies(for: "https://youtu.be/x")
        _ = CookieStore(directory: scratch, keybox: keybox)
        check(leftover.map { !FileManager.default.fileExists(atPath: $0.file.path) } == true,
              "a copy left by a crash is swept at the next start")

        check(CookieStore.shouldRetrySignedOut(code: "DOWNLOAD_FAILED", message: "", platform: "youtube"),
              "a refused YouTube download is tried again signed out")
        check(!CookieStore.shouldRetrySignedOut(code: "DOWNLOAD_FAILED", message: "", platform: "instagram"),
              "an Instagram one is not, where signed out is a login wall")
        check(!CookieStore.shouldRetrySignedOut(code: "FILE_TOO_LARGE", message: "", platform: "youtube"),
              "nor one the engine refused for its size")

        keybox.lock()
        check(try store.runtimeCookies(for: "https://youtube.com/x") == nil,
              "locked, a download runs signed out instead of failing")
        check(store.profiles().count == 3, "and the list still shows, without the key")
    }
}
