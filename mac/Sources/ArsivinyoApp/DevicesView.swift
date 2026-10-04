import ArsivinyoCore
import SwiftUI

/// Pairing with the phone and moving things between them.
struct DevicesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let devices = model.devices {
            DevicesList(devices: devices)
        } else {
            ContentUnavailableView {
                Label("Devices are unavailable", systemImage: "exclamationmark.triangle")
            } description: {
                Text(model.devicesProblem ?? "")
            }
        }
    }
}

private struct DevicesList: View {
    @Environment(AppModel.self) private var model
    @Bindable var devices: DevicesModel
    @State private var linkTarget: PeerRegistry.Peer?
    @State private var browsing: PeerRegistry.Peer?
    @State private var sendingTo: PeerRegistry.Peer?
    @State private var forgetting: PeerRegistry.Peer?

    var body: some View {
        Form {
            Section {
                LabeledContent("Name") {
                    TextField("Name", text: Binding(get: { devices.deviceName }, set: { devices.deviceName = $0 }))
                        .multilineTextAlignment(.trailing)
                        .labelsHidden()
                        .frame(maxWidth: 260)
                }
                LabeledContent("Identity") {
                    Text(Self.short(devices.fingerprint)).monospaced().foregroundStyle(.secondary)
                        .help("The start of this Mac's fingerprint. A device it pairs with sees the same.")
                }
            } header: {
                Text("This Mac")
            } footer: {
                Text(devices.listening
                     ? "Visible to your devices on this network."
                     : "Not visible. Another app may be using the network port, or local network access was refused in System Settings.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section {
                Toggle("Download links from paired devices without asking",
                       isOn: $devices.autoDownloadLinks)
            }

            Section("Paired") {
                if devices.peers.isEmpty {
                    Text("None yet. Open Devices on your phone and tap Add device, then pair with it below.")
                        .foregroundStyle(.secondary)
                }
                ForEach(devices.peers) { peer in
                    let online = devices.connected.contains(peer.fingerprint)
                    HStack {
                        Image(systemName: "iphone").foregroundStyle(online ? Color.accentColor : .secondary)
                        VStack(alignment: .leading) {
                            Text(peer.name.isEmpty ? String(localized: "Unnamed device") : peer.name)
                            Text(online ? "Connected" : (devices.isNearby(peer.fingerprint) ? "Connecting…" : "Not nearby"))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Browse…") {
                            browsing = peer
                            devices.browse(peer.fingerprint)
                        }
                        .disabled(!online)
                        Button("Send Music…") { sendingTo = peer }
                            .disabled(!online)
                        Button("Send Link…") { linkTarget = peer }
                            .disabled(!online)
                        Button(role: .destructive) { forgetting = peer } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless)
                            .help("Forget this device")
                    }
                }
            }

            Section {
                if devices.nearby.isEmpty {
                    Text("No other devices found. They have to be on the same network, with Arsivinyo open.")
                        .foregroundStyle(.secondary)
                }
                ForEach(devices.nearby) { found in
                    HStack {
                        Image(systemName: "iphone.radiowaves.left.and.right").foregroundStyle(.secondary)
                        VStack(alignment: .leading) {
                            Text(found.name.isEmpty ? String(localized: "Unnamed device") : found.name)
                            Text(Self.short(found.fingerprint)).font(.caption).monospaced().foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Pair…") { devices.pair(with: found) }
                    }
                }
            } header: {
                HStack {
                    Text("On This Network")
                    Spacer()
                    Button(devices.pairingMode ? "Stop Waiting" : "Let a Device Pair") { devices.togglePairingWindow() }
                        .help("For a device that pairs from its own side. The window closes by itself after two minutes.")
                }
            } footer: {
                Text("To pair, both devices wait for each other: tap Add device on the phone, then Pair here. Both then show six digits, which have to match.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if let transfer = devices.transfer {
                Section {
                    HStack {
                        if let sending = devices.sending {
                            Text("\(sending.index) of \(sending.count)").monospacedDigit().foregroundStyle(.secondary)
                        }
                        ProgressView(value: Double(transfer.done), total: Double(max(transfer.total, 1)))
                        Button("Cancel") { devices.cancelTransfer() }
                    }
                }
            }
            if let message = devices.message {
                Section { Text(message).foregroundStyle(.secondary) }
            }
        }
        .formStyle(.grouped)
        .onAppear { devices.refresh() }
        .sheet(isPresented: Binding(get: { !devices.pendingCode.isEmpty }, set: { if !$0 { devices.reject() } })) {
            PairingCodeSheet(devices: devices)
        }
        .sheet(item: $browsing) { peer in
            BrowseSheet(devices: devices, peer: peer)
        }
        .sheet(item: $sendingTo) { peer in
            SendMusicSheet(tracks: model.tracks, playlists: model.playlists.filter { !$0.trackIds.isEmpty },
                           send: { chosen in devices.send(chosen, to: peer.fingerprint) },
                           sendPlaylist: { playlist in devices.sendPlaylist(playlist, to: peer.fingerprint) })
        }
        .sheet(item: $linkTarget) { peer in
            SendLinkSheet { url, audio in devices.sendLink(url, audio: audio, to: peer.fingerprint) }
        }
        .confirmationDialog(Text("Forget “\(forgetting?.name ?? "")”?"),
                            isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } })) {
            Button("Forget", role: .destructive) {
                if let peer = forgetting { devices.forget(peer.fingerprint) }
                forgetting = nil
            }
        } message: {
            Text("It will have to pair again to reach this Mac. It is not told.")
        }
    }

    static func short(_ fingerprint: String) -> String {
        stride(from: 0, to: min(16, fingerprint.count), by: 4).map { offset -> String in
            let start = fingerprint.index(fingerprint.startIndex, offsetBy: offset)
            return String(fingerprint[start..<fingerprint.index(start, offsetBy: 4)])
        }.joined(separator: " ")
    }
}

private struct PairingCodeSheet: View {
    let devices: DevicesModel

    var body: some View {
        VStack(spacing: 18) {
            Text("Pair with “\(devices.pendingName)”?").font(.headline)
            Text(devices.pendingCode.enumerated().map { index, digit in index == 3 ? " \(digit)" : "\(digit)" }.joined())
                .font(.system(size: 44, weight: .semibold, design: .monospaced))
                .textSelection(.disabled)
            Text("Check that the other device shows the same six digits. If they differ, something is between you: do not pair.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button("They Differ", role: .cancel) { devices.reject() }
                    .keyboardShortcut(.cancelAction)
                Button("They Match") { devices.confirm() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 400)
    }
}

private struct BrowseSheet: View {
    @Environment(\.dismiss) private var dismiss
    let devices: DevicesModel
    let peer: PeerRegistry.Peer

    var body: some View {
        VStack(spacing: 0) {
            if let listing = devices.listing, listing.fingerprint == peer.fingerprint {
                if listing.items.isEmpty && listing.playlists.isEmpty {
                    ContentUnavailableView("No music on that device", systemImage: "music.note")
                } else {
                    List {
                        if !listing.playlists.isEmpty {
                            Section("Playlists") {
                                ForEach(listing.playlists) { playlist in
                                    HStack {
                                        Label(playlist.favorites ? String(localized: "Favorites") : playlist.name,
                                              systemImage: playlist.favorites ? "heart" : "music.note.list")
                                            .lineLimit(1)
                                        Spacer()
                                        Text("^[\(playlist.count) track](inflect: true)").foregroundStyle(.secondary).monospacedDigit()
                                        Button("Get") { devices.fetchPlaylist(playlist.id, from: peer.fingerprint) }
                                            .help("The tracks you do not have yet come over, and the playlist is made here.")
                                    }
                                }
                            }
                        }
                        Section("Tracks") {
                            ForEach(listing.items) { item in
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(item.title).lineLimit(1)
                                        if !item.artist.isEmpty {
                                            Text(item.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                    }
                                    Spacer()
                                    Text(ByteCountFormatter.string(fromByteCount: item.sizeBytes, countStyle: .file))
                                        .foregroundStyle(.secondary).monospacedDigit()
                                    Button("Get") { devices.fetch(item.id, from: peer.fingerprint) }
                                }
                            }
                        }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if let transfer = devices.transfer {
                ProgressView(value: Double(transfer.done), total: Double(max(transfer.total, 1))).padding()
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(12)
        }
        .frame(width: 480, height: 420)
        .navigationTitle(Text("Music on “\(peer.name)”"))
    }
}

private struct SendLinkSheet: View {
    @Environment(\.dismiss) private var dismiss
    let send: (String, Bool) -> Void
    @State private var url = ""
    @State private var audio = false

    var body: some View {
        Form {
            TextField("Link", text: $url, prompt: Text(verbatim: "https://"))
            Picker("Download as", selection: $audio) {
                Text("Video").tag(false)
                Text("Audio").tag(true)
            }
            .pickerStyle(.segmented)
        }
        .formStyle(.grouped)
        .frame(width: 420)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Send") {
                    send(url.trimmingCharacters(in: .whitespacesAndNewlines), audio)
                    dismiss()
                }
                .disabled(!DownloadQueue.looksLikeLink(url.trimmingCharacters(in: .whitespacesAndNewlines)))
            }
        }
    }
}

/// A paired device asked this Mac to download something. Shown, never started unasked.
struct LinkRequestSheet: View {
    @Environment(\.dismiss) private var dismiss
    let request: DevicesModel.LinkRequest
    let download: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("“\(request.from)” sent a link").font(.headline)
            // The site, not the whole address: enough to recognise, not a record of it.
            Text(URL(string: request.url)?.host() ?? request.url).foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button("Ignore") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(request.audio ? "Download Audio" : "Download Video") {
                    download(request.audio)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 380)
    }
}

/// Music to send to a paired device: a whole playlist, which the device makes too, or tracks
/// from the library, searched, any number chosen.
private struct SendMusicSheet: View {
    @Environment(\.dismiss) private var dismiss
    let tracks: [MusicLibrary.Track]
    let playlists: [MusicLibrary.Playlist]
    let send: ([MusicLibrary.Track]) -> Void
    let sendPlaylist: (MusicLibrary.Playlist) -> Void
    @State private var search = ""
    @State private var selection = Set<MusicLibrary.Track.ID>()

    private var shown: [MusicLibrary.Track] {
        let query = search.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return tracks }
        return tracks.filter { $0.title.localizedStandardContains(query) || $0.artist.localizedStandardContains(query) }
    }

    var body: some View {
        VStack(spacing: 0) {
            if !playlists.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Playlists").font(.headline)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(playlists) { playlist in
                                Button {
                                    sendPlaylist(playlist)
                                    dismiss()
                                } label: {
                                    Label("\(AppModel.displayName(of: playlist)) · \(playlist.trackIds.count)",
                                          systemImage: playlist.isSystem ? "heart" : "music.note.list")
                                }
                                .help("Sends the whole playlist; the other device makes it too.")
                            }
                        }
                    }
                }
                .padding([.horizontal, .top], 12)
            }
            TextField("Search music", text: $search)
                .textFieldStyle(.roundedBorder)
                .padding(12)
            if tracks.isEmpty {
                ContentUnavailableView("No music on this Mac", systemImage: "music.note")
            } else {
                Table(shown, selection: $selection) {
                    TableColumn("Title") { Text($0.title).lineLimit(1) }
                    TableColumn("Artist") { Text($0.artist).foregroundStyle(.secondary).lineLimit(1) }
                    TableColumn("Size") {
                        Text(ByteCountFormatter.string(fromByteCount: $0.sizeBytes, countStyle: .file))
                            .monospacedDigit().foregroundStyle(.secondary)
                    }
                    .width(80)
                }
            }
            HStack {
                Text("Choose with ⌘ or ⇧ for more than one.").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Button(selection.count > 1 ? LocalizedStringKey("Send \(selection.count) Tracks") : LocalizedStringKey("Send")) {
                    send(tracks.filter { selection.contains($0.id) })
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(selection.isEmpty)
            }
            .padding(12)
        }
        .frame(width: 560, height: 480)
    }
}

/// Music going to or coming from a paired device, shown over every section: the device,
/// "3 of 12" for several, and how far the one in flight is. Clicking it opens Devices. It used
/// to be seen only in Devices, so a transfer the phone started ran unseen. Never what the
/// tracks are called.
struct TransferBar: View {
    @Environment(AppModel.self) private var model
    /// A batch pauses between files; the bar stays this long before it goes.
    @State private var lingering = false

    private var moving: Bool { model.devices.map { $0.transfer != nil || $0.sending != nil } ?? false }

    var body: some View {
        // Always here, though often empty, so it sees a transfer end and can wait out a pause.
        VStack(spacing: 0) {
            if let devices = model.devices, moving || lingering {
                bar(devices)
            }
        }
        .onChange(of: moving) { _, now in
            guard !now else { lingering = false; return }
            lingering = true
            Task {
                try? await Task.sleep(for: .seconds(2))
                if !moving { lingering = false }
            }
        }
    }

    private func bar(_ devices: DevicesModel) -> some View {
        let done = devices.transfer?.done ?? 0
        let total = max(devices.transfer?.total ?? 1, 1)
        let peer = devices.transferPeer.isEmpty ? String(localized: "a paired device") : devices.transferPeer
        return Button { model.section = .devices } label: {
            HStack(spacing: 10) {
                Image(systemName: devices.transferIncoming ? "arrow.down.circle.fill" : "arrow.up.circle.fill")
                    .foregroundStyle(.tint)
                Text(devices.transferIncoming ? "Receiving from \(peer)" : "Sending to \(peer)").lineLimit(1)
                if let batch = devices.transferBatch {
                    Text("\(batch.index) of \(batch.count)").foregroundStyle(.secondary).monospacedDigit()
                }
                ProgressView(value: Double(done), total: Double(total)).frame(maxWidth: 220)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(.bar)
        .help("Open Devices")
    }
}
