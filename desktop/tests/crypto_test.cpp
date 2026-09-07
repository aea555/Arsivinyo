// The portable security core, against the vectors Kotlin and C++ are both held to.
//
// The phone gets these primitives from Tink and BouncyCastle; the desktop reimplements them
// over OpenSSL. Two implementations of one format drift unless something forces them not to,
// and a drift here does not mean a failed handshake — it means a vault that will not open.
//
// Kotlin can only check that it reproduces or decrypts each entry, because Tink will not let
// a caller choose a stream's header. C++ can inject the recorded header, so it checks the
// stronger property: byte-for-byte equality with what Tink produced.

#include <QByteArray>
#include <QCryptographicHash>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QString>

#include <cstdio>
#include <cstring>

#include "aead_stream.h"
#include "argon2.h"
#include "hkdf.h"
#include "keybox.h"
#include "secret.h"
#include "subkeys.h"

using namespace arsivinyo::crypto;

static int failures = 0;
static void check(bool ok, const QString &what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", qPrintable(what));
    if (!ok) ++failures;
}

static Bytes unhex(const QString &s) {
    const QByteArray raw = QByteArray::fromHex(s.toLatin1());
    return Bytes(raw.begin(), raw.end());
}
static QString hex(const uint8_t *data, std::size_t length) {
    return QByteArray(reinterpret_cast<const char *>(data), static_cast<int>(length)).toHex();
}
static QString hex(const Bytes &b) { return hex(b.data(), b.size()); }
static QString hex(const SecretBytes &b) { return hex(b.data(), b.size()); }

/** java.util.Random, so a megabyte of plaintext need not live in the vectors file. */
struct JavaRandom {
    uint64_t seed;
    explicit JavaRandom(uint64_t s) : seed((s ^ 0x5DEECE66DULL) & ((1ULL << 48) - 1)) {}
    int32_t next(int bits) {
        seed = (seed * 0x5DEECE66DULL + 0xBULL) & ((1ULL << 48) - 1);
        return static_cast<int32_t>(seed >> (48 - bits));
    }
    void nextBytes(uint8_t *b, std::size_t length) {
        for (std::size_t i = 0; i < length;) {
            int32_t value = next(32);
            for (int n = static_cast<int>(std::min<std::size_t>(length - i, 4)); n-- > 0;
                 value >>= 8) {
                b[i++] = static_cast<uint8_t>(value);
            }
        }
    }
};

static void testArgon2(const QJsonArray &cases) {
    for (const QJsonValue &value : cases) {
        const QJsonObject c = value.toObject();
        Argon2idParams params;
        params.memoryKiB = static_cast<uint32_t>(c["memoryKiB"].toInt());
        params.iterations = static_cast<uint32_t>(c["iterations"].toInt());
        params.parallelism = static_cast<uint32_t>(c["parallelism"].toInt());
        params.version = static_cast<uint32_t>(c["version"].toInt());
        const Bytes salt = unhex(c["salt"].toString());
        // Taken as UTF-8 bytes recorded by Kotlin, not as a re-encoded string, so the test
        // cannot paper over an encoding difference by making the same mistake twice.
        const Bytes password = unhex(c["passwordUtf8"].toString());
        SecretBytes out;
        std::string error;
        const bool ok = DeriveArgon2id(password.data(), password.size(), salt.data(), salt.size(),
                                       params, static_cast<std::size_t>(c["outLength"].toInt()),
                                       &out, &error);
        check(ok && hex(out) == c["out"].toString(), "argon2id: " + c["why"].toString());
    }
}

static void testHkdf(const QJsonArray &cases) {
    for (const QJsonValue &value : cases) {
        const QJsonObject c = value.toObject();
        const Bytes ikm = unhex(c["ikm"].toString());
        const bool hasSalt = !c["salt"].isNull();
        const Bytes salt = hasSalt ? unhex(c["salt"].toString()) : Bytes{};
        const QByteArray info = c["info"].toString().toUtf8();
        SecretBytes out;
        std::string error;
        const bool ok = HkdfSha256(ikm.data(), ikm.size(), hasSalt ? salt.data() : nullptr,
                                   hasSalt ? salt.size() : 0,
                                   reinterpret_cast<const uint8_t *>(info.constData()),
                                   static_cast<std::size_t>(info.size()),
                                   static_cast<std::size_t>(c["outLength"].toInt()), &out, &error);
        check(ok && hex(out) == c["out"].toString(), "hkdf: " + c["why"].toString());
    }
}

static void testSubkeys(const QJsonObject &spec) {
    const Bytes raw = unhex(spec["masterKey"].toString());
    SecretBytes masterKey;
    masterKey.assign(raw.data(), raw.size());
    std::string error;

    SecretBytes verifier;
    check(BackupVerifier(masterKey, &verifier, &error) &&
              hex(verifier) == spec["verifier"].toString(),
          "avsbck verifier label");

    const QJsonObject sections = spec["sections"].toObject();
    for (auto it = sections.begin(); it != sections.end(); ++it) {
        SecretBytes sectionKey;
        check(BackupSectionKey(masterKey, it.key().toStdString(), &sectionKey, &error) &&
                  hex(sectionKey) == it.value().toString(),
              "avsbck section key: " + it.key());
    }
}

static void testNonces(const QJsonObject &spec) {
    const QJsonArray cases = spec["cases"].toArray();
    bool allOk = true;
    for (const QJsonValue &value : cases) {
        const QJsonObject c = value.toObject();
        const Bytes prefix = unhex(c["noncePrefix"].toString());
        uint8_t nonce[12];
        SegmentNonce(prefix.data(), static_cast<uint64_t>(c["segmentIndex"].toDouble()),
                     c["last"].toBool(), nonce);
        if (hex(nonce, 12) != c["nonce"].toString()) allOk = false;
    }
    check(allOk, QString("segment nonce: %1 cases, big-endian index and the last-segment flag")
                     .arg(cases.size()));
}

static void testAeadStream(const QJsonArray &cases) {
    for (const QJsonValue &value : cases) {
        const QJsonObject c = value.toObject();
        const Bytes key = unhex(c["key"].toString());
        const Bytes headerSalt = unhex(c["headerSalt"].toString());
        const Bytes noncePrefix = unhex(c["noncePrefix"].toString());
        const std::string aad = c["associatedData"].toString().toStdString();
        const QJsonObject plainSpec = c["plaintext"].toObject();
        const std::size_t length = static_cast<std::size_t>(plainSpec["length"].toInt());

        Bytes plaintext(length);
        JavaRandom(static_cast<uint64_t>(plainSpec["seed"].toDouble()))
            .nextBytes(plaintext.data(), length);

        Bytes ciphertext;
        std::string error;
        auto sink = [&ciphertext](const uint8_t *data, std::size_t n) {
            ciphertext.insert(ciphertext.end(), data, data + n);
            return true;
        };
        auto encryptor = StreamEncryptor::CreateWithHeader(key.data(), key.size(), aad,
                                                           headerSalt.data(), noncePrefix.data(),
                                                           sink, &error);
        const bool encrypted = encryptor != nullptr &&
                               encryptor->Write(plaintext.data(), length, &error) &&
                               encryptor->Finish(&error);

        const QByteArray digest = QCryptographicHash::hash(
            QByteArray(reinterpret_cast<const char *>(ciphertext.data()),
                       static_cast<int>(ciphertext.size())),
            QCryptographicHash::Sha256);

        const bool matches = encrypted &&
                             ciphertext.size() ==
                                 static_cast<std::size_t>(c["ciphertextLength"].toInt()) &&
                             QString(digest.toHex()) == c["ciphertextSha256"].toString();
        check(matches, QString("aead %1 bytes: %2").arg(length).arg(c["why"].toString()));

        Bytes back;
        const bool roundTrip =
            DecryptBuffer(key.data(), key.size(), aad, ciphertext.data(), ciphertext.size(),
                          &back, &error) &&
            back == plaintext;
        check(roundTrip, QString("aead %1 bytes: decrypts back").arg(length));

        // Forward-only, the way a backup section is read: the length is not known ahead of
        // time, so each segment is decrypted only once the next byte says whether it is last.
        std::size_t consumed = 0;
        auto source = [&ciphertext, &consumed](uint8_t *out, std::size_t want, std::size_t *got) {
            const std::size_t take = std::min(want, ciphertext.size() - consumed);
            std::memcpy(out, ciphertext.data() + consumed, take);
            consumed += take;
            *got = take;
            return true;
        };
        auto decryptor =
            StreamDecryptor::Create(key.data(), key.size(), aad, source, &error);
        Bytes streamed(length);
        std::size_t produced = 0;
        const bool streamedOk = decryptor != nullptr &&
                                decryptor->Read(streamed.data(), length, &produced, &error) &&
                                produced == length && streamed == plaintext;
        check(streamedOk, QString("aead %1 bytes: decrypts forward-only").arg(length));
    }
}

static void testVaultIndex(const QJsonObject &spec) {
    const Bytes raw = unhex(spec["dek"].toString());
    SecretBytes dek;
    dek.assign(raw.data(), raw.size());
    std::string error;

    SecretBytes indexKey;
    check(PurposeKey(dek, kPurposeVaultIndex, &indexKey, &error) &&
              hex(indexKey) == spec["indexKey"].toString(),
          "vault index: the key label matches the phone's");

    bool paddingOk = true;
    for (const QJsonValue &value : spec["padding"].toArray()) {
        const QJsonObject c = value.toObject();
        const std::size_t length = static_cast<std::size_t>(c["length"].toInt());
        Bytes content(length);
        JavaRandom(static_cast<uint64_t>(c["length"].toInt())).nextBytes(content.data(), length);
        const Bytes padded = PadForConcealment(content.data(), length);
        const QByteArray digest = QCryptographicHash::hash(
            QByteArray(reinterpret_cast<const char *>(padded.data()),
                       static_cast<int>(padded.size())),
            QCryptographicHash::Sha256);
        if (padded.size() != static_cast<std::size_t>(c["paddedLength"].toInt()) ||
            QString(digest.toHex()) != c["paddedSha256"].toString()) {
            paddingOk = false;
        }
        Bytes back;
        if (!UnpadFromConcealment(padded, &back, &error) || back != content) paddingOk = false;
    }
    check(paddingOk, "vault index: padding matches, and unpads back");

    const QByteArray sealedRaw = QByteArray::fromBase64(spec["sealed"].toString().toLatin1());
    Bytes padded;
    Bytes listing;
    const bool opened =
        DecryptBuffer(indexKey.data(), indexKey.size(),
                      spec["associatedData"].toString().toStdString(),
                      reinterpret_cast<const uint8_t *>(sealedRaw.constData()),
                      static_cast<std::size_t>(sealedRaw.size()), &padded, &error) &&
        UnpadFromConcealment(padded, &listing, &error);
    const QString text =
        QString::fromUtf8(reinterpret_cast<const char *>(listing.data()),
                          static_cast<int>(listing.size()));
    check(opened && text == spec["listing"].toString(),
          "vault index: a listing sealed by the phone opens here");
}

/** Properties the vectors cannot express, exercised locally. */
static void testSeeking() {
    uint8_t key[32];
    for (int i = 0; i < 32; ++i) key[i] = static_cast<uint8_t>(i * 7 + 1);
    const std::size_t total = 3 * 1024 * 1024 + 12345;
    Bytes plaintext(total);
    for (std::size_t i = 0; i < total; ++i) {
        plaintext[i] = static_cast<uint8_t>((i * 31 + (i >> 13)) & 0xff);
    }
    std::string error;
    Bytes ciphertext;
    if (!EncryptBuffer(key, 32, "vault", plaintext.data(), total, &ciphertext, &error)) {
        check(false, "seek fixture encrypts");
        return;
    }
    auto read = [&ciphertext](uint64_t offset, uint8_t *out, std::size_t length) {
        if (offset + length > ciphertext.size()) return false;
        std::memcpy(out, ciphertext.data() + offset, length);
        return true;
    };
    auto reader = SeekableStreamReader::Open(key, 32, "vault", read, ciphertext.size(), &error);
    if (reader == nullptr) {
        check(false, "seek fixture opens");
        return;
    }
    const uint64_t cap0 = PlaintextSegmentBytes(0);
    const uint64_t capN = PlaintextSegmentBytes(1);
    bool allOk = reader->PlaintextSize() == total;
    // The boundaries are where an off-by-one hides: segment 0 is short by the header, so the
    // first one is not where a uniform division would put it.
    for (uint64_t offset : {uint64_t{0}, cap0 - 1, cap0, cap0 + 1, cap0 + capN - 1, cap0 + capN,
                            cap0 + capN + 1, total - 1}) {
        for (std::size_t length : {std::size_t{1}, std::size_t{4096}, std::size_t{1 << 21}}) {
            Bytes got(length);
            std::size_t produced = 0;
            const std::size_t want =
                static_cast<std::size_t>(std::min<uint64_t>(length, total - offset));
            if (!reader->ReadAt(offset, got.data(), length, &produced, &error) ||
                produced != want ||
                std::memcmp(got.data(), plaintext.data() + offset, produced) != 0) {
                allOk = false;
            }
        }
    }
    check(allOk, "seeking across segment boundaries returns the right bytes");

    Bytes bent = ciphertext;
    bent[bent.size() - 1] ^= 1;
    Bytes ignored;
    check(!DecryptBuffer(key, 32, "vault", bent.data(), bent.size(), &ignored, &error),
          "a flipped tag bit is rejected");
    check(!DecryptBuffer(key, 32, "music", ciphertext.data(), ciphertext.size(), &ignored, &error),
          "the wrong associated data is rejected");
}

static void testKeybox() {
    std::string error;
    const auto fast = Argon2idParams::Fast();
    SecretBytes masterKey;
    check(NewMasterKey(&masterKey, &error) && masterKey.size() == 32,
          "keybox: a master key is 32 random bytes");

    Slot passphrase;
    passphrase.id = "passphrase";
    passphrase.kind = SlotKind::Passphrase;
    passphrase.kdf = fast;
    RandomBytes(16, &passphrase.salt, &error);
    SecretBytes kek;
    PassphraseKek("a correct horse battery staple", passphrase.salt, fast, &kek, &error);
    check(WrapMasterKey(kek, masterKey, &passphrase, &error),
          "keybox: the master key wraps under a passphrase");

    SecretBytes recovered;
    check(UnwrapMasterKey(kek, passphrase, &recovered, &error) == UnwrapResult::Ok &&
              hex(recovered) == hex(masterKey),
          "keybox: the right passphrase recovers it");

    SecretBytes wrongKek;
    PassphraseKek("a correct horse battery stapler", passphrase.salt, fast, &wrongKek, &error);
    check(UnwrapMasterKey(wrongKek, passphrase, &recovered, &error) == UnwrapResult::WrongSecret,
          "keybox: a wrong passphrase reads as wrong, not as damage");

    // The property the whole slot design exists for.
    const Bytes wrappedBefore = passphrase.wrapped;
    Bytes keyfile;
    RandomBytes(32, &keyfile, &error);
    Slot local;
    local.id = "keyfile";
    local.kind = SlotKind::Keyfile;
    RandomBytes(16, &local.salt, &error);
    SecretBytes keyfileKek;
    KeyfileKek(keyfile, local.salt, &keyfileKek, &error);
    WrapMasterKey(keyfileKek, masterKey, &local, &error);
    check(passphrase.wrapped == wrappedBefore,
          "keybox: adding a slot leaves the other slot byte-identical");
    check(UnwrapMasterKey(keyfileKek, local, &recovered, &error) == UnwrapResult::Ok &&
              hex(recovered) == hex(masterKey),
          "keybox: either slot recovers the same master key");

    Slot forged = local;
    forged.wrapped = passphrase.wrapped;
    check(UnwrapMasterKey(keyfileKek, forged, &recovered, &error) == UnwrapResult::Damaged,
          "keybox: one slot's wrapped key pasted into another is rejected");

    Slot bent = passphrase;
    bent.wrapped[20] ^= 1;
    check(UnwrapMasterKey(kek, bent, &recovered, &error) == UnwrapResult::Damaged,
          "keybox: a flipped bit in the wrapped key is damage");

    SecretBytes cookies;
    SecretBytes vault;
    PurposeKey(masterKey, kPurposeCookies, &cookies, &error);
    PurposeKey(masterKey, kPurposeVault, &vault, &error);
    check(hex(cookies) != hex(vault), "keybox: a leaked cookie key does not open the vault");
}

static void testParameterGuards() {
    std::string error;
    Argon2idParams params;
    check(params.Validate(&error), "the shipped kdf profile validates");
    // A backup header is attacker-supplied; an unchecked memory cost asks for a terabyte.
    for (auto [field, value] : {std::pair{&params.memoryKiB, 7000u},
                                std::pair{&params.memoryKiB, 2000000u},
                                std::pair{&params.iterations, 0u},
                                std::pair{&params.iterations, 17u},
                                std::pair{&params.parallelism, 0u},
                                std::pair{&params.parallelism, 17u}}) {
        Argon2idParams bad;
        uint32_t *target = reinterpret_cast<uint32_t *>(
            reinterpret_cast<char *>(&bad) +
            (reinterpret_cast<char *>(field) - reinterpret_cast<char *>(&params)));
        *target = value;
        if (bad.Validate(&error)) {
            check(false, QString("a hostile kdf parameter (%1) was accepted").arg(value));
            return;
        }
    }
    check(true, "hostile kdf parameters are rejected");
}

int main() {
    QFile file(QStringLiteral(ARSIVINYO_CRYPTO_VECTORS));
    if (!file.open(QIODevice::ReadOnly)) {
        std::printf("cannot open %s\n", ARSIVINYO_CRYPTO_VECTORS);
        return 2;
    }
    const QJsonObject vectors = QJsonDocument::fromJson(file.readAll()).object();

    std::printf("argon2id\n");
    testArgon2(vectors["argon2id"].toArray());
    std::printf("hkdf-sha256\n");
    testHkdf(vectors["hkdf_sha256"].toArray());
    std::printf("avsbck key hierarchy\n");
    testSubkeys(vectors["avsbck_subkeys"].toObject());
    std::printf("segment nonces\n");
    testNonces(vectors["segment_nonce"].toObject());
    std::printf("streaming aead, against Tink's own output\n");
    testAeadStream(vectors["aead_stream"].toArray());
    std::printf("vault index\n");
    testVaultIndex(vectors["vault_index"].toObject());
    std::printf("seeking\n");
    testSeeking();
    std::printf("keybox\n");
    testKeybox();
    std::printf("parameter guards\n");
    testParameterGuards();

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
