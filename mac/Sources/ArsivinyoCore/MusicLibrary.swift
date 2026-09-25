import AVFoundation
import Foundation

/// The music library: audio files in a folder, and an index of what they are.
///
/// The index has the phone's shape — `songs`, `playlists`, with Favorites as a reserved
/// playlist whose id is `favorites` — so a phone backup's `music-index` blob maps onto it
/// without translation. The files themselves are plain audio in `~/Music/Arsivinyo`, where
/// any other app can see them; music is deliberately not private, and not in the vault.
///
/// The index is reconciled against the folder on every load, the way the phone reconciles
/// against MediaStore: a file deleted in Finder drops out, and its playlist entries go with
/// it, rather than leaving rows that play nothing.
public final class MusicLibrary: @unchecked Sendable {

    public struct Track: Identifiable, Hashable, Sendable {
        public let id: String
        public var title: String
        public var artist: String
        public var fileName: String
        public var durationSeconds: Double
        public var sizeBytes: Int64
        public var artworkFileName: String?
        public var createdAt: Date
        /// Set on a track made by rendering a preset, with the track it was made from. The
        /// phone's field names, so a backup keeps them either way.
        public var presetId: String? = nil
        public var sourceSongId: String? = nil
    }

    public struct Playlist: Identifiable, Hashable, Sendable {
        public let id: String
        public var name: String
        public var trackIds: [String]
        public var isSystem: Bool { id == MusicLibrary.favoritesId }
    }

    public enum Failure: Error, CustomStringConvertible {
        case notFound
        case reserved
        case io(String)
        public var description: String {
            switch self {
            case .notFound: return "That track is not in the library."
            case .reserved: return "Favorites cannot be renamed or deleted."
            case .io(let why): return why
            }
        }
    }

    /// The same id the phone reserves, so a restored backup's Favorites stays Favorites.
    public static let favoritesId = "favorites"

    /// Where the audio files are. Its own lock, so reading it never waits on, or deadlocks
    /// with, the index lock that `relocate` holds while it moves files.
    public var musicFolder: URL { folderLock.withLock { folder } }
    private var folder: URL
    private let folderLock = NSLock()
    private let indexURL: URL
    private let artworkFolder: URL
    private let guardLock = NSLock()

    public init(musicFolder: URL, supportFolder: URL) {
        self.folder = musicFolder
        self.indexURL = supportFolder.appendingPathComponent("index.json")
        self.artworkFolder = supportFolder.appendingPathComponent("artwork")
    }

    /// Moves the library's files to another folder and uses it from then on.
    ///
    /// The index names files relative to the folder, so pointing it somewhere new without
    /// moving them would leave every track missing. Only the library's own files move;
    /// anything else in the old folder stays put.
    ///
    /// All or nothing: a name already taken in the new folder stops it before anything
    /// moves, and a move that fails partway puts back what had moved.
    public func relocate(to destination: URL) throws {
        try guardLock.withLock {
            let source = musicFolder
            guard source.standardizedFileURL != destination.standardizedFileURL else { return }
            let fm = FileManager.default
            let names = readIndex().0.map(\.fileName)
                .filter { fm.fileExists(atPath: source.appendingPathComponent($0).path) }

            let taken = names.filter { fm.fileExists(atPath: destination.appendingPathComponent($0).path) }
            guard taken.isEmpty else {
                throw Failure.io(String(localized: "The new folder already has files named \(taken.joined(separator: ", ")). Nothing was moved."))
            }

            try fm.createDirectory(at: destination, withIntermediateDirectories: true)
            var moved: [String] = []
            do {
                for name in names {
                    try fm.moveItem(at: source.appendingPathComponent(name),
                                    to: destination.appendingPathComponent(name))
                    moved.append(name)
                }
            } catch {
                for name in moved {
                    try? fm.moveItem(at: destination.appendingPathComponent(name),
                                     to: source.appendingPathComponent(name))
                }
                throw Failure.io(String(localized: "The library could not be moved: \(error.localizedDescription)"))
            }
            folderLock.withLock { folder = destination }
        }
    }

    public func fileURL(for track: Track) -> URL {
        musicFolder.appendingPathComponent(track.fileName)
    }
    public func artworkURL(for track: Track) -> URL? {
        track.artworkFileName.map { artworkFolder.appendingPathComponent($0) }
    }

    // MARK: - Reading

    /// Tracks and playlists, reconciled against what is actually in the folder.
    public func load() -> (tracks: [Track], playlists: [Playlist]) {
        guardLock.withLock {
            var (tracks, playlists) = readIndex()
            let present = tracks.filter {
                FileManager.default.fileExists(atPath: musicFolder.appendingPathComponent($0.fileName).path)
            }
            var changed = present.count != tracks.count
            let ids = Set(present.map(\.id))
            for index in playlists.indices {
                let kept = playlists[index].trackIds.filter(ids.contains)
                if kept.count != playlists[index].trackIds.count {
                    playlists[index].trackIds = kept
                    changed = true
                }
            }
            tracks = present
            if changed { writeIndex(tracks, playlists) }
            return (tracks, playlists)
        }
    }

    // MARK: - Adding

    /// Moves a finished download into the library, reading its tags for title and artist.
    ///
    /// `title` and `artist` from the downloader win over the file's own tags, which are
    /// often missing or wrong on what comes off a site.
    @discardableResult
    public func adopt(_ file: URL, title: String? = nil, artist: String? = nil,
                      artwork: URL? = nil, move: Bool = true,
                      presetId: String? = nil, sourceSongId: String? = nil) async throws -> Track {
        try FileManager.default.createDirectory(at: musicFolder, withIntermediateDirectories: true)
        let destination = uniqueDestination(for: file.lastPathComponent)
        do {
            if move {
                try FileManager.default.moveItem(at: file, to: destination)
            } else {
                try FileManager.default.copyItem(at: file, to: destination)
            }
        } catch {
            throw Failure.io("Could not put \(file.lastPathComponent) in the library: \(error.localizedDescription)")
        }

        let tags = await Self.readTags(destination)
        let id = UUID().uuidString.lowercased()
        var artworkName: String?
        if let artwork, FileManager.default.fileExists(atPath: artwork.path) {
            artworkName = try? storeArtwork(from: artwork, id: id)
        } else if let embedded = tags.artwork {
            artworkName = try? storeArtwork(data: embedded, id: id, ext: "jpg")
        }

        let attributes = try? FileManager.default.attributesOfItem(atPath: destination.path)
        let track = Track(
            id: id,
            title: title?.nonEmpty ?? tags.title?.nonEmpty
                ?? destination.deletingPathExtension().lastPathComponent,
            artist: artist?.nonEmpty ?? tags.artist ?? "",
            fileName: destination.lastPathComponent,
            durationSeconds: tags.duration,
            sizeBytes: (attributes?[.size] as? NSNumber)?.int64Value ?? 0,
            artworkFileName: artworkName,
            createdAt: Date(),
            presetId: presetId,
            sourceSongId: sourceSongId)

        guardLock.withLock {
            var (tracks, playlists) = readIndex()
            tracks.insert(track, at: 0)
            writeIndex(tracks, playlists)
        }
        return track
    }

    /// Copies audio in from elsewhere; the originals stay where they are.
    public func importFiles(_ files: [URL]) async -> (added: [Track], failed: [String]) {
        var added: [Track] = []
        var failed: [String] = []
        for file in files {
            do {
                added.append(try await adopt(file, move: false))
            } catch {
                failed.append(file.lastPathComponent)
            }
        }
        return (added, failed)
    }

    // MARK: - Changing

    public func remove(_ id: String, deleteFile: Bool = true) throws {
        try guardLock.withLock {
            var (tracks, playlists) = readIndex()
            guard let track = tracks.first(where: { $0.id == id }) else { throw Failure.notFound }
            tracks.removeAll { $0.id == id }
            for index in playlists.indices { playlists[index].trackIds.removeAll { $0 == id } }
            writeIndex(tracks, playlists)
            if deleteFile {
                // To the Trash rather than gone: a track is the user's file, in a folder
                // they can see, and the Trash is where a Mac puts things it deletes.
                try? FileManager.default.trashItem(at: musicFolder.appendingPathComponent(track.fileName),
                                                   resultingItemURL: nil)
            }
            if let art = track.artworkFileName {
                try? FileManager.default.removeItem(at: artworkFolder.appendingPathComponent(art))
            }
        }
    }

    public func rename(_ id: String, title: String) throws {
        try mutateTrack(id) { $0.title = title }
    }

    public func setFavorite(_ id: String, _ favorite: Bool) {
        guardLock.withLock {
            var (tracks, playlists) = readIndex()
            guard let index = playlists.firstIndex(where: { $0.id == Self.favoritesId }) else { return }
            playlists[index].trackIds.removeAll { $0 == id }
            if favorite { playlists[index].trackIds.insert(id, at: 0) }
            writeIndex(tracks, playlists)
        }
    }

    @discardableResult
    public func createPlaylist(named name: String) -> Playlist {
        guardLock.withLock {
            var (tracks, playlists) = readIndex()
            let playlist = Playlist(id: UUID().uuidString.lowercased(), name: name, trackIds: [])
            playlists.append(playlist)
            writeIndex(tracks, playlists)
            return playlist
        }
    }

    public func renamePlaylist(_ id: String, to name: String) throws {
        guard id != Self.favoritesId else { throw Failure.reserved }
        try guardLock.withLock {
            var (tracks, playlists) = readIndex()
            guard let index = playlists.firstIndex(where: { $0.id == id }) else { throw Failure.notFound }
            playlists[index].name = name
            writeIndex(tracks, playlists)
        }
    }

    public func deletePlaylist(_ id: String) throws {
        guard id != Self.favoritesId else { throw Failure.reserved }
        guardLock.withLock {
            var (tracks, playlists) = readIndex()
            playlists.removeAll { $0.id == id }
            writeIndex(tracks, playlists)
        }
    }

    public func add(_ trackIds: [String], to playlistId: String) {
        guardLock.withLock {
            var (tracks, playlists) = readIndex()
            guard let index = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
            for id in trackIds where !playlists[index].trackIds.contains(id) {
                playlists[index].trackIds.append(id)
            }
            writeIndex(tracks, playlists)
        }
    }

    public func remove(_ trackIds: [String], from playlistId: String) {
        guardLock.withLock {
            var (tracks, playlists) = readIndex()
            guard let index = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
            playlists[index].trackIds.removeAll(where: trackIds.contains)
            writeIndex(tracks, playlists)
        }
    }

    private func mutateTrack(_ id: String, _ body: (inout Track) -> Void) throws {
        try guardLock.withLock {
            var (tracks, playlists) = readIndex()
            guard let index = tracks.firstIndex(where: { $0.id == id }) else { throw Failure.notFound }
            body(&tracks[index])
            writeIndex(tracks, playlists)
        }
    }

    // MARK: - Files

    /// "Song.m4a", then "Song (2).m4a": never overwrite a file already in the folder.
    private func uniqueDestination(for name: String) -> URL {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var candidate = musicFolder.appendingPathComponent(name)
        var counter = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            let next = ext.isEmpty ? "\(base) (\(counter))" : "\(base) (\(counter)).\(ext)"
            candidate = musicFolder.appendingPathComponent(next)
            counter += 1
        }
        return candidate
    }

    private func storeArtwork(from file: URL, id: String) throws -> String {
        try FileManager.default.createDirectory(at: artworkFolder, withIntermediateDirectories: true)
        let ext = file.pathExtension.isEmpty ? "jpg" : file.pathExtension
        let name = "\(id).\(ext)"
        try FileManager.default.copyItem(at: file, to: artworkFolder.appendingPathComponent(name))
        return name
    }

    private func storeArtwork(data: Data, id: String, ext: String) throws -> String {
        try FileManager.default.createDirectory(at: artworkFolder, withIntermediateDirectories: true)
        let name = "\(id).\(ext)"
        try data.write(to: artworkFolder.appendingPathComponent(name), options: .atomic)
        return name
    }

    private struct Tags {
        var title: String?
        var artist: String?
        var duration: Double = 0
        var artwork: Data?
    }

    private static func readTags(_ url: URL) async -> Tags {
        let asset = AVURLAsset(url: url)
        var tags = Tags()
        tags.duration = (try? await asset.load(.duration).seconds) ?? 0
        if !tags.duration.isFinite { tags.duration = 0 }
        let metadata = (try? await asset.load(.commonMetadata)) ?? []
        for item in metadata {
            switch item.commonKey {
            case .commonKeyTitle: tags.title = try? await item.load(.stringValue)
            case .commonKeyArtist: tags.artist = try? await item.load(.stringValue)
            case .commonKeyArtwork: tags.artwork = try? await item.load(.dataValue)
            default: break
            }
        }
        return tags
    }

    // MARK: - The index

    private func readIndex() -> ([Track], [Playlist]) {
        guard let data = try? Data(contentsOf: indexURL),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            var none: [Playlist] = []
            Self.ensureFavorites(&none)
            return ([], none)
        }

        let tracks = (root["songs"] as? [[String: Any]] ?? []).compactMap { s -> Track? in
            guard let id = s["id"] as? String, let fileName = s["fileName"] as? String else { return nil }
            return Track(
                id: id,
                title: s["title"] as? String ?? fileName,
                artist: s["artist"] as? String ?? "",
                fileName: fileName,
                durationSeconds: s["durationSec"] as? Double ?? 0,
                sizeBytes: (s["sizeBytes"] as? NSNumber)?.int64Value ?? 0,
                artworkFileName: (s["thumbFileName"] as? String)?.nonEmpty,
                createdAt: Date(timeIntervalSince1970: (s["createdAt"] as? Double ?? 0) / 1000),
                presetId: (s["presetId"] as? String)?.nonEmpty,
                sourceSongId: (s["sourceSongId"] as? String)?.nonEmpty)
        }
        var playlists = (root["playlists"] as? [[String: Any]] ?? []).compactMap { p -> Playlist? in
            guard let id = p["id"] as? String else { return nil }
            return Playlist(id: id, name: p["name"] as? String ?? "", trackIds: p["songIds"] as? [String] ?? [])
        }
        Self.ensureFavorites(&playlists)
        return (tracks, playlists)
    }

    /// Favorites exists in every read, not only once `load()` has written it.
    ///
    /// Otherwise favouriting before the first load looks the playlist up, does not find it,
    /// and quietly does nothing — the seed hit exactly that. The phone solves the same
    /// problem the same way, with `ensureFavoritesLocked`.
    private static func ensureFavorites(_ playlists: inout [Playlist]) {
        if !playlists.contains(where: { $0.id == favoritesId }) {
            playlists.insert(Playlist(id: favoritesId, name: "Favorites", trackIds: []), at: 0)
        }
    }

    private func writeIndex(_ tracks: [Track], _ playlists: [Playlist]) {
        let now = Date().timeIntervalSince1970 * 1000
        let root: [String: Any] = [
            "version": 1,
            // The phone's field names and units — milliseconds for timestamps, seconds for
            // duration — so a backup moves between them without translation.
            "songs": tracks.map { t -> [String: Any] in
                var s: [String: Any] = [
                    "id": t.id, "title": t.title, "artist": t.artist, "fileName": t.fileName,
                    "durationSec": t.durationSeconds, "sizeBytes": t.sizeBytes,
                    "createdAt": t.createdAt.timeIntervalSince1970 * 1000, "updatedAt": now,
                ]
                if let art = t.artworkFileName { s["thumbFileName"] = art }
                if let preset = t.presetId { s["presetId"] = preset }
                if let source = t.sourceSongId { s["sourceSongId"] = source }
                return s
            },
            "playlists": playlists.map { p -> [String: Any] in
                ["id": p.id, "name": p.name, "songIds": p.trackIds,
                 "system": p.isSystem, "updatedAt": now]
            },
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
        else { return }
        try? FileManager.default.createDirectory(at: indexURL.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        try? data.write(to: indexURL, options: .atomic)
    }
}

extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
