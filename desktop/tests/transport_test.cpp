// Two devices, in one process, over real TLS: pair, refuse strangers, and move a file.
//
// The parts worth testing here are the ones that are hard to reason about and easy to get
// subtly wrong — that an unpaired key is turned away, that both ends derive the same six
// digits, and that a corrupted transfer is rejected rather than landing in a library.
#include "PairingService.h"
#include "PeerContent.h"
#include "PeerRegistry.h"
#include "PeerSession.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QFile>
#include <QJsonArray>
#include <QJsonObject>
#include <QTemporaryDir>
#include <QSslConfiguration>
#include <QSslSocket>
#include <QTimer>

#include "wire.h"

using namespace arsivinyo::pairing;

#include <cstdio>
#include <functional>

static int failures = 0;
static void check(bool ok, const char *what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", what);
    if (!ok) ++failures;
}

/** Spin the event loop until [done] or the deadline. Returns whether it happened. */
static bool waitFor(const std::function<bool()> &done, int milliseconds = 5000) {
    QElapsedTimer timer;
    timer.start();
    while (!done() && timer.elapsed() < milliseconds) {
        QCoreApplication::processEvents(QEventLoop::AllEvents, 20);
    }
    return done();
}

/** A content source backed by one directory, so the transport can be tested alone. */
class FolderContent : public PeerContent {
public:
    explicit FolderContent(const QString &dir) : m_dir(dir) { QDir().mkpath(dir); }

    QJsonArray listing(const QString &) const override {
        QJsonArray items;
        for (const QFileInfo &info : QDir(m_dir).entryInfoList(QDir::Files)) {
            items.append(QJsonObject{
                {"id", info.fileName()},
                {"title", info.completeBaseName()},
                {"sizeBytes", info.size()},
            });
        }
        return items;
    }

    QString pathForItem(const QString &id) const override {
        // Only a plain name in this folder, never a path the peer composed.
        if (id.contains('/') || id.contains("..")) return {};
        const QString path = m_dir + '/' + id;
        return QFileInfo(path).isFile() ? path : QString();
    }

    QString destinationFor(const QString &name, const QString &) const override {
        return m_dir + '/' + QFileInfo(name).fileName();
    }

    void accepted(const QString &path, const QString &) override { lastAccepted = path; }
    void download(const QString &url, const QString &) override { lastUrl = url; }

    QString lastAccepted;
    QString lastUrl;

private:
    QString m_dir;
};

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);

    QTemporaryDir dirA, dirB;
    QTemporaryDir filesA, filesB;

    // Each device reads its own data directory at construction, so they are built in turn.
    qputenv("ARSIVINYO_DATA_DIR", dirA.path().toUtf8());
    DeviceIdentity identityA;
    PeerRegistry registryA;

    qputenv("ARSIVINYO_DATA_DIR", dirB.path().toUtf8());
    DeviceIdentity identityB;
    PeerRegistry registryB;

    check(identityA.ready() && identityB.ready(), "both devices have an identity");
    check(identityA.fingerprint() != identityB.fingerprint(), "and they are different");

    FolderContent contentA(filesA.path());
    FolderContent contentB(filesB.path());

    PairingService serviceA;
    serviceA.setIdentity(&identityA);
    serviceA.setRegistry(&registryA);
    serviceA.setContent(&contentA);

    PairingService serviceB;
    serviceB.setIdentity(&identityB);
    serviceB.setRegistry(&registryB);
    serviceB.setContent(&contentB);

    check(serviceA.listen(0), "the first device listens");
    const quint16 port = serviceA.port();
    check(port != 0, "on a real port");

    // ---- an unpaired device is turned away ------------------------------------------
    {
        QString refusal;
        QObject::connect(&serviceA, &PairingService::refused, &app,
                         [&refusal](const QString &why) { refusal = why; });
        serviceB.connectToPeer(QStringLiteral("127.0.0.1"), port);
        const bool refused = waitFor([&] { return !refusal.isEmpty(); });
        check(refused, "an unpaired device is refused");
        check(serviceA.sessions().isEmpty(), "and gets no session");
        QObject::disconnect(&serviceA, &PairingService::refused, &app, nullptr);
    }

    // ---- the ceremony ---------------------------------------------------------------
    {
        serviceA.beginPairing(30);
        serviceB.beginPairing(30);
        serviceB.connectToPeer(QStringLiteral("127.0.0.1"), port);

        const bool bothPending = waitFor([&] {
            return !serviceA.pendingCode().isEmpty() && !serviceB.pendingCode().isEmpty();
        });
        check(bothPending, "both devices offer a code to confirm");
        check(serviceA.pendingCode().size() == 6, "the code is six digits");
        check(serviceA.pendingCode() == serviceB.pendingCode(),
              "and both devices derive the same one");

        check(serviceB.confirmPairing(), "the connecting device confirms");
        check(serviceA.confirmPairing(), "the listening device confirms");
        check(registryA.isPaired(identityB.publicKey()), "each now trusts the other's key");
        check(registryB.isPaired(identityA.publicKey()), "in both directions");
        check(!serviceA.pairingMode(), "and pairing mode closes itself");
    }

    // ---- a file moves, hash verified -------------------------------------------------
    {
        // A megabyte and a bit, so it crosses several frames rather than fitting in one.
        QByteArray payload;
        payload.reserve(1100000);
        for (int i = 0; i < 1100000; ++i) payload.append(char((i * 31 + i / 7) & 0xff));

        QFile source(filesB.path() + "/track.m4a");
        source.open(QIODevice::WriteOnly);
        source.write(payload);
        source.close();

        PeerSession *fromB = serviceB.sessions().value(0);
        check(fromB != nullptr, "the sender has a session");

        bool landed = false;
        QObject::connect(&serviceA, &PairingService::peerConnected, &app, [] {});
        PeerSession *onA = serviceA.sessions().value(0);
        check(onA != nullptr, "the receiver has a session");
        QObject::connect(onA, &PeerSession::fileReceived, &app,
                         [&landed](const QString &, const QString &) { landed = true; });

        check(fromB->sendFile(source.fileName(), QStringLiteral("music")), "the send starts");
        check(waitFor([&] { return landed; }, 20000), "and the file arrives");

        QFile received(filesA.path() + "/track.m4a");
        check(received.open(QIODevice::ReadOnly), "the file is where the receiver put it");
        check(received.readAll() == payload, "byte for byte");
        received.close();
        check(contentA.lastAccepted.endsWith("/track.m4a"), "and the library is told");
        check(!QFile::exists(filesA.path() + "/track.m4a.part"),
              "no partial file is left behind");
    }

    // ---- a transfer that does not verify never lands ---------------------------------
    {
        // Drive the link directly, below PeerSession, so it can lie the way a peer with
        // modified software could: announce one hash and send different bytes.
        PeerLink *raw = serviceB.sessions().value(0)->link();
        PeerSession *onA = serviceA.sessions().value(0);

        QString failure;
        QObject::connect(onA, &PeerSession::transferFailed, &app,
                         [&failure](const QString &why) { failure = why; });

        const QByteArray bytes(4096, 'x');
        const QByteArray lie = QCryptographicHash::hash("not these bytes",
                                                        QCryptographicHash::Sha256);
        raw->sendControl({{"t", "put"},
                          {"name", "forged.m4a"},
                          {"kind", "music"},
                          {"sizeBytes", bytes.size()},
                          {"sha256", QString::fromLatin1(lie.toHex())}});
        check(waitFor([&] { return QFile::exists(filesA.path() + "/forged.m4a.part"); }),
              "the receiver opens a part file");
        raw->sendBulk(bytes);
        raw->sendControl({{"t", "complete"},
                          {"sha256", QString::fromLatin1(lie.toHex())}});

        check(waitFor([&] { return !failure.isEmpty(); }), "a mismatched hash is caught");
        check(!QFile::exists(filesA.path() + "/forged.m4a"),
              "and the file never appears in the library");
        check(!QFile::exists(filesA.path() + "/forged.m4a.part"),
              "nor is the partial left behind");
        QObject::disconnect(onA, &PeerSession::transferFailed, &app, nullptr);
    }

    // ---- a truncated transfer is caught by the size, not only the hash ----------------
    {
        PeerLink *raw = serviceB.sessions().value(0)->link();
        PeerSession *onA = serviceA.sessions().value(0);

        QString failure;
        QObject::connect(onA, &PeerSession::transferFailed, &app,
                         [&failure](const QString &why) { failure = why; });

        const QByteArray whole(8192, 'y');
        const QByteArray sent = whole.left(4096);
        const QByteArray digest = QCryptographicHash::hash(whole, QCryptographicHash::Sha256);
        raw->sendControl({{"t", "put"},
                          {"name", "short.m4a"},
                          {"kind", "music"},
                          {"sizeBytes", whole.size()},
                          {"sha256", QString::fromLatin1(digest.toHex())}});
        check(waitFor([&] { return QFile::exists(filesA.path() + "/short.m4a.part"); }),
              "the receiver opens a part file for the short transfer");
        raw->sendBulk(sent);
        raw->sendControl({{"t", "complete"},
                          {"sha256", QString::fromLatin1(digest.toHex())}});

        check(waitFor([&] { return !failure.isEmpty(); }), "a truncated transfer is caught");
        check(!QFile::exists(filesA.path() + "/short.m4a"), "and does not land");
        QObject::disconnect(onA, &PeerSession::transferFailed, &app, nullptr);
    }

    // ---- a declaration whose size and hash disagree is refused ------------------------
    //
    // The case the size check exists for. Here the peer declares a large size but the hash
    // of the *smaller* payload it intends to send, so the hash alone would be satisfied and
    // a short file would land looking verified.
    {
        PeerLink *raw = serviceB.sessions().value(0)->link();
        PeerSession *onA = serviceA.sessions().value(0);

        QString failure;
        QObject::connect(onA, &PeerSession::transferFailed, &app,
                         [&failure](const QString &why) { failure = why; });

        const QByteArray sent(4096, 'w');
        const QByteArray digest = QCryptographicHash::hash(sent, QCryptographicHash::Sha256);
        raw->sendControl({{"t", "put"},
                          {"name", "mismatch.m4a"},
                          {"kind", "music"},
                          {"sizeBytes", 8192},
                          {"sha256", QString::fromLatin1(digest.toHex())}});
        check(waitFor([&] { return QFile::exists(filesA.path() + "/mismatch.m4a.part"); }),
              "the receiver opens a part file for the mismatched declaration");
        raw->sendBulk(sent);
        raw->sendControl({{"t", "complete"},
                          {"sha256", QString::fromLatin1(digest.toHex())}});

        check(waitFor([&] { return !failure.isEmpty(); }),
              "a size that disagrees with the hash is caught");
        check(!QFile::exists(filesA.path() + "/mismatch.m4a"), "and nothing lands");
        QObject::disconnect(onA, &PeerSession::transferFailed, &app, nullptr);
    }

    // ---- more bytes than declared are refused -----------------------------------------
    {
        PeerLink *raw = serviceB.sessions().value(0)->link();
        PeerSession *onA = serviceA.sessions().value(0);

        QString failure;
        QObject::connect(onA, &PeerSession::transferFailed, &app,
                         [&failure](const QString &why) { failure = why; });

        raw->sendControl({{"t", "put"},
                          {"name", "over.m4a"},
                          {"kind", "music"},
                          {"sizeBytes", 16},
                          {"sha256", QString::fromLatin1(
                               QCryptographicHash::hash("", QCryptographicHash::Sha256).toHex())}});
        check(waitFor([&] { return QFile::exists(filesA.path() + "/over.m4a.part"); }),
              "the receiver opens a part file for the oversized transfer");
        raw->sendBulk(QByteArray(4096, 'z'));

        check(waitFor([&] { return !failure.isEmpty(); }),
              "a peer that sends more than it declared is cut off");
        check(!QFile::exists(filesA.path() + "/over.m4a"), "and nothing lands");
        QObject::disconnect(onA, &PeerSession::transferFailed, &app, nullptr);
    }

    // ---- listing and get -------------------------------------------------------------
    {
        PeerSession *fromB = serviceB.sessions().value(0);
        QJsonArray items;
        QObject::connect(fromB, &PeerSession::listingReceived, &app,
                         [&items](const QString &, const QJsonArray &got) { items = got; });
        fromB->requestListing(QStringLiteral("music"));
        check(waitFor([&] { return !items.isEmpty(); }), "a listing comes back");
        check(items.at(0).toObject().value("id").toString() == QLatin1String("track.m4a"),
              "naming what the peer holds");
    }

    // ---- download hands over a URL ----------------------------------------------------
    {
        PeerSession *fromB = serviceB.sessions().value(0);
        fromB->requestDownload(QStringLiteral("https://example.invalid/watch?v=x"),
                               QStringLiteral("audio"));
        check(waitFor([&] { return !contentA.lastUrl.isEmpty(); }), "a URL reaches the peer");
        check(contentA.lastUrl == QLatin1String("https://example.invalid/watch?v=x"),
              "unchanged");
    }

    // ---- forgetting is one-sided and immediate ----------------------------------------
    {
        check(registryA.forget(identityB.fingerprint()), "a peer can be forgotten");
        check(!registryA.isPaired(identityB.publicKey()), "and is no longer trusted");
        check(registryB.isPaired(identityA.publicKey()),
              "while the other end still trusts it, since nothing was sent");
    }

    // ---- the transcript is what keeps a man in the middle out --------------------------
    //
    // An attacker who terminates TLS on both legs holds a genuine Ed25519 key and can sign
    // whatever it likes — but the transcript names *this* connection's two certificates, so
    // a signature made anywhere else does not verify here. Pairing mode is opened first so
    // that "unpaired" cannot be the reason the connection is refused: the signature is.
    {
        QTemporaryDir dirC;
        qputenv("ARSIVINYO_DATA_DIR", dirC.path().toUtf8());
        DeviceIdentity attacker;

        serviceA.beginPairing(30);
        QString refusal;
        QObject::connect(&serviceA, &PairingService::refused, &app,
                         [&refusal](const QString &why) { refusal = why; });

        const SessionCertificate theirs = SessionCertificate::create();
        auto *socket = new QSslSocket;
        QSslConfiguration config = socket->sslConfiguration();
        config.setLocalCertificate(theirs.certificate);
        config.setPrivateKey(theirs.key);
        config.setPeerVerifyMode(QSslSocket::QueryPeer);
        socket->setSslConfiguration(config);
        QObject::connect(socket, &QSslSocket::sslErrors, socket,
                         [socket](const QList<QSslError> &e) { socket->ignoreSslErrors(e); });

        QObject::connect(socket, &QSslSocket::encrypted, socket, [&, socket] {
            // Sign a transcript for certificates that are not the ones in play here,
            // which is exactly what a relayed signature from another leg looks like.
            const std::vector<uint8_t> wrongServer(kCertHashBytes, 0xAA);
            const std::vector<uint8_t> wrongClient(kCertHashBytes, 0xBB);
            const std::vector<uint8_t> transcript =
                AuthTranscript(AuthRole::Client, wrongServer, wrongClient);
            const QByteArray signature = attacker.sign(
                QByteArray(reinterpret_cast<const char *>(transcript.data()),
                           int(transcript.size())));

            const QJsonObject message{
                {"t", "auth"},
                {"v", 1},
                {"key", QString::fromLatin1(attacker.publicKey().toHex())},
                {"name", "impostor"},
                {"sig", QString::fromLatin1(signature.toHex())},
            };
            const QByteArray json = QJsonDocument(message).toJson(QJsonDocument::Compact);
            std::vector<uint8_t> frame;
            EncodeFrame(FrameType::Control, {json.begin(), json.end()}, &frame);
            socket->write(reinterpret_cast<const char *>(frame.data()), frame.size());
        });

        socket->connectToHostEncrypted(QStringLiteral("127.0.0.1"), port);
        check(waitFor([&] { return !refusal.isEmpty(); }),
              "a signature bound to another session is refused");
        check(serviceA.pendingCode().isEmpty(),
              "and it never reaches the code the user would confirm");
        QObject::disconnect(&serviceA, &PairingService::refused, &app, nullptr);
        serviceA.cancelPairing();
        socket->deleteLater();
    }

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
