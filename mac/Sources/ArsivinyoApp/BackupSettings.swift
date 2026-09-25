import ArsivinyoCore
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// The phone's backup file. Not declared by the system, so it is matched by extension.
    static let arsivinyoBackup = UTType(filenameExtension: "avsbck") ?? .data
}

/// Making a backup the phone can restore, and restoring one it made.
struct BackupSettings: View {
    @Environment(AppModel.self) private var model
    @State private var sections: Set<BackupSection> = Set(BackupSection.allCases)
    @State private var passphrase = ""
    @State private var again = ""
    @State private var restoring: PendingRestore?

    struct PendingRestore: Identifiable {
        let id = UUID()
        let url: URL
        let header: BackupHeader
    }

    var body: some View {
        Form {
            if let job = model.backupJob {
                Section { BackupProgressView(job: job) }
            }
            Section {
                ForEach(BackupSection.allCases) { section in
                    Toggle(isOn: Binding(get: { sections.contains(section) },
                                         set: { if $0 { sections.insert(section) } else { sections.remove(section) } })) {
                        VStack(alignment: .leading) {
                            Text(BackupSettings.title(section))
                            Text(BackupSettings.detail(section)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                SecureField("Passphrase for the backup", text: $passphrase)
                SecureField("And again", text: $again)
                if !again.isEmpty && again != passphrase {
                    Text("Those do not match.").font(.caption).foregroundStyle(.secondary)
                }
                HStack {
                    Spacer()
                    Button("Back Up…", action: backUp)
                        .disabled(sections.isEmpty || passphrase.count < 8 || passphrase != again || model.backupJob?.isRunning == true)
                }
            } header: {
                Text("Make a Backup")
            } footer: {
                Text("One file, encrypted with this passphrase, that the phone can restore too. It is separate from the vault's passphrase; without it the backup cannot be opened.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section {
                HStack {
                    Text("Restore from a file made here or on the phone. Nothing is replaced: what is already here stays.")
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("Choose a Backup…", action: chooseBackup)
                        .disabled(model.backupJob?.isRunning == true)
                }
            } header: {
                Text("Restore")
            }
        }
        .formStyle(.grouped)
        .sheet(item: $restoring) { pending in
            RestoreSheet(pending: pending)
        }
    }

    static func title(_ section: BackupSection) -> LocalizedStringKey {
        switch section {
        case .vault: return "Vault"
        case .music: return "Music library"
        case .memes: return "Memes"
        case .settings: return "Presets"
        case .cookies: return "Cookies"
        }
    }

    static func detail(_ section: BackupSection) -> LocalizedStringKey {
        switch section {
        case .vault: return "Every item, decrypted into the backup and encrypted again under its passphrase."
        case .music: return "Tracks, artwork, playlists and Favorites."
        case .memes: return "Memes that are not private, with their tags, people and where they came from. Private ones go with the vault."
        case .settings: return "Your own presets and changes to the built-in ones."
        case .cookies: return "Signed-in sessions. Anyone with the backup and its passphrase can use them."
        }
    }

    private func backUp() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.arsivinyoBackup]
        let date = Date().formatted(.iso8601.year().month().day())
        panel.nameFieldStringValue = "Arsivinyo \(date).avsbck"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        model.createBackup(at: url, secret: passphrase, sections: Array(sections))
        passphrase = ""
        again = ""
    }

    private func chooseBackup() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.arsivinyoBackup]
        panel.message = String(localized: "Choose an .avsbck backup.")
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            restoring = PendingRestore(url: url, header: try Backup.preview(url))
        } catch {
            model.backupJob = BackupJob(kind: .restore)
            model.backupJob?.finish(message: String(describing: error), failed: true)
        }
    }
}

/// What a backup holds, read from its header before any passphrase, and the restore itself.
private struct RestoreSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let pending: BackupSettings.PendingRestore
    @State private var chosen: Set<BackupSection> = []
    @State private var secret = ""

    private var available: [(BackupSection, BackupHeader.Section)] {
        pending.header.sections.compactMap { section in
            BackupSection(rawValue: section.id).map { ($0, section) }
        }
    }

    private var needsUnlock: Bool {
        !model.vaultUnlocked && (chosen.contains(.vault) || chosen.contains(.cookies))
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Made", value: pending.header.createdAt.formatted(date: .abbreviated, time: .shortened))
                ForEach(available, id: \.0) { section, info in
                    Toggle(isOn: Binding(get: { chosen.contains(section) },
                                         set: { if $0 { chosen.insert(section) } else { chosen.remove(section) } })) {
                        HStack {
                            Text(BackupSettings.title(section))
                            Spacer()
                            Text("\(info.itemCount) items, \(ByteCountFormatter.string(fromByteCount: info.plaintextBytes, countStyle: .file))")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } footer: {
                Text("The counts are the backup's own claim, readable without the passphrase. What is actually in it is checked as it is restored.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section {
                SecureField(pending.header.slots.first?.secretKind == "password"
                            ? LocalizedStringKey("The backup's password")
                            : LocalizedStringKey("The backup's passphrase"), text: $secret)
                if needsUnlock {
                    Text("The vault and cookies are restored encrypted with your passphrase, so unlock first.")
                        .font(.caption).foregroundStyle(.orange)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                if needsUnlock {
                    Button("Unlock…") { model.showUnlockSheet = true }
                } else {
                    Button("Restore") {
                        model.restoreBackup(from: pending.url, secret: secret, sections: chosen)
                        dismiss()
                    }
                    .disabled(chosen.isEmpty || secret.isEmpty)
                }
            }
        }
        .onAppear { chosen = Set(available.map(\.0)) }
    }
}

private struct BackupProgressView: View {
    @Environment(AppModel.self) private var model
    let job: BackupJob

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(job.kind == .create ? "Backing up" : "Restoring").font(.headline)
                Spacer()
                if job.isRunning {
                    Button("Cancel") { job.cancel() }
                } else {
                    Button("Done") { model.backupJob = nil }
                }
            }
            if job.isRunning {
                if job.total > 0 {
                    ProgressView(value: Double(job.done), total: Double(job.total))
                } else {
                    ProgressView().progressViewStyle(.linear)
                }
            }
            if let message = job.message {
                Text(message).foregroundStyle(job.failed ? .red : .secondary)
            }
        }
    }
}

/// One backup or restore, for the progress view.
@MainActor
@Observable
final class BackupJob {
    enum Kind { case create, restore }

    let kind: Kind
    var done: Int64 = 0
    var total: Int64 = 0
    var isRunning = true
    var message: String?
    var failed = false
    /// Read from the working thread, so behind a lock of its own rather than the actor.
    let cancelled = CancelFlag()

    init(kind: Kind) { self.kind = kind }

    func cancel() { cancelled.set() }

    func finish(message: String, failed: Bool = false) {
        isRunning = false
        self.message = message
        self.failed = failed
    }
}

final class CancelFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    func set() { lock.withLock { value = true } }
    var isSet: Bool { lock.withLock { value } }
}

extension AppModel {
    private var backupSources: Backup.Sources {
        .init(vault: vault, library: library, presets: presets, cookies: cookies,
              memes: memes, memeFolder: downloadDirectory)
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    func createBackup(at url: URL, secret: String, sections: [BackupSection]) {
        if (sections.contains(.vault) || sections.contains(.cookies)) && !vaultUnlocked {
            showUnlockSheet = true
            return
        }
        let job = BackupJob(kind: .create)
        backupJob = job
        let sources = backupSources
        let version = appVersion
        let flag = job.cancelled
        Task.detached {
            do {
                let header = try Backup.create(
                    at: url, secret: secret, sections: sections, from: sources, appVersion: version,
                    progress: { done, total in Task { @MainActor in job.done = done; job.total = total } },
                    isCancelled: { flag.isSet })
                let count = header.sections.reduce(0) { $0 + $1.itemCount }
                await job.finish(message: String(localized: "Saved \(count) items."))
            } catch Backup.Failure.cancelled {
                await job.finish(message: String(localized: "Cancelled. Nothing was saved."))
            } catch {
                await job.finish(message: String(describing: error), failed: true)
            }
        }
    }

    func restoreBackup(from url: URL, secret: String, sections: Set<BackupSection>) {
        let job = BackupJob(kind: .restore)
        backupJob = job
        let sources = backupSources
        let staging = FileManager.default.temporaryDirectory
            .appendingPathComponent("arsivinyo-restore-\(UUID().uuidString)", isDirectory: true)
        let flag = job.cancelled
        Task {
            do {
                let report = try await Backup.restore(
                    from: url, secret: secret, sections: sections, into: sources, staging: staging,
                    progress: { done, total in Task { @MainActor in job.done = done; job.total = total } },
                    isCancelled: { flag.isSet })
                job.finish(message: Self.describe(report), failed: report.failed > 0)
            } catch Backup.Failure.cancelled {
                job.finish(message: String(localized: "Cancelled. What was restored before stays."))
            } catch {
                job.finish(message: String(describing: error), failed: true)
            }
            refreshVault()
            refreshMusic()
            refreshPresets()
            refreshSecurity()
            refreshMemes()
        }
    }

    static func describe(_ report: Backup.Report) -> String {
        var parts = [String(localized: "\(report.restored) added")]
        if report.duplicates > 0 { parts.append(String(localized: "\(report.duplicates) already here")) }
        if report.applied > 0 { parts.append(String(localized: "\(report.applied) settings and profiles applied")) }
        if report.existing > 0 { parts.append(String(localized: "\(report.existing) cookie profiles kept as they were")) }
        if report.failed > 0 {
            parts.append(String(localized: "\(report.failed) could not be restored: \(report.reasons.joined(separator: "; "))"))
        }
        return parts.joined(separator: ", ") + "."
    }
}
