#include "SecretStore.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QUrl>

#include "DataDir.h"
#include "subkeys.h"

using namespace arsivinyo::crypto;

namespace {

constexpr char kPassphraseSlot[] = "passphrase";
constexpr char kKeyfileSlot[] = "keyfile";
constexpr char kRecoverySlot[] = "recovery";
constexpr int kFormatVersion = 1;
constexpr std::size_t kKeyfileBytes = 32;

QString b64(const Bytes &bytes) {
    return QByteArray(reinterpret_cast<const char *>(bytes.data()),
                      static_cast<int>(bytes.size()))
        .toBase64();
}
Bytes unb64(const QString &text) {
    const QByteArray raw = QByteArray::fromBase64(text.toLatin1());
    return Bytes(raw.begin(), raw.end());
}

QJsonObject slotToJson(const Slot &slot) {
    QJsonObject object;
    object["id"] = QString::fromStdString(slot.id);
    object["kind"] = QString::fromStdString(SlotKindName(slot.kind));
    object["salt"] = b64(slot.salt);
    object["verifier"] = b64(slot.verifier);
    object["wrapped"] = b64(slot.wrapped);
    if (slot.kind == SlotKind::Passphrase) {
        QJsonObject kdf;
        kdf["id"] = "argon2id";
        kdf["version"] = static_cast<int>(slot.kdf.version);
        kdf["memoryKiB"] = static_cast<int>(slot.kdf.memoryKiB);
        kdf["iterations"] = static_cast<int>(slot.kdf.iterations);
        kdf["parallelism"] = static_cast<int>(slot.kdf.parallelism);
        object["kdf"] = kdf;
    }
    return object;
}

bool slotFromJson(const QJsonObject &object, Slot *slot) {
    slot->id = object["id"].toString().toStdString();
    if (slot->id.empty()) return false;
    if (!SlotKindFromName(object["kind"].toString().toStdString(), &slot->kind)) return false;
    slot->salt = unb64(object["salt"].toString());
    slot->verifier = unb64(object["verifier"].toString());
    slot->wrapped = unb64(object["wrapped"].toString());
    if (object.contains("kdf")) {
        const QJsonObject kdf = object["kdf"].toObject();
        if (kdf["id"].toString() != "argon2id") return false;
        slot->kdf.version = static_cast<uint32_t>(kdf["version"].toInt());
        slot->kdf.memoryKiB = static_cast<uint32_t>(kdf["memoryKiB"].toInt());
        slot->kdf.iterations = static_cast<uint32_t>(kdf["iterations"].toInt());
        slot->kdf.parallelism = static_cast<uint32_t>(kdf["parallelism"].toInt());
        // The header is attacker-supplied in the sense that it is just a file on disk. An
        // unchecked memory cost is a request to allocate a terabyte.
        std::string ignored;
        if (!slot->kdf.Validate(&ignored)) return false;
    }
    return true;
}

/** Owner-only, with the permissions set before anything is written. */
bool writePrivateFile(const QString &path, const QByteArray &data, QString *error) {
    const QString temporary = path + ".tmp";
    QFile file(temporary);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        if (error) *error = QObject::tr("Could not write to %1.").arg(path);
        return false;
    }
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    const bool written = file.write(data) == data.size();
    file.flush();
    // The rename can otherwise reach the disk before the bytes do.
    QFile::remove(path);
    file.close();
    if (!written || !QFile::rename(temporary, path)) {
        QFile::remove(temporary);
        if (error) *error = QObject::tr("Could not write to %1.").arg(path);
        return false;
    }
    return true;
}

}  // namespace

SecretStore::SecretStore(QObject *parent) : SecretStore(Argon2idParams{}, parent) {}

SecretStore::SecretStore(const Argon2idParams &params, QObject *parent)
    : QObject(parent), m_params(params) {
    // "Remember on this device" means never asking. Try it before anyone can be prompted.
    if (configured() && remembered()) {
        QFile file(keyfilePath());
        if (file.open(QIODevice::ReadOnly)) {
            const QByteArray raw = file.readAll();
            const Bytes keyfile(raw.begin(), raw.end());
            QJsonObject document;
            if (load(&document)) {
                for (const QJsonValue &value : document["slots"].toArray()) {
                    Slot slot;
                    if (!slotFromJson(value.toObject(), &slot)) continue;
                    if (slot.kind != SlotKind::Keyfile) continue;
                    SecretBytes kek;
                    std::string error;
                    if (!KeyfileKek(keyfile, slot.salt, &kek, &error)) continue;
                    if (UnwrapMasterKey(kek, slot, &m_masterKey, &error) == UnwrapResult::Ok) break;
                }
            }
        }
    }
}

SecretStore::~SecretStore() { m_masterKey.clear(); }

QString SecretStore::keyboxPath() const { return arsivinyo::dataDirPath() + "/keybox.json"; }
QString SecretStore::keyfilePath() const { return arsivinyo::dataDirPath() + "/keybox.key"; }

bool SecretStore::configured() const { return QFileInfo::exists(keyboxPath()); }

bool SecretStore::remembered() const {
    if (!QFileInfo::exists(keyfilePath())) return false;
    QJsonObject document;
    if (!load(&document)) return false;
    for (const QJsonValue &value : document["slots"].toArray()) {
        if (value.toObject()["kind"].toString() == QLatin1String("keyfile")) return true;
    }
    return false;
}

bool SecretStore::load(QJsonObject *out) const {
    QFile file(keyboxPath());
    if (!file.open(QIODevice::ReadOnly)) return false;
    QJsonParseError parseError{};
    const QJsonDocument document = QJsonDocument::fromJson(file.readAll(), &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) return false;
    *out = document.object();
    return (*out)["formatVersion"].toInt() == kFormatVersion;
}

QString SecretStore::save(const QJsonObject &document) const {
    QString error;
    if (!writePrivateFile(keyboxPath(), QJsonDocument(document).toJson(QJsonDocument::Indented),
                          &error)) {
        return error;
    }
    return {};
}

QString SecretStore::create(const QString &passphrase) {
    if (configured()) return tr("A passphrase is already set.");
    if (passphrase.size() < 8) return tr("That passphrase is too short.");

    std::string error;
    SecretBytes masterKey;
    if (!NewMasterKey(&masterKey, &error)) return QString::fromStdString(error);
    m_masterKey = std::move(masterKey);

    QJsonObject document;
    document["formatVersion"] = kFormatVersion;
    document["slots"] = QJsonArray();
    const QString saved = save(document);
    if (!saved.isEmpty()) {
        m_masterKey.clear();
        return saved;
    }

    Slot slot;
    slot.id = kPassphraseSlot;
    slot.kind = SlotKind::Passphrase;
    slot.kdf = m_params;
    if (!RandomBytes(16, &slot.salt, &error)) {
        m_masterKey.clear();
        return QString::fromStdString(error);
    }
    SecretBytes kek;
    if (!PassphraseKek(passphrase.toUtf8().toStdString(), slot.salt, slot.kdf, &kek, &error)) {
        m_masterKey.clear();
        return QString::fromStdString(error);
    }
    const QString added = addSlot(slot, kek);
    if (!added.isEmpty()) {
        m_masterKey.clear();
        QFile::remove(keyboxPath());
        return added;
    }
    m_pendingReason.clear();
    emit changed();
    return {};
}

QString SecretStore::unlock(const QString &passphrase) {
    if (unlocked()) return {};
    QJsonObject document;
    if (!load(&document)) return tr("The keybox could not be read.");

    for (const QJsonValue &value : document["slots"].toArray()) {
        Slot slot;
        if (!slotFromJson(value.toObject(), &slot)) continue;
        if (slot.kind != SlotKind::Passphrase) continue;
        SecretBytes kek;
        std::string error;
        if (!PassphraseKek(passphrase.toUtf8().toStdString(), slot.salt, slot.kdf, &kek, &error)) {
            continue;
        }
        switch (UnwrapMasterKey(kek, slot, &m_masterKey, &error)) {
            case UnwrapResult::Ok:
                m_pendingReason.clear();
                emit changed();
                return {};
            case UnwrapResult::WrongSecret:
                continue;  // another slot may take it
            case UnwrapResult::Damaged:
                return tr("The stored key is damaged.");
        }
    }
    return tr("That passphrase is not correct.");
}

void SecretStore::lock() {
    if (!unlocked()) return;
    m_masterKey.clear();
    emit changed();
}

QString SecretStore::addSlot(Slot slot, const SecretBytes &kek) {
    if (!unlocked()) return tr("Unlock first.");
    QJsonObject document;
    if (!load(&document)) return tr("The keybox could not be read.");

    std::string error;
    // The salt must be the one the key-encryption key was derived from, so it arrives with
    // the slot rather than being invented here.
    if (slot.salt.size() != 16) return tr("The keybox could not be read.");
    if (!WrapMasterKey(kek, m_masterKey, &slot, &error)) return QString::fromStdString(error);

    const QString id = QString::fromStdString(slot.id);
    QJsonArray updated;
    for (const QJsonValue &value : document["slots"].toArray()) {
        if (value.toObject()["id"].toString() != id) updated.append(value);
    }
    updated.append(slotToJson(slot));
    document["slots"] = updated;
    return save(document);
}

QString SecretStore::removeSlot(const QString &id) {
    QJsonObject document;
    if (!load(&document)) return tr("The keybox could not be read.");
    QJsonArray kept;
    for (const QJsonValue &value : document["slots"].toArray()) {
        if (value.toObject()["id"].toString() != id) kept.append(value);
    }
    // Nothing would be left that can unwrap the master key, and every encrypted file would be
    // lost. This is refused in code rather than only in the interface.
    if (kept.isEmpty()) return tr("That is the only way in. Removing it would lose everything.");
    document["slots"] = kept;
    return save(document);
}

QString SecretStore::setRemembered(bool remember) {
    if (!configured()) return tr("Set a passphrase first.");
    if (!remember) {
        const QString removed = removeSlot(kKeyfileSlot);
        if (!removed.isEmpty()) return removed;
        QFile::remove(keyfilePath());
        emit changed();
        return {};
    }
    if (!unlocked()) return tr("Unlock first.");

    std::string error;
    Bytes keyfile;
    if (!RandomBytes(kKeyfileBytes, &keyfile, &error)) return QString::fromStdString(error);

    Slot probe;
    probe.id = kKeyfileSlot;
    probe.kind = SlotKind::Keyfile;
    if (!RandomBytes(16, &probe.salt, &error)) return QString::fromStdString(error);
    SecretBytes kek;
    if (!KeyfileKek(keyfile, probe.salt, &kek, &error)) return QString::fromStdString(error);
    QString writeError;
    if (!writePrivateFile(keyfilePath(),
                          QByteArray(reinterpret_cast<const char *>(keyfile.data()),
                                     static_cast<int>(keyfile.size())),
                          &writeError)) {
        return writeError;
    }
    const QString saved = addSlot(probe, kek);
    if (!saved.isEmpty()) {
        QFile::remove(keyfilePath());
        return saved;
    }
    emit changed();
    return {};
}

QString SecretStore::changePassphrase(const QString &oldPassphrase, const QString &newPassphrase) {
    if (newPassphrase.size() < 8) return tr("That passphrase is too short.");
    const bool wasUnlocked = unlocked();
    if (!wasUnlocked) {
        const QString opened = unlock(oldPassphrase);
        if (!opened.isEmpty()) return opened;
    } else {
        // Already open, but the old passphrase still has to be right — otherwise anyone at an
        // unlocked window could lock the owner out.
        QJsonObject document;
        if (!load(&document)) return tr("The keybox could not be read.");
        bool matched = false;
        for (const QJsonValue &value : document["slots"].toArray()) {
            Slot slot;
            if (!slotFromJson(value.toObject(), &slot)) continue;
            if (slot.kind != SlotKind::Passphrase) continue;
            SecretBytes kek;
            std::string error;
            if (!PassphraseKek(oldPassphrase.toUtf8().toStdString(), slot.salt, slot.kdf, &kek,
                               &error)) {
                continue;
            }
            SecretBytes ignored;
            if (UnwrapMasterKey(kek, slot, &ignored, &error) == UnwrapResult::Ok) {
                matched = true;
                break;
            }
        }
        if (!matched) return tr("That passphrase is not correct.");
    }

    // Only the 32-byte wrap changes. Nothing that was encrypted is touched.
    std::string error;
    Slot slot;
    slot.id = kPassphraseSlot;
    slot.kind = SlotKind::Passphrase;
    slot.kdf = m_params;
    if (!RandomBytes(16, &slot.salt, &error)) return QString::fromStdString(error);
    SecretBytes kek;
    if (!PassphraseKek(newPassphrase.toUtf8().toStdString(), slot.salt, slot.kdf, &kek, &error)) {
        return QString::fromStdString(error);
    }
    return addSlot(slot, kek);
}

QString SecretStore::exportRecoveryKey(const QString &fileUrl) {
    if (!unlocked()) return tr("Unlock first.");
    const QUrl url(fileUrl);
    const QString path = url.isLocalFile() ? url.toLocalFile() : fileUrl;

    std::string error;
    Bytes keyfile;
    if (!RandomBytes(kKeyfileBytes, &keyfile, &error)) return QString::fromStdString(error);

    Slot slot;
    slot.id = kRecoverySlot;
    slot.kind = SlotKind::Recovery;
    if (!RandomBytes(16, &slot.salt, &error)) return QString::fromStdString(error);
    SecretBytes kek;
    if (!KeyfileKek(keyfile, slot.salt, &kek, &error)) return QString::fromStdString(error);
    QString writeError;
    if (!writePrivateFile(path,
                          QByteArray(reinterpret_cast<const char *>(keyfile.data()),
                                     static_cast<int>(keyfile.size())),
                          &writeError)) {
        return writeError;
    }
    const QString saved = addSlot(slot, kek);
    if (!saved.isEmpty()) {
        QFile::remove(path);
        return saved;
    }
    emit changed();
    return {};
}

QString SecretStore::unlockWithRecoveryKey(const QString &fileUrl) {
    if (unlocked()) return {};
    const QUrl url(fileUrl);
    QFile file(url.isLocalFile() ? url.toLocalFile() : fileUrl);
    if (!file.open(QIODevice::ReadOnly)) return tr("That file could not be read.");
    const QByteArray raw = file.readAll();
    const Bytes keyfile(raw.begin(), raw.end());

    QJsonObject document;
    if (!load(&document)) return tr("The keybox could not be read.");
    for (const QJsonValue &value : document["slots"].toArray()) {
        Slot slot;
        if (!slotFromJson(value.toObject(), &slot)) continue;
        if (slot.kind != SlotKind::Keyfile && slot.kind != SlotKind::Recovery) continue;
        SecretBytes kek;
        std::string error;
        if (!KeyfileKek(keyfile, slot.salt, &kek, &error)) continue;
        if (UnwrapMasterKey(kek, slot, &m_masterKey, &error) == UnwrapResult::Ok) {
            m_pendingReason.clear();
            emit changed();
            return {};
        }
    }
    return tr("That recovery key does not open this vault.");
}

void SecretStore::requestUnlock(const QString &reason) {
    if (unlocked()) return;
    m_pendingReason = reason;
    emit changed();
    emit unlockRequested(reason);
}

bool SecretStore::purposeKey(const QString &purpose, SecretBytes *out) const {
    if (!unlocked() || out == nullptr) return false;
    std::string error;
    return PurposeKey(m_masterKey, purpose.toStdString(), out, &error);
}
