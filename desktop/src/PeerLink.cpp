#include "PeerLink.h"

#include <QCryptographicHash>
#include <QHostAddress>
#include <QJsonDocument>
#include <QSslConfiguration>

#include "wire.h"

using namespace arsivinyo::pairing;

namespace {

std::vector<uint8_t> toVector(const QByteArray &bytes) {
    return {bytes.begin(), bytes.end()};
}

QByteArray toByteArray(const std::vector<uint8_t> &bytes) {
    return QByteArray(reinterpret_cast<const char *>(bytes.data()),
                      static_cast<int>(bytes.size()));
}

}  // namespace

int PeerLink::maxBulkChunk() {
    // The frame length covers the type byte, so a payload may be one byte short of the cap.
    return static_cast<int>(kMaxFrameBytes) - 1;
}

PeerLink::PeerLink(QSslSocket *socket, Role role, const SessionCertificate &certificate,
                   const DeviceIdentity *identity, QObject *parent)
    : QObject(parent),
      m_socket(socket),
      m_role(role),
      m_certificate(certificate),
      m_identity(identity) {
    m_socket->setParent(this);

    QSslConfiguration config = m_socket->sslConfiguration();
    config.setLocalCertificate(m_certificate.certificate);
    config.setPrivateKey(m_certificate.key);
    // Ask for the peer's certificate but do not judge it. There is no certificate
    // authority here by design; the Ed25519 transcript below is what decides identity.
    // Refusing to verify is only safe *because* nothing downstream trusts the result.
    config.setPeerVerifyMode(QSslSocket::QueryPeer);
    config.setProtocol(QSsl::TlsV1_2OrLater);
    m_socket->setSslConfiguration(config);

    connect(m_socket, &QSslSocket::encrypted, this, &PeerLink::onEncrypted);
    connect(m_socket, &QSslSocket::readyRead, this, &PeerLink::onReadyRead);
    connect(m_socket, &QSslSocket::disconnected, this, &PeerLink::closed);
    connect(m_socket, &QSslSocket::bytesWritten, this, [this](qint64) { emit drained(); });
    connect(m_socket, &QSslSocket::sslErrors, this,
            [this](const QList<QSslError> &errors) {
                // Self-signed and unknown-issuer are the expected shape of every
                // connection here. Anything else is a genuine transport problem.
                for (const QSslError &error : errors) {
                    switch (error.error()) {
                        case QSslError::SelfSignedCertificate:
                        case QSslError::SelfSignedCertificateInChain:
                        case QSslError::HostNameMismatch:
                        case QSslError::CertificateUntrusted:
                        case QSslError::UnableToGetLocalIssuerCertificate:
                        case QSslError::UnableToVerifyFirstCertificate:
                            continue;
                        default:
                            fail(QStringLiteral("TLS error: ") + error.errorString());
                            return;
                    }
                }
                m_socket->ignoreSslErrors(errors);
            });
    connect(m_socket, &QAbstractSocket::errorOccurred, this,
            [this](QAbstractSocket::SocketError) { fail(m_socket->errorString()); });

    if (m_role == Role::Server) m_socket->startServerEncryption();
}

qint64 PeerLink::bytesToWrite() const {
    return m_socket ? m_socket->bytesToWrite() : 0;
}

void PeerLink::connectTo(const QString &host, quint16 port) {
    m_socket->connectToHostEncrypted(host, port);
}

QString PeerLink::peerAddress() const {
    if (!m_socket) return {};
    const QHostAddress address = m_socket->peerAddress();
    if (address.isNull()) return {};
    return address.toString() + QLatin1Char(':') + QString::number(m_socket->peerPort());
}

QByteArray PeerLink::transcriptFor(bool forPeer) const {
    if (!m_socket) return {};
    const QByteArray own = m_certificate.fingerprint();
    const QByteArray peer = SessionCertificate::fingerprintOf(m_socket->peerCertificate());
    if (own.size() != int(kCertHashBytes) || peer.size() != int(kCertHashBytes)) return {};

    // The server's certificate always comes first, so both ends build identical bytes
    // without negotiating an order.
    const bool weAreServer = m_role == Role::Server;
    const auto [serverHash, clientHash] = TranscriptOrder(
        weAreServer ? AuthRole::Server : AuthRole::Client, toVector(own), toVector(peer));

    // Our own signature carries our role; the peer's carries theirs.
    const bool signerIsServer = forPeer ? !weAreServer : weAreServer;
    return toByteArray(AuthTranscript(signerIsServer ? AuthRole::Server : AuthRole::Client,
                                      serverHash, clientHash));
}

void PeerLink::onEncrypted() {
    if (m_sentAuth) return;
    if (!m_identity || !m_identity->ready()) {
        fail(QStringLiteral("this device has no identity"));
        return;
    }

    const QByteArray transcript = transcriptFor(false);
    if (transcript.isEmpty()) {
        fail(QStringLiteral("the peer presented no certificate"));
        return;
    }

    const QByteArray signature = m_identity->sign(transcript);
    if (signature.isEmpty()) {
        fail(QStringLiteral("could not sign the session transcript"));
        return;
    }

    m_sentAuth = true;
    sendControl({
        {"t", "auth"},
        {"v", 1},
        {"key", QString::fromLatin1(m_identity->publicKey().toHex())},
        {"name", m_identity->deviceName()},
        {"sig", QString::fromLatin1(signature.toHex())},
    });
}

void PeerLink::onReadyRead() {
    m_inbox.append(m_socket->readAll());

    for (;;) {
        FrameType type = FrameType::Control;
        std::vector<uint8_t> payload;
        size_t consumed = 0;
        const std::vector<uint8_t> buffer = toVector(m_inbox);

        switch (DecodeFrame(buffer, &type, &payload, &consumed)) {
            case DecodeResult::Incomplete:
                return;
            case DecodeResult::TooLarge:
                fail(QStringLiteral("the peer announced an oversized frame"));
                return;
            case DecodeResult::BadType:
                fail(QStringLiteral("the peer sent an unknown frame type"));
                return;
            case DecodeResult::Ok:
                break;
        }

        m_inbox.remove(0, static_cast<int>(consumed));
        const QByteArray body = toByteArray(payload);

        if (type == FrameType::Control) {
            handleControl(body);
        } else if (!m_authenticated) {
            // Bulk before the peer has proved who it is would mean writing an unknown
            // device's bytes to disk.
            fail(QStringLiteral("the peer sent data before authenticating"));
            return;
        } else {
            emit bulkReceived(body);
        }

        if (!m_socket) return;  // fail() may have torn the link down
    }
}

void PeerLink::handleControl(const QByteArray &payload) {
    QJsonParseError error{};
    const QJsonDocument document = QJsonDocument::fromJson(payload, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) {
        fail(QStringLiteral("the peer sent a malformed control message"));
        return;
    }

    const QJsonObject message = document.object();
    if (!m_authenticated) {
        if (message.value("t").toString() != QLatin1String("auth")) {
            fail(QStringLiteral("the peer spoke before authenticating"));
            return;
        }
        handleAuth(message);
        return;
    }
    emit controlReceived(message);
}

void PeerLink::handleAuth(const QJsonObject &message) {
    const QByteArray key = QByteArray::fromHex(message.value("key").toString().toLatin1());
    const QByteArray signature = QByteArray::fromHex(message.value("sig").toString().toLatin1());
    if (key.size() != int(kPublicKeyBytes)) {
        fail(QStringLiteral("the peer offered a malformed identity key"));
        return;
    }

    const QByteArray transcript = transcriptFor(true);
    if (transcript.isEmpty() || !DeviceIdentity::verify(key, transcript, signature)) {
        // Either the peer does not hold the key it claims, or something is sitting in the
        // middle terminating TLS — the transcript names this connection's certificates,
        // so a signature from another connection cannot verify here.
        fail(QStringLiteral("the peer could not prove its identity"));
        return;
    }

    m_peerKey = key;
    m_peerName = message.value("name").toString();
    m_authenticated = true;
    emit authenticated(m_peerKey, m_peerName);
}

bool PeerLink::sendControl(const QJsonObject &message) {
    return sendFrame(static_cast<quint8>(FrameType::Control),
                     QJsonDocument(message).toJson(QJsonDocument::Compact));
}

bool PeerLink::sendBulk(const QByteArray &chunk) {
    return sendFrame(static_cast<quint8>(FrameType::Bulk), chunk);
}

bool PeerLink::sendFrame(quint8 type, const QByteArray &payload) {
    if (!m_socket || m_socket->state() != QAbstractSocket::ConnectedState) return false;

    std::vector<uint8_t> frame;
    if (!EncodeFrame(static_cast<FrameType>(type), toVector(payload), &frame)) return false;

    const QByteArray bytes = toByteArray(frame);
    return m_socket->write(bytes) == bytes.size();
}

void PeerLink::fail(const QString &reason) {
    if (!m_socket) return;
    QSslSocket *socket = m_socket;
    m_socket = nullptr;  // so a cascade of socket errors does not re-enter
    socket->abort();
    emit failed(reason);
}

void PeerLink::close() {
    if (!m_socket) return;
    m_socket->disconnectFromHost();
}
