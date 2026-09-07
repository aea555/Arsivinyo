#pragma once

#include <QAbstractListModel>
#include <QQmlEngine>
#include <QString>
#include <QVector>

/**
 * Cookie files, per site, for downloads that need to be signed in.
 *
 * Most sites rate-limit or refuse an anonymous downloader outright — a 403 on the first
 * try, or a ban after a handful. The phone has had this from the start; the desktop went
 * without, which made it the lesser app on the thing both exist to do.
 *
 * The engine already looks for `<cookies>/<platform>/<name>.txt` and picks the newest file
 * unless a profile names one, so this only has to put files in the right place. The format
 * is Netscape cookies.txt, which every browser extension exports.
 */
class CookieStore : public QAbstractListModel {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(int count READ rowCount NOTIFY changed)
    Q_PROPERTY(QString directory READ directory CONSTANT)

public:
    struct Entry {
        QString platform;
        QString label;
        /** Empty when nothing has been imported for this site. */
        QString file;
        qint64 importedAt = 0;
    };

    enum Roles {
        PlatformRole = Qt::UserRole + 1,
        LabelRole,
        HasCookiesRole,
        ImportedAtRole,
    };

    explicit CookieStore(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    /** What the engine is given; it resolves the per-site folder itself. */
    QString directory() const;

    /**
     * Copy a cookies.txt into place for [platform].
     *
     * @return empty on success, otherwise why it was refused. A file that is not in
     *   Netscape format is rejected here rather than at download time, where it would
     *   surface as an unexplained failure to sign in.
     */
    Q_INVOKABLE QString importFile(const QString &platform, const QString &fileUrl);
    Q_INVOKABLE bool clear(const QString &platform);

signals:
    void changed();

private:
    void reload();

    QVector<Entry> m_entries;
};
