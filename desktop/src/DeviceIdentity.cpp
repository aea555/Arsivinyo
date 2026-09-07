#include "DeviceIdentity.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QHostInfo>
#include <QStandardPaths>

#include <openssl/evp.h>

#include "wire.h"

#include "DataDir.h"

namespace {

/** Ed25519: 32-byte public key, 32-byte private seed, 64-byte signature. */
constexpr int kPublicBytes = 32;
constexpr int kPrivateBytes = 32;

QString dataDir() { return arsivinyo::dataDirPath(); }

}  // namespace

DeviceIdentity::DeviceIdentity(QObject *parent) : QObject(parent) {
    if (!load()) generate();

    QFile nameFile(namePath());
    if (nameFile.open(QIODevice::ReadOnly))
        m_deviceName = QString::fromUtf8(nameFile.readAll()).trimmed();
    if (m_deviceName.isEmpty()) m_deviceName = QHostInfo::localHostName();
}

QString DeviceIdentity::keyPath() const { return dataDir() + "/device.key"; }
QString DeviceIdentity::namePath() const { return dataDir() + "/device.name"; }

bool DeviceIdentity::load() {
    QFile file(keyPath());
    if (!file.open(QIODevice::ReadOnly)) return false;
    const QByteArray raw = file.readAll();
    if (raw.size() != kPrivateBytes + kPublicBytes) return false;

    m_privateKey = raw.left(kPrivateBytes);
    m_publicKey = raw.mid(kPrivateBytes, kPublicBytes);
    m_fingerprint = QCryptographicHash::hash(m_publicKey, QCryptographicHash::Sha256).toHex();
    return true;
}

bool DeviceIdentity::generate() {
    EVP_PKEY *key = nullptr;
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_ED25519, nullptr);
    if (!ctx) return false;
    const bool made = EVP_PKEY_keygen_init(ctx) == 1 && EVP_PKEY_keygen(ctx, &key) == 1;
    EVP_PKEY_CTX_free(ctx);
    if (!made || !key) return false;

    size_t publicLen = kPublicBytes, privateLen = kPrivateBytes;
    QByteArray publicKey(kPublicBytes, 0), privateKey(kPrivateBytes, 0);
    const bool exported =
        EVP_PKEY_get_raw_public_key(key, reinterpret_cast<unsigned char *>(publicKey.data()), &publicLen) == 1 &&
        EVP_PKEY_get_raw_private_key(key, reinterpret_cast<unsigned char *>(privateKey.data()), &privateLen) == 1;
    EVP_PKEY_free(key);
    if (!exported) return false;

    QFile file(keyPath());
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)) return false;
    // Set before writing, so the key is never briefly world-readable on disk.
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    file.write(privateKey);
    file.write(publicKey);
    file.close();

    m_privateKey = privateKey;
    m_publicKey = publicKey;
    m_fingerprint = QCryptographicHash::hash(m_publicKey, QCryptographicHash::Sha256).toHex();
    emit readyChanged();
    return true;
}

QString DeviceIdentity::shortFingerprint() const {
    if (m_fingerprint.size() < 8) return m_fingerprint;
    return m_fingerprint.left(4).toUpper() + " " + m_fingerprint.mid(4, 4).toUpper();
}

void DeviceIdentity::setDeviceName(const QString &name) {
    const QString trimmed = name.trimmed();
    if (trimmed.isEmpty() || trimmed == m_deviceName) return;
    m_deviceName = trimmed;
    QFile file(namePath());
    if (file.open(QIODevice::WriteOnly | QIODevice::Truncate)) file.write(trimmed.toUtf8());
    emit deviceNameChanged();
}

QByteArray DeviceIdentity::sign(const QByteArray &message) const {
    if (m_privateKey.size() != kPrivateBytes) return {};
    EVP_PKEY *key = EVP_PKEY_new_raw_private_key(
        EVP_PKEY_ED25519, nullptr,
        reinterpret_cast<const unsigned char *>(m_privateKey.constData()), kPrivateBytes);
    if (!key) return {};

    QByteArray signature(64, 0);
    size_t length = signature.size();
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    const bool ok = ctx &&
        EVP_DigestSignInit(ctx, nullptr, nullptr, nullptr, key) == 1 &&
        EVP_DigestSign(ctx, reinterpret_cast<unsigned char *>(signature.data()), &length,
                       reinterpret_cast<const unsigned char *>(message.constData()),
                       message.size()) == 1;
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return ok ? signature : QByteArray();
}

bool DeviceIdentity::verify(const QByteArray &publicKey, const QByteArray &message,
                            const QByteArray &signature) {
    if (publicKey.size() != kPublicBytes || signature.size() != 64) return false;
    EVP_PKEY *key = EVP_PKEY_new_raw_public_key(
        EVP_PKEY_ED25519, nullptr,
        reinterpret_cast<const unsigned char *>(publicKey.constData()), kPublicBytes);
    if (!key) return false;

    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    const bool ok = ctx &&
        EVP_DigestVerifyInit(ctx, nullptr, nullptr, nullptr, key) == 1 &&
        EVP_DigestVerify(ctx, reinterpret_cast<const unsigned char *>(signature.constData()),
                         signature.size(),
                         reinterpret_cast<const unsigned char *>(message.constData()),
                         message.size()) == 1;
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return ok;
}

QString DeviceIdentity::pairingCodeWith(const QString &peerPublicKeyHex) const {
    const QByteArray peer = QByteArray::fromHex(peerPublicKeyHex.toLatin1());
    if (peer.size() != kPublicBytes || m_publicKey.size() != kPublicBytes) return {};

    const std::vector<uint8_t> mine(m_publicKey.begin(), m_publicKey.end());
    const std::vector<uint8_t> theirs(peer.begin(), peer.end());
    const std::vector<uint8_t> input = arsivinyo::pairing::CodeInput(mine, theirs);

    const QByteArray digest = QCryptographicHash::hash(
        QByteArray(reinterpret_cast<const char *>(input.data()), int(input.size())),
        QCryptographicHash::Sha256);
    return QString::fromStdString(arsivinyo::pairing::PairingCode(
        reinterpret_cast<const uint8_t *>(digest.constData()), digest.size()));
}
