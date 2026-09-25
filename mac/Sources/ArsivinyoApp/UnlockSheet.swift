import SwiftUI

/// The passphrase prompt, and on first use the place a passphrase is set.
struct UnlockSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var passphrase = ""
    @State private var confirmation = ""
    @State private var remember = false
    @State private var problem: String?
    @State private var working = false

    private var creating: Bool { !model.vaultConfigured }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(creating ? LocalizedStringKey("Set a Passphrase") : LocalizedStringKey("Unlock the Vault"),
                  systemImage: creating ? "key" : "lock.open")
                .font(.headline)

            // Two Text views rather than a ternary of strings: a ternary is typed as String,
            // and a String is shown as it is, never translated.
            (creating
                ? Text("Your vault and cookies are encrypted with this. There is no way to recover it — write it down, or export a recovery key once it is set.")
                : Text("Everything in the vault is encrypted with your passphrase."))
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            SecureField("Passphrase", text: $passphrase)
                .onSubmit(submit)
            if creating {
                SecureField("And again", text: $confirmation)
                    .onSubmit(submit)
            }

            Toggle("Remember on this Mac", isOn: $remember)
                .help("Keeps a key in your login Keychain so this Mac never asks. Convenient, but anyone logged in as you can then open the vault.")

            if let problem {
                Text(problem).font(.callout).foregroundStyle(.red)
            }

            HStack {
                if working { ProgressView().controlSize(.small) }
                if !creating {
                    Button("Use Recovery Key…", action: useRecoveryKey)
                        .disabled(working)
                }
                if touchID {
                    Button("Use Touch ID", action: useTouchID).disabled(working)
                }
                Spacer()
                Button("Not Now") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(creating ? LocalizedStringKey("Set Passphrase") : LocalizedStringKey("Unlock"), action: submit)
                    .keyboardShortcut(.defaultAction)
                    .disabled(passphrase.isEmpty || working)
            }
        }
        .padding(20)
        .frame(width: 420)
        // Asked for straight away when that is how this Mac is set up; the passphrase stays
        // one click away.
        .task { if touchID { useTouchID() } }
    }

    private func submit() {
        guard !passphrase.isEmpty, !working else { return }
        if creating && passphrase != confirmation {
            problem = String(localized: "Those do not match.")
            return
        }
        // Argon2id at 64 MiB takes a moment; keep the sheet responsive while it runs.
        working = true
        problem = nil
        let secret = passphrase
        let rememberThisMac = remember
        Task {
            let failure = await model.unlockVault(passphrase: secret)
            if failure == nil && rememberThisMac {
                _ = model.setRemembered(true)
            }
            working = false
            if let failure { problem = failure } else { dismiss() }
        }
    }

    private var touchID: Bool {
        !creating && model.askTouchID && model.isRemembered && model.touchIDAvailable
    }

    private func useTouchID() {
        working = true
        problem = nil
        Task {
            let failure = await model.unlockWithTouchID()
            working = false
            if let failure { problem = failure } else { dismiss() }
        }
    }

    private func useRecoveryKey() {
        let panel = NSOpenPanel()
        panel.message = String(localized: "Choose the recovery key you exported.")
        guard panel.runModal() == .OK, let url = panel.url else { return }
        if let failure = model.unlockVault(recoveryKeyAt: url) {
            problem = failure
        } else {
            dismiss()
        }
    }
}
