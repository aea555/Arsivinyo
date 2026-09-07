#include "Vault.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMimeDatabase>
#include <QUrl>

#include "DataDir.h"
#include "aead_stream.h"
#include "subkeys.h"

using namespace arsivinyo::crypto;

namespace {

constexpr char kIndexAad[] = "vault/index/v1";
constexpr int kFormatVersion = 1;
constexpr qint64 kCopyChunk = 1 << 20;

QString newId() {
    Bytes raw;
    std::string error;
    if (!RandomBytes(16, &raw, &error)) return {};
    return QByteArray(reinterpret_cast<const char *>(raw.data()), 16).toHex();
}

}  // namespace

Vault::Vault(QObject *parent) : QAbstractListModel(parent) {}

QString Vault::root() const { return arsivinyo::dataDirPath() + "/vault"; }
QString Vault::objectPath(const QString &id) const { return root() + "/objects/" + id + ".enc"; }

void Vault::setSecrets(SecretStore *secrets) {
    if (m_secrets == secrets) return;
    m_secrets = secrets;
    if (m_secrets != nullptr) {
        connect(m_secrets, &SecretStore::changed, this, [this]() {
            if (unlocked()) {
                load();
            } else {
                reset();
            }
            emit changed();
        });
    }
    if (unlocked()) load();
    emit changed();
}

bool Vault::contentKey(SecretBytes *out) const {
    if (m_secrets == nullptr) return false;
    return m_secrets->purposeKey(kPurposeVault, out);
}

void Vault::reset() {
    beginResetModel();
    m_entries.clear();
    endResetModel();
}

bool Vault::load() {
    if (!unlocked()) return false;
    const QString path = root() + "/index.enc";

    beginResetModel();
    m_entries.clear();
    m_unreadable = false;

    if (QFileInfo::exists(path)) {
        SecretBytes key;
        QFile file(path);
        QJsonObject document;
        bool opened = false;
        if (m_secrets->purposeKey(kPurposeVaultIndex, &key) &&
            file.open(QIODevice::ReadOnly)) {
            const QByteArray ciphertext = file.readAll();
            Bytes plaintext;
            std::string error;
            Bytes padded;
            if (DecryptBuffer(key.data(), key.size(), kIndexAad,
                              reinterpret_cast<const uint8_t *>(ciphertext.constData()),
                              static_cast<std::size_t>(ciphertext.size()), &padded, &error) &&
                UnpadFromConcealment(padded, &plaintext, &error)) {
                document = QJsonDocument::fromJson(
                               QByteArray(reinterpret_cast<const char *>(plaintext.data()),
                                          static_cast<int>(plaintext.size())))
                               .object();
                opened = document["formatVersion"].toInt() == kFormatVersion;
            }
        }
        if (!opened) {
            // The objects are still on disk. An empty list here would be written back by the
            // next change and orphan every one of them, which is exactly the bug being fixed
            // on the phone. Refuse instead.
            m_unreadable = true;
            endResetModel();
            return false;
        }
        for (const QJsonValue &value : document["items"].toArray()) {
            const QJsonObject object = value.toObject();
            Entry entry;
            entry.id = object["id"].toString();
            entry.title = object["title"].toString();
            entry.mimeType = object["mimeType"].toString();
            entry.sizeBytes = static_cast<qint64>(object["sizeBytes"].toDouble());
            entry.addedAt = static_cast<qint64>(object["addedAt"].toDouble());
            entry.extension = object["extension"].toString();
            if (!entry.id.isEmpty()) m_entries.append(entry);
        }
    }
    endResetModel();
    return true;
}

QString Vault::store() {
    if (!unlocked()) return tr("Unlock first.");
    if (m_unreadable) return tr("The vault listing could not be read, so it will not be changed.");

    QJsonArray items;
    for (const Entry &entry : m_entries) {
        QJsonObject object;
        object["id"] = entry.id;
        object["title"] = entry.title;
        object["mimeType"] = entry.mimeType;
        object["sizeBytes"] = static_cast<double>(entry.sizeBytes);
        object["addedAt"] = static_cast<double>(entry.addedAt);
        object["extension"] = entry.extension;
        items.append(object);
    }
    QJsonObject document;
    document["formatVersion"] = kFormatVersion;
    document["items"] = items;
    const QByteArray plaintext = QJsonDocument(document).toJson(QJsonDocument::Compact);

    SecretBytes key;
    if (!m_secrets->purposeKey(kPurposeVaultIndex, &key)) return tr("Could not reach the key.");
    // Padded before it is sealed, so the file's size does not say roughly how many items
    // are in the vault — which is most of what the listing itself would have told anyone.
    const Bytes padded = PadForConcealment(
        reinterpret_cast<const uint8_t *>(plaintext.constData()),
        static_cast<std::size_t>(plaintext.size()));

    Bytes ciphertext;
    std::string error;
    if (!EncryptBuffer(key.data(), key.size(), kIndexAad, padded.data(), padded.size(),
                       &ciphertext, &error)) {
        return tr("Could not write the vault listing.");
    }

    QDir().mkpath(root());
    const QString path = root() + "/index.enc";
    const QString temporary = path + ".tmp";
    QFile file(temporary);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        return tr("Could not write the vault listing.");
    }
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    const qint64 written = file.write(reinterpret_cast<const char *>(ciphertext.data()),
                                      static_cast<qint64>(ciphertext.size()));
    file.flush();
    file.close();
    if (written != static_cast<qint64>(ciphertext.size())) {
        QFile::remove(temporary);
        return tr("Could not write the vault listing.");
    }
    QFile::remove(path);
    if (!QFile::rename(temporary, path)) {
        QFile::remove(temporary);
        return tr("Could not write the vault listing.");
    }
    return {};
}

int Vault::rowCount(const QModelIndex &parent) const {
    return parent.isValid() ? 0 : m_entries.size();
}

QVariant Vault::data(const QModelIndex &index, int role) const {
    if (index.row() < 0 || index.row() >= m_entries.size()) return {};
    const Entry &entry = m_entries.at(index.row());
    switch (role) {
        case IdRole: return entry.id;
        case TitleRole: return entry.title;
        case MimeTypeRole: return entry.mimeType;
        case SizeBytesRole: return entry.sizeBytes;
        case AddedAtRole: return entry.addedAt;
        default: return {};
    }
}

QHash<int, QByteArray> Vault::roleNames() const {
    return {
        {IdRole, "itemId"},
        {TitleRole, "title"},
        {MimeTypeRole, "mimeType"},
        {SizeBytesRole, "sizeBytes"},
        {AddedAtRole, "addedAt"},
    };
}

QVariantMap Vault::get(int row) const {
    if (row < 0 || row >= m_entries.size()) return {};
    const Entry &entry = m_entries.at(row);
    return {{"itemId", entry.id},
            {"title", entry.title},
            {"mimeType", entry.mimeType},
            {"sizeBytes", entry.sizeBytes},
            {"addedAt", entry.addedAt}};
}

QString Vault::importFile(const QString &fileUrl, bool removeOriginal) {
    if (!unlocked()) {
        if (m_secrets != nullptr) m_secrets->requestUnlock(tr("to add to the vault"));
        return tr("Unlock first.");
    }
    if (m_unreadable) return tr("The vault listing could not be read, so it will not be changed.");

    const QUrl url(fileUrl);
    const QString source = url.isLocalFile() ? url.toLocalFile() : fileUrl;
    const QFileInfo info(source);
    if (!info.isFile()) return tr("That file could not be read.");

    SecretBytes key;
    if (!contentKey(&key)) return tr("Could not reach the key.");

    const QString id = newId();
    if (id.isEmpty()) return tr("Could not generate an id.");

    QDir().mkpath(root() + "/objects");
    const QString target = objectPath(id);
    const QString partial = target + ".partial";

    QFile input(source);
    if (!input.open(QIODevice::ReadOnly)) return tr("That file could not be read.");
    QFile output(partial);
    if (!output.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        return tr("Could not write into the vault.");
    }
    output.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);

    bool writeFailed = false;
    auto sink = [&output, &writeFailed](const uint8_t *data, std::size_t length) {
        const qint64 written =
            output.write(reinterpret_cast<const char *>(data), static_cast<qint64>(length));
        if (written != static_cast<qint64>(length)) writeFailed = true;
        return !writeFailed;
    };

    std::string error;
    // The id is the associated data, so an object file swapped for another fails the tag.
    auto encryptor =
        StreamEncryptor::Create(key.data(), key.size(), id.toStdString(), sink, &error);
    if (encryptor == nullptr) {
        output.close();
        QFile::remove(partial);
        return tr("Could not write into the vault.");
    }

    QByteArray buffer(kCopyChunk, Qt::Uninitialized);
    qint64 total = 0;
    while (true) {
        const qint64 read = input.read(buffer.data(), kCopyChunk);
        if (read < 0) {
            writeFailed = true;
            break;
        }
        if (read == 0) break;
        total += read;
        if (!encryptor->Write(reinterpret_cast<const uint8_t *>(buffer.constData()),
                              static_cast<std::size_t>(read), &error)) {
            writeFailed = true;
            break;
        }
    }
    if (!writeFailed && !encryptor->Finish(&error)) writeFailed = true;
    input.close();
    output.flush();
    output.close();

    if (writeFailed) {
        QFile::remove(partial);
        return tr("Could not write into the vault.");
    }
    // The object is complete before the index names it, so a crash leaves an orphan rather
    // than an entry pointing at nothing.
    QFile::remove(target);
    if (!QFile::rename(partial, target)) {
        QFile::remove(partial);
        return tr("Could not write into the vault.");
    }

    Entry entry;
    entry.id = id;
    entry.title = info.completeBaseName();
    entry.mimeType = QMimeDatabase().mimeTypeForFile(info).name();
    entry.sizeBytes = total;
    entry.addedAt = QDateTime::currentSecsSinceEpoch();
    entry.extension = info.suffix();

    beginInsertRows({}, m_entries.size(), m_entries.size());
    m_entries.append(entry);
    endInsertRows();

    const QString stored = store();
    if (!stored.isEmpty()) {
        beginRemoveRows({}, m_entries.size() - 1, m_entries.size() - 1);
        m_entries.removeLast();
        endRemoveRows();
        QFile::remove(target);
        return stored;
    }

    if (removeOriginal) QFile::remove(source);
    emit changed();
    return {};
}

QString Vault::remove(const QString &id) {
    if (!unlocked()) return tr("Unlock first.");
    if (m_unreadable) return tr("The vault listing could not be read, so it will not be changed.");

    int row = -1;
    for (int i = 0; i < m_entries.size(); ++i) {
        if (m_entries.at(i).id == id) {
            row = i;
            break;
        }
    }
    if (row < 0) return tr("That item is not in the vault.");

    const Entry removed = m_entries.at(row);
    beginRemoveRows({}, row, row);
    m_entries.removeAt(row);
    endRemoveRows();

    // The index loses it first. The other order would leave an entry pointing at a file that
    // is already gone, which reads as corruption rather than as a completed delete.
    const QString stored = store();
    if (!stored.isEmpty()) {
        beginInsertRows({}, row, row);
        m_entries.insert(row, removed);
        endInsertRows();
        return stored;
    }
    QFile::remove(objectPath(id));
    QFile::remove(root() + "/thumbs/" + id + ".enc");
    emit changed();
    return {};
}

QString Vault::exportTo(const QString &id, const QString &destinationUrl) {
    if (!unlocked()) return tr("Unlock first.");
    SecretBytes key;
    if (!contentKey(&key)) return tr("Could not reach the key.");

    QFile input(objectPath(id));
    if (!input.open(QIODevice::ReadOnly)) return tr("That item is not in the vault.");

    const QUrl url(destinationUrl);
    QFile output(url.isLocalFile() ? url.toLocalFile() : destinationUrl);
    if (!output.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        return tr("Could not write there.");
    }

    const qint64 size = input.size();
    auto read = [&input](uint64_t offset, uint8_t *out, std::size_t length) {
        if (!input.seek(static_cast<qint64>(offset))) return false;
        return input.read(reinterpret_cast<char *>(out), static_cast<qint64>(length)) ==
               static_cast<qint64>(length);
    };
    std::string error;
    auto reader = SeekableStreamReader::Open(key.data(), key.size(), id.toStdString(), read,
                                             static_cast<uint64_t>(size), &error);
    if (reader == nullptr) return tr("That item could not be read.");

    Bytes buffer(kCopyChunk);
    uint64_t offset = 0;
    while (offset < reader->PlaintextSize()) {
        std::size_t got = 0;
        if (!reader->ReadAt(offset, buffer.data(), buffer.size(), &got, &error) || got == 0) {
            output.close();
            return tr("That item could not be read.");
        }
        output.write(reinterpret_cast<const char *>(buffer.data()), static_cast<qint64>(got));
        offset += got;
    }
    output.close();
    return {};
}
