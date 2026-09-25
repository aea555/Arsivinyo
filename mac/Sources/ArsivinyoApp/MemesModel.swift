import AVFoundation
import AppKit
import ArsivinyoCore
import Foundation

extension AppModel {
    typealias Meme = MemeLibrary.Item

    func refreshMemes() {
        do {
            memeSnapshot = try memes.load()
            memeProblem = nil
        } catch {
            memeProblem = String(describing: error)
        }
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
        let meme = MemeTransfer.encode(item: item, tags: labels.tags, people: labels.people)
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
