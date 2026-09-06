#pragma once

#include <QByteArray>
#include <QJsonObject>
#include <QObject>
#include <QSslSocket>

#include "DeviceIdentity.h"
#include "SessionCertificate.h"

/**
 * One connection to one peer: TLS, identity, and framing.
 *
 * The link is not usable until [authenticated] fires. Until then it will accept nothing
 * but the peer's own `auth` message — an unpaired device that connects and starts issuing
 * requests gets no further than the handshake.
 *
 * **How the peer is identified.** TLS here is encryption only; the certificate is
 * self-signed and nothing checks a name or a chain, because no certificate authority is
 * involved and the pairing *is* the trust. What settles identity is the `auth` message:
 * each side signs, with its Ed25519 identity key, a transcript naming both certificates of
 * *this* connection. A man in the middle terminating TLS on both legs presents different
 * certificates on each, so a signature made for one leg does not verify on the other, and
 * it cannot forge one without the key.
 */
class PeerLink : public QObject {
    Q_OBJECT

public:
    /** Which end this is. The server accepted the connection; the client opened it. */
    enum class Role { Server, Client };

    /**
     * [socket] is adopted. For a server link it is an already-accepted socket; for a
     * client link a fresh one that has not yet connected.
     */
    PeerLink(QSslSocket *socket, Role role, const SessionCertificate &certificate,
             const DeviceIdentity *identity, QObject *parent = nullptr);

    Role role() const { return m_role; }
    bool isAuthenticated() const { return m_authenticated; }
    QByteArray peerKey() const { return m_peerKey; }
    QString peerName() const { return m_peerName; }
    QString peerAddress() const;

    /** Start a client link's TLS handshake. A server link begins on construction. */
    void connectTo(const QString &host, quint16 port);

    bool sendControl(const QJsonObject &message);
    bool sendBulk(const QByteArray &chunk);

    void close();

    /** The largest bulk payload one frame may carry, leaving room for the header. */
    static int maxBulkChunk();

    /** How much is queued but not yet on the wire, so a sender can pace itself. */
    qint64 bytesToWrite() const;

signals:
    void authenticated(const QByteArray &peerKey, const QString &peerName);
    void controlReceived(const QJsonObject &message);
    void bulkReceived(const QByteArray &chunk);
    /** Fatal. The socket is closed by the time this fires. */
    void failed(const QString &reason);
    void closed();
    /** The socket drained; a streaming sender may queue more. */
    void drained();

private slots:
    void onEncrypted();
    void onReadyRead();

private:
    void fail(const QString &reason);
    bool sendFrame(quint8 type, const QByteArray &payload);
    void handleControl(const QByteArray &payload);
    void handleAuth(const QJsonObject &message);
    QByteArray transcriptFor(bool forPeer) const;

    QSslSocket *m_socket = nullptr;
    Role m_role;
    SessionCertificate m_certificate;
    const DeviceIdentity *m_identity = nullptr;

    QByteArray m_inbox;
    bool m_authenticated = false;
    bool m_sentAuth = false;
    QByteArray m_peerKey;
    QString m_peerName;
};
