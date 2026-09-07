// The vault: what lands on disk, and what refuses to happen.
//
// Two negatives carry the weight. The object must not contain the file's contents, and the
// index must not contain its title — the phone encrypts the first and not the second, which
// is the gap this does not repeat.

#include <QDir>
#include <QFile>
#include <QString>
#include <QTemporaryDir>

#include <cstdio>

#include "SecretStore.h"
#include "Vault.h"

static int failures = 0;
static void check(bool ok, const QString &what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", qPrintable(what));
    if (!ok) ++failures;
}

static bool fileContains(const QString &path, const QByteArray &needle) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return false;
    return file.readAll().contains(needle);
}

int main() {
    QTemporaryDir temporary;
    qputenv("ARSIVINYO_DATA_DIR", temporary.path().toUtf8());
    const auto fast = arsivinyo::crypto::Argon2idParams::Fast();

    // Big enough to span several 1 MiB segments, so the streaming path is what is tested.
    const QByteArray marker = "THE-CONTENTS-OF-A-PRIVATE-FILE";
    QByteArray payload;
    payload.reserve(5 * 1024 * 1024);
    while (payload.size() < 5 * 1024 * 1024) payload.append(marker).append(QByteArray(97, 'x'));

    const QString sourcePath = temporary.path() + "/A Very Private Recording.mp4";
    {
        QFile source(sourcePath);
        source.open(QIODevice::WriteOnly);
        source.write(payload);
        source.close();
    }

    SecretStore secrets(fast, nullptr);
    Vault vault;
    vault.setSecrets(&secrets);

    check(!vault.importFile(sourcePath, false).isEmpty(), "importing while locked is refused");
    check(secrets.create("a correct horse battery staple").isEmpty(), "a passphrase is set");
    check(vault.importFile(sourcePath, false).isEmpty(), "a file imports once unlocked");
    check(vault.rowCount() == 1, "and appears in the list");

    const QString id = vault.get(0)["itemId"].toString();
    check(id.length() == 32, "its id is 16 random bytes, not the file name");
    check(vault.get(0)["title"].toString() == "A Very Private Recording", "the title is kept");
    check(vault.get(0)["sizeBytes"].toLongLong() == payload.size(), "and the size");

    const QString object = temporary.path() + "/vault/objects/" + id + ".enc";
    check(QFile::exists(object), "the object is on disk");
    check(!fileContains(object, marker), "the object does not contain the file's contents");
    check(QFile(object).size() > payload.size(), "and is larger, being segmented and tagged");

    const QString index = temporary.path() + "/vault/index.enc";
    check(QFile::exists(index), "the index is on disk");
    check(!fileContains(index, "A Very Private Recording"),
          "the index does not contain the title");
    check(!fileContains(index, "mp4"), "nor the file's extension");
    const QFileDevice::Permissions permissions = QFile::permissions(object);
    check(!(permissions & QFileDevice::ReadGroup) && !(permissions & QFileDevice::ReadOther),
          "and the object is readable only by its owner");
    check(!QDir(temporary.path() + "/vault/objects")
               .entryList({"*.partial"}, QDir::Files)
               .size(),
          "no partial file is left behind");

    // What comes back out.
    const QString exported = temporary.path() + "/exported.mp4";
    check(vault.exportTo(id, exported).isEmpty(), "an item exports");
    QFile check1(exported);
    check1.open(QIODevice::ReadOnly);
    check(check1.readAll() == payload, "byte for byte");
    check1.close();

    // A restart has to find it, which is the real test of the encrypted index.
    {
        Vault reopened;
        SecretStore secondSession(fast, nullptr);
        reopened.setSecrets(&secondSession);
        check(reopened.rowCount() == 0, "a locked vault lists nothing");
        check(secondSession.unlock("a correct horse battery staple").isEmpty(), "it unlocks");
        check(reopened.rowCount() == 1, "and the item is there after a restart");
        check(reopened.get(0)["title"].toString() == "A Very Private Recording",
              "with its title intact");
    }

    // An index that will not open must never read as empty, because the next change would
    // write that emptiness back and orphan every object.
    {
        QFile damaged(index);
        damaged.open(QIODevice::ReadWrite);
        damaged.seek(damaged.size() - 1);
        char last = 0;
        damaged.read(&last, 1);
        damaged.seek(damaged.size() - 1);
        last = static_cast<char>(last ^ 0x01);
        damaged.write(&last, 1);
        damaged.close();

        Vault broken;
        SecretStore session(fast, nullptr);
        broken.setSecrets(&session);
        session.unlock("a correct horse battery staple");
        check(broken.unreadable(), "a damaged index is reported as unreadable");
        check(broken.rowCount() == 0, "and lists nothing");
        check(!broken.importFile(sourcePath, false).isEmpty(),
              "an import into an unreadable vault is refused rather than overwriting it");
        check(QFile::exists(object), "so the object nobody can list is still on disk");
    }

    // Delete, on a fresh index.
    {
        QFile::remove(index);
        Vault fresh;
        SecretStore session(fast, nullptr);
        fresh.setSecrets(&session);
        session.unlock("a correct horse battery staple");
        check(fresh.importFile(sourcePath, false).isEmpty(), "a file imports into a fresh vault");
        const QString freshId = fresh.get(0)["itemId"].toString();
        check(fresh.remove(freshId).isEmpty(), "an item deletes");
        check(fresh.rowCount() == 0, "and leaves the list empty");
        check(!QFile::exists(temporary.path() + "/vault/objects/" + freshId + ".enc"),
              "with its object removed");
    }

    // Import with removeOriginal, which is what "move into the vault" does.
    {
        Vault fresh;
        SecretStore session(fast, nullptr);
        fresh.setSecrets(&session);
        session.unlock("a correct horse battery staple");
        const QString throwaway = temporary.path() + "/throwaway.mp4";
        QFile file(throwaway);
        file.open(QIODevice::WriteOnly);
        file.write(payload);
        file.close();
        check(fresh.importFile(throwaway, true).isEmpty(), "a file can be moved in");
        check(!QFile::exists(throwaway), "and the original is gone");
    }

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
