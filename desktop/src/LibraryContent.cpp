#include "LibraryContent.h"

#include <QDir>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonObject>

namespace {

/**
 * Reduce whatever the peer called the file to a bare name this side is willing to write.
 *
 * A name is data, not a path. `QFileInfo::fileName` drops any directory the peer put in
 * front of it, which handles `../../etc/passwd` and `/etc/passwd` alike, and what is left
 * is stripped of the separators some platforms accept.
 */
QString safeName(const QString &name) {
    QString bare = QFileInfo(name).fileName();
    bare.replace('\\', '_');
    bare.replace('/', '_');
    // A leading dot would hide the file; a name that is only dots is not a name.
    while (bare.startsWith('.')) bare.remove(0, 1);
    return bare.trimmed();
}

}  // namespace

LibraryContent::LibraryContent(QObject *parent) : QObject(parent) {}

LibraryContent::LibraryContent(Library *library, EngineClient *engine, QObject *parent)
    : QObject(parent), m_library(library), m_engine(engine) {}

void LibraryContent::setLibrary(Library *library) {
    if (m_library == library) return;
    m_library = library;
    emit wiringChanged();
}

void LibraryContent::setEngine(EngineClient *engine) {
    if (m_engine == engine) return;
    m_engine = engine;
    emit wiringChanged();
}

QJsonArray LibraryContent::listing(const QString &kind) const {
    QJsonArray items;
    // Only music is listed. "backups" is accepted as a `put` kind but this device does
    // not offer its own backups for browsing: a backup is a deliberate export, not
    // something a peer helps itself to.
    if (kind != QLatin1String("music") || !m_library) return items;

    for (int row = 0; row < m_library->rowCount(); ++row) {
        const QVariantMap item = m_library->get(row);
        items.append(QJsonObject{
            {"id", item.value(QStringLiteral("songId")).toString()},
            {"title", item.value(QStringLiteral("title")).toString()},
            {"artist", item.value(QStringLiteral("artist")).toString()},
            {"durationSec", item.value(QStringLiteral("durationSec")).toDouble()},
            {"sizeBytes", item.value(QStringLiteral("sizeBytes")).toLongLong()},
        });
    }
    return items;
}

QString LibraryContent::pathForItem(const QString &id) const {
    if (!m_library || id.isEmpty()) return {};
    for (int row = 0; row < m_library->rowCount(); ++row) {
        const QVariantMap item = m_library->get(row);
        if (item.value(QStringLiteral("songId")).toString() != id) continue;
        const QString path = item.value(QStringLiteral("path")).toString();
        return QFileInfo(path).isFile() ? path : QString();
    }
    // An id that is not in the library resolves to nothing, so a peer cannot name a path.
    return {};
}

QString LibraryContent::destinationFor(const QString &name, const QString &) const {
    if (!m_library) return {};
    const QString bare = safeName(name);
    if (bare.isEmpty()) return {};

    const QDir dir(m_library->musicDir());
    QString candidate = bare;
    // Never overwrite. A peer sending a second "track.m4a" gets "track (2).m4a" rather
    // than replacing what is already in the library.
    for (int attempt = 2; dir.exists(candidate) && attempt < 1000; ++attempt) {
        const QFileInfo info(bare);
        candidate = QStringLiteral("%1 (%2)").arg(info.completeBaseName()).arg(attempt);
        if (!info.suffix().isEmpty()) candidate += '.' + info.suffix();
    }
    return dir.filePath(candidate);
}

void LibraryContent::accepted(const QString &path, const QString &kind) {
    if (!m_library || kind == QLatin1String("backups")) return;
    // The file is already in the music folder, so a scan is enough to pick it up; adopt()
    // is for a download that still has to be moved.
    m_library->scan();
    emit fileAdopted(QFileInfo(path).completeBaseName());
}

void LibraryContent::download(const QString &url, const QString &mediaKind) {
    // Surfaced rather than started. A peer asking this device to fetch something is a
    // request the user sees, not an instruction the app obeys.
    emit downloadRequested(url, mediaKind);
}
