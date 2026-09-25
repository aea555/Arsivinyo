import Foundation
import ArsivinyoCore

/// The key box and the vault, held to the same properties as the Qt app's.
///
/// Most of what matters here is negative: the object must not contain the file, the listing
/// must not contain the title, a damaged listing must not read as empty, and changing a
/// passphrase must not change the key the content is under.
extension CoreChecks {

    mutating func checkKeyboxAndVault() throws {
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-checks-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }
        let service = "com.arsivinyo.mac.checks.\(UUID().uuidString)"

        func keybox() -> Keybox {
            Keybox(directory: scratch, params: .fast, keychainService: service)
        }
        func contains(_ url: URL, _ needle: String) -> Bool {
            guard let data = try? Data(contentsOf: url) else { return false }
            return data.range(of: Data(needle.utf8)) != nil
        }

        print("key box")
        let first = keybox()
        check(!first.isConfigured, "a fresh install has no key box")
        check((try? first.create(passphrase: "short")) == nil, "a short passphrase is refused")
        try first.create(passphrase: "a correct horse battery staple")
        check(first.isUnlocked, "creating one leaves it unlocked")
        let cookieKey = try first.key(for: .cookies)
        check(try first.key(for: .vault) != cookieKey, "each purpose gets a different key")

        let keyboxFile = scratch.appendingPathComponent("keybox.json")
        let mode = (try FileManager.default.attributesOfItem(atPath: keyboxFile.path)[.posixPermissions]
                    as? NSNumber)?.intValue ?? 0
        check(mode & 0o077 == 0, "keybox.json is readable only by its owner")

        let second = keybox()
        check(second.isConfigured && !second.isUnlocked, "a restart finds it, still locked")
        check((try? second.unlock(passphrase: "not it")) == nil, "the wrong passphrase is refused")
        do {
            try second.unlock(passphrase: "wrong one")
        } catch let error as Keybox.Failure {
            check(error == .wrongPassphrase, "and is reported as wrong, not as damage")
        }
        try second.unlock(passphrase: "a correct horse battery staple")
        check(try second.key(for: .cookies) == cookieKey, "the same key comes back after a restart")

        try second.changePassphrase(from: "a correct horse battery staple",
                                    to: "a different long passphrase")
        check(try second.key(for: .cookies) == cookieKey,
              "changing the passphrase does not change the content key")
        let third = keybox()
        check((try? third.unlock(passphrase: "a correct horse battery staple")) == nil,
              "the old passphrase no longer works")
        try third.unlock(passphrase: "a different long passphrase")

        // Remember on this Mac: a random key in a Keychain item of the checks' own.
        try third.setRemembered(true)
        check(third.isRemembered, "remembering adds a Keychain slot")
        let fourth = keybox()
        check(fourth.unlockFromKeychain(), "a restart opens from the Keychain without asking")
        check(try fourth.key(for: .cookies) == cookieKey, "and reaches the same key")
        // Its Keychain item deleted behind the app's back, as Keychain Access can.
        fourth.deleteKeychainItem()
        let orphaned = keybox()
        check(!orphaned.unlockFromKeychain() && !orphaned.isRemembered,
              "a slot whose Keychain item is gone is dropped, not shown as remembering")
        try orphaned.unlock(passphrase: "a different long passphrase")
        try orphaned.setRemembered(true)
        try fourth.setRemembered(false)
        check(!keybox().unlockFromKeychain(), "forgetting removes it")
        fourth.deleteKeychainItem()

        let recovery = scratch.appendingPathComponent("recovery.key")
        try fourth.exportRecoveryKey(to: recovery)
        let fifth = keybox()
        try fifth.unlock(recoveryKey: try Data(contentsOf: recovery))
        check(try fifth.key(for: .cookies) == cookieKey, "a recovery key opens it")

        print("vault")
        let vaultRoot = scratch.appendingPathComponent("vault")
        let vault = Vault(root: vaultRoot, keybox: fifth)

        // Several segments' worth, so the streaming path is what is exercised.
        let marker = "THE-CONTENTS-OF-A-PRIVATE-FILE"
        var payload = Data()
        while payload.count < 5 * 1024 * 1024 {
            payload.append(Data(marker.utf8))
            payload.append(Data(repeating: 0x78, count: 97))
        }
        let source = scratch.appendingPathComponent("A Very Private Recording.mp4")
        try payload.write(to: source)

        let item = try vault.add(source)
        check(item.id.count == 32, "its id is random, not the file name")
        check(item.title == "A Very Private Recording", "the title is kept")
        check(item.sizeBytes == Int64(payload.count), "and the size")
        check(item.isVideo, "and it knows it is a video")

        let object = vault.objectURL(for: item.id)
        check(!contains(object, marker), "the object does not contain the file's contents")
        let index = vaultRoot.appendingPathComponent("index.enc")
        check(!contains(index, "A Very Private Recording"), "the listing does not contain the title")
        check(!contains(index, "mp4"), "nor the extension")
        let indexSize = (try FileManager.default.attributesOfItem(atPath: index.path)[.size]
                         as? NSNumber)?.intValue ?? 0
        check(indexSize % 4096 == 56, "and its size is padded, so it does not count the items")

        let exported = scratch.appendingPathComponent("exported.mp4")
        try vault.export(item.id, to: exported)
        check(try Data(contentsOf: exported) == payload, "an item exports byte for byte")

        // Playback reads: a jump to the end, then back — what a demuxer does.
        let reader = try vault.reader(for: item.id)
        check(reader.size == Int64(payload.count), "a reader reports the plaintext size")
        let tail = try reader.read(offset: reader.size - 4096, length: 4096)
        check(tail == payload.suffix(4096), "and reads the end correctly")
        let boundary = try reader.read(offset: 1_048_520 - 10, length: 20)
        check(boundary == payload.subdata(in: (1_048_520 - 10)..<(1_048_520 + 10)),
              "across the first segment boundary, which is short by the header")

        let reopened = Vault(root: vaultRoot, keybox: fifth)
        check(try reopened.items().first?.title == "A Very Private Recording",
              "a fresh instance reads the encrypted listing")

        // A damaged listing must never become an empty one.
        var bent = try Data(contentsOf: index)
        bent[bent.count - 1] ^= 1
        try bent.write(to: index)
        let damaged = Vault(root: vaultRoot, keybox: fifth)
        check((try? damaged.items()) == nil, "a damaged listing does not read")
        check(damaged.isUnreadable, "and is reported as unreadable, not as empty")
        check((try? damaged.add(source)) == nil, "so an add is refused rather than overwriting it")
        check(FileManager.default.fileExists(atPath: object.path),
              "and the file nobody can list is still on disk")

        try FileManager.default.removeItem(at: index)
        let fresh = Vault(root: vaultRoot, keybox: fifth)
        let second_ = try fresh.add(source, removeOriginal: true)
        check(!FileManager.default.fileExists(atPath: source.path), "moving in removes the original")
        // A delete that fails half way must not leave the listing naming a file that is
        // gone. Make the listing unwritable so the second step fails: with the listing
        // written first the file survives, and with the file removed first it does not.
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: vaultRoot.path)
        check((try? fresh.remove(second_.id)) == nil, "a delete whose listing cannot be written fails")
        check(FileManager.default.fileExists(atPath: fresh.objectURL(for: second_.id).path),
              "and leaves the file in place, still listed, rather than a listing naming nothing")
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: vaultRoot.path)

        try fresh.remove(second_.id)
        check(try fresh.items().isEmpty, "an item deletes")
        check(!FileManager.default.fileExists(atPath: fresh.objectURL(for: second_.id).path),
              "with its object")

        fifth.lock()
        check((try? fifth.key(for: .vault)) == nil, "locking forgets the key")

        // Locked is not damaged. If a locked read marked the listing unreadable, every
        // write would be refused after the next unlock and the vault would look broken.
        let locked = Vault(root: vaultRoot, keybox: fifth)
        var lockedError: Error?
        do { _ = try locked.items() } catch { lockedError = error }
        check(lockedError != nil, "a locked vault refuses to list")
        check(!locked.isUnreadable, "and is not marked unreadable for it")
        try fifth.unlock(passphrase: "a different long passphrase")
        check((try? locked.items()) != nil, "so it lists again once unlocked")
    }
}
