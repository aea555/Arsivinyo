import SwiftUI
import UniformTypeIdentifiers

/// The download pane: where a link goes in, and what is happening to the ones already in.
///
/// The controls sit in a header strip and the rest of the pane is the queue, so the window
/// has something in it rather than three controls centred in a field of grey.
struct DownloadView: View {
    @Environment(AppModel.self) private var model
    @State private var audioOnly = false
    @State private var isTargetedForDrop = false

    private var queue: DownloadQueue { model.queue }
    private var canStart: Bool {
        DownloadQueue.looksLikeLink(model.pendingURL) && model.engineProblem == nil
    }

    var body: some View {
        @Bindable var model = model

        VStack(spacing: 0) {
            if let problem = model.engineProblem {
                // Said out loud rather than leaving a button that quietly does nothing.
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text(problem).font(.callout)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.orange.opacity(0.12))
                Divider()
            }
            header
            Divider()
            queueList
        }
        // A link dragged from a browser is how a Mac user expects to do this, and it is
        // faster than copy, switch, paste.
        .onDrop(of: [.url, .plainText], isTargeted: $isTargetedForDrop) { providers in
            handleDrop(providers)
        }
        .overlay {
            if isTargetedForDrop {
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(Color.accentColor, lineWidth: 2)
                    .padding(6)
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        @Bindable var model = model

        return VStack(spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "link")
                    .foregroundStyle(.secondary)
                TextField("Paste a link, or drop one here", text: $model.pendingURL)
                    .textFieldStyle(.plain)
                    .font(.system(size: 13))
                    .onSubmit(start)

                if !model.pendingURL.isEmpty {
                    Button {
                        model.pendingURL = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.tertiary)
                    .help("Clear")
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 7))

            HStack(spacing: 12) {
                Picker("", selection: $audioOnly) {
                    Text("Video").tag(false)
                    Text("Audio").tag(true)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 150)

                Spacer()

                Button("Paste") { model.pasteAndDownload() }
                    .help("⇧⌘V")

                Button("Download", action: start)
                    .buttonStyle(.borderedProminent)
                    .disabled(!canStart)
                    .keyboardShortcut(.return, modifiers: .command)
            }
        }
        .padding(16)
    }

    // MARK: - Queue

    @ViewBuilder
    private var queueList: some View {
        if queue.items.isEmpty {
            VStack {
                Spacer()
                ContentUnavailableView {
                    Label("Nothing downloading", systemImage: "arrow.down.circle")
                } description: {
                    Text("Paste a link above, or drag one in from your browser.")
                }
                Spacer()
            }
        } else {
            List {
                if !queue.active.isEmpty {
                    Section("Downloading") {
                        ForEach(queue.active) { DownloadRow(item: $0, queue: queue) }
                    }
                }
                if !queue.recent.isEmpty {
                    Section {
                        ForEach(queue.recent) { DownloadRow(item: $0, queue: queue) }
                    } header: {
                        HStack {
                            Text("Recent")
                            Spacer()
                            Button("Clear", action: queue.clearFinished)
                                .buttonStyle(.link)
                                .font(.caption)
                        }
                    }
                }
            }
            .listStyle(.inset)
        }
    }

    // MARK: - Actions

    private func start() {
        guard canStart else { return }
        queue.enqueue(url: model.pendingURL, audioOnly: audioOnly)
        model.pendingURL = ""
    }

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        guard let provider = providers.first else { return false }
        _ = provider.loadObject(ofClass: URL.self) { url, _ in
            guard let url else { return }
            Task { @MainActor in
                model.pendingURL = url.absoluteString
            }
        }
        return true
    }
}

/// One row of the queue.
private struct DownloadRow: View {
    let item: DownloadItem
    let queue: DownloadQueue

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.audioOnly ? "music.note" : "film")
                .foregroundStyle(.secondary)
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .lineLimit(1)
                    .truncationMode(.middle)

                switch item.state {
                case .queued:
                    Text("Waiting").font(.caption).foregroundStyle(.secondary)
                case .running(let stage):
                    // A determinate bar with a made-up number is worse than an
                    // indeterminate one, so the bar only appears once a size is known.
                    if let progress = item.progress {
                        ProgressView(value: progress).progressViewStyle(.linear)
                    } else {
                        ProgressView().progressViewStyle(.linear)
                    }
                    Text(stage).font(.caption).foregroundStyle(.secondary)
                case .finished:
                    Text("Done").font(.caption).foregroundStyle(.secondary)
                case .failed(let message):
                    Text(message).font(.caption).foregroundStyle(.red).lineLimit(1)
                case .cancelled:
                    Text("Cancelled").font(.caption).foregroundStyle(.secondary)
                }
            }

            Spacer()

            switch item.state {
            case .queued, .running:
                Button { queue.cancel(item) } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.plain)
                    .foregroundStyle(.tertiary)
                    .help("Cancel")
            case .finished(let path):
                Button { NSWorkspace.shared.activateFileViewerSelecting([path]) } label: {
                    Image(systemName: "folder")
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .help("Show in Finder")
            case .failed, .cancelled:
                EmptyView()
            }
        }
        .padding(.vertical, 4)
    }
}
