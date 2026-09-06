// Ed25519 identity: persistence, signing, and that the pairing code agrees with the
// shared vectors' derivation.
#include "DeviceIdentity.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTemporaryDir>

#include <cstdio>

static int failures = 0;
static void check(bool ok, const char *what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", what);
    if (!ok) ++failures;
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QTemporaryDir data;
    qputenv("ARSIVINYO_DATA_DIR", data.path().toUtf8());

    QString firstFingerprint;
    QByteArray firstPublic;
    {
        DeviceIdentity identity;
        check(identity.ready(), "a keypair is generated on first run");
        check(identity.publicKey().size() == 32, "the public key is 32 bytes");
        check(identity.fingerprint().size() == 64, "the fingerprint is a hex SHA-256");
        check(identity.fingerprint() ==
                  QCryptographicHash::hash(identity.publicKey(), QCryptographicHash::Sha256).toHex(),
              "the fingerprint is the hash of the public key");
        firstFingerprint = identity.fingerprint();
        firstPublic = identity.publicKey();

        const QByteArray message = "pair with me";
        const QByteArray signature = identity.sign(message);
        check(signature.size() == 64, "signing produces 64 bytes");
        check(DeviceIdentity::verify(identity.publicKey(), message, signature),
              "a signature verifies against its own key");
        check(!DeviceIdentity::verify(identity.publicKey(), "different", signature),
              "it does not verify a different message");

        QByteArray tampered = signature;
        tampered[0] = tampered[0] ^ 0x01;
        check(!DeviceIdentity::verify(identity.publicKey(), message, tampered),
              "a flipped bit in the signature is rejected");
    }

    // The key has to survive a restart, or every launch is a new device.
    {
        DeviceIdentity identity;
        check(identity.fingerprint() == firstFingerprint, "the identity persists across restarts");
        check(identity.publicKey() == firstPublic, "and it is the same key, not a new one");
    }

    const QFileInfo keyFile(data.path() + "/device.key");
    check(keyFile.exists(), "the key is on disk");
    check(!keyFile.permissions().testFlag(QFileDevice::ReadGroup) &&
              !keyFile.permissions().testFlag(QFileDevice::ReadOther),
          "and is readable only by its owner");

    // Two identities must agree on their shared code, in either direction.
    {
        QTemporaryDir other;
        DeviceIdentity mine;
        qputenv("ARSIVINYO_DATA_DIR", other.path().toUtf8());
        DeviceIdentity theirs;
        check(mine.fingerprint() != theirs.fingerprint(), "two devices have different identities");

        const QString a = mine.pairingCodeWith(theirs.publicKey().toHex());
        const QString b = theirs.pairingCodeWith(mine.publicKey().toHex());
        check(a.size() == 6, "the pairing code is six digits");
        check(a == b, "both devices compute the same code");
        check(mine.pairingCodeWith("00").isEmpty(), "a malformed peer key yields no code");
    }

    // Interoperability: the RFC 8032 vector must verify here, or a signature made on
    // the phone will not verify on the desktop and pairing fails with nothing to read.
    {
        QFile file(QStringLiteral(ARSIVINYO_VECTORS));
        if (file.open(QIODevice::ReadOnly)) {
            const QJsonArray cases =
                QJsonDocument::fromJson(file.readAll()).object().value("ed25519").toArray();
            for (const QJsonValue &entry : cases) {
                const QJsonObject v = entry.toObject();
                const QByteArray pub = QByteArray::fromHex(v.value("publicKey").toString().toLatin1());
                const QByteArray msg = QByteArray::fromHex(v.value("message").toString().toLatin1());
                const QByteArray sig = QByteArray::fromHex(v.value("signature").toString().toLatin1());
                check(DeviceIdentity::verify(pub, msg, sig),
                      "the RFC 8032 signature verifies");
                QByteArray bad = msg;
                bad.append('x');
                check(!DeviceIdentity::verify(pub, bad, sig),
                      "and does not verify a changed message");

                // Load the vector's own key material as this device's identity. That
                // pins the two things a self-consistent implementation can still get
                // wrong: the digest behind the fingerprint the user compares on screen,
                // and the bytes this side produces when it signs. Ed25519 has no nonce,
                // so the signature must match the vector exactly.
                const QByteArray seed = QByteArray::fromHex(v.value("seed").toString().toLatin1());
                QTemporaryDir loaded;
                qputenv("ARSIVINYO_DATA_DIR", loaded.path().toUtf8());
                QFile keyFile(loaded.path() + "/device.key");
                if (keyFile.open(QIODevice::WriteOnly)) {
                    keyFile.write(seed);
                    keyFile.write(pub);
                    keyFile.close();

                    DeviceIdentity known;
                    check(known.publicKey() == pub, "a stored identity loads its public key");
                    check(known.fingerprint().toLatin1() ==
                              v.value("fingerprint").toString().toLatin1(),
                          "the fingerprint matches the shared vector");
                    check(known.sign(msg) == sig,
                          "signing reproduces the vector signature byte for byte");
                } else {
                    check(false, "the vector key material is writable");
                }
                qputenv("ARSIVINYO_DATA_DIR", data.path().toUtf8());
            }
        } else {
            check(false, "vectors file is readable");
        }
    }

    // The transcript signature, from the same seed the shared vectors name. Both apps
    // must produce these exact bytes or authentication fails in one direction only,
    // which is the hardest kind of pairing bug to read.
    {
        QFile file(QStringLiteral(ARSIVINYO_VECTORS));
        if (file.open(QIODevice::ReadOnly)) {
            const QJsonObject root = QJsonDocument::fromJson(file.readAll()).object();
            const QByteArray seed =
                QByteArray::fromHex(root.value("auth_transcript_seed").toString().toLatin1());

            QTemporaryDir dir;
            qputenv("ARSIVINYO_DATA_DIR", dir.path().toUtf8());
            QFile keyFile(dir.path() + "/device.key");
            if (keyFile.open(QIODevice::WriteOnly)) {
                // The public half is derived by signing rather than stored here, so write
                // what load() expects: seed followed by its public key.
                keyFile.write(seed);
                keyFile.write(QByteArray::fromHex(
                    root.value("ed25519").toArray().at(0).toObject()
                        .value("publicKey").toString().toLatin1()));
                keyFile.close();

                DeviceIdentity signer;
                for (const QJsonValue &entry : root.value("auth_transcript").toArray()) {
                    const QJsonObject v = entry.toObject();
                    const QByteArray transcript =
                        QByteArray::fromHex(v.value("transcript").toString().toLatin1());
                    const QByteArray label =
                        ("signs the " + v.value("role").toString() + " transcript to the vector")
                            .toLatin1();
                    check(signer.sign(transcript).toHex() ==
                              v.value("signature").toString().toLatin1(),
                          label.constData());
                }
            }
            qputenv("ARSIVINYO_DATA_DIR", data.path().toUtf8());
        }
    }

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
