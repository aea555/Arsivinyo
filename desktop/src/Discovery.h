#pragma once

#include <QAbstractListModel>
#include <QMap>
#include <QQmlEngine>
#include <QString>
#include <QTimer>
#include <QUdpSocket>

/**
 * Announces this device on the local network and lists the others.
 *
 * Discovery reveals only that an Arsivinyo device exists, what it calls itself, and its
 * identity fingerprint. It says nothing about a library and grants nothing: a peer found
 * here still has to authenticate, and is still refused unless it has been paired. The
 * fingerprint is published so a *known* peer can be recognised without opening a
 * connection to ask.
 *
 * Nothing here is trusted. The name is chosen by the peer and is only ever displayed, and
 * the fingerprint is a claim until the transport verifies a signature against it.
 */
class Discovery : public QAbstractListModel {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(int count READ rowCount NOTIFY changed)
    Q_PROPERTY(bool active READ isActive NOTIFY changed)

public:
    struct Found {
        QString fingerprint;
        QString name;
        QString host;
        quint16 port = 0;
        qint64 seenAt = 0;
    };

    enum Roles {
        FingerprintRole = Qt::UserRole + 1,
        NameRole,
        HostRole,
        PortRole,
    };

    explicit Discovery(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    bool isActive() const { return m_socket && m_socket->state() == QAbstractSocket::BoundState; }

    /** Start listening, and announce this device if [port] is non-zero. */
    Q_INVOKABLE bool start(const QString &fingerprint, const QString &name, quint16 port);
    Q_INVOKABLE void stop();
    /** Ask everyone to announce themselves now. */
    Q_INVOKABLE void browse();

    /** The service type, as it appears on the wire. */
    static QString serviceType();

signals:
    void changed();
    void peerFound(const QString &fingerprint, const QString &name, const QString &host,
                   quint16 port);

private slots:
    void onDatagram();

private:
    void announce();
    void forget(qint64 olderThan);

    QUdpSocket *m_socket = nullptr;
    QTimer m_expiry;
    QString m_fingerprint;
    QString m_name;
    quint16 m_port = 0;
    QString m_address;
    QList<Found> m_found;
};
