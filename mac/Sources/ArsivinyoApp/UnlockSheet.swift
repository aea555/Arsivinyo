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
                Spacer()
                Button("Not Now") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(creating ? LocalizedStringKey("Set Passphrase") : LocalizedStringKey("Unlock"), action: submit)
                    .keyboardShortcut(.defaultAction)
                    .disabled(passphrase.isEmpty || working)
            }
        }
        .padding(20)
        .frame(width: 420)
    }

    private func submit() {
        guard !passphrase.isEmpty, !working else { return }
        if creating && passphrase != confirmation {
            problem = "Those do not match."
            return
        }
        // Argon2id at 64 MiB takes a moment; keep the sheet responsive while it runs.
        working = true
        problem = nil
        let secret = passphrase
        let rememberThisMac = remember
        Task {
            let failure = model.unlockVault(passphrase: secret)
            if failure == nil && rememberThisMac {
                try? model.keybox.setRemembered(true)
            }
            working = false
            if let failure { problem = failure } else { dismiss() }
        }
    }
}
