// Reading a `.avsbck` written by the phone.
//
// The pairing protocol names a backup as the only supported way to move vault contents
// between devices, and the desktop could not open one. The container in VECTORS.json was
// produced by the shipping Kotlin writer, so this is the desktop reading a real file rather
// than its own idea of the format.

#include <QByteArray>
#include <QCryptographicHash>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QString>

#include <cstdio>
#include <cstring>

#include "avsbck.h"
#include "subkeys.h"

using namespace arsivinyo::crypto;

static int failures = 0;
static void check(bool ok, const QString &what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", qPrintable(what));
    if (!ok) ++failures;
}

/** A sequential reader over a buffer, which is what BackupReader wants. */
struct Cursor {
    const Bytes *bytes;
    std::size_t offset = 0;
    BackupReader::Read reader() {
        return [this](uint8_t *out, std::size_t length, std::size_t *got) {
            const std::size_t take = std::min(length, bytes->size() - offset);
            std::memcpy(out, bytes->data() + offset, take);
            offset += take;
            *got = take;
            return true;
        };
    }
};

static Bytes fromBase64(const QString &text) {
    const QByteArray raw = QByteArray::fromBase64(text.toLatin1());
    return Bytes(raw.begin(), raw.end());
}
static Bytes fromB64Std(const QString &text) { return fromBase64(text); }

static Argon2idParams kdfFrom(const QJsonObject &header) {
    const QJsonObject kdf = header["kdf"].toObject();
    Argon2idParams params;
    params.version = static_cast<uint32_t>(kdf["version"].toInt());
    params.memoryKiB = static_cast<uint32_t>(kdf["memoryKiB"].toInt());
    params.iterations = static_cast<uint32_t>(kdf["iterations"].toInt());
    params.parallelism = static_cast<uint32_t>(kdf["parallelism"].toInt());
    return params;
}

int main() {
    QFile file(QStringLiteral(ARSIVINYO_CRYPTO_VECTORS));
    if (!file.open(QIODevice::ReadOnly)) {
        std::printf("cannot open %s\n", ARSIVINYO_CRYPTO_VECTORS);
        return 2;
    }
    const QJsonObject vectors = QJsonDocument::fromJson(file.readAll()).object();
    const QJsonObject spec = vectors["container"].toObject();
    const Bytes container = fromBase64(spec["base64"].toString());
    const QString passphrase = spec["passphrase"].toString();
    check(!container.empty(), "a container was recorded by the Kotlin writer");

    // --- the preview, which needs no secret at all
    std::string headerJson;
    std::string error;
    Cursor cursor{&container};
    auto reader = BackupReader::Open(cursor.reader(), &headerJson, &error);
    check(reader != nullptr, "the header reads without a passphrase");
    if (reader == nullptr) {
        std::printf("\nFAILURES\n");
        return 1;
    }
    const QJsonObject header =
        QJsonDocument::fromJson(QByteArray::fromStdString(headerJson)).object();
    check(header["formatVersion"].toInt() == 1, "it declares format version 1");
    const QJsonArray sections = header["sections"].toArray();
    check(sections.size() == 2, "with two sections");
    check(sections.at(0).toObject()["id"].toString() == "settings" &&
              sections.at(1).toObject()["id"].toString() == "music",
          "named settings then music, which is the order their payloads follow");
    check(header["producer"].toObject()["app"].toString().size() > 0, "and names its producer");

    const QJsonObject slot = header["keySlots"].toArray().at(0).toObject();
    const Bytes salt = fromB64Std(slot["salt"].toString());
    const Bytes verifier = fromB64Std(slot["verifier"].toString());
    const Argon2idParams params = kdfFrom(header);

    // --- a wrong passphrase, before any payload is touched
    SecretBytes rejected;
    check(OpenBackupSlot("not the passphrase", salt, verifier, params, &rejected, &error) ==
              SlotOpenResult::WrongSecret,
          "a wrong passphrase is refused as wrong, not as damage");

    SecretBytes masterKey;
    check(OpenBackupSlot(passphrase.toStdString(), salt, verifier, params, &masterKey, &error) ==
              SlotOpenResult::Ok,
          "the right passphrase opens the slot");

    // --- every entry, in order
    QStringList seen;
    for (const QJsonValue &value : sections) {
        const QString id = value.toObject()["id"].toString();
        SecretBytes sectionKey;
        check(BackupSectionKey(masterKey, id.toStdString(), &sectionKey, &error),
              "a section key derives for " + id);
        const bool walked = reader->ReadSection(
            id.toStdString(), sectionKey,
            [&seen, &id](const std::string &entryHeader,
                         const BackupReader::PayloadReader &payload) {
                const QJsonObject entry =
                    QJsonDocument::fromJson(QByteArray::fromStdString(entryHeader)).object();
                QByteArray content;
                Bytes buffer(8192);
                while (true) {
                    std::size_t got = 0;
                    if (!payload(buffer.data(), buffer.size(), &got) || got == 0) break;
                    content.append(reinterpret_cast<const char *>(buffer.data()),
                                   static_cast<int>(got));
                }
                seen.append(QString("%1/%2/%3/%4/%5")
                                .arg(id, entry["name"].toString(), entry["kind"].toString())
                                .arg(entry["size"].toInteger())
                                .arg(QString(QCryptographicHash::hash(
                                                 content, QCryptographicHash::Sha256)
                                                 .toHex())));
                return true;
            },
            &error);
        check(walked, "the " + id + " section reads through");
    }

    QStringList want;
    for (const QJsonValue &value : spec["entries"].toArray()) {
        const QJsonObject entry = value.toObject();
        want.append(QString("%1/%2/%3/%4/%5")
                        .arg(entry["section"].toString(), entry["name"].toString(),
                             entry["kind"].toString())
                        .arg(entry["size"].toInteger())
                        .arg(entry["sha256"].toString()));
    }
    check(seen == want, QString("every entry came back byte for byte (%1 of %2)")
                            .arg(seen.size())
                            .arg(want.size()));
    if (seen != want) {
        for (const QString &line : seen) std::printf("        got  %s\n", qPrintable(line));
        for (const QString &line : want) std::printf("        want %s\n", qPrintable(line));
    }

    // --- a partial restore steps over what it does not want
    {
        Cursor skipCursor{&container};
        std::string skipHeader;
        auto skipper = BackupReader::Open(skipCursor.reader(), &skipHeader, &error);
        check(skipper != nullptr && skipper->SkipSection(&error),
              "the settings section can be stepped over without a key");
        SecretBytes musicKey;
        BackupSectionKey(masterKey, "music", &musicKey, &error);
        int count = 0;
        const bool walked = skipper->ReadSection(
            "music", musicKey,
            [&count](const std::string &, const BackupReader::PayloadReader &) {
                ++count;
                return true;
            },
            &error);
        check(walked && count == 2, "and music still reads, with its two entries");
    }

    // --- a section decrypted with the wrong section's key must fail
    {
        Cursor wrongCursor{&container};
        std::string wrongHeader;
        auto wrong = BackupReader::Open(wrongCursor.reader(), &wrongHeader, &error);
        SecretBytes musicKey;
        BackupSectionKey(masterKey, "music", &musicKey, &error);
        const bool walked = wrong->ReadSection(
            "settings", musicKey,
            [](const std::string &, const BackupReader::PayloadReader &) { return true; },
            &error);
        check(!walked, "the wrong section key is rejected");
    }

    // --- a tampered byte in a payload
    {
        Bytes bent = container;
        bent[bent.size() - 40] ^= 0x01;
        Cursor bentCursor{&bent};
        std::string bentHeader;
        auto bentReader = BackupReader::Open(bentCursor.reader(), &bentHeader, &error);
        SecretBytes settingsKey;
        BackupSectionKey(masterKey, "settings", &settingsKey, &error);
        bool anyFailed = !bentReader->ReadSection(
            "settings", settingsKey,
            [](const std::string &, const BackupReader::PayloadReader &) { return true; },
            &error);
        if (!anyFailed) {
            SecretBytes musicKey;
            BackupSectionKey(masterKey, "music", &musicKey, &error);
            anyFailed = !bentReader->ReadSection(
                "music", musicKey,
                [](const std::string &, const BackupReader::PayloadReader &) { return true; },
                &error);
        }
        check(anyFailed, "a flipped bit anywhere in the file is caught");
    }

    // --- not a backup at all
    {
        Bytes junk(200, 0x41);
        Cursor junkCursor{&junk};
        std::string junkHeader;
        check(BackupReader::Open(junkCursor.reader(), &junkHeader, &error) == nullptr,
              "a file that is not a backup is refused");
    }

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
