// Connect to a peer and report what happens, for proving the two apps interoperate.
//
// Everything else is C++ against C++ or Kotlin against Kotlin. The shared vectors are what
// keep the two honest about bytes, but they say nothing about whether a Conscrypt TLS
// stack and an OpenSSL one will actually complete a handshake with self-signed P-256
// certificates and client auth on both ends. This finds out.
//
// Connecting as an unpaired device is enough: the refusal happens *after* the handshake
// and after the peer has verified this device's signature, so a clean "unpaired" refusal
// proves both directions of the identity exchange worked.
//
//   pair_probe <host> <port>
#include "DeviceIdentity.h"
#include "PeerLink.h"
#include "SessionCertificate.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QSslSocket>
#include <QTemporaryDir>
#include <QTimer>

#include <cstdio>

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    if (argc < 3) {
        std::printf("usage: pair_probe <host> <port>\n");
        return 2;
    }
    const QString host = QString::fromUtf8(argv[1]);
    const quint16 port = quint16(QString::fromUtf8(argv[2]).toUShort());

    // A throwaway identity, so probing never touches the real one.
    QTemporaryDir dir;
    qputenv("ARSIVINYO_DATA_DIR", dir.path().toUtf8());
    DeviceIdentity identity;
    identity.setDeviceName(QStringLiteral("Probe"));
    std::printf("this probe    : %s\n", qPrintable(identity.fingerprint().left(16)));

    const SessionCertificate certificate = SessionCertificate::create();
    if (!certificate.isValid()) {
        std::printf("FAIL: no session certificate\n");
        return 1;
    }

    auto *socket = new QSslSocket;
    auto *link = new PeerLink(socket, PeerLink::Role::Client, certificate, &identity);

    int exitCode = 1;
    QObject::connect(link, &PeerLink::authenticated, &app,
                     [&](const QByteArray &key, const QString &name) {
                         std::printf("TLS           : %s\n",
                                     socket->sessionProtocol() == QSsl::TlsV1_3 ? "TLS 1.3"
                                     : socket->sessionProtocol() == QSsl::TlsV1_2 ? "TLS 1.2"
                                     : "negotiated");
                         std::printf("peer verified : yes\n");
                         std::printf("peer name     : %s\n", qPrintable(name));
                         // The fingerprint, not the raw key: this is what the peer
                         // advertises over mDNS, so printing it here is what proves a
                         // device authenticates as the identity it announced.
                         const QByteArray fingerprint =
                             QCryptographicHash::hash(key, QCryptographicHash::Sha256).toHex();
                         std::printf("peer id       : %s\n",
                                     qPrintable(QString::fromLatin1(fingerprint)));
                         std::printf("\nThe peer proved its identity to this device.\n");
                         std::printf("Waiting to see what it does with ours...\n");
                     });
    QObject::connect(link, &PeerLink::failed, &app, [&](const QString &reason) {
        std::printf("\npeer response : %s\n", qPrintable(reason));
        exitCode = 0;  // a refusal is a successful probe: the stack ran end to end
        app.quit();
    });
    QObject::connect(link, &PeerLink::closed, &app, [&] {
        std::printf("\npeer closed the connection (expected for an unpaired device)\n");
        exitCode = 0;
        app.quit();
    });

    link->connectTo(host, port);
    QTimer::singleShot(20000, &app, [&] {
        std::printf("\nTIMEOUT: no answer in 20s\n");
        app.quit();
    });

    app.exec();
    return exitCode;
}
