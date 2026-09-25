import ArsivinyoCore
import Foundation
import SwiftUI

/// One thing being fetched.
///
/// The phone's queue is the part of it people actually watch, so this exists before the
/// engine that will fill it — a download pane with nothing but a button and a text field is
/// what the Qt app was, and the point of the rewrite is not to land there again.
@Observable
final class DownloadItem: Identifiable {
    enum State: Equatable {
        case queued
        case running(stage: String)
        case finished(path: URL)
        case failed(String)
        case cancelled
    }

    let id = UUID()
    let url: String
    let audioOnly: Bool
    var title: String
    var state: State = .queued
    /// 0…1, or nil while the size is still unknown and a bar would be a lie.
    var progress: Double?
    var speedBytesPerSecond: Double?
    let startedAt = Date()

    init(url: String, audioOnly: Bool) {
        self.url = url
        self.audioOnly = audioOnly
        // Until the extractor reports a real title, the host is more use than the full URL.
        self.title = URL(string: url)?.host() ?? url
    }

    var isActive: Bool {
        switch state {
        case .queued, .running: return true
        case .finished, .failed, .cancelled: return false
        }
    }
}

/// What is being fetched and what just was.
@MainActor
@Observable
final class DownloadQueue {
    private(set) var items: [DownloadItem] = []

    /// How many run at once. The rest wait their turn in order. More than a few mostly
    /// splits the same bandwidth more ways and invites a site's rate limit.
    var maxConcurrent: Int = max(1, min(4, UserDefaults.standard.object(forKey: "maxConcurrentDownloads") as? Int ?? 2)) {
        didSet {
            UserDefaults.standard.set(maxConcurrent, forKey: "maxConcurrentDownloads")
            pump()
        }
    }

    var destination: URL

    private let engine: EngineClient
    /// Engine request ids, so a cancel reaches the right download.
    private var requestIds: [UUID: String] = [:]
    private var running: Set<UUID> = []

    /// Called when a download lands, with where it landed and what the engine said about it.
    var onFinished: ((DownloadItem, URL, JSONValue) -> Void)?
    /// Called once a download has ended, however it ended.
    var onSettled: ((DownloadItem) -> Void)?
    /// A decrypted cookie file for a link, and the site it matched, or nil to go signed out.
    var cookiesFor: ((String) -> (file: URL, platform: String?)?)?
    /// Deletes what `cookiesFor` decrypted.
    var discardCookies: ((URL) -> Void)?

    init(engine: EngineClient, destination: URL) {
        self.engine = engine
        self.destination = destination
    }

    var active: [DownloadItem] { items.filter(\.isActive) }
    var recent: [DownloadItem] { items.filter { !$0.isActive } }

    @discardableResult
    func enqueue(url: String, audioOnly: Bool) -> DownloadItem? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.looksLikeLink(trimmed) else { return nil }
        // Asking for the same thing twice usually means the button was clicked twice.
        if let existing = items.first(where: { $0.url == trimmed && $0.isActive }) {
            return existing
        }
        let item = DownloadItem(url: trimmed, audioOnly: audioOnly)
        items.insert(item, at: 0)
        pump()
        return item
    }

    /// Starts waiting downloads, oldest first, while there is room.
    private func pump() {
        let waiting = items.reversed().filter { $0.state == .queued && !running.contains($0.id) }
        for item in waiting where running.count < maxConcurrent {
            running.insert(item.id)
            Task {
                await run(item)
                running.remove(item.id)
                onSettled?(item)
                pump()
            }
        }
    }

    private func run(_ item: DownloadItem) async {
        try? FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        item.state = .running(stage: String(localized: "Starting"))

        let cookies = cookiesFor?(item.url)
        defer { if let cookies { discardCookies?(cookies.file) } }

        var failure = await attempt(item, cookieFile: cookies?.file, signedOut: false)
        // The phone's rule. A download refused while signed in is tried once more signed
        // out, because an expired session fails where no session at all would not. Not on
        // strict sites: there a signed-out answer is a login wall that looks like success.
        if let failure, let cookies, CookieStore.shouldRetrySignedOut(code: failure.code, message: failure.description, platform: cookies.platform) {
            item.progress = nil
            _ = await attempt(item, cookieFile: nil, signedOut: true)
        } else if let failure {
            item.state = .failed(failure.description)
        }
    }

    /// One try at the download. Returns the failure, or nil once it has landed or been
    /// cancelled.
    private func attempt(_ item: DownloadItem, cookieFile: URL?, signedOut: Bool) async
        -> EngineClient.Event.EngineError?
    {
        var arguments = EngineClient.downloadArguments(
            url: item.url, outputDirectory: destination, audioOnly: item.audioOnly)
        if let cookieFile { arguments["cookieFile"] = cookieFile.path }
        if signedOut { arguments["forceNoCookie"] = true }

        let request = engine.perform("download", arguments)
        requestIds[item.id] = request.id
        defer { requestIds[item.id] = nil }

        for await event in request.events {
            switch event {
            case .ready:
                break

            case .progress(let status, let percent, let detail):
                if case .cancelled = item.state { break }
                // A percentage the engine has not worked out yet arrives as nil, and the
                // row shows an indeterminate bar rather than inventing a number.
                item.progress = percent.map { $0 / 100 }
                item.state = .running(stage: detail ?? Self.readable(status))

            case .finished(.success(let payload)):
                if let title = payload["title"]?.string, !title.isEmpty { item.title = title }
                let path = (payload["filePath"]?.string ?? payload["file_path"]?.string)
                    .map { URL(fileURLWithPath: $0) }
                item.state = .finished(path: path ?? destination)
                item.progress = 1
                if let path { onFinished?(item, path, payload) }
                return nil

            case .finished(.failure(let error)):
                // A cancel comes back as a failure; it is not one the user needs telling.
                if case .cancelled = item.state { return nil }
                if signedOut { item.state = .failed(error.description) }
                return error
            }
        }
        return nil
    }

    /// Turns the engine's own stage names into something worth showing.
    private static func readable(_ status: String) -> String {
        switch status {
        case "downloading": return String(localized: "Downloading")
        case "postprocessing", "processing": return String(localized: "Converting")
        case "preflight": return String(localized: "Checking the link")
        default: return status.capitalized
        }
    }

    func cancel(_ item: DownloadItem) {
        guard item.isActive else { return }
        item.state = .cancelled
        if let id = requestIds[item.id] { engine.cancel(id: id) }
    }

    func clearFinished() {
        items.removeAll { !$0.isActive }
    }

    /// Cheap enough to run on every keystroke, and it keeps the button honest about
    /// whether there is anything worth pressing it for.
    static func looksLikeLink(_ text: String) -> Bool {
        guard let url = URL(string: text), let scheme = url.scheme?.lowercased() else {
            return false
        }
        return (scheme == "http" || scheme == "https") && (url.host()?.contains(".") ?? false)
    }
}
