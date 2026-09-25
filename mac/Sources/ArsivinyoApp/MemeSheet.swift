import AVKit
import ArsivinyoCore
import SwiftUI

/// One view for looking at memes and labelling them, in four roles:
///
/// - detail: one meme, opened from the grid;
/// - batch: labels applied to many at once;
/// - review: the untagged inbox, one at a time;
/// - prompt: the meme that just downloaded, with Save and Later.
struct MemeSheet: View {
    enum Mode { case detail, batch, review, prompt }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let mode: Mode
    @State private var queue: [String]
    @State private var position = 0

    init(ids: Set<String>, mode: Mode, order: [String]? = nil) {
        self.mode = mode
        _queue = State(initialValue: order ?? ids.sorted())
    }

    /// What the editor works on: the one being reviewed, or all of them in a batch.
    private var targets: Set<String> {
        mode == .batch ? Set(queue) : (position < queue.count ? [queue[position]] : [])
    }

    private var current: MemeLibrary.Item? {
        mode == .batch || position >= queue.count ? nil : model.meme(queue[position])
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 18) {
                if let current {
                    MemePreview(item: current).frame(width: 360, height: 360)
                }
                VStack(alignment: .leading, spacing: 14) {
                    header
                    if let current, let source = current.source { SourceView(source: source) }
                    TagEditor(targets: targets)
                }
                .frame(minWidth: 340, maxWidth: .infinity, alignment: .topLeading)
            }
            .padding(20)
            Divider()
            footer.padding(14)
        }
        .frame(minWidth: mode == .batch ? 460 : 780, minHeight: 440)
    }

    @ViewBuilder
    private var header: some View {
        switch mode {
        case .batch: Text("Tag \(queue.count) Memes").font(.headline)
        case .review: Text("Untagged, \(min(position + 1, queue.count)) of \(queue.count)").font(.headline)
        case .prompt: Text("Just downloaded").font(.headline)
        case .detail: EmptyView()
        }
    }

    @ViewBuilder
    private var footer: some View {
        HStack {
            switch mode {
            case .detail, .batch:
                Spacer()
                Button("Done") { finish() }.keyboardShortcut(.defaultAction)
            case .review:
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button("Skip") { advance(save: false) }
                Button("Save and Next") { advance(save: true) }.keyboardShortcut(.defaultAction)
            case .prompt:
                Toggle("Ask after every meme download", isOn: Binding(get: { model.askForMemeTags },
                                                                      set: { model.askForMemeTags = $0 }))
                    .toggleStyle(.checkbox)
                Spacer()
                Button("Later") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Save") { finish() }.keyboardShortcut(.defaultAction)
            }
        }
    }

    /// Marks what was labelled here as tagged, which takes it out of the inbox.
    private func finish() {
        let labelled = targets.filter { model.meme($0)?.isUntagged == true }
        if !labelled.isEmpty { model.labelMemes(labelled) }
        dismiss()
    }

    private func advance(save: Bool) {
        if save, !targets.isEmpty { model.labelMemes(targets) }
        if position + 1 < queue.count { position += 1 } else { dismiss() }
    }
}

/// Where a meme came from: the post's caption first, since it is usually the best label.
private struct SourceView: View {
    let source: MemeLibrary.Source

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let caption = source.caption, !caption.isEmpty {
                Text(caption).font(.callout).lineLimit(5).textSelection(.enabled)
            }
            HStack(spacing: 6) {
                if let platform = source.platform { Text(platform.capitalized) }
                if let account = source.account { Text("@" + account) }
                if let posted = source.postedAt {
                    Text(Date(timeIntervalSince1970: posted / 1000).formatted(date: .abbreviated, time: .omitted))
                }
                if let link = source.url.flatMap(URL.init(string:)) {
                    Link(destination: link) { Image(systemName: "arrow.up.right.square") }.help("Open the post")
                }
            }
            .font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// The meme itself: a looping video, or the image. A private one plays from the vault
/// without being decrypted to disk.
struct MemePreview: View {
    @Environment(AppModel.self) private var model
    let item: MemeLibrary.Item
    @State private var player: AVQueuePlayer?
    @State private var looper: AVPlayerLooper?
    @State private var loader: VaultAssetLoader?
    @State private var image: NSImage?

    var body: some View {
        ZStack {
            Color.black
            if let player {
                LoopingPlayerView(player: player)
            } else if let image {
                Image(nsImage: image).resizable().scaledToFit()
            } else {
                ProgressView()
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: item.id) { load() }
        .onDisappear {
            player?.pause()
            player = nil
            looper = nil
            loader = nil
        }
    }

    private func load() {
        player?.pause()
        player = nil
        looper = nil
        image = nil
        let asset: AVURLAsset
        if item.isPrivate, let vaultId = item.vaultId {
            guard let reader = try? model.vault.reader(for: vaultId),
                  let stored = model.vaultItems.first(where: { $0.id == vaultId }) else { return }
            if !item.isVideo {
                image = (try? reader.read(offset: 0, length: Int(reader.size))).flatMap(NSImage.init(data:))
                return
            }
            let made = VaultAssetLoader(reader: reader, contentType: stored.contentType)
            loader = made
            asset = made.makeAsset(id: vaultId)
        } else if let file = item.fileURL {
            if !item.isVideo {
                image = NSImage(contentsOf: file)
                return
            }
            asset = AVURLAsset(url: file)
        } else {
            return
        }
        let queuePlayer = AVQueuePlayer()
        looper = AVPlayerLooper(player: queuePlayer, templateItem: AVPlayerItem(asset: asset))
        player = queuePlayer
        queuePlayer.play()
    }
}

private struct LoopingPlayerView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.controlsStyle = .inline
        view.player = player
        return view
    }

    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
}

/// Labels for one meme or many. Changes apply as they are made; a label only some of a
/// batch have shows as partial, and a tap gives it to all.
struct TagEditor: View {
    @Environment(AppModel.self) private var model
    let targets: Set<String>
    @State private var text = ""
    @State private var newFacets: Set<MemeLibrary.Facet> = []
    @FocusState private var focused: Bool

    private var items: [MemeLibrary.Item] { model.memeSnapshot.items.filter { targets.contains($0.id) } }

    private func share(_ ids: (MemeLibrary.Item) -> [String], _ id: String) -> Double {
        guard !items.isEmpty else { return 0 }
        return Double(items.filter { ids($0).contains(id) }.count) / Double(items.count)
    }

    private var presentTags: [MemeLibrary.Tag] {
        let ids = Set(items.flatMap(\.tags))
        return model.memeSnapshot.tags.filter { ids.contains($0.id) }.sorted { $0.name < $1.name }
    }

    private var presentPeople: [MemeLibrary.Person] {
        let ids = Set(items.flatMap(\.people))
        return model.memeSnapshot.people.filter { ids.contains($0.id) }.sorted { $0.name < $1.name }
    }

    private var suggested: [MemeLibrary.Tag] {
        guard items.count == 1, let item = items.first else { return [] }
        return MemeLibrary.suggestions(for: item, in: model.memeSnapshot)
    }

    private var matches: (tags: [MemeLibrary.Tag], people: [MemeLibrary.Person]) {
        let words = MemeLibrary.tokens(text)
        guard !words.isEmpty else { return ([], []) }
        func hit(_ name: String) -> Bool {
            let own = MemeLibrary.tokens(name)
            return words.allSatisfy { word in own.contains { $0.hasPrefix(word) } }
        }
        return (Array(model.memeSnapshot.tags.filter { hit($0.name) }.prefix(6)),
                Array(model.memeSnapshot.people.filter { hit($0.name) }.prefix(4)))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !presentTags.isEmpty || !presentPeople.isEmpty {
                FlowLayout(spacing: 6) {
                    ForEach(presentPeople) { person in
                        LabelChip(text: person.name, systemImage: "person.fill", share: share(\.people, person.id)) {
                            toggle(person: person.id)
                        }
                    }
                    ForEach(presentTags) { tag in
                        LabelChip(text: tag.name, systemImage: nil, share: share(\.tags, tag.id)) { toggle(tag: tag.id) }
                    }
                }
            }

            TextField("Add a tag or a person", text: $text)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .onSubmit(addTyped)

            let found = matches
            if !text.trimmingCharacters(in: .whitespaces).isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    FlowLayout(spacing: 6) {
                        ForEach(found.people) { person in
                            Chip(title: Text("\(Image(systemName: "person.fill")) \(person.name)"), on: false) {
                                add(person: person.id)
                            }
                        }
                        ForEach(found.tags) { tag in
                            Chip(title: Text(tag.name), on: false) { add(tag: tag.id) }
                        }
                    }
                    HStack(spacing: 6) {
                        Button("New Tag “\(text.trimmingCharacters(in: .whitespaces))”") { createTyped() }
                        Button("New Person") {
                            if let person = model.createPerson(text) { add(person: person.id) }
                            text = ""
                        }
                    }
                    .controlSize(.small)
                    HStack(spacing: 4) {
                        Text("Facets for a new tag:").font(.caption).foregroundStyle(.secondary)
                        ForEach(MemeLibrary.Facet.allCases) { facet in
                            Chip(title: Text(FacetLabel.title(facet)), on: newFacets.contains(facet)) {
                                if newFacets.contains(facet) { newFacets.remove(facet) } else { newFacets.insert(facet) }
                            }
                        }
                    }
                }
            } else if !suggested.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Suggested").font(.caption).foregroundStyle(.secondary)
                    FlowLayout(spacing: 6) {
                        ForEach(suggested) { tag in
                            Chip(title: Text(tag.name), on: false) { add(tag: tag.id) }
                        }
                    }
                }
            }
        }
        .onAppear { focused = true }
    }

    private func toggle(tag id: String) {
        // Partial or absent: give it to all. Present on all: take it away.
        if share(\.tags, id) == 1 {
            model.labelMemes(targets, removeTags: [id], markTagged: false)
        } else {
            add(tag: id)
        }
    }

    private func toggle(person id: String) {
        if share(\.people, id) == 1 {
            model.labelMemes(targets, removePeople: [id], markTagged: false)
        } else {
            add(person: id)
        }
    }

    private func add(tag id: String) {
        model.labelMemes(targets, addTags: [id], markTagged: false)
        text = ""
    }

    private func add(person id: String) {
        model.labelMemes(targets, addPeople: [id], markTagged: false)
        text = ""
    }

    /// Return takes the first match, person or tag, or makes a new tag.
    private func addTyped() {
        let found = matches
        if let person = found.people.first, found.tags.isEmpty {
            add(person: person.id)
        } else if let tag = found.tags.first {
            add(tag: tag.id)
        } else {
            createTyped()
        }
    }

    private func createTyped() {
        let name = text.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, let tag = model.createTag(name, facets: MemeLibrary.Facet.allCases.filter(newFacets.contains)) else { return }
        add(tag: tag.id)
        newFacets = []
    }
}

/// A label on the meme: filled when all of the selection has it, outlined when some do.
private struct LabelChip: View {
    let text: String
    let systemImage: String?
    let share: Double
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let systemImage { Image(systemName: systemImage) }
                Text(text)
                if share < 1 { Image(systemName: "minus").font(.caption2).foregroundStyle(.secondary) }
                Image(systemName: share < 1 ? "plus.circle" : "xmark.circle.fill").foregroundStyle(.secondary)
            }
            .font(.callout)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(share == 1 ? Color.accentColor.opacity(0.25) : Color.clear, in: Capsule())
            .overlay(Capsule().strokeBorder(Color.accentColor.opacity(share == 1 ? 0 : 0.6), style: StrokeStyle(lineWidth: 1, dash: [3])))
        }
        .buttonStyle(.plain)
        .help(share < 1 ? String(localized: "Some of the selection has this. Click to give it to all.") : String(localized: "Click to remove"))
    }
}

/// Lays chips out left to right, wrapping.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                y += line + spacing
                x = 0
                line = 0
            }
            x += size.width + spacing
            line = max(line, size.height)
            widest = max(widest, x)
        }
        return CGSize(width: min(widest, width), height: y + line)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, line: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                y += line + spacing
                x = bounds.minX
                line = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            line = max(line, size.height)
        }
    }
}

/// All the tags: rename, set facets, delete.
struct TagManager: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var renaming: MemeLibrary.Tag?
    @State private var deleting: MemeLibrary.Tag?

    var body: some View {
        VStack(spacing: 0) {
            if model.memeSnapshot.tags.isEmpty {
                ContentUnavailableView("No tags yet", systemImage: "tag",
                                       description: Text("Tags are made while tagging a meme."))
            } else {
                List {
                    ForEach(model.memeSnapshot.tags.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }) { tag in
                        let uses = model.memeSnapshot.items.filter { $0.tags.contains(tag.id) }.count
                        HStack {
                            VStack(alignment: .leading) {
                                Text(tag.name)
                                Text("\(uses) memes").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            ForEach(MemeLibrary.Facet.allCases) { facet in
                                Chip(title: Text(FacetLabel.title(facet)), on: tag.facets.contains(facet)) {
                                    var facets = tag.facets
                                    if let index = facets.firstIndex(of: facet) { facets.remove(at: index) } else { facets.append(facet) }
                                    model.setFacets(facets, of: tag.id)
                                }
                            }
                        }
                        .contextMenu {
                            Button("Rename…") { renaming = tag }
                            Button("Delete", role: .destructive) { deleting = tag }
                        }
                    }
                }
            }
            HStack {
                Text("Right-click a tag to rename or delete it.").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(12)
        }
        .frame(width: 720, height: 480)
        .sheet(item: $renaming) { tag in
            NameSheet(title: "Rename Tag", initial: tag.name) { model.renameTag(tag.id, to: $0) }
        }
        .confirmationDialog(Text("Delete “\(deleting?.name ?? "")”?"),
                            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Delete", role: .destructive) {
                if let tag = deleting { model.deleteTag(tag.id) }
                deleting = nil
            }
        } message: {
            Text("It comes off every meme that has it. The memes stay.")
        }
    }
}
