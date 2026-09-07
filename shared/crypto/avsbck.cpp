#include "avsbck.h"

#include <algorithm>
#include <cstring>

#include <openssl/evp.h>

#include "aead_stream.h"
#include "hkdf.h"
#include "subkeys.h"

namespace arsivinyo::crypto {

namespace {

constexpr uint8_t kMagic[kBackupMagicBytes] = {'A', 'V', 'S', 'B', 'C', 'K', 0};

bool ReadExactly(const BackupReader::Read &read, uint8_t *out, std::size_t length) {
    std::size_t total = 0;
    while (total < length) {
        std::size_t got = 0;
        if (!read(out + total, length - total, &got) || got == 0) return false;
        total += got;
    }
    return true;
}

bool ReadU16(const BackupReader::Read &read, uint16_t *out) {
    uint8_t buffer[2];
    if (!ReadExactly(read, buffer, 2)) return false;
    *out = static_cast<uint16_t>((buffer[0] << 8) | buffer[1]);
    return true;
}

bool ReadU32(const BackupReader::Read &read, uint32_t *out) {
    uint8_t buffer[4];
    if (!ReadExactly(read, buffer, 4)) return false;
    *out = (static_cast<uint32_t>(buffer[0]) << 24) | (static_cast<uint32_t>(buffer[1]) << 16) |
           (static_cast<uint32_t>(buffer[2]) << 8) | static_cast<uint32_t>(buffer[3]);
    return true;
}

/** The same four bytes, but read out of a decrypted section rather than the file. */
bool ReadU32From(const std::function<bool(uint8_t *, std::size_t, std::size_t *)> &read,
                 uint32_t *out, bool *endOfStream) {
    uint8_t buffer[4];
    std::size_t total = 0;
    while (total < 4) {
        std::size_t got = 0;
        if (!read(buffer + total, 4 - total, &got)) return false;
        if (got == 0) {
            if (total == 0 && endOfStream != nullptr) {
                *endOfStream = true;
                return true;
            }
            return false;
        }
        total += got;
    }
    *out = (static_cast<uint32_t>(buffer[0]) << 24) | (static_cast<uint32_t>(buffer[1]) << 16) |
           (static_cast<uint32_t>(buffer[2]) << 8) | static_cast<uint32_t>(buffer[3]);
    return true;
}

std::string HexDigest(const uint8_t *digest, std::size_t length) {
    static const char *kHex = "0123456789abcdef";
    std::string out;
    out.reserve(length * 2);
    for (std::size_t i = 0; i < length; ++i) {
        out.push_back(kHex[digest[i] >> 4]);
        out.push_back(kHex[digest[i] & 0x0f]);
    }
    return out;
}

/** Reads one JSON blob preceded by its u32 length, out of a decrypted section. */
bool ReadJson(const std::function<bool(uint8_t *, std::size_t, std::size_t *)> &read,
              std::size_t limit, std::string *out, std::string *error) {
    uint32_t length = 0;
    if (!ReadU32From(read, &length, nullptr)) {
        if (error) *error = "the section ended where a length was expected";
        return false;
    }
    if (length > limit) {
        if (error) *error = "a metadata block is larger than the format allows";
        return false;
    }
    Bytes buffer(length);
    std::size_t total = 0;
    while (total < length) {
        std::size_t got = 0;
        if (!read(buffer.data() + total, length - total, &got) || got == 0) {
            if (error) *error = "the section ended mid-way through a metadata block";
            return false;
        }
        total += got;
    }
    out->assign(reinterpret_cast<const char *>(buffer.data()), length);
    return true;
}

}  // namespace

SlotOpenResult OpenBackupSlot(const std::string &passphrase, const Bytes &salt,
                              const Bytes &verifier, const Argon2idParams &params,
                              SecretBytes *masterKey, std::string *error) {
    if (masterKey == nullptr) return SlotOpenResult::Failed;
    SecretBytes derived;
    if (!DeriveArgon2id(reinterpret_cast<const uint8_t *>(passphrase.data()), passphrase.size(),
                        salt.data(), salt.size(), params, 32, &derived, error)) {
        return SlotOpenResult::Failed;
    }
    SecretBytes computed;
    if (!BackupVerifier(derived, &computed, error)) return SlotOpenResult::Failed;
    if (!ConstantTimeEquals(computed.data(), computed.size(), verifier.data(), verifier.size())) {
        if (error) *error = "that passphrase is not correct";
        return SlotOpenResult::WrongSecret;
    }
    *masterKey = std::move(derived);
    return SlotOpenResult::Ok;
}

std::unique_ptr<BackupReader> BackupReader::Open(Read read, std::string *headerJson,
                                                 std::string *error) {
    if (read == nullptr || headerJson == nullptr) return nullptr;

    uint8_t magic[kBackupMagicBytes];
    if (!ReadExactly(read, magic, kBackupMagicBytes) ||
        std::memcmp(magic, kMagic, kBackupMagicBytes) != 0) {
        if (error) *error = "this is not a backup file";
        return nullptr;
    }
    uint16_t version = 0;
    if (!ReadU16(read, &version)) {
        if (error) *error = "the file ended before its version";
        return nullptr;
    }
    if (version != kBackupFormatVersion) {
        if (error) *error = "this backup was written by a newer version";
        return nullptr;
    }
    uint32_t headerLength = 0;
    if (!ReadU32(read, &headerLength)) {
        if (error) *error = "the file ended before its header";
        return nullptr;
    }
    if (headerLength == 0 || headerLength > kMaxBackupHeaderBytes) {
        if (error) *error = "the header is not a size this format allows";
        return nullptr;
    }
    Bytes buffer(headerLength);
    if (!ReadExactly(read, buffer.data(), headerLength)) {
        if (error) *error = "the file ended part way through its header";
        return nullptr;
    }
    headerJson->assign(reinterpret_cast<const char *>(buffer.data()), headerLength);

    std::unique_ptr<BackupReader> reader(new BackupReader());
    reader->m_read = std::move(read);
    return reader;
}

bool BackupReader::SkipSection(std::string *error) {
    while (true) {
        uint32_t chunk = 0;
        if (!ReadU32(m_read, &chunk)) {
            if (error) *error = "the file ended inside a section";
            return false;
        }
        if (chunk == 0) return true;
        if (chunk > kMaxChunkBytes) {
            if (error) *error = "a chunk is larger than the format allows";
            return false;
        }
        Bytes discard(chunk);
        if (!ReadExactly(m_read, discard.data(), chunk)) {
            if (error) *error = "the file ended inside a section";
            return false;
        }
    }
}

bool BackupReader::ReadSection(const std::string &sectionId, const SecretBytes &sectionKey,
                               const OnEntry &onEntry, std::string *error) {
    // The section's ciphertext arrives as length-prefixed chunks; the decryptor sees one
    // continuous stream and never learns about the framing.
    Bytes carry;
    std::size_t carryOffset = 0;
    bool chunksDone = false;
    Read fileRead = m_read;

    auto source = [&carry, &carryOffset, &chunksDone, &fileRead, error](
                      uint8_t *out, std::size_t length, std::size_t *got) {
        std::size_t produced = 0;
        while (produced < length) {
            if (carryOffset >= carry.size()) {
                if (chunksDone) break;
                uint32_t chunk = 0;
                if (!ReadU32(fileRead, &chunk)) {
                    if (error) *error = "the file ended inside a section";
                    return false;
                }
                if (chunk == 0) {
                    chunksDone = true;
                    break;
                }
                if (chunk > kMaxChunkBytes) {
                    if (error) *error = "a chunk is larger than the format allows";
                    return false;
                }
                carry.assign(chunk, 0);
                carryOffset = 0;
                if (!ReadExactly(fileRead, carry.data(), chunk)) {
                    if (error) *error = "the file ended inside a section";
                    return false;
                }
            }
            const std::size_t take = std::min(carry.size() - carryOffset, length - produced);
            std::memcpy(out + produced, carry.data() + carryOffset, take);
            carryOffset += take;
            produced += take;
        }
        *got = produced;
        return true;
    };

    auto decryptor =
        StreamDecryptor::Create(sectionKey.data(), sectionKey.size(), sectionId, source, error);
    if (decryptor == nullptr) return false;

    auto plain = [&decryptor, error](uint8_t *out, std::size_t length, std::size_t *got) {
        return decryptor->Read(out, length, got, error);
    };

    while (true) {
        uint32_t headerLength = 0;
        bool ended = false;
        if (!ReadU32From(plain, &headerLength, &ended)) {
            if (error) *error = "the section ended where an entry was expected";
            return false;
        }
        // A zero length is the section terminator, not an entry with no header.
        if (ended || headerLength == 0) break;
        if (headerLength > kMaxEntryHeaderBytes) {
            if (error) *error = "an entry header is larger than the format allows";
            return false;
        }
        Bytes headerBuffer(headerLength);
        std::size_t total = 0;
        while (total < headerLength) {
            std::size_t got = 0;
            if (!plain(headerBuffer.data() + total, headerLength - total, &got) || got == 0) {
                if (error) *error = "the section ended inside an entry header";
                return false;
            }
            total += got;
        }
        const std::string entryHeader(reinterpret_cast<const char *>(headerBuffer.data()),
                                      headerLength);

        // The payload is chunk-framed again inside the plaintext, which is what stops a
        // wrong advisory size in the header from desynchronising the reader.
        Bytes payloadCarry;
        std::size_t payloadOffset = 0;
        bool payloadDone = false;
        uint64_t payloadBytes = 0;
        EVP_MD_CTX *digestContext = EVP_MD_CTX_new();
        EVP_DigestInit_ex(digestContext, EVP_sha256(), nullptr);

        auto payload = [&](uint8_t *out, std::size_t length, std::size_t *got) {
            std::size_t produced = 0;
            while (produced < length) {
                if (payloadOffset >= payloadCarry.size()) {
                    if (payloadDone) break;
                    uint32_t chunk = 0;
                    if (!ReadU32From(plain, &chunk, nullptr)) return false;
                    if (chunk == 0) {
                        payloadDone = true;
                        break;
                    }
                    if (chunk > kMaxChunkBytes) return false;
                    payloadCarry.assign(chunk, 0);
                    payloadOffset = 0;
                    std::size_t filled = 0;
                    while (filled < chunk) {
                        std::size_t step = 0;
                        if (!plain(payloadCarry.data() + filled, chunk - filled, &step) ||
                            step == 0) {
                            return false;
                        }
                        filled += step;
                    }
                    EVP_DigestUpdate(digestContext, payloadCarry.data(), payloadCarry.size());
                    payloadBytes += payloadCarry.size();
                }
                const std::size_t take =
                    std::min(payloadCarry.size() - payloadOffset, length - produced);
                std::memcpy(out + produced, payloadCarry.data() + payloadOffset, take);
                payloadOffset += take;
                produced += take;
            }
            *got = produced;
            return true;
        };

        const bool keepGoing = onEntry(entryHeader, payload);

        // Drain whatever the caller did not read, so the trailer is where it should be.
        Bytes discard(64 * 1024);
        while (!payloadDone) {
            std::size_t got = 0;
            if (!payload(discard.data(), discard.size(), &got)) {
                EVP_MD_CTX_free(digestContext);
                if (error) *error = "the section ended inside an entry payload";
                return false;
            }
            if (got == 0) break;
        }

        uint8_t digest[EVP_MAX_MD_SIZE];
        unsigned int digestLength = 0;
        EVP_DigestFinal_ex(digestContext, digest, &digestLength);
        EVP_MD_CTX_free(digestContext);

        std::string trailerJson;
        if (!ReadJson(plain, kMaxEntryHeaderBytes, &trailerJson, error)) return false;

        // The trailer records the plaintext size and hash. Checking them here is what catches
        // a payload that was truncated at export: its own bytes would hash consistently.
        const std::string computed = HexDigest(digest, digestLength);
        if (trailerJson.find(computed) == std::string::npos) {
            if (error) {
                *error = "an item's contents do not match the hash recorded with it";
            }
            return false;
        }
        if (trailerJson.find("\"incomplete\":true") != std::string::npos ||
            trailerJson.find("\"incomplete\": true") != std::string::npos) {
            if (error) *error = "an item was not fully written when the backup was made";
            return false;
        }
        const std::string sizeField = "\"size\":" + std::to_string(payloadBytes);
        if (trailerJson.find(sizeField) == std::string::npos &&
            trailerJson.find("\"size\": " + std::to_string(payloadBytes)) == std::string::npos) {
            if (error) *error = "an item's length does not match the length recorded with it";
            return false;
        }

        if (!keepGoing) return SkipSection(error);
    }
    return true;
}

}  // namespace arsivinyo::crypto
