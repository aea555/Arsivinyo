import AppKit
import ArsivinyoCore
import SwiftUI
import UniformTypeIdentifiers

private func bytes(_ n: Double) -> String {
    ByteCountFormatter.string(fromByteCount: Int64(n), countStyle: .file)
}

private func duration(_ seconds: Double) -> String {
    let formatter = DateComponentsFormatter()
    formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute] : seconds >= 60 ? [.minute] : [.second]
    formatter.unitsStyle = .abbreviated
    return formatter.string(from: seconds) ?? ""
}

/// Torrent downloads (`shared/watch/CONTRACT.md`, "Downloading"), a section of their own: add a magnet or a .torrent,
/// pick its files and where they land, and follow them. Public files go to
/// ~/Downloads/Arsivinyo; private ones into the vault, each as soon as it is complete.
struct TorrentsView: View {
    @Environment(AppModel.self) private var model
    @State private var downloads: [(record: WatchLibrary.Torrent, engine: [String: Any]?)] = []
    @State private var link = ""
    @State private var message: String?
    @State private var choosing: String?
    @State private var removing: WatchLibrary.Torrent?
    @State private var headsUpFor: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VPNNotice()
            HStack {
                TextField("Paste a magnet link", text: $link).onSubmit { add(link) }
                Button("Add") { add(link) }.disabled(link.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("Open a .torrent File…") { openFile() }
            }
            if let message { Text(message).foregroundStyle(.orange) }
            if downloads.isEmpty {
                Text("No torrent downloads yet.").foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 120)
            } else {
                List(downloads, id: \.record.infoHash) { item in row(item.record, item.engine) }
            }
            SeedingSettings()
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .navigationTitle("Torrents")
        // A magnet or a .torrent opened from elsewhere, or a stream's "Download…", whether
        // the section was showing already or not.
        .onChange(of: model.torrentLink, initial: true) { _, pending in
            guard let pending else { return }
            model.torrentLink = nil
            add(pending)
        }
        .task {
            // Once a second while the sheet is open.
            while !Task.isCancelled {
                downloads = (try? model.torrents.downloads()) ?? []
                try? await Task.sleep(for: .seconds(1))
            }
        }
        .sheet(item: Binding(get: { choosing.map(ChoosingID.init) }, set: { choosing = $0?.id })) { item in
            ChooseFilesSheet(id: item.id)
        }
        .confirmationDialog("Remove this download?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            presenting: removing) { record in
            Button("Remove, Keep What Was Fetched") { try? model.torrents.remove(record.infoHash, deleteFiles: false) }
            Button("Remove with What Was Fetched", role: .destructive) { try? model.torrents.remove(record.infoHash, deleteFiles: true) }
        } message: { _ in
            Text("Files already in Downloads or in the vault stay where they are.")
        }
        .alert("Everyone in a torrent sees your address", isPresented: Binding(get: { headsUpFor != nil }, set: { if !$0 { headsUpFor = nil } }),
               presenting: headsUpFor) { input in
            Button("Got it") {
                TorrentHeadsUp.seen = true
                start(input)
            }
            .keyboardShortcut(.defaultAction)
            Button("Don't show again") {
                TorrentHeadsUp.seen = true
                var settings = model.torrents.settings()
                settings.headsUpDismissed = true
                try? model.torrents.setSettings(settings)
                start(input)
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Streaming or downloading a torrent shows this Mac's IP address to everyone sharing the same torrent. A VPN hides it; Arsivinyo recommends one but does not require it.")
        }
    }

    private struct ChoosingID: Identifiable { let id: String }

    /// A magnet link, or the path of a .torrent file.
    private func add(_ input: String) {
        let text = input.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        if !TorrentHeadsUp.seen, !model.torrents.settings().headsUpDismissed {
            headsUpFor = text
            return
        }
        start(text)
    }

    private func start(_ text: String) {
        message = nil
        do {
            let id = text.lowercased().hasPrefix("magnet:")
                ? try model.torrents.add(magnet: text)
                : try model.torrents.add(torrent: Data(contentsOf: URL(fileURLWithPath: text)))
            link = ""
            choosing = id
        } catch {
            message = String(localized: "Could not add it (\(String(describing: error))).")
        }
    }

    private func openFile() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "torrent") ?? .data]
        if panel.runModal() == .OK, let url = panel.url { add(url.path) }
    }

    @ViewBuilder
    private func row(_ record: WatchLibrary.Torrent, _ engine: [String: Any]?) -> some View {
        let done = (engine?["done"] as? NSNumber)?.doubleValue ?? 0
        let wanted = (engine?["wanted"] as? NSNumber)?.doubleValue ?? 0
        let rate = (engine?["downloadRate"] as? NSNumber)?.doubleValue ?? 0
        let paused = engine?["paused"] as? Bool ?? false
        let finished = engine?["finished"] as? Bool ?? false
        let fraction = wanted > 0 ? done / wanted : (engine?["progress"] as? NSNumber)?.doubleValue ?? 0
        HStack(spacing: 12) {
            Image(systemName: record.destination == "private" ? "lock" : "arrow.down.circle").foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 4) {
                Text(record.name.isEmpty ? String(localized: "A torrent") : record.name).lineLimit(2)
                Text(line(record, engine, fraction: fraction, rate: rate, paused: paused, finished: finished, left: wanted - done))
                    .font(.caption).foregroundStyle(.secondary)
                if record.state == "downloading", engine != nil { ProgressView(value: min(1, fraction)) }
            }
            Spacer()
            if record.state == "choosing" {
                Button("Choose Files…") { choosing = record.infoHash }
            } else if record.state == "downloading" {
                Button { paused ? (try? model.torrents.resume(record.infoHash)) : (try? model.torrents.pause(record.infoHash)) } label: {
                    Image(systemName: paused ? "play.fill" : "pause.fill")
                }
                .buttonStyle(.borderless)
            }
            Button(role: .destructive) { removing = record } label: { Image(systemName: "trash") }.buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }

    private func line(_ record: WatchLibrary.Torrent, _ engine: [String: Any]?, fraction: Double, rate: Double,
                      paused: Bool, finished: Bool, left: Double) -> String {
        let percent = Int(fraction * 100)
        if record.state == "choosing" {
            return engine?["state"] as? String == "metadata" ? String(localized: "Getting the file list from peers…")
                                                               : String(localized: "Choose which files to download")
        }
        if record.state == "done" {
            return record.destination == "private" ? String(localized: "Done, in the vault") : String(localized: "Done, in Downloads")
        }
        guard let engine else { return String(localized: "Waiting…") }
        if paused { return String(localized: "Paused at \(percent)%") }
        if finished {
            // Private and finished: being encrypted now if the vault is open, else waiting for it.
            if record.destination == "private" {
                return model.vaultUnlocked ? String(localized: "Encrypting into the vault…")
                                           : String(localized: "Finished: into the vault when it is unlocked")
            }
            return String(localized: "Finished, sharing back · ↑ \(bytes((engine["uploadRate"] as? NSNumber)?.doubleValue ?? 0))/s")
        }
        let peers = (engine["peers"] as? NSNumber)?.intValue ?? 0
        var parts = ["\(percent)%", "↓ \(bytes(rate))/s", String(localized: "\(peers) peers")]
        if rate > 0, left > 0 { parts.append(String(localized: "\(duration(left / rate)) left")) }
        return parts.joined(separator: " · ")
    }
}

/// This Mac's seeding rule and cache size (`shared/watch/CONTRACT.md`, "Seeding").
private struct SeedingSettings: View {
    @Environment(AppModel.self) private var model
    @State private var settings = TorrentEngine.Settings()

    var body: some View {
        HStack(spacing: 20) {
            Picker("Share back until", selection: Binding(get: { settings.seedRatio }, set: { save { $0.seedRatio = $1 }($0) })) {
                Text("Never").tag(0.0)
                ForEach([0.5, 1.0, 2.0], id: \.self) { Text("\($0.formatted())×").tag($0) }
            }
            .help("After a torrent finishes, it is shared with others until it has uploaded this many times its size, while the app is open.")
            Picker("Stream cache", selection: Binding(get: { settings.cacheLimitBytes }, set: { save { $0.cacheLimitBytes = $1 }($0) })) {
                ForEach([5, 10, 20, 50], id: \.self) { gb in Text("\(gb) GB").tag(Int64(gb) << 30) }
            }
            .help("What you stream is kept so watching it again starts at once; the least recently watched goes first when it is full.")
        }
        .fixedSize()
        .onAppear { settings = model.torrents.settings() }
    }

    private func save<T>(_ change: @escaping (inout TorrentEngine.Settings, T) -> Void) -> (T) -> Void {
        { value in
            change(&settings, value)
            try? model.torrents.setSettings(settings)
        }
    }
}

/// A torrent's files with their sizes, all of them chosen to begin with, and where they land.
private struct ChooseFilesSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let id: String
    @State private var files: [TorrentEngine.File]?
    @State private var chosen: Set<Int> = []
    @State private var vault = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Choose Files").font(.title2.bold())
            if let files {
                Toggle("All \(files.count) files", isOn: Binding(get: { chosen.count == files.count },
                                                                 set: { chosen = $0 ? Set(files.map(\.index)) : [] }))
                List(files) { file in
                    Toggle(isOn: Binding(get: { chosen.contains(file.index) },
                                         set: { if $0 { chosen.insert(file.index) } else { chosen.remove(file.index) } })) {
                        HStack {
                            Text(file.path).lineLimit(2)
                            Spacer()
                            Text(bytes(Double(file.size))).foregroundStyle(.secondary).monospacedDigit()
                        }
                    }
                }
                Toggle("Into the vault", isOn: $vault)
                Text(vault ? "Each file is encrypted into the vault as it finishes." : "Files land in Downloads/Arsivinyo.")
                    .font(.caption).foregroundStyle(.secondary)
                HStack {
                    Spacer()
                    Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                    let total = files.filter { chosen.contains($0.index) }.reduce(0) { $0 + Double($1.size) }
                    Button("Download \(bytes(total))") {
                        try? model.torrents.choose(id, wanted: chosen.sorted(), destination: vault ? "private" : "public")
                        dismiss()
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(chosen.isEmpty)
                }
            } else {
                ProgressView("Getting the file list from peers…").frame(maxWidth: .infinity, maxHeight: .infinity)
                Button("Cancel") { dismiss() }
            }
        }
        .padding(20)
        .frame(width: 620, height: 520)
        .task {
            // A magnet's files are known once a peer has sent its metadata.
            while files == nil, !Task.isCancelled {
                if let found = try? model.torrents.files(id) {
                    files = found
                    chosen = Set(found.map(\.index))
                } else {
                    try? await Task.sleep(for: .seconds(1))
                }
            }
        }
    }
}
