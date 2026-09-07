#pragma once

// Private files, encrypted at rest, listed from an encrypted index.
//
// The phone's vault encrypts the videos but keeps `index.json` in the clear, so every title,
// tag, folder name and size is readable by anything that can read the app's storage. That is
// being fixed there; it is not repeated here. The index is sealed with the same construction
// as the content, under its own subkey.
//
// Ids are random, never derived from the file name — a derived id would leak the names back
// through the object file names and undo the encrypted index.

#include <QAbstractListModel>
#include <QJsonObject>
#include <QQmlEngine>
#include <QString>
#include <QVector>

#include "SecretStore.h"

class Vault : public QAbstractListModel {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(int count READ rowCount NOTIFY changed)
    Q_PROPERTY(bool unlocked READ unlocked NOTIFY changed)
    Q_PROPERTY(SecretStore *secrets READ secrets WRITE setSecrets NOTIFY changed)
    /** Set when the index exists but will not open. Never silently treated as empty. */
    Q_PROPERTY(bool unreadable READ unreadable NOTIFY changed)

 public:
    struct Entry {
        QString id;
        QString title;
        QString mimeType;
        qint64 sizeBytes = 0;
        qint64 addedAt = 0;
        QString extension;
    };

    enum Roles {
        IdRole = Qt::UserRole + 1,
        TitleRole,
        MimeTypeRole,
        SizeBytesRole,
        AddedAtRole,
    };

    explicit Vault(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    SecretStore *secrets() const { return m_secrets; }
    void setSecrets(SecretStore *secrets);
    bool unlocked() const { return m_secrets != nullptr && m_secrets->unlocked(); }
    bool unreadable() const { return m_unreadable; }

    /** Streams a file in, encrypting as it goes. Returns empty on success. */
    Q_INVOKABLE QString importFile(const QString &fileUrl, bool removeOriginal);
    Q_INVOKABLE QString remove(const QString &id);
    /** Decrypts one item back out to a chosen path. */
    Q_INVOKABLE QString exportTo(const QString &id, const QString &destinationUrl);
    /** data() is not reachable from QML, so the player asks for a row this way. */
    Q_INVOKABLE QVariantMap get(int row) const;

    /** Where an item's ciphertext lives. Used by the playback device. */
    QString objectPath(const QString &id) const;
    bool contentKey(arsivinyo::crypto::SecretBytes *out) const;

 signals:
    void changed();

 private:
    QString root() const;
    bool load();
    QString store();
    void reset();

    QVector<Entry> m_entries;
    SecretStore *m_secrets = nullptr;
    bool m_unreadable = false;
};
