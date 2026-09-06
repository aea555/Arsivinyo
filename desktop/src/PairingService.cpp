#include "PairingService.h"

#include <QCryptographicHash>
#include <QHostAddress>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkInterface>
#include <QSslSocket>
#include <QTimer>

#include "wire.h"

using namespace arsivinyo::pairing;

namespace {

/** A QTcpServer that hands the raw descriptor over instead of making a QTcpSocket. */
class SslTcpServer : public QTcpServer {
public:
    using QTcpServer::QTcpServer;
    std::function<void(qintptr)> onDescriptor;

protected:
    void incomingConnection(qintptr descriptor) override {
        if (onDescriptor) onDescriptor(descriptor);
    }
};

/** The first non-loopback address, which is what a peer on the LAN can reach. */
QString localAddress() {
    for (const QHostAddress &address : QNetworkInterface::allAddresses()) {
        if (address.isLoopback() || address.protocol() != QAbstractSocket::IPv4Protocol) continue;
        return address.toString();
    }
    return QStringLiteral("127.0.0.1");
}

}  // namespace

PairingService::PairingService(QObject *parent) : QObject(parent) {}

bool PairingService::isListening() const { return m_server && m_server->isListening(); }

quint16 PairingService::port() const { return m_server ? m_server->serverPort() : 0; }

bool PairingService::listen(quint16 port) {
    if (!m_identity || !m_identity->ready() || !m_registry) return false;

    if (!m_certificate.isValid()) m_certificate = SessionCertificate::create();
    if (!m_certificate.isValid()) return false;

    auto *server = new SslTcpServer(this);
    server->onDescriptor = [this](qintptr descriptor) {
        auto *socket = new QSslSocket;
        if (!socket->setSocketDescriptor(descriptor)) {
            delete socket;
            return;
        }
        adopt(new PeerLink(socket, PeerLink::Role::Server, m_certificate, m_identity, this));
    };

    if (!server->listen(QHostAddress::Any, port)) {
        delete server;
        return false;
    }

    delete m_server;
    m_server = server;
    emit listeningChanged();
    return true;
}

void PairingService::stop() {
    if (!m_server) return;
    m_server->close();
    m_server->deleteLater();
    m_server = nullptr;
    emit listeningChanged();
}

void PairingService::connectToPeer(const QString &host, quint16 port) {
    if (!m_identity || !m_identity->ready()) return;
    if (!m_certificate.isValid()) m_certificate = SessionCertificate::create();

    auto *link = new PeerLink(new QSslSocket, PeerLink::Role::Client, m_certificate,
                              m_identity, this);
    adopt(link);
    link->connectTo(host, port);
}

void PairingService::adopt(PeerLink *link) {
    connect(link, &PeerLink::authenticated, this,
            [this, link](const QByteArray &key, const QString &name) {
                onAuthenticated(link, key, name);
            });
    connect(link, &PeerLink::failed, this, [this, link](const QString &reason) {
        emit refused(reason);
        dropLink(link);
    });
    connect(link, &PeerLink::closed, this, [this, link] { dropLink(link); });
}

void PairingService::onAuthenticated(PeerLink *link, const QByteArray &key, const QString &name) {
    const QString fingerprint =
        QString::fromLatin1(QCryptographicHash::hash(key, QCryptographicHash::Sha256).toHex());

    if (!m_registry->isPaired(key)) {
        if (!m_pairingMode) {
            // The ordinary case for an unknown device: it proved it holds a key, and this
            // device has never agreed to trust that key.
            emit refused(QStringLiteral("an unpaired device tried to connect"));
            link->close();
            dropLink(link);
            return;
        }
        // Hold the connection while the user compares the six digits. Nothing is stored
        // and no verb is served until confirmPairing().
        m_pendingLink = link;
        m_pendingKey = key;
        m_pendingName = name;
        m_pendingCode = m_identity->pairingCodeWith(QString::fromLatin1(key.toHex()));
        emit pendingPeerChanged();
        return;
    }

    m_registry->noteAddress(key, link->peerAddress());
    auto *session = new PeerSession(link, m_content, this);
    m_sessions.append(session);
    emit peerConnected(fingerprint, name);
}

bool PairingService::confirmPairing() {
    if (!m_pendingLink || m_pendingKey.isEmpty()) return false;

    PeerLink *link = m_pendingLink;
    const QByteArray key = m_pendingKey;
    const QString name = m_pendingName;

    if (!m_registry->remember(key, name, link->peerAddress())) return false;

    m_pendingLink = nullptr;
    m_pendingKey.clear();
    m_pendingCode.clear();
    m_pendingName.clear();
    m_pairingMode = false;
    emit pendingPeerChanged();
    emit pairingModeChanged();

    const QString fingerprint =
        QString::fromLatin1(QCryptographicHash::hash(key, QCryptographicHash::Sha256).toHex());
    auto *session = new PeerSession(link, m_content, this);
    m_sessions.append(session);
    emit paired(fingerprint, name);
    emit peerConnected(fingerprint, name);
    return true;
}

void PairingService::beginPairing(int seconds) {
    m_pairingMode = true;
    emit pairingModeChanged();
    // Pairing mode is a window the user opened, not a state the device sits in. It closes
    // on its own so a device left alone does not stay open to the first key that asks.
    QTimer::singleShot(seconds * 1000, this, [this] {
        if (m_pairingMode) cancelPairing();
    });
}

void PairingService::cancelPairing() {
    if (m_pendingLink) {
        m_pendingLink->close();
        dropLink(m_pendingLink);
        m_pendingLink = nullptr;
    }
    m_pendingKey.clear();
    m_pendingCode.clear();
    m_pendingName.clear();
    if (m_pairingMode) {
        m_pairingMode = false;
        emit pairingModeChanged();
    }
    emit pendingPeerChanged();
}

void PairingService::dropLink(PeerLink *link) {
    for (int i = 0; i < m_sessions.size(); ++i) {
        if (m_sessions.at(i)->link() != link) continue;
        PeerSession *session = m_sessions.takeAt(i);
        const QByteArray key = link->peerKey();
        session->deleteLater();
        if (key.size() == int(kPublicKeyBytes)) {
            emit peerDisconnected(QString::fromLatin1(
                QCryptographicHash::hash(key, QCryptographicHash::Sha256).toHex()));
        }
        break;
    }
    if (m_pendingLink == link) {
        m_pendingLink = nullptr;
        m_pendingKey.clear();
        m_pendingCode.clear();
        emit pendingPeerChanged();
    }
    link->deleteLater();
}

PeerSession *PairingService::sessionFor(const QString &fingerprint) const {
    for (PeerSession *session : m_sessions) {
        const QByteArray key = session->link()->peerKey();
        if (QString::fromLatin1(QCryptographicHash::hash(key, QCryptographicHash::Sha256).toHex())
            == fingerprint) {
            return session;
        }
    }
    return nullptr;
}

QString PairingService::pairingPayload() const {
    if (!m_identity || !m_identity->ready()) return {};
    // Everything the phone needs to reach this device and to know its real key before a
    // single byte crosses the network.
    const QJsonObject payload{
        {"v", 1},
        {"id", m_identity->fingerprint()},
        {"key", QString::fromLatin1(m_identity->publicKey().toHex())},
        {"name", m_identity->deviceName()},
        {"host", localAddress()},
        {"port", port()},
    };
    return QString::fromUtf8(QJsonDocument(payload).toJson(QJsonDocument::Compact));
}
