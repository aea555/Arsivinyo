#include "CookieStore.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QTextStream>
#include <QUrl>

#include "DataDir.h"

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

}  // namespace

CookieStore::CookieStore(QObject *parent) : QAbstractListModel(parent) {
    reload();
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
        const QFileInfoList files =
            dir.entryInfoList({"*.txt", "*.json"}, QDir::Files, QDir::Time);
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

    const QString target = dir + "/cookies.txt";
    if (!QFile::copy(source, target)) return tr("Could not store the cookie file.");
    // Readable only by this user: a cookie file is a signed-in session.
    QFile::setPermissions(target, QFileDevice::ReadOwner | QFileDevice::WriteOwner);

    reload();
    return {};
}

bool CookieStore::clear(const QString &platform) {
    const QDir dir(directory() + '/' + platform);
    if (!dir.exists()) return false;
    bool removed = false;
    for (const QFileInfo &file : dir.entryInfoList({"*.txt", "*.json"}, QDir::Files)) {
        removed = QFile::remove(file.absoluteFilePath()) || removed;
    }
    reload();
    return removed;
}
