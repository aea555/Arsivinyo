#pragma once

#include <QAbstractListModel>
#include <QQmlEngine>
#include <QString>
#include <QVector>

#include "SecretStore.h"

/**
 * Cookie files, per site, for downloads that need to be signed in.
 *
 * Most sites rate-limit or refuse an anonymous downloader outright — a 403 on the first
 * try, or a ban after a handful. The phone has had this from the start; the desktop went
 * without, which made it the lesser app on the thing both exist to do.
 *
 * A cookie file is a signed-in session, so it is encrypted at rest under a key from the
 * keybox — the phone has done this since the secure cookie store landed, and the desktop was
 * keeping the same secrets in plain text.
 *
 * The engine is a separate process that opens the file itself and cannot read ciphertext, so
 * a run decrypts into a private directory under the cache, hands the engine that, and deletes
 * it afterwards. The phone does exactly this with `cookie_runtime/<taskId>/`.
 *
 * The format is Netscape cookies.txt, which every browser extension exports.
 */
class CookieStore : public QAbstractListModel {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(int count READ rowCount NOTIFY changed)
    Q_PROPERTY(QString directory READ directory CONSTANT)
    /** The keybox this store takes its key from. Set from QML at startup. */
    Q_PROPERTY(SecretStore *secrets READ secrets WRITE setSecrets NOTIFY changed)

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

    SecretStore *secrets() const { return m_secrets; }
    void setSecrets(SecretStore *secrets);

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

    /**
     * Decrypt every stored jar into a fresh private directory and return its path, for the
     * engine to read during one run. Empty when the keybox is locked or nothing is stored.
     */
    Q_INVOKABLE QString prepareRuntime();
    /** Delete every decrypted directory. Called after a run, and again at startup. */
    Q_INVOKABLE void sweepRuntime();

signals:
    void changed();

private:
    void reload();
    /** Encrypt any plain-text jar left by an older build, then remove the plain text. */
    void migratePlaintext();
    /** Seal `plaintext` for `platform` into cookies.enc. Empty on success. */
    QString writeEncrypted(const QString &platform, const QByteArray &plaintext);

    QVector<Entry> m_entries;
    SecretStore *m_secrets = nullptr;
};
