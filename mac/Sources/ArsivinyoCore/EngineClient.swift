import Foundation

/// The download engine, as a child process.
///
/// `shared/engine/host.py` is the same engine the Android app runs; it speaks JSON, one
/// object per line, over stdin and stdout. Driving it as a subprocess rather than
/// reimplementing yt-dlp in Swift is the whole reason the Mac app can reach parity at all —
/// and it is the arrangement the Qt app used, so the boundary is already proven.
///
/// Events arrive on a background reader and are handed to the main actor, because everything
/// that reacts to them is UI.
@MainActor
@Observable
public final class EngineClient {

    // MARK: - Configuration

    /// Where the pieces live. Separate from the class so a test or a bundled app can point
    /// at a different layout without changing anything here.
    public struct Layout: Sendable {
        public var python: URL
        /// The directory holding `host.py` and `local_downloader.py`.
        public var engine: URL
        /// The unpacked yt-dlp, which the updater can replace underneath us.
        public var ytDlp: URL
        /// Extra packages: curl_cffi for impersonation, which is what stops sites refusing.
        public var site: URL?
        /// Needed to merge separate video and audio streams, which most sites serve.
        /// The engine only looks beside its own executable, so it is passed explicitly.
        public var ffmpeg: URL?

        /// Where yt-dlp and its downloaded overrides live together. The updater writes here
        /// and the bootstrap reads here, so both have to be told the same place.
        public var root: URL { ytDlp.deletingLastPathComponent() }

        public init(python: URL, engine: URL, ytDlp: URL, site: URL? = nil, ffmpeg: URL? = nil) {
            self.python = python
            self.engine = engine
            self.ytDlp = ytDlp
            self.site = site
            self.ffmpeg = ffmpeg
        }
    }

    public enum Failure: Error, CustomStringConvertible {
        case notRunning
        case launchFailed(String)
        public var description: String {
            switch self {
            case .notRunning: return "the engine is not running"
            case .launchFailed(let why): return "the engine would not start: \(why)"
            }
        }
    }

    /// One line from the engine.
    public enum Event: Sendable {
        /// Emitted once, when the engine is up.
        case ready(frozen: Bool)
        case progress(status: String, percent: Double?, detail: String?)
        case finished(Result<JSONValue, EngineError>)

        public struct EngineError: Error, Sendable, CustomStringConvertible {
            public let description: String
            /// The engine's own code, such as DOWNLOAD_FAILED, when it gave one. What a
            /// retry is decided on; the message is only for people.
            public var code: String? = nil
        }
    }

    // MARK: - State

    public private(set) var isRunning = false
    /// The yt-dlp the engine actually loaded, once it has said so.
    public private(set) var ytDlpVersion: String?
    /// The yt-dlp fetched with the app, which is what "use the bundled one" goes back to.
    public private(set) var bundledYtDlpVersion: String?
    /// "bundled" or "override": which copy the bootstrap put first on the path.
    public private(set) var ytDlpSource: String?
    /// Set when an override was downloaded but would not load, so the fall back to the
    /// bundled copy is visible rather than silent.
    public private(set) var ytDlpActivationProblem: String?

    public var layoutRoot: URL { layout.root }
    /// The ffmpeg the engine uses, which the preset renderer shares.
    public var ffmpegURL: URL? { layout.ffmpeg }

    private let layout: Layout
    private var process: Process?
    private var stdin: FileHandle?
    private var nextId = 0
    private var listeners: [String: (Event) -> Void] = [:]

    public init(layout: Layout) {
        self.layout = layout
    }

    // MARK: - Lifecycle

    public func start() throws {
        guard process == nil else { return }

        let task = Process()
        task.executableURL = layout.python
        // Through the bootstrap, not host.py directly: it is what puts a downloaded yt-dlp
        // ahead of the bundled one. Started without it, an update would download and then
        // never be used.
        task.arguments = [layout.engine.appendingPathComponent("bootstrap.py").path]

        var searchPath: [String] = []
        if let site = layout.site { searchPath.append(site.path) }
        searchPath.append(layout.engine.path)

        var environment = ProcessInfo.processInfo.environment
        environment["PYTHONPATH"] = searchPath.joined(separator: ":")
        environment["ARSIVINYO_ENGINE_ROOT"] = layout.root.path
        // Otherwise the engine's own output sits in a buffer and progress arrives in bursts
        // at the end, which looks like a hang.
        environment["PYTHONUNBUFFERED"] = "1"
        task.environment = environment

        let input = Pipe()
        let output = Pipe()
        task.standardInput = input
        task.standardOutput = output
        task.standardError = Pipe()

        do {
            try task.run()
        } catch {
            throw Failure.launchFailed(error.localizedDescription)
        }

        process = task
        stdin = input.fileHandleForWriting
        isRunning = true

        read(from: output.fileHandleForReading)

        task.terminationHandler = { [weak self] ended in
            // Only if it is still the current process: after a restart the old one ends
            // late, and must not take the new one's state with it.
            let endedId = ObjectIdentifier(ended)
            Task { @MainActor in
                guard let self, let process = self.process,
                      ObjectIdentifier(process) == endedId else { return }
                self.isRunning = false
                self.process = nil
                self.stdin = nil
                // Nothing will answer these now; let their callers stop waiting.
                for (_, listener) in self.listeners {
                    listener(.finished(.failure(.init(description: "the engine stopped"))))
                }
                self.listeners.removeAll()
            }
        }
    }

    public func stop() {
        process?.terminate()
        process = nil
        stdin = nil
        isRunning = false
        for (_, listener) in listeners {
            listener(.finished(.failure(.init(description: "the engine stopped"))))
        }
        listeners.removeAll()
    }

    /// A downloaded yt-dlp is only picked up at start, so switching versions ends here.
    public func restart() throws {
        stop()
        ytDlpVersion = nil
        ytDlpSource = nil
        bundledYtDlpVersion = nil
        ytDlpActivationProblem = nil
        try start()
    }

    // MARK: - Requests

    /// Sends one request and streams back everything the engine says about it.
    ///
    /// The id comes back with the stream because cancelling is a separate request that has
    /// to name the one being cancelled.
    @discardableResult
    public func perform(_ operation: String, _ arguments: [String: Any] = [:])
        -> (id: String, events: AsyncStream<Event>)
    {
        nextId += 1
        let id = String(nextId)

        let events = AsyncStream<Event> { continuation in
            guard let stdin else {
                continuation.yield(.finished(.failure(.init(description: "the engine is not running"))))
                continuation.finish()
                return
            }

            listeners[id] = { event in
                continuation.yield(event)
                if case .finished = event { continuation.finish() }
            }

            var request = arguments
            request["id"] = id
            request["op"] = operation
            if let ffmpeg = layout.ffmpeg, request["ffmpegPath"] == nil {
                request["ffmpegPath"] = ffmpeg.path
            }
            guard let line = try? JSONSerialization.data(withJSONObject: request) else {
                continuation.yield(.finished(.failure(.init(description: "could not encode the request"))))
                continuation.finish()
                return
            }
            stdin.write(line)
            stdin.write(Data("\n".utf8))

            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.listeners[id] = nil }
            }
        }
        return (id, events)
    }

    /// Cancels a running download by the id of the request that started it.
    public func cancel(id: String) {
        guard let stdin,
              let line = try? JSONSerialization.data(withJSONObject: ["id": id, "op": "cancel"])
        else { return }
        stdin.write(line)
        stdin.write(Data("\n".utf8))
    }

    // MARK: - Reading

    private func read(from handle: FileHandle) {
        // A detached reader, because `availableData` blocks and the main actor must not.
        nonisolated(unsafe) let handle = handle
        Task.detached {
            var buffer = Data()
            while true {
                let chunk = handle.availableData
                if chunk.isEmpty { break }
                buffer.append(chunk)
                // One JSON object per line; a partial line stays in the buffer.
                while let newline = buffer.firstIndex(of: 0x0A) {
                    let line = buffer[buffer.startIndex..<newline]
                    buffer.removeSubrange(buffer.startIndex...newline)
                    guard !line.isEmpty,
                          let object = try? JSONSerialization.jsonObject(with: line)
                    else { continue }
                    // Converted here, on this side of the hop: a [String: Any] is not
                    // Sendable and must not cross to the main actor.
                    let value = JSONValue(object)
                    await MainActor.run { self.dispatch(value) }
                }
            }
        }
    }

    private func dispatch(_ object: JSONValue) {
        let type = object["type"]?.string

        if type == "bootstrap" {
            let status = object["ytDlp"]
            ytDlpSource = status?["source"]?.string
            bundledYtDlpVersion = status?["bundledVersion"]?.string
            // failedReason stays in the manifest after a failure, so it only counts while a
            // failed version is named alongside it.
            ytDlpActivationProblem = status?["activateError"]?.string
                ?? (status?["failedVersion"]?.string != nil ? status?["failedReason"]?.string : nil)
            return
        }

        if type == "ready" {
            let frozen = object["frozen"]?.bool ?? false
            for (_, listener) in listeners { listener(.ready(frozen: frozen)) }
            return
        }

        guard let id = object["id"]?.string, let listener = listeners[id] else { return }

        switch type {
        case "progress":
            listener(.progress(
                status: object["status"]?.string ?? "working",
                percent: object["progressPercent"]?.double,
                detail: object["detail"]?.string))

        case "ytDlpProgress":
            let done = object["done"]?.double ?? 0
            let total = object["total"]?.double ?? 0
            listener(.progress(
                status: object["stage"]?.string ?? "working",
                percent: total > 0 ? done / total * 100 : nil,
                detail: nil))

        case "result":
            listeners[id] = nil
            if object["ok"]?.bool == true {
                let payload = object["result"] ?? .object([:])
                if let version = payload["ytDlp"]?.string { ytDlpVersion = version }
                // The request succeeded; the download inside it may not have. The engine
                // reports that as ok with success false, and reading only `ok` would show
                // a refused download as finished.
                if payload["success"]?.bool == false {
                    let message = payload["message"]?.string
                        ?? payload["code"]?.string
                        ?? "the download failed"
                    listener(.finished(.failure(.init(description: message, code: payload["code"]?.string))))
                } else {
                    listener(.finished(.success(payload)))
                }
            } else {
                let message = object["error"]?.string ?? "the download failed"
                listener(.finished(.failure(.init(description: message))))
            }

        default:
            break
        }
    }
}

// MARK: - Finding the pieces

extension EngineClient.Layout {
    /// The layout while working in the repository, as opposed to inside a built bundle.
    ///
    /// `mac/.build/engine` is where `ytdlp_updater.py` unpacks yt-dlp and where curl_cffi is
    /// installed, mirroring what the Qt build did into its own build directory.
    public static func development(repositoryRoot: URL) -> Self? {
        let engine = repositoryRoot.appendingPathComponent("shared/engine")
        let build = repositoryRoot.appendingPathComponent("mac/.build/engine")
        let ytDlp = build.appendingPathComponent("yt-dlp")
        let site = build.appendingPathComponent("site")

        // Whichever Python is around, newest first. macOS ships 3.9, which is too old for
        // a current yt-dlp, so the system one is deliberately last.
        let candidates = [
            "/opt/homebrew/opt/python@3.13/bin/python3.13",
            "/opt/homebrew/opt/python@3.12/bin/python3.12",
            "/opt/homebrew/bin/python3",
            "/usr/bin/python3",
        ]
        guard let python = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }),
              FileManager.default.fileExists(atPath: engine.appendingPathComponent("host.py").path),
              FileManager.default.fileExists(atPath: ytDlp.path)
        else { return nil }

        // Beside the engine first, so a bundled copy wins, then Homebrew, then PATH.
        let ffmpegCandidates = [
            build.appendingPathComponent("ffmpeg").path,
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
        ]
        let ffmpeg = ffmpegCandidates
            .first { FileManager.default.isExecutableFile(atPath: $0) }
            .map { URL(fileURLWithPath: $0) }

        return .init(
            python: URL(fileURLWithPath: python),
            engine: engine,
            ytDlp: ytDlp,
            site: FileManager.default.fileExists(atPath: site.path) ? site : nil,
            ffmpeg: ffmpeg)
    }

    /// Walks up from a file in the repository until it finds the root.
    public static func developmentFromSource(_ file: String = #filePath) -> Self? {
        var dir = URL(fileURLWithPath: file).deletingLastPathComponent()
        while !FileManager.default.fileExists(
            atPath: dir.appendingPathComponent("shared/engine/host.py").path
        ) {
            let parent = dir.deletingLastPathComponent()
            if parent == dir { return nil }
            dir = parent
        }
        return development(repositoryRoot: dir)
    }
}

// MARK: - Requests the engine understands

extension EngineClient {
    /// The arguments for a download, in the host's own keys.
    ///
    /// Built here rather than at the call site because the host ignores keys it does not
    /// know. Sending `mediaKind: "audio"` — a reasonable-looking guess — downloads the video
    /// every time, with no error. `CoreChecks` holds this to the keys `host.py` reads.
    nonisolated public static func downloadArguments(url: String, outputDirectory: URL, audioOnly: Bool,
                                         cookiesDirectory: URL? = nil,
                                         cookieProfile: String? = nil) -> [String: Any] {
        var arguments: [String: Any] = [
            "url": url,
            "outputDir": outputDirectory.path,
            "audioOnly": audioOnly,
            // Left to itself the engine takes what the phone plays best, VP9 with Opus,
            // which no Mac player opens. H.264 and AAC first, when the site has them.
            "preferAppleCodecs": true,
        ]
        if let cookiesDirectory { arguments["cookiesDir"] = cookiesDirectory.path }
        if let cookieProfile { arguments["cookieProfile"] = cookieProfile }
        return arguments
    }
}
