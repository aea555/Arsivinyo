#include "Library.h"

#include <QCoreApplication>
#include <QDirIterator>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <QSqlError>
#include <QSqlQuery>
#include <QStandardPaths>
#include <QSettings>
#include <QUrl>
#include <QUuid>

#include "DataDir.h"

namespace {
constexpr auto kFavourites = "favorites";   // spelling matches the phone's reserved id
const QStringList kAudioSuffixes = {"flac", "m4a", "mp3", "opus", "ogg", "wav", "aac"};
}

Library::Library(QObject *parent) : QAbstractListModel(parent) {
    // Overridable so a test run does not write into the real music folder.
    // A folder the user chose wins over the default, but never over an explicit
    // environment override, which is what the tests use.
    m_musicDir = qEnvironmentVariable("ARSIVINYO_MUSIC_DIR");
    if (m_musicDir.isEmpty()) {
        m_musicDir = QSettings().value(QStringLiteral("folders/music")).toString();
    }
    if (m_musicDir.isEmpty()) {
        QString base = QStandardPaths::writableLocation(QStandardPaths::MusicLocation);
        if (base.isEmpty()) base = QDir::homePath() + "/Music";
        m_musicDir = base + "/Arsivinyo";
    }
    QDir().mkpath(m_musicDir);

    if (openDatabase()) {
        migrate();
        reload();
    }
}

Library::~Library() {
    if (m_db.isOpen()) m_db.close();
}

bool Library::openDatabase() {
    const QString dir = arsivinyo::dataDirPath();

    m_db = QSqlDatabase::addDatabase("QSQLITE", "library");
    m_db.setDatabaseName(dir + "/library.db");
    if (!m_db.open()) {
        qWarning("library: cannot open database: %s", qPrintable(m_db.lastError().text()));
        return false;
    }
    QSqlQuery(m_db).exec("PRAGMA journal_mode=WAL");
    QSqlQuery(m_db).exec("PRAGMA foreign_keys=ON");
    return true;
}

void Library::migrate() {
    QSqlQuery q(m_db);
    q.exec(R"(CREATE TABLE IF NOT EXISTS songs (
        id TEXT PRIMARY KEY,
        file_name TEXT NOT NULL,
        title TEXT, artist TEXT,
        duration_sec REAL DEFAULT 0,
        size_bytes INTEGER DEFAULT 0,
        thumb_file TEXT,
        source_url_hash TEXT,
        preset_id TEXT,
        source_song_id TEXT,
        created_at INTEGER, updated_at INTEGER))");
    q.exec(R"(CREATE TABLE IF NOT EXISTS playlists (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        system INTEGER DEFAULT 0,
        created_at INTEGER, updated_at INTEGER))");
    q.exec(R"(CREATE TABLE IF NOT EXISTS playlist_songs (
        playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
        song_id TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
        position INTEGER DEFAULT 0,
        PRIMARY KEY (playlist_id, song_id)))");
    q.exec("CREATE INDEX IF NOT EXISTS idx_playlist_songs_song ON playlist_songs(song_id)");

    // Favourites is a reserved system playlist, created lazily so it always exists —
    // the same arrangement as ensureFavoritesLocked on the phone.
    q.prepare("INSERT OR IGNORE INTO playlists (id, name, system, created_at, updated_at)"
              " VALUES (?, 'Favourites', 1, ?, ?)");
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    q.addBindValue(kFavourites);
    q.addBindValue(now);
    q.addBindValue(now);
    q.exec();
}

QString Library::artworkDir() const {
    const QString dir = QFileInfo(m_db.databaseName()).absolutePath() + "/artwork";
    QDir().mkpath(dir);
    return dir;
}

QString Library::adopt(const QString &mediaPath, const QString &thumbnailPath) {
    QFileInfo media(mediaPath);
    if (!media.isFile()) return {};

    // The file name is the identity, so it has to be unique in the folder. The phone
    // solves the same problem in uniqueDisplayNameLocked.
    QString name = media.fileName();
    const QString stem = media.completeBaseName();
    const QString suffix = media.suffix();
    for (int n = 1; QFileInfo::exists(QDir(m_musicDir).filePath(name)); ++n)
        name = QStringLiteral("%1 (%2).%3").arg(stem).arg(n).arg(suffix);

    const QString target = QDir(m_musicDir).filePath(name);
    if (!QFile::rename(mediaPath, target)) {
        // Different filesystem: fall back to copy, then drop the original.
        if (!QFile::copy(mediaPath, target)) return {};
        QFile::remove(mediaPath);
    }

    QString thumbName;
    const QFileInfo thumb(thumbnailPath);
    if (!thumbnailPath.isEmpty() && thumb.isFile()) {
        thumbName = name + "." + thumb.suffix();
        const QString thumbTarget = QDir(artworkDir()).filePath(thumbName);
        QFile::remove(thumbTarget);
        // Copied verbatim: the cover is already a decoded image and Qt renders it as is.
        if (!QFile::copy(thumbnailPath, thumbTarget)) thumbName.clear();
        QFile::remove(thumbnailPath);
    }

    scan();

    if (!thumbName.isEmpty()) {
        QSqlQuery q(m_db);
        q.prepare("UPDATE songs SET thumb_file = ? WHERE id = ?");
        q.addBindValue(thumbName);
        q.addBindValue(name);
        q.exec();
        reload();
    }
    return name;
}

QString Library::ffprobePath() {
    const QDir dir(QCoreApplication::applicationDirPath());
    for (const QString &name : {QStringLiteral("ffprobe"), QStringLiteral("ffprobe.exe")}) {
        const QFileInfo candidate(dir.filePath(name));
        if (candidate.isExecutable()) return candidate.absoluteFilePath();
    }
    return QStandardPaths::findExecutable("ffprobe");
}

bool Library::probe(const QString &path, Row &row) const {
    const QString tool = ffprobePath();
    if (tool.isEmpty()) return false;

    QProcess p;
    p.start(tool, {"-v", "quiet", "-print_format", "json", "-show_format", path});
    if (!p.waitForFinished(15000)) { p.kill(); return false; }

    const QJsonObject format = QJsonDocument::fromJson(p.readAllStandardOutput())
                                   .object().value("format").toObject();
    if (format.isEmpty()) return false;

    row.durationSec = format.value("duration").toString().toDouble();
    const QJsonObject tags = format.value("tags").toObject();
    for (const QString &key : {QStringLiteral("title"), QStringLiteral("TITLE")})
        if (tags.contains(key)) { row.title = tags.value(key).toString(); break; }
    for (const QString &key : {QStringLiteral("artist"), QStringLiteral("ARTIST")})
        if (tags.contains(key)) { row.artist = tags.value(key).toString(); break; }
    return true;
}

void Library::scan() {
    if (m_scanning || !m_db.isOpen()) return;
    m_scanning = true;
    emit scanningChanged();

    QSet<QString> onDisk;
    int added = 0;

    m_db.transaction();
    QDirIterator it(m_musicDir, QDir::Files | QDir::Readable);
    while (it.hasNext()) {
        const QFileInfo info(it.next());
        if (!kAudioSuffixes.contains(info.suffix().toLower())) continue;
        const QString id = info.fileName();
        onDisk.insert(id);

        QSqlQuery existing(m_db);
        existing.prepare("SELECT size_bytes FROM songs WHERE id = ?");
        existing.addBindValue(id);
        existing.exec();

        const qint64 size = info.size();
        if (existing.next()) {
            // Known file. Only its size is refreshed; tags cost a subprocess and the
            // user may have edited the title, which is theirs to keep.
            if (existing.value(0).toLongLong() != size) {
                QSqlQuery up(m_db);
                up.prepare("UPDATE songs SET size_bytes = ?, updated_at = ? WHERE id = ?");
                up.addBindValue(size);
                up.addBindValue(QDateTime::currentMSecsSinceEpoch());
                up.addBindValue(id);
                up.exec();
            }
            continue;
        }

        Row row;
        row.id = id;
        row.fileName = id;
        row.sizeBytes = size;
        probe(info.absoluteFilePath(), row);
        if (row.title.isEmpty()) row.title = info.completeBaseName();

        QSqlQuery ins(m_db);
        ins.prepare("INSERT INTO songs (id, file_name, title, artist, duration_sec, size_bytes,"
                    " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        ins.addBindValue(row.id);
        ins.addBindValue(row.fileName);
        ins.addBindValue(row.title);
        ins.addBindValue(row.artist);
        ins.addBindValue(row.durationSec);
        ins.addBindValue(row.sizeBytes);
        ins.addBindValue(now);
        ins.addBindValue(now);
        ins.exec();
        ++added;
    }

    // Anything the database still lists but the folder no longer has is gone. Playlist
    // rows follow it, by the cascade.
    int removed = 0;
    QSqlQuery all(m_db);
    all.exec("SELECT id FROM songs");
    QStringList missing;
    while (all.next()) {
        const QString id = all.value(0).toString();
        if (!onDisk.contains(id)) missing.append(id);
    }
    for (const QString &id : missing) {
        QSqlQuery del(m_db);
        del.prepare("DELETE FROM songs WHERE id = ?");
        del.addBindValue(id);
        del.exec();
        ++removed;
    }
    m_db.commit();

    reload();
    m_scanning = false;
    emit scanningChanged();
    emit scanFinished(added, removed);
}

void Library::reload() {
    beginResetModel();
    m_rows.clear();
    QSqlQuery q(m_db);
    const bool filtered = !m_filter.trimmed().isEmpty();
    const bool byPlaylist = !m_playlistFilter.isEmpty();
    const QString restrict = byPlaylist
        ? " AND EXISTS(SELECT 1 FROM playlist_songs m WHERE m.song_id = s.id AND m.playlist_id = :pl)"
        : QString();
    if (filtered) {
        q.prepare("SELECT s.id, s.title, s.artist, s.file_name, s.duration_sec, s.size_bytes,"
                  " s.thumb_file, s.preset_id,"
                  " EXISTS(SELECT 1 FROM playlist_songs p WHERE p.song_id = s.id AND p.playlist_id = :fav)"
                  " FROM songs s WHERE (s.title LIKE :like OR s.artist LIKE :like)"
                  + restrict + " ORDER BY s.created_at DESC");
        q.bindValue(":fav", kFavourites);
        q.bindValue(":like", "%" + m_filter.trimmed() + "%");
        if (byPlaylist) q.bindValue(":pl", m_playlistFilter);
    } else {
        q.prepare("SELECT s.id, s.title, s.artist, s.file_name, s.duration_sec, s.size_bytes,"
                  " s.thumb_file, s.preset_id,"
                  " EXISTS(SELECT 1 FROM playlist_songs p WHERE p.song_id = s.id AND p.playlist_id = :fav)"
                  " FROM songs s" + (byPlaylist ? " WHERE 1=1" + restrict : QString())
                  + " ORDER BY s.created_at DESC");
        q.bindValue(":fav", kFavourites);
        if (byPlaylist) q.bindValue(":pl", m_playlistFilter);
    }
    q.exec();
    while (q.next()) {
        Row row;
        row.id = q.value(0).toString();
        row.title = q.value(1).toString();
        row.artist = q.value(2).toString();
        row.fileName = q.value(3).toString();
        row.durationSec = q.value(4).toDouble();
        row.sizeBytes = q.value(5).toLongLong();
        row.thumb = q.value(6).toString();
        row.presetId = q.value(7).toString();
        row.favourite = q.value(8).toBool();
        m_rows.append(row);
    }
    endResetModel();
    emit countChanged();
}

void Library::setFilter(const QString &value) {
    if (m_filter == value) return;
    m_filter = value;
    emit filterChanged();
    reload();
}

void Library::setFavourite(const QString &id, bool favourite) {
    QSqlQuery q(m_db);
    if (favourite) {
        q.prepare("INSERT OR IGNORE INTO playlist_songs (playlist_id, song_id, position)"
                  " VALUES (?, ?, (SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = ?))");
        q.addBindValue(kFavourites);
        q.addBindValue(id);
        q.addBindValue(kFavourites);
    } else {
        q.prepare("DELETE FROM playlist_songs WHERE playlist_id = ? AND song_id = ?");
        q.addBindValue(kFavourites);
        q.addBindValue(id);
    }
    q.exec();

    for (int i = 0; i < m_rows.size(); ++i) {
        if (m_rows[i].id != id) continue;
        m_rows[i].favourite = favourite;
        emit dataChanged(index(i), index(i), {FavouriteRole});
        break;
    }
}

bool Library::isFavourite(const QString &id) const {
    for (const Row &row : m_rows)
        if (row.id == id) return row.favourite;
    return false;
}

QVariantList Library::playlists() const {
    QVariantList out;
    QSqlQuery q(m_db);
    q.exec("SELECT p.id, p.name, p.system, COUNT(ps.song_id)"
           " FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id"
           " GROUP BY p.id ORDER BY p.system DESC, p.name COLLATE NOCASE");
    while (q.next()) {
        out.append(QVariantMap{{"id", q.value(0).toString()},
                               {"name", q.value(1).toString()},
                               {"system", q.value(2).toBool()},
                               {"count", q.value(3).toInt()}});
    }
    return out;
}

QString Library::createPlaylist(const QString &name) {
    const QString trimmed = name.trimmed();
    if (trimmed.isEmpty()) return {};
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QSqlQuery q(m_db);
    q.prepare("INSERT INTO playlists (id, name, system, created_at, updated_at) VALUES (?,?,0,?,?)");
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    q.addBindValue(id);
    q.addBindValue(trimmed);
    q.addBindValue(now);
    q.addBindValue(now);
    if (!q.exec()) return {};
    emit playlistsChanged();
    return id;
}

bool Library::deletePlaylist(const QString &id) {
    // Favourites is reserved. The phone refuses the same request for the same reason:
    // the heart in the UI has nowhere to point if it can be deleted.
    if (id == QLatin1String(kFavourites)) return false;
    QSqlQuery q(m_db);
    q.prepare("DELETE FROM playlists WHERE id = ? AND system = 0");
    q.addBindValue(id);
    if (!q.exec() || q.numRowsAffected() == 0) return false;
    if (m_playlistFilter == id) showPlaylist({});
    emit playlistsChanged();
    return true;
}

bool Library::renamePlaylist(const QString &id, const QString &name) {
    if (id == QLatin1String(kFavourites)) return false;
    const QString trimmed = name.trimmed();
    if (trimmed.isEmpty()) return false;
    QSqlQuery q(m_db);
    q.prepare("UPDATE playlists SET name = ?, updated_at = ? WHERE id = ? AND system = 0");
    q.addBindValue(trimmed);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    q.addBindValue(id);
    if (!q.exec() || q.numRowsAffected() == 0) return false;
    emit playlistsChanged();
    return true;
}

void Library::addToPlaylist(const QString &playlistId, const QString &songId) {
    QSqlQuery q(m_db);
    q.prepare("INSERT OR IGNORE INTO playlist_songs (playlist_id, song_id, position)"
              " VALUES (?, ?, (SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = ?))");
    q.addBindValue(playlistId);
    q.addBindValue(songId);
    q.addBindValue(playlistId);
    q.exec();
    emit playlistsChanged();
    if (!m_playlistFilter.isEmpty()) reload();
}

void Library::removeFromPlaylist(const QString &playlistId, const QString &songId) {
    QSqlQuery q(m_db);
    q.prepare("DELETE FROM playlist_songs WHERE playlist_id = ? AND song_id = ?");
    q.addBindValue(playlistId);
    q.addBindValue(songId);
    q.exec();
    emit playlistsChanged();
    if (!m_playlistFilter.isEmpty()) reload();
}

void Library::showPlaylist(const QString &id) {
    if (m_playlistFilter == id) return;
    m_playlistFilter = id;
    reload();
}

QVariantMap Library::get(int row) const {
    QVariantMap out;
    if (row < 0 || row >= m_rows.size()) return out;
    const QModelIndex idx = index(row);
    const QHash<int, QByteArray> names = roleNames();
    for (auto it = names.constBegin(); it != names.constEnd(); ++it)
        out.insert(QString::fromUtf8(it.value()), data(idx, it.key()));
    return out;
}

int Library::rowCount(const QModelIndex &parent) const {
    return parent.isValid() ? 0 : m_rows.size();
}

QVariant Library::data(const QModelIndex &index, int role) const {
    if (!index.isValid() || index.row() >= m_rows.size()) return {};
    const Row &row = m_rows.at(index.row());
    switch (role) {
    case IdRole:        return row.id;
    case TitleRole:     return row.title;
    case ArtistRole:    return row.artist;
    case FileNameRole:  return row.fileName;
    case PathRole:      return QDir(m_musicDir).filePath(row.fileName);
    case DurationRole:  return row.durationSec;
    case SizeRole:      return row.sizeBytes;
    case ThumbRole:     return row.thumb.isEmpty()
                            ? QString()
                            : QUrl::fromLocalFile(QDir(artworkDir()).filePath(row.thumb)).toString();
    case FavouriteRole: return row.favourite;
    case PresetIdRole:  return row.presetId;
    default:            return {};
    }
}

QHash<int, QByteArray> Library::roleNames() const {
    return {{IdRole, "songId"}, {TitleRole, "title"}, {ArtistRole, "artist"},
            {FileNameRole, "fileName"}, {PathRole, "path"}, {DurationRole, "durationSec"},
            {SizeRole, "sizeBytes"}, {ThumbRole, "thumb"}, {FavouriteRole, "favourite"},
            {PresetIdRole, "presetId"}};
}

void Library::setMusicDir(const QString &path) {
    const QString cleaned = QUrl(path).isLocalFile() ? QUrl(path).toLocalFile() : path;
    if (cleaned.isEmpty() || cleaned == m_musicDir) return;

    QDir().mkpath(cleaned);
    m_musicDir = cleaned;
    QSettings().setValue(QStringLiteral("folders/music"), cleaned);
    emit musicDirChanged();
    // The folder is the library: a different one is a different set of tracks.
    scan();
}
