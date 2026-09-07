#include "CookieStore.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QTextStream>
#include <QUrl>

#include "DataDir.h"
#include "aead_stream.h"
#include "subkeys.h"

namespace {

/** The sites the engine knows how to match a URL to. Mirrors COOKIE_PLATFORMS. */
struct Platform {
    const char *id;
    const char *label;
};

constexpr Platform kPlatforms[] = {
    {"youtube", "YouTube"},
    {"twitter", "X / Twitter"},
    {"instagram", "Instagram"},
    {"facebook", "Facebook"},
    {"reddit", "Reddit"},
    {"tiktok", "TikTok"},
};

QString dataDir() { return arsivinyo::dataDirPath(/*create=*/false); }

/**
 * A Netscape cookies.txt is tab-separated with seven fields per line.
 *
 * Checked on import because the alternative is a download that fails to sign in with no
 * explanation — the engine would read the file, find no cookies, and carry on anonymously.
 */
bool looksLikeCookieFile(const QString &path, int *cookieCount) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) return false;

    QTextStream stream(&file);
    int found = 0;
    int lines = 0;
    while (!stream.atEnd() && lines < 5000) {
        const QString line = stream.readLine();
        ++lines;
        if (line.trimmed().isEmpty() || line.startsWith('#')) continue;
        if (line.split('\t').size() >= 7) ++found;
    }
    if (cookieCount) *cookieCount = found;
    return found > 0;
}

/** One run's decrypted jars live here, and nowhere else. */
QString runtimeRoot() { return dataDir() + "/cookie_runtime"; }

}  // namespace

CookieStore::CookieStore(QObject *parent) : QAbstractListModel(parent) {
    // Anything left by a crash is plain text on disk, so it goes before anything else.
    sweepRuntime();
    reload();
}

void CookieStore::setSecrets(SecretStore *secrets) {
    if (m_secrets == secrets) return;
    m_secrets = secrets;
    if (m_secrets != nullptr) {
        // A jar written by an older build is still in the clear; encrypt it the moment
        // there is a key to encrypt it with.
        connect(m_secrets, &SecretStore::changed, this, [this]() {
            migratePlaintext();
            reload();
        });
        migratePlaintext();
    }
    reload();
    emit changed();
}

QString CookieStore::directory() const { return dataDir() + "/cookies"; }

void CookieStore::reload() {
    beginResetModel();
    m_entries.clear();
    for (const Platform &platform : kPlatforms) {
        Entry entry;
        entry.platform = QString::fromLatin1(platform.id);
        entry.label = QString::fromLatin1(platform.label);

        const QDir dir(directory() + '/' + entry.platform);
        // Existence and a timestamp are all the list needs, so this works locked.
        const QFileInfoList files = dir.entryInfoList({"*.enc"}, QDir::Files, QDir::Time);
        if (!files.isEmpty()) {
            entry.file = files.first().absoluteFilePath();
            entry.importedAt = files.first().lastModified().toSecsSinceEpoch();
        }
        m_entries.append(entry);
    }
    endResetModel();
    emit changed();
}

int CookieStore::rowCount(const QModelIndex &parent) const {
    return parent.isValid() ? 0 : m_entries.size();
}

QVariant CookieStore::data(const QModelIndex &index, int role) const {
    if (index.row() < 0 || index.row() >= m_entries.size()) return {};
    const Entry &entry = m_entries.at(index.row());
    switch (role) {
        case PlatformRole: return entry.platform;
        case LabelRole: return entry.label;
        case HasCookiesRole: return !entry.file.isEmpty();
        case ImportedAtRole: return entry.importedAt;
        default: return {};
    }
}

QHash<int, QByteArray> CookieStore::roleNames() const {
    return {
        {PlatformRole, "platform"},
        {LabelRole, "label"},
        {HasCookiesRole, "hasCookies"},
        {ImportedAtRole, "importedAt"},
    };
}

QString CookieStore::importFile(const QString &platform, const QString &fileUrl) {
    const QUrl url(fileUrl);
    const QString source = url.isLocalFile() ? url.toLocalFile() : fileUrl;
    if (!QFileInfo(source).isFile()) return tr("That file could not be read.");

    int cookies = 0;
    if (!looksLikeCookieFile(source, &cookies)) {
        return tr("That is not a cookies.txt file. Export one in Netscape format.");
    }

    const QString dir = directory() + '/' + platform;
    if (!QDir().mkpath(dir)) return tr("Could not create the cookie folder.");

    // One file per site: the engine takes the newest, so leaving older ones would only
    // make which is in use depend on timestamps.
    for (const QFileInfo &existing :
         QDir(dir).entryInfoList({"*.txt", "*.json"}, QDir::Files)) {
        QFile::remove(existing.absoluteFilePath());
    }

    if (m_secrets == nullptr || !m_secrets->unlocked()) {
        if (m_secrets != nullptr) m_secrets->requestUnlock(tr("to store this cookie file"));
        return tr("Unlock first, so the cookie file can be encrypted.");
    }
    QFile input(source);
    if (!input.open(QIODevice::ReadOnly)) return tr("That file could not be read.");
    const QByteArray plaintext = input.readAll();

    const QString stored = writeEncrypted(platform, plaintext);
    if (!stored.isEmpty()) return stored;

    reload();
    return {};
}

QString CookieStore::writeEncrypted(const QString &platform, const QByteArray &plaintext) {
    arsivinyo::crypto::SecretBytes key;
    if (!m_secrets->purposeKey(arsivinyo::crypto::kPurposeCookies, &key)) {
        return tr("Could not reach the key.");
    }
    // The site is the associated data, so one site's jar cannot be renamed into another's.
    const std::string aad = ("cookies/" + platform).toStdString();
    arsivinyo::crypto::Bytes ciphertext;
    std::string error;
    if (!arsivinyo::crypto::EncryptBuffer(
            key.data(), key.size(), aad,
            reinterpret_cast<const uint8_t *>(plaintext.constData()),
            static_cast<std::size_t>(plaintext.size()), &ciphertext, &error)) {
        return tr("Could not encrypt the cookie file.");
    }

    const QString dir = directory() + '/' + platform;
    if (!QDir().mkpath(dir)) return tr("Could not create the cookie folder.");
    const QString target = dir + "/cookies.enc";
    QFile file(target);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        return tr("Could not store the cookie file.");
    }
    // Set before the write, so the file is never briefly readable by anyone else.
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    const qint64 written =
        file.write(reinterpret_cast<const char *>(ciphertext.data()),
                   static_cast<qint64>(ciphertext.size()));
    file.close();
    if (written != static_cast<qint64>(ciphertext.size())) {
        QFile::remove(target);
        return tr("Could not store the cookie file.");
    }
    return {};
}

bool CookieStore::clear(const QString &platform) {
    const QDir dir(directory() + '/' + platform);
    if (!dir.exists()) return false;
    bool removed = false;
    for (const QFileInfo &file : dir.entryInfoList({"*.enc", "*.txt", "*.json"}, QDir::Files)) {
        removed = QFile::remove(file.absoluteFilePath()) || removed;
    }
    reload();
    return removed;
}

QString CookieStore::prepareRuntime() {
    sweepRuntime();
    if (m_secrets == nullptr || !m_secrets->unlocked()) return {};

    arsivinyo::crypto::SecretBytes key;
    if (!m_secrets->purposeKey(arsivinyo::crypto::kPurposeCookies, &key)) return {};

    arsivinyo::crypto::Bytes name;
    std::string error;
    if (!arsivinyo::crypto::RandomBytes(8, &name, &error)) return {};
    const QString root =
        runtimeRoot() + '/' +
        QByteArray(reinterpret_cast<const char *>(name.data()), 8).toHex();
    if (!QDir().mkpath(root)) return {};
    QFile::setPermissions(root, QFileDevice::ReadOwner | QFileDevice::WriteOwner |
                                    QFileDevice::ExeOwner);

    int written = 0;
    for (const Entry &entry : m_entries) {
        if (entry.file.isEmpty()) continue;
        QFile input(entry.file);
        if (!input.open(QIODevice::ReadOnly)) continue;
        const QByteArray ciphertext = input.readAll();

        const std::string aad = ("cookies/" + entry.platform).toStdString();
        arsivinyo::crypto::Bytes plaintext;
        if (!arsivinyo::crypto::DecryptBuffer(
                key.data(), key.size(), aad,
                reinterpret_cast<const uint8_t *>(ciphertext.constData()),
                static_cast<std::size_t>(ciphertext.size()), &plaintext, &error)) {
            continue;
        }

        const QString dir = root + '/' + entry.platform;
        if (!QDir().mkpath(dir)) continue;
        QFile out(dir + "/cookies.txt");
        if (!out.open(QIODevice::WriteOnly | QIODevice::Truncate)) continue;
        out.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
        out.write(reinterpret_cast<const char *>(plaintext.data()),
                  static_cast<qint64>(plaintext.size()));
        out.close();
        ++written;
    }
    if (written == 0) {
        QDir(root).removeRecursively();
        return {};
    }
    return root;
}

void CookieStore::sweepRuntime() {
    QDir root(runtimeRoot());
    if (root.exists()) root.removeRecursively();
}

void CookieStore::migratePlaintext() {
    if (m_secrets == nullptr || !m_secrets->unlocked()) return;
    for (const Platform &platform : kPlatforms) {
        const QString id = QString::fromLatin1(platform.id);
        const QDir dir(directory() + '/' + id);
        const QFileInfoList plain = dir.entryInfoList({"*.txt", "*.json"}, QDir::Files, QDir::Time);
        if (plain.isEmpty()) continue;

        QFile input(plain.first().absoluteFilePath());
        if (!input.open(QIODevice::ReadOnly)) continue;
        const QByteArray plaintext = input.readAll();
        input.close();
        if (!writeEncrypted(id, plaintext).isEmpty()) continue;

        // Read it back before destroying the only copy — the order the phone's own cookie
        // migration uses. Only then remove the plain text.
        QFile check(directory() + '/' + id + "/cookies.enc");
        if (!check.open(QIODevice::ReadOnly)) continue;
        const QByteArray stored = check.readAll();
        check.close();
        arsivinyo::crypto::SecretBytes key;
        if (!m_secrets->purposeKey(arsivinyo::crypto::kPurposeCookies, &key)) continue;
        arsivinyo::crypto::Bytes back;
        std::string error;
        const std::string aad = ("cookies/" + id).toStdString();
        if (!arsivinyo::crypto::DecryptBuffer(
                key.data(), key.size(), aad,
                reinterpret_cast<const uint8_t *>(stored.constData()),
                static_cast<std::size_t>(stored.size()), &back, &error)) {
            continue;
        }
        if (back.size() != static_cast<std::size_t>(plaintext.size())) continue;
        for (const QFileInfo &file : plain) QFile::remove(file.absoluteFilePath());
    }
}
