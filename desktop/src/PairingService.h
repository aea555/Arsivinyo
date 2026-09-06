#pragma once

#include <QByteArray>
#include <QList>
#include <QQmlEngine>
#include <QString>
#include <QTcpServer>
#include <QUrl>

#include "DeviceIdentity.h"
#include "PeerContent.h"
#include "PeerRegistry.h"
#include "PeerSession.h"
#include "SessionCertificate.h"

/**
 * Listens for peers, opens connections to them, and runs the pairing ceremony.
 *
 * **Who may talk to this device.** A connection that authenticates with a key this device
 * has not paired with is closed — unless pairing mode is on, which the user turns on
 * deliberately and which ends as soon as one device is paired or the window elapses. So
 * the ordinary state of an idle device is that an unknown peer gets a TLS handshake, an
 * identity check, and a disconnection.
 *
 * **The ceremony.** The QR code the desktop shows carries its fingerprint, address and
 * port, which is how the phone learns the desktop's real key over a channel an attacker
 * on the network cannot reach. The phone then connects and proves its own key over that
 * channel, and both ends display six digits derived from the two keys. Confirming the
 * digits is what authenticates the phone's key to the desktop: a man in the middle would
 * have to make two different key pairs produce the same six digits.
 */
class PairingService : public QObject {
    Q_OBJECT
    QML_ELEMENT

    // Set from QML, which is where the Library and the engine are created.
    Q_PROPERTY(DeviceIdentity *identity READ identity WRITE setIdentity NOTIFY wiringChanged)
    Q_PROPERTY(PeerRegistry *registry READ registry WRITE setRegistry NOTIFY wiringChanged)
    Q_PROPERTY(QObject *content READ contentObject WRITE setContentObject NOTIFY wiringChanged)
    Q_PROPERTY(bool listening READ isListening NOTIFY listeningChanged)
    Q_PROPERTY(quint16 port READ port NOTIFY listeningChanged)
    Q_PROPERTY(bool pairingMode READ pairingMode NOTIFY pairingModeChanged)
    Q_PROPERTY(QString pendingCode READ pendingCode NOTIFY pendingPeerChanged)
    Q_PROPERTY(QString pendingName READ pendingName NOTIFY pendingPeerChanged)

public:
    explicit PairingService(QObject *parent = nullptr);

    /** Must be set before listening. Not owned. */
    DeviceIdentity *identity() const { return m_identity; }
    void setIdentity(DeviceIdentity *identity);
    PeerRegistry *registry() const { return m_registry; }
    void setRegistry(PeerRegistry *registry);
    void setContent(PeerContent *content) { m_content = content; }
    QObject *contentObject() const { return m_contentObject; }
    void setContentObject(QObject *content);

    bool isListening() const;
    quint16 port() const;
    bool pairingMode() const { return m_pairingMode; }
    QString pendingCode() const { return m_pendingCode; }
    QString pendingName() const { return m_pendingName; }

    /** [port] of 0 asks the system for a free one. */
    Q_INVOKABLE bool listen(quint16 port = 0);
    Q_INVOKABLE void stop();

    /** Open pairing mode for [seconds]; an unknown peer may then present itself. */
    Q_INVOKABLE void beginPairing(int seconds = 120);
    Q_INVOKABLE void cancelPairing();
    /** The user confirmed the six digits match. */
    Q_INVOKABLE bool confirmPairing();

    /** Connect to a peer. Use during pairing with the address from its QR code. */
    Q_INVOKABLE void connectToPeer(const QString &host, quint16 port);

    /** What the desktop's QR code encodes. */
    Q_INVOKABLE QString pairingPayload() const;

    QList<PeerSession *> sessions() const { return m_sessions; }
    /** The live session for a paired peer, or null. */
    Q_INVOKABLE PeerSession *sessionFor(const QString &fingerprint) const;

    /** The dialogs hand back URLs; the transport wants paths. */
    Q_INVOKABLE QString pathOf(const QUrl &url) const { return url.toLocalFile(); }

signals:
    void wiringChanged();
    void listeningChanged();
    void pairingModeChanged();
    void pendingPeerChanged();
    void peerConnected(const QString &fingerprint, const QString &name);
    void peerDisconnected(const QString &fingerprint);
    void paired(const QString &fingerprint, const QString &name);
    /** A connection was refused or lost. Never carries a file name. */
    void refused(const QString &reason);

private:
    void adopt(PeerLink *link);
    void onAuthenticated(PeerLink *link, const QByteArray &key, const QString &name);
    void dropLink(PeerLink *link);

    DeviceIdentity *m_identity = nullptr;
    PeerRegistry *m_registry = nullptr;
    PeerContent *m_content = nullptr;
    QObject *m_contentObject = nullptr;

    QTcpServer *m_server = nullptr;
    SessionCertificate m_certificate;

    bool m_pairingMode = false;
    QString m_pendingCode;
    QString m_pendingName;
    PeerLink *m_pendingLink = nullptr;
    QByteArray m_pendingKey;

    QList<PeerSession *> m_sessions;
};
