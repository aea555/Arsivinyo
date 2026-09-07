#include "VaultDevice.h"

#include <QFile>

using namespace arsivinyo::crypto;

VaultDevice::VaultDevice(QObject *parent) : QIODevice(parent) {}
VaultDevice::~VaultDevice() = default;

VaultDevice *VaultDevice::open(const QString &objectPath, const QString &associatedData,
                               const SecretBytes &key, QObject *parent) {
    auto file = std::make_unique<QFile>(objectPath);
    if (!file->open(QIODevice::ReadOnly)) return nullptr;
    const qint64 ciphertextSize = file->size();

    QFile *raw = file.get();
    auto read = [raw](uint64_t offset, uint8_t *out, std::size_t length) {
        if (!raw->seek(static_cast<qint64>(offset))) return false;
        return raw->read(reinterpret_cast<char *>(out), static_cast<qint64>(length)) ==
               static_cast<qint64>(length);
    };

    std::string error;
    auto reader = SeekableStreamReader::Open(key.data(), key.size(), associatedData.toStdString(),
                                             read, static_cast<uint64_t>(ciphertextSize), &error);
    if (reader == nullptr) return nullptr;

    auto *device = new VaultDevice(parent);
    device->m_file = std::move(file);
    device->m_reader = std::move(reader);
    device->QIODevice::open(QIODevice::ReadOnly);
    return device;
}

qint64 VaultDevice::size() const {
    return m_reader == nullptr ? 0 : static_cast<qint64>(m_reader->PlaintextSize());
}

bool VaultDevice::atEnd() const { return pos() >= size(); }

qint64 VaultDevice::readData(char *data, qint64 maxSize) {
    if (m_reader == nullptr || maxSize <= 0) return 0;
    const qint64 remaining = size() - pos();
    if (remaining <= 0) return 0;

    const qint64 wanted = std::min(maxSize, remaining);
    std::size_t got = 0;
    std::string error;
    if (!m_reader->ReadAt(static_cast<uint64_t>(pos()), reinterpret_cast<uint8_t *>(data),
                          static_cast<std::size_t>(wanted), &got, &error)) {
        return -1;
    }
    return static_cast<qint64>(got);
}
