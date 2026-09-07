// The keybox as the app uses it: on disk, across restarts, with slots added and removed.
//
// The property that matters most is that adding or changing a way in never touches what was
// encrypted. If that breaks, every "change my passphrase" silently destroys a vault.

#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QString>
#include <QTemporaryDir>

#include <cstdio>

#include "SecretStore.h"
#include "subkeys.h"

using namespace arsivinyo::crypto;

static int failures = 0;
static void check(bool ok, const QString &what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", qPrintable(what));
    if (!ok) ++failures;
}

static QString hexOf(const SecretBytes &key) {
    return QByteArray(reinterpret_cast<const char *>(key.data()), static_cast<int>(key.size()))
        .toHex();
}

/** The cookie key, as a string, so it can be compared before and after a change. */
static QString cookieKey(const SecretStore &store) {
    SecretBytes key;
    if (!store.purposeKey(kPurposeCookies, &key)) return {};
    return hexOf(key);
}

static QJsonObject readKeybox(const QString &dir) {
    QFile file(dir + "/keybox.json");
    if (!file.open(QIODevice::ReadOnly)) return {};
    return QJsonDocument::fromJson(file.readAll()).object();
}

static QString wrappedFor(const QJsonObject &keybox, const QString &id) {
    for (const QJsonValue &value : keybox["slots"].toArray()) {
        if (value.toObject()["id"].toString() == id) return value.toObject()["wrapped"].toString();
    }
    return {};
}

int main() {
    QTemporaryDir temporary;
    qputenv("ARSIVINYO_DATA_DIR", temporary.path().toUtf8());
    // Cheap parameters: the shipped profile is 64 MiB a go, and this suite unlocks a dozen
    // times. The profile itself is pinned by crypto_test against the Kotlin vectors.
    const Argon2idParams fast = Argon2idParams::Fast();

    QString keyBeforeAnything;
    {
        SecretStore store(fast, nullptr);
        check(!store.configured(), "a fresh install has no keybox");
        check(!store.unlocked(), "and is locked");
        check(store.create("a correct horse battery staple").isEmpty(), "a passphrase can be set");
        check(store.configured() && store.unlocked(), "creating one leaves it unlocked");
        keyBeforeAnything = cookieKey(store);
        check(!keyBeforeAnything.isEmpty(), "an unlocked store derives a purpose key");

        SecretBytes vault;
        store.purposeKey(kPurposeVault, &vault);
        check(hexOf(vault) != keyBeforeAnything, "different purposes give different keys");

        store.lock();
        check(!store.unlocked() && cookieKey(store).isEmpty(), "locking forgets the key");
    }

    {
        SecretStore store(fast, nullptr);
        check(store.configured() && !store.unlocked(), "a restart finds the keybox, still locked");
        check(!store.unlock("not the passphrase").isEmpty(), "the wrong passphrase is refused");
        check(!store.unlocked(), "and does not unlock it");
        check(store.unlock("a correct horse battery staple").isEmpty(), "the right one opens it");
        check(cookieKey(store) == keyBeforeAnything, "the same key comes back after a restart");
    }

    // Remembering on this device: a second slot, and no prompt on the next run.
    {
        SecretStore store(fast, nullptr);
        store.unlock("a correct horse battery staple");
        const QString passphraseWrapBefore = wrappedFor(readKeybox(temporary.path()), "passphrase");
        check(store.setRemembered(true).isEmpty(), "a key file slot can be added");
        check(store.remembered(), "and is reported as present");
        const QJsonObject after = readKeybox(temporary.path());
        check(wrappedFor(after, "passphrase") == passphraseWrapBefore,
              "adding a slot leaves the passphrase slot byte-identical");
        check(after["slots"].toArray().size() == 2, "the keybox now holds two slots");
    }
    {
        SecretStore store(fast, nullptr);
        check(store.unlocked(), "with a key file, a restart unlocks without asking");
        check(cookieKey(store) == keyBeforeAnything, "and reaches the same key");
    }

    // Changing the passphrase must not disturb anything that was encrypted.
    {
        SecretStore store(fast, nullptr);
        store.unlock("a correct horse battery staple");
        check(!store.changePassphrase("wrong", "a different long passphrase").isEmpty(),
              "changing it needs the old one, even at an unlocked window");
        check(store.changePassphrase("a correct horse battery staple",
                                     "a different long passphrase")
                  .isEmpty(),
              "the passphrase can be changed");
        check(cookieKey(store) == keyBeforeAnything,
              "changing the passphrase does not change the content key");
    }
    {
        SecretStore store(fast, nullptr);
        store.lock();
        check(!store.unlock("a correct horse battery staple").isEmpty(),
              "the old passphrase no longer works");
        check(store.unlock("a different long passphrase").isEmpty(), "the new one does");
    }

    // A recovery key, for when the passphrase is gone.
    const QString recoveryPath = temporary.path() + "/recovery.key";
    {
        SecretStore store(fast, nullptr);
        store.unlock("a different long passphrase");
        check(store.exportRecoveryKey(recoveryPath).isEmpty(), "a recovery key can be written");
        check(QFile::exists(recoveryPath), "and lands where it was asked for");
    }
    {
        SecretStore store(fast, nullptr);
        store.lock();
        check(store.unlockWithRecoveryKey(recoveryPath).isEmpty(), "a recovery key opens it");
        check(cookieKey(store) == keyBeforeAnything, "and reaches the same key");
    }

    // Permissions, in the shape identity_test already asserts for device.key.
    for (const QString &name : {QStringLiteral("keybox.json"), QStringLiteral("keybox.key")}) {
        const QFileDevice::Permissions permissions = QFile::permissions(temporary.path() + "/" + name);
        check(!(permissions & QFileDevice::ReadGroup) && !(permissions & QFileDevice::ReadOther),
              name + " is readable only by its owner");
    }

    // The refusal that keeps a vault from being orphaned.
    {
        SecretStore store(fast, nullptr);
        store.unlock("a different long passphrase");
        check(store.setRemembered(false).isEmpty(), "the key file slot can be removed");
        check(!store.remembered(), "and is gone");
        check(!QFile::exists(temporary.path() + "/keybox.key"), "the key file is deleted with it");
    }
    // The guard that keeps a vault from being orphaned. Reaching it means a keybox whose
    // only slot is the key file, which the app cannot produce on its own — so the file is
    // edited by hand to get there, and the refusal is then checked through the real path.
    {
        SecretStore store(fast, nullptr);
        store.unlock("a different long passphrase");
        check(store.setRemembered(true).isEmpty(), "a key file slot is added back");

        QJsonObject keybox = readKeybox(temporary.path());
        QJsonArray onlyKeyfile;
        for (const QJsonValue &value : keybox["slots"].toArray()) {
            if (value.toObject()["kind"].toString() == QLatin1String("keyfile")) {
                onlyKeyfile.append(value);
            }
        }
        keybox["slots"] = onlyKeyfile;
        QFile file(temporary.path() + "/keybox.json");
        file.open(QIODevice::WriteOnly | QIODevice::Truncate);
        file.write(QJsonDocument(keybox).toJson());
        file.close();
        check(onlyKeyfile.size() == 1, "the keybox is down to one slot");
    }
    {
        SecretStore store(fast, nullptr);
        check(store.unlocked(), "the key file still opens it");
        check(!store.setRemembered(false).isEmpty(),
              "removing the last slot is refused, because nothing could open it again");
        check(readKeybox(temporary.path())["slots"].toArray().size() == 1,
              "and the slot is still there");
        check(QFile::exists(temporary.path() + "/keybox.key"),
              "and the key file was not deleted either");
    }

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
