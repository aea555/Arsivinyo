import ArsivinyoCore
import Foundation
import LocalAuthentication
import SwiftUI
import UserNotifications

/// Which part of the app the source list is showing.
///
/// Settings is absent on purpose: on a Mac it is a window behind ⌘, not a place you
/// navigate to.
enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case download
    case library
    case watch
    case memes
    case vault
    case devices

    var id: String { rawValue }

    var title: String {
        switch self {
        case .download: return String(localized: "Download")
        case .library: return String(localized: "Music")
        case .watch: return String(localized: "Watch")
        // "Mimler" in Turkish: the TDK's word for memes. The literal plural means something else.
        case .memes: return String(localized: "Memes")
        case .vault: return String(localized: "Vault")
        case .devices: return String(localized: "Devices")
        }
    }

    /// SF Symbols, so the source list looks like every other Mac app's.
    var symbol: String {
        switch self {
        case .download: return "arrow.down.circle"
        case .library: return "music.note.list"
        case .watch: return "play.tv"
        case .memes: return "theatermasks"
        case .vault: return "lock.shield"
        case .devices: return "laptopcomputer.and.iphone"
        }
    }

    var shortcut: KeyEquivalent {
        switch self {
        case .download: return "1"
        case .library: return "2"
        case .watch: return "3"
        case .memes: return "4"
        case .vault: return "5"
        case .devices: return "6"
        }
    }
}

/// What the source list has selected: a section, or one playlist inside Music.
///
/// Playlists sit in the source list the way they do in Music.app, rather than behind a
/// menu inside the Music pane — they are places you go, so they belong where places are.
enum SidebarSelection: Hashable {
    case section(AppSection)
    case playlist(String)
}

/// App-wide state.
///
/// Observable rather than a pile of singletons so the views can read it directly and the
/// menu commands can drive the same state the sidebar does.
@MainActor
@Observable
final class AppModel {
    /// Reopens where you left off, as a Mac app does.
    var selection: SidebarSelection = .section(AppSection(
        rawValue: UserDefaults.standard.string(forKey: "section") ?? "") ?? .download) {
        didSet { UserDefaults.standard.set(section.rawValue, forKey: "section") }
    }

    /// The section the selection is in. A playlist is inside Music.
    var section: AppSection {
        get {
            switch selection {
            case .section(let section): return section
            case .playlist: return .library
            }
        }
        set { selection = .section(newValue) }
    }

    var selectedPlaylistId: String? {
        if case .playlist(let id) = selection { return id }
        return nil
    }

    /// The engine and the queue live here rather than in the download view, so switching
    /// to another section does not throw away what is running.
    let engine: EngineClient
    let queue: DownloadQueue

    /// Why the engine is not usable, when it is not. Shown rather than swallowed: a
    /// download button that quietly does nothing is the worst of both.
    private(set) var engineProblem: String?

    var downloadDirectory: URL {
        didSet {
            UserDefaults.standard.set(downloadDirectory.path, forKey: "downloadDirectory")
            queue.destination = downloadDirectory
        }
    }

    /// A notice when a download ends while you are in another app. Not while you are
    /// looking at this one: the queue is already telling you.
    var notifyWhenDone: Bool = UserDefaults.standard.object(forKey: "notifyWhenDone") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(notifyWhenDone, forKey: "notifyWhenDone")
            if notifyWhenDone { requestNotificationPermission() }
        }
    }

    /// Application Support/Arsivinyo, or its override.
    let supportFolder: URL

    init() {
        // Overridable, so a seeded vault can be looked at without touching the real one or
        // the real Keychain item. The Qt app had the same escape hatch.
        let environment = ProcessInfo.processInfo.environment
        let support = environment["ARSIVINYO_DATA_DIR"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("Arsivinyo", isDirectory: true)
        let keychainService = environment["ARSIVINYO_KEYCHAIN_SERVICE"] ?? "com.arsivinyo.mac.keybox"
        supportFolder = support
        keybox = Keybox(directory: support, keychainService: keychainService)
        vault = Vault(root: support.appendingPathComponent("vault"), keybox: keybox)
        // Its own Keychain key, as the memes index has: an add-on's URL can hold an account token.
        watch = WatchService(library: WatchLibrary(file: support.appendingPathComponent("watch/library.enc")) {
            try MemeDeviceKey.load(service: keychainService + ".watch", account: "watch-library")
        })
        memes = MemeLibrary(support: support.appendingPathComponent("memes"), vault: vault, keybox: keybox) {
            try MemeDeviceKey.load(service: keychainService + ".memes")
        }
        cookies = CookieStore(directory: support, keybox: keybox)
        presets = PresetStore(directory: support)
        renderScratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-renders", isDirectory: true)

        let musicFolder = environment["ARSIVINYO_MUSIC_DIR"].map { URL(fileURLWithPath: $0) }
            ?? UserDefaults.standard.string(forKey: "musicDirectory").map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.urls(for: .musicDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("Arsivinyo", isDirectory: true)
        library = MusicLibrary(musicFolder: musicFolder,
                               supportFolder: support.appendingPathComponent("music"))
        player = Player(library: library)

        let stored = UserDefaults.standard.string(forKey: "downloadDirectory")
        let destination = stored.map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Downloads/Arsivinyo")
        downloadDirectory = destination

        // While the app runs from the repository this finds the fetched engine; a bundled
        // build will carry its own copy and this is where that choice will be made.
        let layout = EngineClient.Layout.developmentFromSource()
        engine = EngineClient(layout: layout ?? .init(
            python: URL(fileURLWithPath: "/usr/bin/python3"),
            engine: URL(fileURLWithPath: "/nonexistent"),
            ytDlp: URL(fileURLWithPath: "/nonexistent")))
        queue = DownloadQueue(engine: engine, destination: destination)

        if layout == nil {
            engineProblem = "The download engine is not set up. Run mac/scripts/fetch-engine.sh."
        } else {
            do {
                try engine.start()
            } catch {
                engineProblem = String(describing: error)
            }
        }

        // "Remember on this Mac" means never being asked.
        // With Touch ID asked for first, the remembered key waits for a fingerprint instead.
        if !askTouchID, keybox.unlockFromKeychain() { vaultDidUnlock() }
        refreshMusic()
        refreshSecurity()
        refreshPresets()
        refreshMemes()

        do {
            // Asked from a connection thread, so nothing of the main actor's: a backup a
            // device sends waits in Downloads, where a restore looks first.
            let devices = try DevicesModel(support: support, library: library) {
                FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            }
            // A track from a paired device goes into the library like a download does.
            devices.content.onTrackArrived = { [weak self] file, artwork in
                Task { @MainActor in
                    guard let self else { return }
                    defer { if let artwork { try? FileManager.default.removeItem(at: artwork) } }
                    do {
                        try await self.library.adopt(file, artwork: artwork)
                        self.refreshMusic()
                    } catch {
                        self.musicProblem = String(describing: error)
                    }
                }
            }
            devices.onAutoDownload = { [weak self] url, audio in self?.queue.enqueue(url: url, audioOnly: audio) }
            // A meme from a paired device joins the collection with its labels merged by name.
            devices.content.onMemeArrived = { [weak self] file, object in
                Task { @MainActor in
                    guard let self else { return }
                    do {
                        let target = MemeLibrary.unique(file.lastPathComponent, in: self.downloadDirectory)
                        try FileManager.default.createDirectory(at: self.downloadDirectory, withIntermediateDirectories: true)
                        try FileManager.default.moveItem(at: file, to: target)
                        let decoded = MemeTransfer.decode(object)
                        try self.memes.receive(target, source: decoded.source, tags: decoded.tags, people: decoded.people,
                                               signatures: MemeTransfer.signatures(object))
                        self.refreshMemes()
                    } catch {
                        self.memeProblem = String(describing: error)
                    }
                }
            }
            devices.start()
            self.devices = devices
        } catch {
            devicesProblem = String(describing: error)
        }

        // Signed in where there is a profile for the site, and only while the key is here.
        let cookies = cookies
        queue.cookiesFor = { url in try? cookies.runtimeCookies(for: url) }
        queue.discardCookies = { cookies.discardRuntime($0) }
        queue.onSettled = { [weak self] item in self?.notifySettled(item) }
        if notifyWhenDone { requestNotificationPermission() }
        refreshEngineVersion()

        // An audio download goes into the library the moment it lands, as on the phone.
        queue.onFinished = { [weak self] item, path, payload in
            guard let self else { return }
            guard item.audioOnly else {
                self.adoptMeme(path, payload: payload)
                return
            }
            let thumb = payload["thumbnail_path"]?.string.map { URL(fileURLWithPath: $0) }
            Task {
                do {
                    let track = try await self.library.adopt(path, title: payload["title"]?.string,
                                                 artist: payload["artist"]?.string ?? payload["uploader"]?.string,
                                                 artwork: thumb)
                    self.refreshMusic()
                    await self.autoApplyPresets(to: track)
                } catch {
                    self.musicProblem = String(describing: error)
                }
            }
        }
    }

    /// Favorites is stored under its English name, the way the phone stores it, because that
    /// is data a backup carries. What is shown is the word in the reader's own language.
    static func displayName(of playlist: MusicLibrary.Playlist) -> String {
        playlist.isSystem ? String(localized: "Favorites") : playlist.name
    }

    // MARK: - Notifications

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func notifySettled(_ item: DownloadItem) {
        guard notifyWhenDone, !NSApplication.shared.isActive else { return }
        let content = UNMutableNotificationContent()
        switch item.state {
        case .finished:
            content.title = String(localized: "Downloaded")
        case .failed(let why):
            content.title = String(localized: "Download failed")
            content.subtitle = why
        default:
            return
        }
        // The title the site gave, or its host until it did. Never the saved file's path.
        content.body = item.title
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: item.id.uuidString, content: content, trigger: nil))
    }

    // MARK: - The engine

    /// What an update is doing, while it is.
    private(set) var ytDlpWorking: String?
    /// How the last change went, or why it did not.
    private(set) var ytDlpMessage: String?
    private(set) var ytDlpReleases: [String] = []

    func refreshEngineVersion() {
        Task { for await _ in engine.perform("version").events {} }
    }

    func loadYtDlpReleases() {
        Task {
            for await event in engine.perform("listYtDlpVersions", ["limit": 12]).events {
                if case .finished(.success(let payload)) = event {
                    ytDlpReleases = payload["versions"]?.array?.compactMap(\.string) ?? []
                }
            }
        }
    }

    /// Fetches a yt-dlp and restarts the engine on it.
    ///
    /// - Parameter version: nil for the newest, "bundled" for the one fetched with the app,
    ///   or a release number.
    func switchYtDlp(to version: String?) {
        // A restart ends whatever the engine is doing, and a download cut off halfway is
        // worse than an old extractor for another minute.
        guard queue.active.isEmpty else {
            ytDlpMessage = String(localized: "Wait for the downloads to finish first.")
            return
        }
        ytDlpMessage = nil
        ytDlpWorking = String(localized: "Checking")
        var arguments: [String: Any] = ["root": engine.layoutRoot.path]
        if let version { arguments["version"] = version }
        Task {
            var outcome: Result<JSONValue, EngineClient.Event.EngineError>?
            for await event in engine.perform("updateYtDlp", arguments).events {
                switch event {
                case .progress(let stage, let percent, _):
                    let label = stage == "downloading" ? String(localized: "Downloading")
                        : stage == "installing" ? String(localized: "Installing")
                        : String(localized: "Checking")
                    ytDlpWorking = percent.map { "\(label) \(Int($0))%" } ?? label
                case .finished(let result):
                    outcome = result
                default:
                    break
                }
            }
            ytDlpWorking = nil
            switch outcome {
            case .success(let payload) where payload["status"]?.string == "current":
                ytDlpMessage = String(localized: "Already in use.")
            case .success:
                do {
                    try engine.restart()
                    refreshEngineVersion()
                } catch {
                    ytDlpMessage = String(describing: error)
                }
            case .failure(let error):
                ytDlpMessage = error.description
            case nil:
                ytDlpMessage = String(localized: "The engine stopped.")
            }
        }
    }

    // MARK: - Folders

    /// Returns a message to show, or nil once the library is in its new place.
    func moveMusicLibrary(to folder: URL) -> String? {
        do {
            player.stop()
            try library.relocate(to: folder)
            UserDefaults.standard.set(folder.path, forKey: "musicDirectory")
            refreshMusic()
            return nil
        } catch {
            return String(describing: error)
        }
    }

    // MARK: - Security

    let cookies: CookieStore
    private(set) var cookieProfiles: [CookieStore.Profile] = []
    private(set) var isRemembered = false
    private(set) var hasRecoveryKey = false

    /// The key box is not observable, so what the settings show is copied out after every
    /// change that goes through here.
    func refreshSecurity() {
        isRemembered = keybox.isRemembered
        hasRecoveryKey = keybox.hasRecoveryKey
        cookieProfiles = cookies.profiles()
    }

    /// Each returns a message to show, or nil on success.
    /// Asks for Touch ID before the remembered key is used, rather than opening at launch.
    ///
    /// A gate in the app, not a key bound to the fingerprint: the key stays in the login
    /// Keychain as "Remember" keeps it. Binding it to Touch ID needs the data protection
    /// Keychain, which needs the app signed with a developer certificate.
    var askTouchID: Bool = UserDefaults.standard.bool(forKey: "askTouchID") {
        didSet { UserDefaults.standard.set(askTouchID, forKey: "askTouchID") }
    }

    var touchIDAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    /// Returns a message to show, or nil once the vault is open.
    func unlockWithTouchID() async -> String? {
        let context = LAContext()
        context.localizedCancelTitle = String(localized: "Use Passphrase")
        do {
            let ok = try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                                                      localizedReason: String(localized: "open the vault"))
            guard ok else { return String(localized: "Touch ID did not recognise you.") }
        } catch {
            return error.localizedDescription
        }
        guard keybox.unlockFromKeychain() else {
            refreshSecurity()
            return String(localized: "The key this Mac remembered is gone. Use the passphrase.")
        }
        vaultDidUnlock()
        return nil
    }

    func setRemembered(_ remember: Bool) -> String? {
        defer { refreshSecurity() }
        do { try keybox.setRemembered(remember); return nil } catch { return String(describing: error) }
    }

    func changePassphrase(from old: String, to new: String) async -> String? {
        let keybox = keybox
        do {
            try await Task.detached { try keybox.changePassphrase(from: old, to: new) }.value
            return nil
        } catch {
            return String(describing: error)
        }
    }

    func exportRecoveryKey(to url: URL) -> String? {
        defer { refreshSecurity() }
        do { try keybox.exportRecoveryKey(to: url); return nil } catch { return String(describing: error) }
    }

    func unlockVault(recoveryKeyAt url: URL) -> String? {
        do {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            try keybox.unlock(recoveryKey: Data(contentsOf: url))
            vaultDidUnlock()
            return nil
        } catch {
            return String(describing: error)
        }
    }

    // MARK: - Cookies

    func importCookies(_ url: URL, into scope: CookieStore.Scope, name: String) -> String? {
        defer { refreshSecurity() }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do { try cookies.importFile(url, into: scope, name: name); return nil } catch { return String(describing: error) }
    }

    func setDefaultCookies(_ profile: CookieStore.Profile) {
        try? cookies.setDefault(profile.scope, name: profile.name)
        refreshSecurity()
    }

    func removeCookies(_ profile: CookieStore.Profile) {
        try? cookies.remove(profile.scope, name: profile.name)
        refreshSecurity()
    }

    // MARK: - Music actions

    func refreshMusic() {
        let loaded = library.load()
        tracks = loaded.tracks
        playlists = loaded.playlists
    }

    func importMusic(_ urls: [URL]) {
        Task {
            let result = await library.importFiles(urls)
            refreshMusic()
            if !result.failed.isEmpty {
                musicProblem = String(localized: "Could not import: \(result.failed.joined(separator: ", "))")
            }
        }
    }

    func toggleFavorite(_ track: MusicLibrary.Track) {
        library.setFavorite(track.id, !favorites.contains(track.id))
        refreshMusic()
    }

    func removeTracks(_ ids: [String]) {
        for id in ids {
            if player.current?.id == id { player.stop() }
            try? library.remove(id)
        }
        refreshMusic()
    }

    func createPlaylist(named name: String, with trackIds: [String] = []) {
        let playlist = library.createPlaylist(named: name)
        if !trackIds.isEmpty { library.add(trackIds, to: playlist.id) }
        refreshMusic()
        selection = .playlist(playlist.id)
    }

    func addTracks(_ ids: [String], toPlaylist id: String) {
        library.add(ids, to: id)
        refreshMusic()
    }

    func removeTracks(_ ids: [String], fromPlaylist id: String) {
        library.remove(ids, from: id)
        refreshMusic()
    }

    func deletePlaylist(_ id: String) {
        try? library.deletePlaylist(id)
        if selectedPlaylistId == id { selection = .section(.library) }
        refreshMusic()
    }

    func renamePlaylist(_ id: String, to name: String) {
        try? library.renamePlaylist(id, to: name)
        refreshMusic()
    }

    // MARK: - Memes

    let memes: MemeLibrary
    var memeSnapshot = MemeLibrary.Snapshot(items: [], tags: [], people: [])
    var memeProblem: String?
    /// Memes that just downloaded, waiting for their quick tag prompt, oldest first.
    var tagPrompts: [String] = []
    /// The faces pipeline, loaded on first scan. Nil until then, or if the models are missing.
    @ObservationIgnored var faceScanner: FaceScanner?
    var faceProblem: String?
    /// How many memes are left to scan while a scan runs; nil when idle.
    var facesRemaining: Int?
    /// The quick prompt after a meme downloads. On unless turned off.
    var askForMemeTags: Bool = UserDefaults.standard.object(forKey: "askForMemeTags") as? Bool ?? true {
        didSet { UserDefaults.standard.set(askForMemeTags, forKey: "askForMemeTags") }
    }

    // MARK: - Watch

    let watch: WatchService
    /// What the player window plays; set before opening it.
    var watchPlaying: WatchPlayRequest?

    // MARK: - Backup

    var backupJob: BackupJob?

    // MARK: - Devices

    /// Nil only if this Mac's pairing identity could not be made, which the Devices section
    /// then says rather than showing controls that cannot work.
    var devices: DevicesModel?
    var devicesProblem: String?

    // MARK: - Presets

    let presets: PresetStore
    /// Rendered files wait here until the library takes them in.
    let renderScratch: URL
    var presetList: [AudioPreset] = []
    var autoPresets = AutoPresetConfig()
    var renderJobs: [RenderJob] = []

    // MARK: - Music

    let library: MusicLibrary
    let player: Player
    private(set) var tracks: [MusicLibrary.Track] = []
    private(set) var playlists: [MusicLibrary.Playlist] = []
    var musicProblem: String?

    var favorites: Set<String> {
        Set(playlists.first { $0.id == MusicLibrary.favoritesId }?.trackIds ?? [])
    }

    // MARK: - The vault

    let keybox: Keybox
    let vault: Vault

    /// Mirrors the key box, which is not observable itself. Every change goes through this
    /// model, so the toolbar, the menu and the vault pane cannot disagree about it.
    private(set) var vaultUnlocked = false
    private(set) var vaultItems: [Vault.Item] = []
    private(set) var vaultProblem: String?
    var showUnlockSheet = false

    var vaultConfigured: Bool { keybox.isConfigured }

    /// What the user last pasted or dropped, waiting to be downloaded.
    var pendingURL: String = ""

    func pasteAndDownload() {
        section = .download
        if let text = NSPasteboard.general.string(forType: .string) {
            pendingURL = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    func toggleVaultLock() {
        if vaultUnlocked {
            lockVault()
        } else {
            section = .vault
            showUnlockSheet = true
        }
    }

    // MARK: - Vault actions

    /// Returns a message to show, or nil on success.
    /// Argon2id takes most of a second by design, so it runs off the main actor and the
    /// window keeps drawing while it does.
    func unlockVault(passphrase: String) async -> String? {
        let keybox = keybox
        do {
            try await Task.detached {
                if keybox.isConfigured {
                    try keybox.unlock(passphrase: passphrase)
                } else {
                    try keybox.create(passphrase: passphrase)
                }
            }.value
            vaultDidUnlock()
            return nil
        } catch {
            return String(describing: error)
        }
    }

    func lockVault() {
        keybox.lock()
        vault.forget()
        memes.forgetPrivate()
        refreshMemes()
        vaultUnlocked = false
        vaultItems = []
        vaultProblem = nil
        refreshSecurity()
    }

    private func vaultDidUnlock() {
        vaultUnlocked = true
        showUnlockSheet = false
        refreshVault()
        refreshMemes()
        refreshSecurity()
    }

    func refreshVault() {
        do {
            vaultItems = try vault.items()
            vaultProblem = nil
        } catch {
            vaultItems = []
            vaultProblem = String(describing: error)
        }
    }

    /// Encrypting is slow for video, so it runs off the main actor.
    func addToVault(_ urls: [URL], removeOriginals: Bool = false) {
        guard vaultUnlocked else { showUnlockSheet = true; return }
        let vault = vault
        Task.detached {
            var failure: String?
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    try vault.add(url, removeOriginal: removeOriginals)
                } catch {
                    failure = String(describing: error)
                }
            }
            await MainActor.run {
                self.refreshVault()
                if let failure { self.vaultProblem = failure }
            }
        }
    }

    func removeFromVault(_ item: Vault.Item) {
        do {
            try vault.remove(item.id)
            refreshVault()
        } catch {
            vaultProblem = String(describing: error)
        }
    }

    func renameInVault(_ item: Vault.Item, to title: String) {
        do {
            try vault.rename(item.id, to: title)
            refreshVault()
        } catch {
            vaultProblem = String(describing: error)
        }
    }

    func exportFromVault(_ item: Vault.Item, to url: URL) {
        let vault = vault
        Task.detached {
            do {
                try vault.export(item.id, to: url)
            } catch {
                await MainActor.run { self.vaultProblem = String(describing: error) }
            }
        }
    }
}
