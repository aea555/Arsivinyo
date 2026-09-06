#pragma once

#include <QAbstractListModel>
#include <QDir>
#include <QQmlEngine>
#include <QSqlDatabase>
#include <QString>

/**
 * The desktop music library.
 *
 * Keeps the phone's split: the **folder** is the truth about what exists, the
 * **database** is the truth about what the user did — playlists, favourites, titles they
 * edited. `scan()` reconciles the two, the way `SoundsStore.listLibrary()` reconciles its
 * index against MediaStore. Files can be moved or deleted by anything on the machine, so
 * a database that believed itself would drift within a day.
 *
 * SQLite rather than the phone's JSON because a desktop library gets larger and a
 * favourite toggle should not rewrite the whole index, and because metadata extraction is
 * expensive enough that only unseen files should pay for it.
 */
class Library : public QAbstractListModel {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
    Q_PROPERTY(bool scanning READ scanning NOTIFY scanningChanged)
    Q_PROPERTY(QString musicDir READ musicDir CONSTANT)
    Q_PROPERTY(QString filter READ filter WRITE setFilter NOTIFY filterChanged)

public:
    enum Roles {
        IdRole = Qt::UserRole + 1,
        TitleRole, ArtistRole, FileNameRole, PathRole,
        DurationRole, SizeRole, ThumbRole, FavouriteRole, PresetIdRole,
    };
    Q_ENUM(Roles)

    explicit Library(QObject *parent = nullptr);
    ~Library() override;

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    bool scanning() const { return m_scanning; }
    QString musicDir() const { return m_musicDir; }
    QString filter() const { return m_filter; }
    void setFilter(const QString &value);

    /** Reconcile the folder with the database. Cheap after the first run. */
    Q_INVOKABLE void scan();
    Q_INVOKABLE void setFavourite(const QString &id, bool favourite);
    Q_INVOKABLE bool isFavourite(const QString &id) const;

    /** One row as a map. QAbstractListModel::data is not callable from QML. */
    Q_INVOKABLE QVariantMap get(int row) const;

    // ---- playlists -------------------------------------------------------
    /** Every playlist with its track count. Favourites sorts first and is marked. */
    Q_INVOKABLE QVariantList playlists() const;
    Q_INVOKABLE QString createPlaylist(const QString &name);
    /** Refuses the reserved Favourites playlist, as the phone does. */
    Q_INVOKABLE bool deletePlaylist(const QString &id);
    Q_INVOKABLE bool renamePlaylist(const QString &id, const QString &name);
    Q_INVOKABLE void addToPlaylist(const QString &playlistId, const QString &songId);
    Q_INVOKABLE void removeFromPlaylist(const QString &playlistId, const QString &songId);
    /** Empty shows everything; otherwise only that playlist's members. */
    Q_INVOKABLE void showPlaylist(const QString &id);

    /**
     * File a finished download into the library.
     *
     * Moves the media into the music folder and, if yt-dlp downloaded a cover, copies it
     * into the artwork store beside the database. The engine reports the cover's path
     * because the bundled FFmpeg cannot embed one — the same reason the phone keeps
     * artwork as a sidecar rather than inside the file.
     */
    Q_INVOKABLE QString adopt(const QString &mediaPath, const QString &thumbnailPath);

signals:
    void countChanged();
    void scanningChanged();
    void filterChanged();
    void playlistsChanged();
    void scanFinished(int added, int removed);

private:
    struct Row {
        QString id, title, artist, fileName, thumb, presetId;
        double durationSec = 0;
        qint64 sizeBytes = 0;
        bool favourite = false;
    };

    bool openDatabase();
    void migrate();
    void reload();
    /** Reads tags with ffprobe. Only ever called for a file the database has not seen. */
    bool probe(const QString &path, Row &row) const;
    static QString ffprobePath();
    QString artworkDir() const;

    QSqlDatabase m_db;
    QList<Row> m_rows;
    QString m_musicDir;
    QString m_filter;
    QString m_playlistFilter;
    bool m_scanning = false;
};
