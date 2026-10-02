import ArsivinyoCore
import SwiftUI

/// What the player window plays, and what to remember it as.
struct WatchPlayRequest: Hashable {
    var url: URL
    var headers: [String: String]
    var title: WatchLibrary.Title
    var videoId: String
    var videoName: String
    var source: WatchService.Source?
    var bingeGroup: String?
    var startMs: Int64
    /// The episode after this one, for a series.
    var nextVideo: Addons.Video?
    /// False for a trailer: watching it is not watching the title.
    var record = true
    /// The stream's own subtitles, and its file name, which subtitle add-ons match on.
    var subtitles: [Addons.Subtitle] = []
    var filename: String?
}

/// A title to open, with what is already known about it.
struct WatchRoute: Hashable {
    var title: WatchLibrary.Title
}

extension AppModel {
    /// Something a player can open: a media URL as it is; a page, a YouTube video or an
    /// external link through yt-dlp first.
    func prepare(_ stream: Addons.Stream) async throws -> (url: URL, headers: [String: String]) {
        if stream.kind == .torrent { throw WatchService.Failure(code: "WATCH_TORRENTS_LATER") }
        if stream.kind == .url, let url = URL(string: stream.target), await !watch.isPage(stream.target, headers: stream.headers) {
            return (url, stream.headers)
        }
        let resolved = try await engine.resolveStream(stream.target)
        return (resolved.url, resolved.headers)
    }

    /// The next episode from the same add-on and binge group, as Stremio does.
    func nextRequest(after request: WatchPlayRequest) async -> WatchPlayRequest? {
        guard let next = request.nextVideo, let source = request.source,
              let streams = try? await watch.streams(from: source, type: request.title.type, id: next.id),
              let stream = streams.first(where: { $0.kind != .torrent && request.bingeGroup != nil && $0.bingeGroup == request.bingeGroup })
                ?? streams.first(where: { $0.kind != .torrent }),
              let prepared = try? await prepare(stream),
              let meta = try? await watch.meta(type: request.title.type, id: request.title.id) else { return nil }
        let regular = meta.videos.filter { ($0.season ?? 1) != 0 }
        let following = regular.firstIndex(where: { $0.id == next.id }).flatMap { $0 + 1 < regular.count ? regular[$0 + 1] : nil }
        return WatchPlayRequest(url: prepared.url, headers: prepared.headers, title: request.title, videoId: next.id,
                                videoName: WatchTitleView.episodeName(next), source: source,
                                bingeGroup: stream.bingeGroup, startMs: 0, nextVideo: following,
                                subtitles: stream.subtitles, filename: stream.filename)
    }
}

/// Watch: continue watching, then every add-on's catalogs as rows, or search across them
/// (`shared/watch/CONTRACT.md`, phase 1). Each row loads on its own.
struct WatchView: View {
    @Environment(AppModel.self) private var model
    @State private var path: [WatchRoute] = []
    @State private var rows: [WatchService.Row] = []
    @State private var continuing: [WatchLibrary.Item] = []
    @State private var saved: [WatchLibrary.Item] = []
    @State private var hasAddons: Bool?
    @State private var query = ""
    @State private var searching = ""
    @State private var managingAddons = false
    @State private var problem: String?
    @State private var installing = false

    var body: some View {
        NavigationStack(path: $path) {
            board
                .navigationDestination(for: WatchRoute.self) { route in WatchTitleView(title: route.title) }
        }
        .searchable(text: $query, placement: .toolbar, prompt: Text("Search films and series"))
        .onSubmit(of: .search) { searching = query.trimmingCharacters(in: .whitespaces) }
        .onChange(of: query) { _, text in if text.isEmpty { searching = "" } }
        .toolbar {
            ToolbarItem {
                Button { managingAddons = true } label: { Label("Add-ons", systemImage: "puzzlepiece.extension") }
                    .help("Install, order and remove add-ons")
            }
        }
        .sheet(isPresented: $managingAddons, onDismiss: reload) { AddonsSheet() }
        .task(id: searching) { reload() }
        .onAppear(perform: reload)
        .onChange(of: model.watchNotice) { _, _ in reload() }
        .safeAreaInset(edge: .top) {
            if let notice = model.watchNotice {
                HStack {
                    Text(notice)
                    Spacer()
                    Button { model.watchNotice = nil } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
                }
                .padding(10)
                .background(.bar)
            }
        }
    }

    private func reload() {
        hasAddons = !((try? model.watch.library.addons()) ?? []).isEmpty
        rows = (try? searching.isEmpty ? model.watch.rows() : model.watch.searchable()) ?? []
        continuing = (try? model.watch.library.continueWatching()) ?? []
        saved = ((try? model.watch.library.items()) ?? []).filter(\.saved).sorted { $0.addedAt > $1.addedAt }
    }

    @ViewBuilder
    private var board: some View {
        if hasAddons == false {
            ContentUnavailableView {
                Label("Nothing to watch yet", systemImage: "play.tv")
            } description: {
                Text("Watch shows catalogs and streams from Stremio add-ons. Start with Cinemeta, Stremio's own catalog, then add the add-ons you use.")
            } actions: {
                Button {
                    installing = true
                    Task {
                        do {
                            try await model.watch.install("https://v3-cinemeta.strem.io/manifest.json")
                            problem = nil
                        } catch {
                            problem = String(describing: error)
                        }
                        installing = false
                        reload()
                    }
                } label: {
                    if installing { ProgressView().controlSize(.small) } else { Text("Install Cinemeta") }
                }
                .buttonStyle(.borderedProminent)
                Button("Add Another Add-on…") { managingAddons = true }
                if let problem { Text(problem).foregroundStyle(.red) }
            }
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 22) {
                    if searching.isEmpty && !continuing.isEmpty {
                        Shelf(title: Text("Continue Watching")) {
                            ForEach(continuing) { item in
                                PosterTile(name: item.name, poster: item.poster,
                                           progress: item.progress.map { Double($0.positionMs) / Double(max(1, $0.durationMs)) }) {
                                    path.append(WatchRoute(title: title(of: item)))
                                }
                                .contextMenu {
                                    Button("Remove from Continue Watching") {
                                        try? model.watch.library.dismissProgress(item.id)
                                        reload()
                                    }
                                }
                            }
                        }
                    }
                    if searching.isEmpty && !saved.isEmpty {
                        Shelf(title: Text("My List")) {
                            ForEach(saved) { item in
                                PosterTile(name: item.name, poster: item.poster) { path.append(WatchRoute(title: title(of: item))) }
                            }
                        }
                    }
                    if !searching.isEmpty && rows.isEmpty {
                        Text("No add-on can search. Install one that can, such as Cinemeta.").foregroundStyle(.secondary).padding()
                    }
                    ForEach(rows) { row in
                        CatalogShelf(row: row, search: searching) { preview in
                            path.append(WatchRoute(title: .init(id: preview.id, type: preview.type, name: preview.name, poster: preview.poster)))
                        }
                        .id("\(row.id)|\(searching)")
                    }
                }
                .padding(.vertical, 16)
            }
        }
    }

    private func title(of item: WatchLibrary.Item) -> WatchLibrary.Title {
        .init(id: item.id, type: item.type, name: item.name, poster: item.poster)
    }
}

private struct Shelf<Content: View>: View {
    let title: Text
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            title.font(.title3.bold()).padding(.horizontal, 20)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14) { content }.padding(.horizontal, 20)
            }
        }
    }
}

/// One catalog of one add-on, fetched on its own.
private struct CatalogShelf: View {
    @Environment(AppModel.self) private var model
    let row: WatchService.Row
    let search: String
    let open: (Addons.Preview) -> Void
    @State private var items: [Addons.Preview]?
    @State private var failed: String?

    var body: some View {
        if !(search.isEmpty == false && items?.isEmpty == true) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Text(row.catalog.name).font(.title3.bold())
                    Text("· \(typeName(row.catalog.type)) · \(row.addonName)").foregroundStyle(.secondary)
                }
                .padding(.horizontal, 20)
                if let failed {
                    Text("Did not answer (\(failed)).").foregroundStyle(.secondary).padding(.horizontal, 20)
                } else if let items {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 14) {
                            ForEach(items) { preview in PosterTile(name: preview.name, poster: preview.poster) { open(preview) } }
                        }
                        .padding(.horizontal, 20)
                    }
                } else {
                    ProgressView().controlSize(.small).frame(height: 200).padding(.horizontal, 20)
                }
            }
            .task {
                do {
                    items = try await model.watch.catalog(row, extra: search.isEmpty ? [] : [("search", search)])
                } catch {
                    failed = (error as? WatchService.Failure)?.code ?? String(describing: error)
                }
            }
        }
    }

    private func typeName(_ type: String) -> String {
        switch type {
        case "movie": return String(localized: "Films")
        case "series": return String(localized: "Series")
        case "channel": return String(localized: "Channels")
        case "tv": return String(localized: "TV")
        default: return type
        }
    }
}

private struct PosterTile: View {
    let name: String
    let poster: String?
    var progress: Double?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack(alignment: .bottom) {
                    RoundedRectangle(cornerRadius: 8).fill(.quaternary)
                    if let poster, let url = URL(string: poster) {
                        AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Color.clear }
                    } else {
                        Text(name).font(.caption).foregroundStyle(.secondary).padding(8).frame(maxHeight: .infinity)
                    }
                    if let progress {
                        GeometryReader { geo in
                            Rectangle().fill(Color.accentColor).frame(width: geo.size.width * min(1, max(0.02, progress)), height: 4)
                        }
                        .frame(height: 4)
                    }
                }
                .frame(width: 140, height: 207)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                Text(name).font(.callout).lineLimit(1).frame(width: 140, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
        .help(name)
    }
}

/// A title: what it is, its episodes, and every way to watch the one picked, gathered from
/// every add-on that offers streams for it. Each add-on answers on its own.
struct WatchTitleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    let title: WatchLibrary.Title
    @State private var meta: Addons.Meta?
    @State private var metaFailed: String?
    @State private var item: WatchLibrary.Item?
    @State private var season: Int?
    @State private var episode: Addons.Video?
    @State private var preparingTrailer = false
    @State private var trailerProblem: String?

    private func playTrailer(_ stream: Addons.Stream) {
        preparingTrailer = true
        trailerProblem = nil
        Task {
            defer { preparingTrailer = false }
            do {
                let prepared = try await model.prepare(stream)
                model.watchPlaying = WatchPlayRequest(
                    url: prepared.url, headers: prepared.headers, title: known, videoId: known.id,
                    videoName: String(localized: "Trailer · \(known.name)"), source: nil, bingeGroup: nil,
                    startMs: 0, nextVideo: nil, record: false)
                openWindow(id: "watch-player")
            } catch {
                trailerProblem = String(localized: "Could not play it: \(String(describing: error))")
            }
        }
    }

    private var known: WatchLibrary.Title {
        .init(id: title.id, type: title.type, name: meta?.name ?? title.name, poster: meta?.poster ?? title.poster)
    }

    private var isSeries: Bool { !(meta?.videos.isEmpty ?? true) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                hero
                if let trailer = meta?.trailers.first {
                    Button { playTrailer(trailer) } label: {
                        Label(preparingTrailer ? "Loading…" : "Trailer", systemImage: "play.fill")
                    }
                    .disabled(preparingTrailer)
                }
                if let trailerProblem { Text(trailerProblem).foregroundStyle(.orange) }
                if let description = meta?.description { Text(description).frame(maxWidth: 760, alignment: .leading) }
                if let metaFailed, meta == nil { Text("No add-on describes this title (\(metaFailed)).").foregroundStyle(.secondary) }
                if meta == nil && metaFailed == nil { ProgressView() }
                if isSeries {
                    episodes
                } else if meta != nil || (metaFailed != nil && title.type == "movie") {
                    Text("Streams").font(.title3.bold())
                    StreamList(title: known, video: .init(id: meta?.id ?? title.id, title: known.name),
                               isSeries: false, nextVideo: nil, item: item)
                }
            }
            .padding(20)
        }
        .navigationTitle(known.name)
        .toolbar {
            ToolbarItem {
                Button {
                    try? model.watch.library.setSaved(known, !(item?.saved ?? false))
                    reloadItem()
                } label: {
                    Label(item?.saved == true ? "Remove from My List" : "Add to My List",
                          systemImage: item?.saved == true ? "bookmark.fill" : "bookmark")
                }
            }
        }
        .sheet(item: $episode) { video in
            VStack(spacing: 0) {
                HStack {
                    Text(Self.episodeName(video)).font(.headline)
                    Spacer()
                    Button("Done") { episode = nil }.keyboardShortcut(.cancelAction)
                }
                .padding(14)
                Divider()
                ScrollView {
                    StreamList(title: known, video: video, isSeries: true, nextVideo: next(after: video), item: item)
                        .padding(14)
                }
            }
            .frame(minWidth: 620, minHeight: 440)
        }
        .task {
            reloadItem()
            do {
                meta = try await model.watch.meta(type: title.type, id: title.id)
                let start = meta?.videos.first { $0.id == item?.progress?.videoId }
                    ?? meta?.videos.first { ($0.season ?? 1) != 0 && !(item?.watched.contains($0.id) ?? false) }
                    ?? meta?.videos.first
                season = start?.season ?? 1
            } catch {
                metaFailed = (error as? WatchService.Failure)?.code ?? String(describing: error)
            }
        }
    }

    private func reloadItem() { item = try? model.watch.library.item(title.id) }

    private func next(after video: Addons.Video) -> Addons.Video? {
        let regular = (meta?.videos ?? []).filter { ($0.season ?? 1) != 0 }
        guard let at = regular.firstIndex(where: { $0.id == video.id }), at + 1 < regular.count else { return nil }
        return regular[at + 1]
    }

    static func episodeName(_ v: Addons.Video) -> String {
        let number = v.season.flatMap { s in v.episode.map { "\(s)×\(String(format: "%02d", $0))" } }
        return [number, v.title.isEmpty ? nil : v.title].compactMap { $0 }.joined(separator: " · ")
    }

    private var hero: some View {
        HStack(alignment: .bottom, spacing: 18) {
            if let poster = known.poster, let url = URL(string: poster) {
                AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { Color.secondary.opacity(0.15) }
                    .frame(width: 150, height: 222).clipShape(RoundedRectangle(cornerRadius: 10))
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(known.name).font(.largeTitle.bold())
                Text([meta?.releaseInfo, meta?.runtime, meta?.imdbRating.map { "IMDb \($0)" },
                      meta.map { $0.genres.prefix(3).joined(separator: ", ") }].compactMap { $0 }.filter { !$0.isEmpty }
                        .joined(separator: " · "))
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var episodes: some View {
        let seasons = Array(Set((meta?.videos ?? []).map { $0.season ?? 1 })).sorted { a, b in a == 0 ? false : b == 0 ? true : a < b }
        Picker("Season", selection: Binding(get: { season ?? seasons.first ?? 1 }, set: { season = $0 })) {
            ForEach(seasons, id: \.self) { n in Text(n == 0 ? String(localized: "Specials") : String(localized: "Season \(n)")).tag(n) }
        }
        .pickerStyle(.menu)
        .frame(maxWidth: 220)
        VStack(spacing: 2) {
            ForEach((meta?.videos ?? []).filter { ($0.season ?? 1) == (season ?? seasons.first ?? 1) }) { video in
                let watched = item?.watched.contains(video.id) ?? false
                Button { episode = video } label: {
                    HStack(spacing: 12) {
                        if let thumb = video.thumbnail, let url = URL(string: thumb) {
                            AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { Color.secondary.opacity(0.15) }
                                .frame(width: 120, height: 68).clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                        VStack(alignment: .leading, spacing: 3) {
                            Text(Self.episodeName(video)).lineLimit(1)
                            if let released = video.released { Text(released.prefix(10)).font(.caption).foregroundStyle(.secondary) }
                            if item?.progress?.videoId == video.id, let p = item?.progress {
                                ProgressView(value: Double(p.positionMs), total: Double(max(1, p.durationMs))).frame(maxWidth: 200)
                            }
                        }
                        Spacer()
                        if watched { Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor) }
                    }
                    .padding(8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .contextMenu {
                    Button(watched ? "Mark as Not Watched" : "Mark as Watched") {
                        try? model.watch.library.setWatched(known, videoId: video.id, !watched)
                        reloadItem()
                    }
                }
            }
        }
    }
}

/// Every add-on's streams for one video, each add-on answering on its own.
private struct StreamList: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    let title: WatchLibrary.Title
    let video: Addons.Video
    let isSeries: Bool
    let nextVideo: Addons.Video?
    let item: WatchLibrary.Item?
    @State private var sources: [WatchService.Source] = []
    @State private var streams: [String: [Addons.Stream]] = [:]
    @State private var failures: [String: String] = [:]
    @State private var preparing: String?
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let message { Text(message).foregroundStyle(.orange) }
            if sources.isEmpty { Text("No installed add-on offers streams for this.").foregroundStyle(.secondary) }
            ForEach(sources) { source in
                VStack(alignment: .leading, spacing: 6) {
                    Text(source.name).font(.headline).foregroundStyle(.secondary)
                    if let failure = failures[source.base] {
                        Text("Did not answer (\(failure)).").foregroundStyle(.secondary)
                    } else if let list = streams[source.base] {
                        if list.isEmpty { Text("No streams.").foregroundStyle(.secondary) }
                        ForEach(Array(list.enumerated()), id: \.offset) { _, stream in row(source, stream) }
                    } else {
                        ProgressView().controlSize(.small)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task(id: video.id) {
            sources = (try? model.watch.streamSources(type: title.type, id: video.id)) ?? []
            // Each add-on on its own: the first to answer shows first, a slow one only waits itself.
            for source in sources { Task { await load(source) } }
        }
    }

    private func load(_ source: WatchService.Source) async {
        do {
            streams[source.base] = try await model.watch.streams(from: source, type: title.type, id: video.id)
        } catch {
            failures[source.base] = (error as? WatchService.Failure)?.code ?? String(describing: error)
        }
    }

    private func row(_ source: WatchService.Source, _ stream: Addons.Stream) -> some View {
        Button { play(source, stream) } label: {
            HStack(spacing: 12) {
                Image(systemName: icon(stream.kind)).frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(stream.label.isEmpty ? stream.kind.rawValue : stream.label).lineLimit(2)
                    if !stream.detail.isEmpty { Text(stream.detail).font(.caption).foregroundStyle(.secondary).lineLimit(3) }
                }
                Spacer()
                if preparing == stream.target { ProgressView().controlSize(.small) }
            }
            .padding(10)
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
            .opacity(stream.kind == .torrent ? 0.5 : 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func icon(_ kind: Addons.Kind) -> String {
        switch kind {
        case .url: return "play.circle"
        case .youtube: return "play.rectangle"
        case .torrent: return "arrow.down.circle.dotted"
        case .external: return "arrow.up.forward.square"
        }
    }

    private func play(_ source: WatchService.Source, _ stream: Addons.Stream) {
        if stream.kind == .torrent {
            message = String(localized: "Torrent streams arrive in a later update.")
            return
        }
        preparing = stream.target
        message = nil
        Task {
            defer { preparing = nil }
            do {
                let prepared = try await model.prepare(stream)
                let startMs = item?.progress?.videoId == video.id ? item?.progress?.positionMs ?? 0 : 0
                model.watchPlaying = WatchPlayRequest(
                    url: prepared.url, headers: prepared.headers, title: title, videoId: video.id,
                    videoName: isSeries ? WatchTitleView.episodeName(video) : title.name, source: source,
                    bingeGroup: stream.bingeGroup, startMs: startMs, nextVideo: nextVideo,
                    subtitles: stream.subtitles, filename: stream.filename)
                openWindow(id: "watch-player")
            } catch {
                if stream.kind == .external, let url = URL(string: stream.target) {
                    NSWorkspace.shared.open(url)
                } else {
                    message = String(localized: "Could not play it: \(String(describing: error))")
                }
            }
        }
    }
}

/// Installed add-ons: install from a URL, turn off, reorder, remove. An add-on's URL can hold
/// an account token, so after installing only its name and host are shown.
private struct AddonsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var addons: [WatchLibrary.Addon] = []
    @State private var url = ""
    @State private var busy = false
    @State private var message: String?
    @State private var discovering = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Add-ons").font(.title2.bold())
                Spacer()
                Picker("", selection: $discovering) {
                    Text("Installed").tag(false)
                    Text("Discover").tag(true)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 220)
            }
            if discovering {
                DiscoverAddons(installed: Set(addons.map(\.base))) { address in
                    url = address
                    install()
                }
            } else {
                installedPane
            }
            HStack {
                if let message { Text(message).font(.callout) }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 680, height: 600)
        .onAppear(perform: reload)
    }

    @ViewBuilder
    private var installedPane: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Paste an add-on's address: its manifest link, or a stremio:// link. Configured add-ons often keep an account key in their address, so it is stored encrypted and never shown again.")
                .foregroundStyle(.secondary)
            HStack {
                TextField("https://…/manifest.json", text: $url).onSubmit(install)
                Button("Install", action: install).disabled(busy || url.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            List {
                ForEach(Array(addons.enumerated()), id: \.element.base) { index, addon in
                    let manifest = Addons.manifest(addon.manifest)
                    HStack {
                        VStack(alignment: .leading) {
                            Text(manifest?.name ?? WatchService.host(addon.base)).bold()
                            Text([WatchService.host(addon.base), manifest.map { "v\($0.version)" }].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button { move(addon, index - 1) } label: { Image(systemName: "arrow.up") }.disabled(index == 0)
                        Button { move(addon, index + 1) } label: { Image(systemName: "arrow.down") }.disabled(index == addons.count - 1)
                        Toggle("", isOn: Binding(get: { addon.enabled }, set: { value in
                            try? model.watch.library.setEnabled(base: addon.base, value)
                            reload()
                        }))
                        .labelsHidden()
                        Button(role: .destructive) {
                            try? model.watch.library.uninstall(base: addon.base)
                            reload()
                        } label: { Image(systemName: "trash") }
                    }
                }
            }
            .frame(minHeight: 220)
            SubtitleLanguages()
        }
    }

    private func reload() { addons = (try? model.watch.library.addons()) ?? [] }

    private func move(_ addon: WatchLibrary.Addon, _ position: Int) {
        try? model.watch.library.move(base: addon.base, to: position)
        reload()
    }

    private func install() {
        let address = url
        busy = true
        Task {
            do {
                let manifest = try await model.watch.install(address)
                message = String(localized: "Installed \(manifest.name).")
                url = ""
            } catch {
                message = String(localized: "Could not install it (\(String(describing: error))).")
            }
            busy = false
            reload()
        }
    }
}

/// The languages subtitles and audio are picked in, most preferred first
/// (`shared/watch/CONTRACT.md`, "The player"). A chosen one is clicked to drop it; another is
/// added last from the menu.
private struct SubtitleLanguages: View {
    @Environment(AppModel.self) private var model
    @State private var chosen: [String] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Subtitle languages").font(.headline)
            Text("Subtitles and audio in these languages are picked first, in this order.").foregroundStyle(.secondary)
            HStack {
                ForEach(Array(chosen.enumerated()), id: \.element) { index, code in
                    Button { save(chosen.filter { $0 != code }) } label: {
                        Label("\(index + 1). \(languageName(code))", systemImage: "xmark")
                    }
                }
                Menu("Add a language") {
                    ForEach(Addons.languages.map(\.code).filter { !chosen.contains($0) }, id: \.self) { code in
                        Button(languageName(code)) { save(chosen + [code]) }
                    }
                }
                .fixedSize()
            }
        }
        .onAppear { chosen = (try? model.watch.library.languages()) ?? WatchLibrary.defaultLanguages }
    }

    private func save(_ codes: [String]) {
        try? model.watch.library.setLanguages(codes)
        chosen = (try? model.watch.library.languages()) ?? codes
    }
}

/// Add-ons that installed add-ons offer, from Stremio's own lists (Cinemeta's official and
/// community ones). Configurable ones open their settings page in the browser; its Install
/// button comes back to this app as a stremio:// link.
private struct DiscoverAddons: View {
    @Environment(AppModel.self) private var model
    let installed: Set<String>
    let install: (String) -> Void
    @State private var lists: [WatchService.Row] = []
    @State private var list: WatchService.Row?
    @State private var offers: [Addons.Offer]?
    @State private var failed: String?
    @State private var filter = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Add-ons your installed add-ons recommend, from Stremio's own lists. Many stream add-ons find torrents; those streams play once torrents arrive in a later update.")
                .foregroundStyle(.secondary)
            HStack {
                Picker("List", selection: $list) {
                    ForEach(lists) { row in Text("\(row.catalog.name) · \(row.addonName)").tag(Optional(row)) }
                }
                .frame(maxWidth: 300)
                TextField("Filter", text: $filter)
            }
            if let failed { Text("Did not answer (\(failed)).").foregroundStyle(.secondary) }
            if offers == nil && failed == nil && list != nil { ProgressView().controlSize(.small) }
            List(shown) { offer in
                HStack(alignment: .top, spacing: 10) {
                    if let logo = offer.manifest.logo, let url = URL(string: logo) {
                        AsyncImage(url: url) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                            .frame(width: 32, height: 32)
                    } else {
                        Image(systemName: "puzzlepiece.extension").frame(width: 32, height: 32)
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(offer.manifest.name).bold()
                        if !offer.manifest.description.isEmpty {
                            Text(offer.manifest.description).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                        }
                        Text(offer.manifest.resources.map(\.name).joined(separator: ", ")).font(.caption2).foregroundStyle(.tertiary)
                    }
                    Spacer()
                    if installed.contains(offer.base) {
                        Text("Installed").foregroundStyle(.green)
                    } else if offer.manifest.configurationRequired {
                        Text("Needs configuring first").font(.caption).foregroundStyle(.secondary)
                    } else {
                        Button("Install") { install(offer.base + "/manifest.json") }
                    }
                    if offer.manifest.configurable, let url = URL(string: offer.base + "/configure") {
                        Button("Configure…") { NSWorkspace.shared.open(url) }
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .onAppear {
            lists = (try? model.watch.offerLists()) ?? []
            if list == nil { list = lists.first }
        }
        .task(id: list) {
            guard let list else { return }
            offers = nil
            failed = nil
            do {
                offers = try await model.watch.offers(list)
            } catch {
                failed = (error as? WatchService.Failure)?.code ?? String(describing: error)
            }
        }
    }

    private var shown: [Addons.Offer] {
        let q = filter.trimmingCharacters(in: .whitespaces).lowercased()
        return (offers ?? []).filter { q.isEmpty || $0.manifest.name.lowercased().contains(q) || $0.manifest.description.lowercased().contains(q) }
    }
}

/// An add-on's stream in the player, in its own window (`shared/watch/CONTRACT.md`, "The
/// player"). It resumes where the library says the video stopped, writes the position down as
/// it plays, brings in subtitles from add-ons, and offers the next episode at the end.
struct WatchPlayerWindow: View {
    @Environment(AppModel.self) private var model
    @State private var player = MPVPlayer()
    @State private var request: WatchPlayRequest?
    @State private var subtitles: [Addons.Subtitle] = []
    @State private var loadingNext = false

    var body: some View {
        VideoPlayerScreen(player: player, title: request?.videoName ?? "", subtitles: subtitles) {
            if player.ended, request?.nextVideo != nil {
                Button {
                    loadingNext = true
                    Task {
                        if let current = request, let next = await model.nextRequest(after: current) { start(next) }
                        loadingNext = false
                    }
                } label: {
                    if loadingNext { ProgressView() } else { Label("Next Episode", systemImage: "forward.end.fill") }
                }
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
            }
        }
        .navigationTitle(request?.videoName ?? "")
        .onAppear { if let pending = model.watchPlaying { start(pending) } }
        .onChange(of: model.watchPlaying) { _, pending in if let pending, pending != request { start(pending) } }
        .task {
            // Where it is, written down every ten seconds.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(10))
                save()
            }
        }
        .onDisappear {
            save()
            player.detach()
        }
    }

    private func start(_ next: WatchPlayRequest) {
        save()
        request = next
        subtitles = []
        player.languages = (try? model.watch.library.languages()) ?? WatchLibrary.defaultLanguages
        player.onEnded = { save(atEnd: true) }
        player.load(.init(url: next.url.absoluteString, headers: next.headers, startMs: next.startMs))
        // A trailer is not the title: add-ons have nothing for it.
        guard next.record else { return }
        Task {
            let found = (try? await model.watch.subtitles(type: next.title.type, id: next.videoId,
                                                         filename: next.filename, own: next.subtitles)) ?? []
            if request == next { subtitles = found }
        }
    }

    private func save(atEnd: Bool = false) {
        guard let request, request.record, player.durationMs > 0 else { return }
        let duration = Int64(player.durationMs)
        try? model.watch.library.recordProgress(request.title, videoId: request.videoId,
                                                positionMs: atEnd ? duration : Int64(player.positionMs), durationMs: duration,
                                                addon: request.source?.base, bingeGroup: request.bingeGroup)
    }
}
