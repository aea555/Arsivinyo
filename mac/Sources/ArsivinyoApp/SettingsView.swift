import ArsivinyoCore
import SwiftUI
import UniformTypeIdentifiers

/// The Settings window, behind ⌘, as on every Mac app.
struct SettingsView: View {
    var body: some View {
        TabView {
            GeneralSettings()
                .frame(width: 560, height: 440)
                .tabItem { Label("General", systemImage: "gearshape") }
            CookieSettings()
                .frame(width: 560, height: 440)
                .tabItem { Label("Cookies", systemImage: "person.badge.key") }
            PresetSettings()
                .frame(width: 720, height: 560)
                .tabItem { Label("Presets", systemImage: "slider.horizontal.3") }
            EngineSettings()
                .frame(width: 560, height: 440)
                .tabItem { Label("Engine", systemImage: "shippingbox") }
            SecuritySettings()
                .frame(width: 560, height: 440)
                .tabItem { Label("Security", systemImage: "lock") }
        }
    }
}

// MARK: - General

private struct GeneralSettings: View {
    @Environment(AppModel.self) private var model
    @State private var problem: String?

    var body: some View {
        @Bindable var model = model
        Form {
            Section("Folders") {
                LabeledContent("Downloads") {
                    FolderField(url: model.downloadDirectory) { chooseDownloads() }
                }
                LabeledContent("Music library") {
                    FolderField(url: model.library.musicFolder) { chooseMusic() }
                }
                Text("Changing the music folder moves the library's files into it.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Downloads") {
                LabeledContent("At the same time") {
                    HStack {
                        Text(model.queue.maxConcurrent, format: .number).monospacedDigit()
                        Stepper("At the same time",
                                value: Binding(get: { model.queue.maxConcurrent },
                                               set: { model.queue.maxConcurrent = $0 }),
                                in: 1...4)
                            .labelsHidden()
                    }
                }
                Toggle("Notify when a download ends in the background", isOn: $model.notifyWhenDone)
            }
            if let problem {
                Text(problem).foregroundStyle(.red)
            }
        }
        .formStyle(.grouped)
    }

    private func chooseDownloads() {
        if let url = pickFolder(prompt: String(localized: "Use This Folder")) {
            model.downloadDirectory = url
        }
    }

    private func chooseMusic() {
        guard let url = pickFolder(prompt: String(localized: "Move Library Here")) else { return }
        problem = model.moveMusicLibrary(to: url)
    }

    private func pickFolder(prompt: String) -> URL? {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.prompt = prompt
        return panel.runModal() == .OK ? panel.url : nil
    }
}

/// A folder the way Finder shows it: its icon and name, the path in a tooltip.
private struct FolderField: View {
    let url: URL
    let change: () -> Void

    var body: some View {
        HStack {
            // A folder that does not exist yet (it is made on the first download) would
            // otherwise show as a blank document.
            Image(nsImage: FileManager.default.fileExists(atPath: url.path)
                  ? NSWorkspace.shared.icon(forFile: url.path)
                  : NSWorkspace.shared.icon(for: .folder))
                .resizable().frame(width: 16, height: 16)
            Text(FileManager.default.displayName(atPath: url.path))
                .lineLimit(1).truncationMode(.middle)
                .help(url.path)
            Button("Change…", action: change)
        }
    }
}

// MARK: - Cookies

private struct CookieSettings: View {
    @Environment(AppModel.self) private var model
    @State private var importing: PendingImport?
    @State private var problem: String?

    struct PendingImport: Identifiable {
        let id = UUID()
        let url: URL
        var scope: CookieStore.Scope
    }

    var body: some View {
        Form {
            Section {
                if model.cookieProfiles.isEmpty {
                    Text("No cookie profiles. A site that refuses signed-out downloads works with a cookies.txt exported from a browser where you are signed in.")
                        .foregroundStyle(.secondary)
                }
                ForEach(groups, id: \.scope) { group in
                    ForEach(group.profiles) { profile in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(profile.scope.label)
                                Text(profile.name).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if profile.isDefault {
                                Text("Default").font(.caption).foregroundStyle(.secondary)
                            } else {
                                Button("Make Default") { model.setDefaultCookies(profile) }
                            }
                            Button(role: .destructive) {
                                model.removeCookies(profile)
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.borderless)
                            .help("Delete this profile")
                        }
                    }
                }
            } header: {
                HStack {
                    Text("Profiles")
                    Spacer()
                    Button("Import cookies.txt…", action: pick)
                }
            } footer: {
                Text("Profiles are encrypted with your passphrase and used only while the vault is unlocked. Locked, downloads run signed out.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let problem {
                Text(problem).foregroundStyle(.red)
            }
        }
        .formStyle(.grouped)
        .sheet(item: $importing) { pending in
            CookieImportSheet(pending: pending) { scope, name in
                problem = model.importCookies(pending.url, into: scope, name: name)
            }
        }
    }

    private var groups: [(scope: CookieStore.Scope, profiles: [CookieStore.Profile])] {
        Dictionary(grouping: model.cookieProfiles, by: \.scope)
            .map { ($0.key, $0.value.sorted { $0.name < $1.name }) }
            .sorted { $0.scope.label.localizedStandardCompare($1.scope.label) == .orderedAscending }
    }

    private func pick() {
        problem = nil
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.plainText, .text, .data]
        panel.message = String(localized: "Choose a cookies.txt in Netscape format.")
        guard panel.runModal() == .OK, let url = panel.url else { return }
        guard model.vaultUnlocked else {
            problem = CookieStore.Failure.locked.description
            model.showUnlockSheet = true
            return
        }
        let data = (try? Data(contentsOf: url)) ?? Data()
        // Filed under the site its cookies are for, so there is usually nothing to choose.
        importing = PendingImport(url: url, scope: CookieStore.scope(forCookies: data) ?? .platform("youtube"))
    }
}

private struct CookieImportSheet: View {
    @Environment(\.dismiss) private var dismiss
    let pending: CookieSettings.PendingImport
    let save: (CookieStore.Scope, String) -> Void

    @State private var scopeId: String = ""
    @State private var customDomain = ""
    @State private var name = "main"

    private let customTag = "custom"

    var body: some View {
        Form {
            Picker("Site", selection: $scopeId) {
                ForEach(CookieStore.platforms, id: \.id) { Text($0.label).tag($0.id) }
                Divider()
                Text("Another site…").tag(customTag)
            }
            if scopeId == customTag {
                TextField("Domain", text: $customDomain, prompt: Text(verbatim: "example.com"))
            }
            TextField("Profile name", text: $name)
        }
        .formStyle(.grouped)
        .frame(width: 380)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Import") {
                    if let scope { save(scope, name); dismiss() }
                }
                .disabled(scope == nil || !CookieStore.isValidName(name))
            }
        }
        .onAppear {
            switch pending.scope {
            case .platform(let id): scopeId = id
            case .domain(let domain): scopeId = customTag; customDomain = domain
            }
        }
    }

    private var scope: CookieStore.Scope? {
        if scopeId == customTag {
            return CookieStore.canonicalDomain(customDomain).map(CookieStore.Scope.domain)
        }
        return scopeId.isEmpty ? nil : .platform(scopeId)
    }
}

// MARK: - Engine

private struct EngineSettings: View {
    @Environment(AppModel.self) private var model
    @State private var chosen = ""

    var body: some View {
        Form {
            Section {
                LabeledContent("In use") {
                    Text(inUse)
                }
                if let bundled = model.engine.bundledYtDlpVersion {
                    LabeledContent("Fetched with the app", value: bundled)
                }
                if let problem = model.engine.ytDlpActivationProblem {
                    Text("A downloaded version would not load, so the one fetched with the app is in use: \(problem)")
                        .font(.caption).foregroundStyle(.orange)
                }
                HStack {
                    Button("Update to the Newest") { model.switchYtDlp(to: nil) }
                    Picker("Version", selection: $chosen) {
                        Text("Choose…").tag("")
                        ForEach(model.ytDlpReleases, id: \.self) { Text($0).tag($0) }
                    }
                    .labelsHidden()
                    .frame(maxWidth: 160)
                    Button("Use") { model.switchYtDlp(to: chosen) }.disabled(chosen.isEmpty)
                }
                .disabled(model.ytDlpWorking != nil)
                if model.engine.ytDlpSource == "override" {
                    Button("Go Back to the One Fetched with the App") { model.switchYtDlp(to: "bundled") }
                        .disabled(model.ytDlpWorking != nil)
                }
                if let working = model.ytDlpWorking {
                    HStack { ProgressView().controlSize(.small); Text(working) }
                } else if let message = model.ytDlpMessage {
                    Text(message).foregroundStyle(.secondary)
                }
            } header: {
                Text(verbatim: "yt-dlp")
            }
            Section {
                Text("Sites change faster than apps do, so the part that reads them updates on its own. Switching restarts the engine; downloads have to finish first.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .onAppear { model.loadYtDlpReleases() }
    }

    private var inUse: String {
        guard let version = model.engine.ytDlpVersion else { return String(localized: "Not running") }
        return model.engine.ytDlpSource == "override"
            ? String(localized: "\(version), downloaded")
            : version
    }
}

// MARK: - Security

private struct SecuritySettings: View {
    @Environment(AppModel.self) private var model
    @State private var changing = false
    @State private var message: String?

    var body: some View {
        Form {
            if !model.vaultConfigured {
                Section {
                    Text("No passphrase is set yet. Setting one starts the vault.")
                    Button("Set a Passphrase…") { model.toggleVaultLock() }
                }
            } else {
                Section("Passphrase") {
                    Button("Change Passphrase…") { changing = true }
                        .disabled(!model.vaultUnlocked)
                }
                Section {
                    Toggle("Remember on this Mac", isOn: Binding(
                        get: { model.isRemembered },
                        set: { message = model.setRemembered($0) }))
                    .disabled(!model.vaultUnlocked)
                } footer: {
                    Text("Keeps a key in your login Keychain so this Mac never asks. Convenient, but anyone logged in as you can then open the vault.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section {
                    LabeledContent("Recovery key") {
                        Text(model.hasRecoveryKey ? "Exported" : "None")
                    }
                    Button(model.hasRecoveryKey ? "Export a New Recovery Key…" : "Export a Recovery Key…",
                           action: exportRecovery)
                        .disabled(!model.vaultUnlocked)
                } footer: {
                    Text("A file that opens the vault if the passphrase is forgotten. Keep it somewhere other than this Mac.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if !model.vaultUnlocked {
                    Section {
                        Button("Unlock to Change These…") { model.toggleVaultLock() }
                    }
                }
            }
            if let message {
                Text(message).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .sheet(isPresented: $changing) {
            ChangePassphraseSheet { old, new in await model.changePassphrase(from: old, to: new) }
        }
    }

    private func exportRecovery() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "Arsivinyo Recovery Key.avskey"
        panel.message = String(localized: "Anyone with this file can open the vault. Keep it off this Mac.")
        guard panel.runModal() == .OK, let url = panel.url else { return }
        message = model.exportRecoveryKey(to: url)
            ?? String(localized: "Saved. Keep it somewhere safe.")
    }
}

private struct ChangePassphraseSheet: View {
    @Environment(\.dismiss) private var dismiss
    let change: (String, String) async -> String?

    @State private var current = ""
    @State private var new = ""
    @State private var again = ""
    @State private var problem: String?
    @State private var working = false

    var body: some View {
        Form {
            SecureField("Current passphrase", text: $current)
            SecureField("New passphrase", text: $new)
            SecureField("And again", text: $again)
            if working {
                ProgressView().controlSize(.small)
            } else if let problem {
                Text(problem).foregroundStyle(.red)
            } else if !again.isEmpty && again != new {
                Text("Those do not match.").foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 380)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Change") {
                    working = true
                    problem = nil
                    Task {
                        problem = await change(current, new)
                        working = false
                        if problem == nil { dismiss() }
                    }
                }
                .disabled(current.isEmpty || new.isEmpty || new != again || working)
            }
        }
    }
}
