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

    private let engine: EngineClient
    private let destination: URL
    /// Engine request ids, so a cancel reaches the right download.
    private var requestIds: [UUID: String] = [:]

    /// Called when a download lands, with where it landed and what the engine said about it.
    var onFinished: ((DownloadItem, URL, JSONValue) -> Void)?

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
        Task { await run(item) }
        return item
    }

    private func run(_ item: DownloadItem) async {
        try? FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)

        item.state = .running(stage: String(localized: "Starting"))
        let request = engine.perform("download", EngineClient.downloadArguments(
            url: item.url, outputDirectory: destination, audioOnly: item.audioOnly))
        requestIds[item.id] = request.id

        for await event in request.events {
            switch event {
            case .ready:
                break

            case .progress(let status, let percent, let detail):
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

            case .finished(.failure(let error)):
                // A cancel comes back as a failure; it is not one the user needs telling.
                if case .cancelled = item.state { break }
                item.state = .failed(error.description)
            }
        }
        requestIds[item.id] = nil
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
