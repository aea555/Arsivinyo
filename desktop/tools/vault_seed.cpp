// Fills a vault with sample items, so the vault screen can be looked at with something in
// it. Not part of the app and not a test — the same footing as pair_probe.
//
//   cmake -DARSIVINYO_DEV_TOOLS=ON ... && ARSIVINYO_DATA_DIR=/tmp/x ./vault_seed
//
// It sets a known passphrase and turns on "remember on this device", so the app launched
// against the same directory opens unlocked.

#include <QCoreApplication>
#include <QProcess>
#include <QTemporaryDir>

#include <cstdio>

#include "SecretStore.h"
#include "Vault.h"

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    if (qEnvironmentVariableIsEmpty("ARSIVINYO_DATA_DIR")) {
        std::printf("set ARSIVINYO_DATA_DIR first; this writes a keybox and a vault\n");
        return 2;
    }

    // Cheap parameters: this is a fixture, and the passphrase is printed below anyway.
    SecretStore secrets(arsivinyo::crypto::Argon2idParams::Fast(), nullptr);
    Vault vault;
    vault.setSecrets(&secrets);

    if (!secrets.configured()) {
        const QString created = secrets.create("a correct horse battery staple");
        if (!created.isEmpty()) {
            std::printf("create failed: %s\n", qPrintable(created));
            return 1;
        }
        secrets.setRemembered(true);
    } else {
        secrets.unlock("a correct horse battery staple");
    }

    QTemporaryDir scratch;
    const char *names[] = {"Holiday footage", "Interview take 3", "Old home video"};
    for (const char *name : names) {
        const QString path = scratch.path() + "/" + QString::fromLatin1(name) + ".mp4";
        QProcess ffmpeg;
        ffmpeg.start("ffmpeg", {"-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                                "testsrc=size=320x240:rate=15:duration=2", "-c:v", "libx264",
                                "-preset", "ultrafast", "-pix_fmt", "yuv420p", path});
        ffmpeg.waitForFinished(60000);
        const QString imported = vault.importFile(path, true);
        std::printf("%-20s %s\n", name, imported.isEmpty() ? "in" : qPrintable(imported));
    }
    std::printf("passphrase: a correct horse battery staple\n");
    return 0;
}
