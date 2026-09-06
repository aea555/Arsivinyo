#pragma once

#include <QAbstractListModel>
#include <QByteArray>
#include <QQmlEngine>
#include <QSqlDatabase>
#include <QString>
#include <QVector>

/**
 * The devices this one has paired with.
 *
 * A peer is remembered by its Ed25519 public key. Nothing else here is trusted: the name
 * is what the peer called itself and is only ever shown, never matched against.
 *
 * Unpairing is local and one-sided — [forget] deletes the row and no message is sent,
 * because a device that has been forgotten should not be told.
 */
class PeerRegistry : public QAbstractListModel {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(int count READ rowCount NOTIFY changed)

public:
    struct Peer {
        QByteArray publicKey;   ///< 32 raw bytes
        QString fingerprint;    ///< hex SHA-256 of publicKey
        QString name;
        QString lastAddress;
        qint64 pairedAt = 0;
    };

    enum Roles {
        FingerprintRole = Qt::UserRole + 1,
        NameRole,
        LastAddressRole,
        PairedAtRole,
    };

    explicit PeerRegistry(QObject *parent = nullptr);
    ~PeerRegistry() override;

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    /** Add or update a pairing. Returns false only if the key is not 32 bytes. */
    bool remember(const QByteArray &publicKey, const QString &name, const QString &address);
    Q_INVOKABLE bool forget(const QString &fingerprint);

    /** The single question the transport asks: may this key connect? */
    bool isPaired(const QByteArray &publicKey) const;
    bool peerFor(const QByteArray &publicKey, Peer *out) const;

    /** Record where a peer was last reached, so it can be tried again without discovery. */
    void noteAddress(const QByteArray &publicKey, const QString &address);

signals:
    void changed();

private:
    void load();

    QSqlDatabase m_db;
    QVector<Peer> m_peers;
    QString m_connectionName;
};
