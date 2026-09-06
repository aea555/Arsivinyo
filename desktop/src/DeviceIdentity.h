#pragma once

#include <QByteArray>
#include <QObject>
#include <QQmlEngine>
#include <QString>

/**
 * This device's permanent identity: one Ed25519 keypair, generated once and kept.
 *
 * The public key *is* the identity. Its SHA-256 is the fingerprint shown to the user and
 * advertised over mDNS, so a known peer is recognised before a connection is opened.
 *
 * The private key is written with owner-only permissions. The Android side stores its
 * key the same way, in app-private storage rather than the Keystore, because the
 * Keystore does not support Ed25519 — see the note on DeviceIdentity.kt. The threat
 * either defends against is another user or app on the same machine, not its owner.
 * Moving to a platform keychain later changes no bytes on the wire.
 *
 * Losing the key means becoming a new device that must pair again. That is intended: it
 * is the same property that makes a wiped phone's old pairings useless.
 */
class DeviceIdentity : public QObject {
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON

    Q_PROPERTY(QString fingerprint READ fingerprint NOTIFY readyChanged)
    Q_PROPERTY(QString shortFingerprint READ shortFingerprint NOTIFY readyChanged)
    Q_PROPERTY(QString deviceName READ deviceName WRITE setDeviceName NOTIFY deviceNameChanged)
    Q_PROPERTY(bool ready READ ready NOTIFY readyChanged)

public:
    explicit DeviceIdentity(QObject *parent = nullptr);

    bool ready() const { return !m_publicKey.isEmpty(); }
    /** Hex SHA-256 of the public key. */
    QString fingerprint() const { return m_fingerprint; }
    /** The first eight hex characters, grouped, for showing next to a device name. */
    QString shortFingerprint() const;
    QString deviceName() const { return m_deviceName; }
    void setDeviceName(const QString &name);

    QByteArray publicKey() const { return m_publicKey; }

    /** Sign with the private key. Empty on failure. */
    QByteArray sign(const QByteArray &message) const;
    /** Verify a peer's signature against their public key. */
    static bool verify(const QByteArray &publicKey, const QByteArray &message,
                       const QByteArray &signature);

    /** The six digits both devices must show. See shared/pairing/VECTORS.json. */
    Q_INVOKABLE QString pairingCodeWith(const QString &peerPublicKeyHex) const;

signals:
    void readyChanged();
    void deviceNameChanged();

private:
    bool load();
    bool generate();
    QString keyPath() const;
    QString namePath() const;

    QByteArray m_publicKey;
    QByteArray m_privateKey;
    QString m_fingerprint;
    QString m_deviceName;
};
