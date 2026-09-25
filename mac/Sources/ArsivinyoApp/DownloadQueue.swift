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
@Observable
final class DownloadQueue {
    private(set) var items: [DownloadItem] = []

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
        return item
    }

    func cancel(_ item: DownloadItem) {
        guard item.isActive else { return }
        item.state = .cancelled
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
