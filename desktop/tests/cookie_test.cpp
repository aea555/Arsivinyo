// Cookie jars at rest, and the decrypted copy a run gets.
//
// The assertion that matters is that the stored file does not contain the session token. A
// cookie file is a signed-in session, and this store previously kept it verbatim.

#include <QDir>
#include <QFile>
#include <QString>
#include <QTemporaryDir>

#include <cstdio>

#include "CookieStore.h"
#include "SecretStore.h"

static int failures = 0;
static void check(bool ok, const QString &what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", qPrintable(what));
    if (!ok) ++failures;
}

static const char *kSessionToken = "SUPER_SECRET_SESSION_VALUE_9f3a";

static QString writeJar(const QString &path) {
    QFile file(path);
    file.open(QIODevice::WriteOnly | QIODevice::Truncate);
    file.write("# Netscape HTTP Cookie File\n");
    file.write(QByteArray(".youtube.com\tTRUE\t/\tTRUE\t2147483647\tSID\t") + kSessionToken + "\n");
    file.write(".youtube.com\tTRUE\t/\tTRUE\t2147483647\tHSID\tsomething-else\n");
    file.close();
    return path;
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
    const QString source = writeJar(temporary.path() + "/exported.txt");

    SecretStore secrets(fast, nullptr);
    CookieStore cookies;
    cookies.setSecrets(&secrets);

    check(!cookies.importFile("youtube", source).isEmpty(),
          "importing while locked is refused rather than stored in the clear");

    check(secrets.create("a correct horse battery staple").isEmpty(), "a passphrase is set");
    check(cookies.importFile("youtube", source).isEmpty(), "a jar imports once unlocked");

    const QString stored = temporary.path() + "/cookies/youtube/cookies.enc";
    check(QFile::exists(stored), "it lands as cookies.enc");
    check(!QFile::exists(temporary.path() + "/cookies/youtube/cookies.txt"),
          "and no plain-text copy is left beside it");
    check(!fileContains(stored, kSessionToken),
          "the stored file does not contain the session token");
    const QFileDevice::Permissions permissions = QFile::permissions(stored);
    check(!(permissions & QFileDevice::ReadGroup) && !(permissions & QFileDevice::ReadOther),
          "and is readable only by its owner");

    // What the engine is handed for one run.
    const QString runtime = cookies.prepareRuntime();
    check(!runtime.isEmpty(), "a run gets a decrypted directory");
    const QString runtimeJar = runtime + "/youtube/cookies.txt";
    check(QFile::exists(runtimeJar), "with the jar where the engine looks for it");
    check(fileContains(runtimeJar, kSessionToken), "and the contents came back intact");

    cookies.sweepRuntime();
    check(!QFile::exists(runtimeJar), "sweeping removes the decrypted copy");
    check(!QDir(temporary.path() + "/cookie_runtime").exists(), "and the directory with it");

    // A locked store still lists what it holds, so Settings renders without a prompt.
    secrets.lock();
    check(cookies.rowCount() > 0, "the list still renders while locked");
    check(cookies.prepareRuntime().isEmpty(), "but nothing decrypts while locked");

    // An older build's plain-text jar is encrypted on the next unlock.
    QDir().mkpath(temporary.path() + "/cookies/twitter");
    writeJar(temporary.path() + "/cookies/twitter/cookies.txt");
    secrets.unlock("a correct horse battery staple");
    check(QFile::exists(temporary.path() + "/cookies/twitter/cookies.enc"),
          "a jar left in the clear by an older build is encrypted on unlock");
    check(!QFile::exists(temporary.path() + "/cookies/twitter/cookies.txt"),
          "and the plain text is removed once the ciphertext reads back");

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
