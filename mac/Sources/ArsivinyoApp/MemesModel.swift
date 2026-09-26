import AVFoundation
import AppKit
import ArsivinyoCore
import Foundation
import UniformTypeIdentifiers

extension AppModel {
    typealias Meme = MemeLibrary.Item

    func refreshMemes() {
        do {
            memeSnapshot = try memes.load()
            memeProblem = nil
        } catch {
            memeProblem = String(describing: error)
        }
        scanFaces()
    }

    // MARK: Faces

    /// Scans whatever has not been scanned, one meme at a time, off the main actor. Private
    /// memes are scanned only while the vault is open; the rest wait for it.
    func scanFaces() {
        guard facesRemaining == nil else { return }
        let pending = MemeLibrary.needingScan(memeSnapshot).filter { !$0.isPrivate || vaultUnlocked }
        guard !pending.isEmpty else { return }
        if faceScanner == nil {
            guard let folder = FaceScanner.modelsFolder() else {
                faceProblem = FaceScanner.Failure.modelsMissing.description
                return
            }
            do {
                faceScanner = try FaceScanner(folder: folder)
            } catch {
                faceProblem = String(describing: error)
                return
            }
        }
        guard let scanner = faceScanner else { return }
        facesRemaining = pending.count
        let memes = memes, vault = vault
        let vaultTypes = Dictionary(uniqueKeysWithValues: vaultItems.map { ($0.id, $0.contentType) })
        Task.detached(priority: .utility) { [weak self] in
            for (done, item) in pending.enumerated() {
                let faces: [MemeLibrary.ScannedFace]
                do {
                    faces = try await Self.scan(item, scanner: scanner, vault: vault, vaultTypes: vaultTypes)
                } catch {
                    // A file that cannot be read is recorded as scanned with nothing found,
                    // rather than tried again on every launch.
                    faces = []
                }
                try? memes.record(faces: faces, for: item.id)
                await MainActor.run { self?.facesRemaining = pending.count - done - 1 }
            }
            await MainActor.run {
                guard let self else { return }
                self.facesRemaining = nil
                self.memeSnapshot = (try? memes.load()) ?? self.memeSnapshot
                // Anything that arrived during the scan.
                self.scanFaces()
            }
        }
    }

    nonisolated private static func scan(_ item: Meme, scanner: FaceScanner, vault: Vault,
                                         vaultTypes: [String: String]) async throws -> [MemeLibrary.ScannedFace] {
        if let file = item.fileURL {
            return try await scanner.scan(file: file, kind: item.kind)
        }
        guard let vaultId = item.vaultId else { return [] }
        let reader = try vault.reader(for: vaultId)
        if item.isVideo {
            // From the vault through the same loader playback uses: no plaintext on disk.
            let loader = VaultAssetLoader(reader: reader, contentType: vaultTypes[vaultId] ?? UTType.mpeg4Movie.identifier)
            return try await scanner.scan(asset: loader.makeAsset(id: vaultId))
        }
        return scanner.scan(imageData: try reader.read(offset: 0, length: Int(reader.size)))
    }

    func confirmFace(_ faceId: String) {
        do { try memes.confirm(face: faceId) } catch { memeProblem = String(describing: error) }
        refreshMemes()
    }

    func rejectFace(_ faceId: String) {
        do { try memes.reject(face: faceId) } catch { memeProblem = String(describing: error) }
        refreshMemes()
    }

    func nameFaces(_ faceIds: Set<String>, as name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do { try memes.name(faces: faceIds, as: trimmed) } catch { memeProblem = String(describing: error) }
        refreshMemes()
    }

    /// A video or image download joins the collection with where it came from, and asks for
    /// tags if that is how this Mac is set up.
    func adoptMeme(_ file: URL, payload: JSONValue) {
        let source = payload["source"]
        let meme = MemeLibrary.Source(
            platform: source?["platform"]?.string, account: source?["account"]?.string,
            accountName: source?["accountName"]?.string, caption: source?["caption"]?.string,
            url: source?["url"]?.string, postedAt: source?["postedAt"]?.double)
        do {
            let item = try memes.add(file, source: meme)
            refreshMemes()
            if askForMemeTags && item.isUntagged { tagPrompts.append(item.id) }
        } catch MemeLibrary.Failure.notMedia {
            // Something that is neither video nor image; it stays a plain download.
        } catch {
            memeProblem = String(describing: error)
        }
    }

    func importMemes(_ urls: [URL]) {
        let result = memes.importFiles(urls, into: downloadDirectory)
        refreshMemes()
        if !result.failed.isEmpty {
            memeProblem = String(localized: "Could not import: \(result.failed.joined(separator: ", "))")
        }
    }

    func meme(_ id: String) -> Meme? { memeSnapshot.items.first { $0.id == id } }

    // MARK: Labels

    @discardableResult
    func createTag(_ name: String, facets: [MemeLibrary.Facet] = []) -> MemeLibrary.Tag? {
        defer { refreshMemes() }
        return try? memes.tag(named: name, facets: facets)
    }

    @discardableResult
    func createPerson(_ name: String) -> MemeLibrary.Person? {
        defer { refreshMemes() }
        return try? memes.person(named: name)
    }

    func labelMemes(_ ids: Set<String>, addTags: Set<String> = [], removeTags: Set<String> = [],
                    addPeople: Set<String> = [], removePeople: Set<String> = [], markTagged: Bool = true) {
        do {
            try memes.label(ids, addTags: addTags, removeTags: removeTags, addPeople: addPeople,
                            removePeople: removePeople, markTagged: markTagged)
        } catch {
            memeProblem = String(describing: error)
        }
        refreshMemes()
    }

    func setFacets(_ facets: [MemeLibrary.Facet], of tagId: String) {
        try? memes.setFacets(facets, of: tagId)
        refreshMemes()
    }

    func renameTag(_ tagId: String, to name: String) {
        try? memes.rename(tag: tagId, to: name)
        refreshMemes()
    }

    func deleteTag(_ tagId: String) {
        try? memes.delete(tag: tagId)
        refreshMemes()
    }

    // MARK: Files

    func setPrivate(_ ids: Set<String>, _ makePrivate: Bool) {
        guard vaultUnlocked else {
            showUnlockSheet = true
            return
        }
        for id in ids {
            do {
                if makePrivate {
                    try memes.makePrivate(id)
                } else {
                    try memes.makePublic(id, into: downloadDirectory)
                }
            } catch {
                memeProblem = String(describing: error)
            }
        }
        refreshMemes()
        refreshVault()
    }

    func removeMemes(_ ids: Set<String>) {
        for id in ids {
            do { try memes.remove(id) } catch { memeProblem = String(describing: error) }
        }
        tagPrompts.removeAll { ids.contains($0) }
        refreshMemes()
        refreshVault()
    }

    /// Sends a meme to a paired device with its labels. Private ones do not travel.
    func sendMeme(_ id: String, to fingerprint: String) {
        guard let item = meme(id), !item.isPrivate, let file = item.fileURL,
              let session = devices?.service.session(for: fingerprint) else { return }
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        let labels = MemeLibrary.labels(of: item, in: memeSnapshot)
        let meme = MemeTransfer.encode(item: item, tags: labels.tags, people: labels.people,
                                       signatures: MemeLibrary.signatures(of: labels.people, in: memeSnapshot))
        if !session.send(ItemSource(name: file.lastPathComponent, sizeBytes: size, file: file, meme: meme), kind: "meme") {
            devices?.message = String(localized: "Wait for the transfer in progress to finish.")
        }
    }
}

/// Thumbnails for the grid, made once per meme and kept beside the index. Only for memes that
/// are not private: a private meme's picture is not written anywhere outside the vault.
enum MemeThumbnails {
    static func folder(_ support: URL) -> URL { support.appendingPathComponent("memes/thumbs", isDirectory: true) }

    static func image(for item: MemeLibrary.Item, support: URL) async -> NSImage? {
        guard !item.isPrivate, let file = item.fileURL else { return nil }
        let cached = folder(support).appendingPathComponent(item.id + ".jpg")
        if let image = NSImage(contentsOf: cached) { return image }
        let made: CGImage?
        if item.isVideo {
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: file))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 480, height: 480)
            made = try? await generator.image(at: CMTime(seconds: 0.5, preferredTimescale: 600)).image
        } else {
            let source = CGImageSourceCreateWithURL(file as CFURL, nil)
            made = source.flatMap {
                CGImageSourceCreateThumbnailAtIndex($0, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                            kCGImageSourceThumbnailMaxPixelSize: 480,
                                                            kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary)
            }
        }
        guard let made else { return nil }
        let rep = NSBitmapImageRep(cgImage: made)
        if let data = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.8]) {
            try? FileManager.default.createDirectory(at: folder(support), withIntermediateDirectories: true)
            try? FileManager.default.writePrivately(data, to: cached)
        }
        return NSImage(cgImage: made, size: .zero)
    }
}
