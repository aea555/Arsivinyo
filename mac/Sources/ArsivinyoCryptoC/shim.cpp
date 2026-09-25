#include "include/arsivinyo_crypto.h"

#include <cstdio>
#include <cstring>
#include <memory>
#include <string>

#include "aead_stream.h"
#include "argon2.h"
#include "hkdf.h"
#include "keybox.h"
#include "secret.h"
#include "subkeys.h"

using namespace arsivinyo::crypto;

namespace {

// Thread-local, so a failure on one thread cannot overwrite the message another is about
// to read. A shared buffer here would be a race that only shows up under load.
thread_local std::string g_error;

int fail(const std::string &message) {
    g_error = message;
    return 0;
}
int ok() {
    g_error.clear();
    return 1;
}

}  // namespace

const char *av_last_error(void) { return g_error.c_str(); }

int av_random(uint8_t *out, size_t length) {
    Bytes bytes;
    std::string error;
    if (!RandomBytes(length, &bytes, &error)) return fail(error);
    if (length > 0) std::memcpy(out, bytes.data(), length);
    return ok();
}

int av_argon2id(const uint8_t *secret, size_t secretLength, const uint8_t *salt,
                size_t saltLength, uint32_t memoryKiB, uint32_t iterations,
                uint32_t parallelism, uint32_t version, uint8_t *out, size_t outLength) {
    Argon2idParams params;
    params.memoryKiB = memoryKiB;
    params.iterations = iterations;
    params.parallelism = parallelism;
    params.version = version;

    SecretBytes derived;
    std::string error;
    if (!DeriveArgon2id(secret, secretLength, salt, saltLength, params, outLength, &derived,
                        &error)) {
        return fail(error);
    }
    std::memcpy(out, derived.data(), outLength);
    return ok();
}

int av_hkdf_sha256(const uint8_t *ikm, size_t ikmLength, const uint8_t *salt, size_t saltLength,
                   const uint8_t *info, size_t infoLength, uint8_t *out, size_t outLength) {
    SecretBytes derived;
    std::string error;
    if (!HkdfSha256(ikm, ikmLength, salt, saltLength, info, infoLength, outLength, &derived,
                    &error)) {
        return fail(error);
    }
    std::memcpy(out, derived.data(), outLength);
    return ok();
}

namespace {

/** The three subkey helpers differ only in which label they use. */
int derive32(const uint8_t *master, size_t masterLength, uint8_t *out32,
             bool (*fn)(const SecretBytes &, SecretBytes *, std::string *)) {
    SecretBytes key;
    key.assign(master, masterLength);
    SecretBytes derived;
    std::string error;
    if (!fn(key, &derived, &error)) return fail(error);
    std::memcpy(out32, derived.data(), 32);
    return ok();
}

}  // namespace

int av_purpose_key(const uint8_t *master, size_t masterLength, const char *purpose,
                   uint8_t *out32) {
    SecretBytes key;
    key.assign(master, masterLength);
    SecretBytes derived;
    std::string error;
    if (!PurposeKey(key, purpose, &derived, &error)) return fail(error);
    std::memcpy(out32, derived.data(), 32);
    return ok();
}

int av_backup_section_key(const uint8_t *master, size_t masterLength, const char *sectionId,
                          uint8_t *out32) {
    SecretBytes key;
    key.assign(master, masterLength);
    SecretBytes derived;
    std::string error;
    if (!BackupSectionKey(key, sectionId, &derived, &error)) return fail(error);
    std::memcpy(out32, derived.data(), 32);
    return ok();
}

int av_backup_verifier(const uint8_t *master, size_t masterLength, uint8_t *out32) {
    return derive32(master, masterLength, out32, BackupVerifier);
}

int av_constant_time_equals(const uint8_t *a, size_t aLength, const uint8_t *b, size_t bLength) {
    return ConstantTimeEquals(a, aLength, b, bLength) ? 1 : 0;
}

size_t av_aead_sealed_length(size_t plaintextLength) {
    const size_t capFirst = PlaintextSegmentBytes(0);
    if (plaintextLength <= capFirst) return kHeaderBytes + plaintextLength + kTagBytes;
    const size_t rest = plaintextLength - capFirst;
    const size_t capOther = PlaintextSegmentBytes(1);
    const size_t fullOthers = rest / capOther;
    const size_t remainder = rest % capOther;
    const size_t segments = 1 + fullOthers + (remainder > 0 ? 1 : 0);
    return kHeaderBytes + plaintextLength + segments * kTagBytes;
}

int64_t av_aead_opened_length(uint64_t ciphertextLength) {
    bool valid = false;
    const uint64_t length = PlaintextSizeFor(ciphertextLength, &valid);
    return valid ? static_cast<int64_t>(length) : -1;
}

int av_aead_seal(const uint8_t *key, size_t keyLength, const char *associatedData,
                 const uint8_t *plaintext, size_t plaintextLength, uint8_t *out) {
    Bytes sealed;
    std::string error;
    if (!EncryptBuffer(key, keyLength, associatedData, plaintext, plaintextLength, &sealed,
                       &error)) {
        return fail(error);
    }
    std::memcpy(out, sealed.data(), sealed.size());
    return ok();
}

int av_aead_open(const uint8_t *key, size_t keyLength, const char *associatedData,
                 const uint8_t *ciphertext, size_t ciphertextLength, uint8_t *out) {
    Bytes opened;
    std::string error;
    if (!DecryptBuffer(key, keyLength, associatedData, ciphertext, ciphertextLength, &opened,
                       &error)) {
        return fail(error);
    }
    if (!opened.empty()) std::memcpy(out, opened.data(), opened.size());
    return ok();
}

int av_aead_seal_with_header(const uint8_t *key, size_t keyLength, const char *associatedData,
                             const uint8_t *headerSalt32, const uint8_t *noncePrefix7,
                             const uint8_t *plaintext, size_t plaintextLength, uint8_t *out) {
    Bytes sealed;
    std::string error;
    auto sink = [&sealed](const uint8_t *data, std::size_t length) {
        sealed.insert(sealed.end(), data, data + length);
        return true;
    };
    auto encryptor = StreamEncryptor::CreateWithHeader(key, keyLength, associatedData,
                                                       headerSalt32, noncePrefix7, sink, &error);
    if (encryptor == nullptr) return fail(error);
    if (!encryptor->Write(plaintext, plaintextLength, &error)) return fail(error);
    if (!encryptor->Finish(&error)) return fail(error);
    std::memcpy(out, sealed.data(), sealed.size());
    return ok();
}

size_t av_pad_length(size_t contentLength) {
    const size_t framed = 4 + contentLength;
    const size_t total = ((framed + kPadBoundary - 1) / kPadBoundary) * kPadBoundary;
    return total == 0 ? kPadBoundary : total;
}

void av_pad(const uint8_t *content, size_t contentLength, uint8_t *out) {
    const Bytes padded = PadForConcealment(content, contentLength);
    std::memcpy(out, padded.data(), padded.size());
}

int64_t av_unpad(const uint8_t *padded, size_t paddedLength, uint8_t *out) {
    const Bytes input(padded, padded + paddedLength);
    Bytes content;
    std::string error;
    if (!UnpadFromConcealment(input, &content, &error)) {
        g_error = error;
        return -1;
    }
    if (!content.empty()) std::memcpy(out, content.data(), content.size());
    g_error.clear();
    return static_cast<int64_t>(content.size());
}

// ---- the key box -----------------------------------------------------------------------

int av_keyfile_kek(const uint8_t *keyfile, size_t keyfileLength, const uint8_t *salt,
                   size_t saltLength, uint8_t *out32) {
    const Bytes file(keyfile, keyfile + keyfileLength);
    const Bytes saltBytes(salt, salt + saltLength);
    SecretBytes kek;
    std::string error;
    if (!KeyfileKek(file, saltBytes, &kek, &error)) return fail(error);
    std::memcpy(out32, kek.data(), 32);
    return ok();
}

int av_keybox_wrap(const uint8_t *kek, size_t kekLength, const uint8_t *masterKey32,
                   const char *slotId, uint8_t *outVerifier32, uint8_t *outWrapped60) {
    SecretBytes kekBytes;
    kekBytes.assign(kek, kekLength);
    SecretBytes master;
    master.assign(masterKey32, 32);
    Slot slot;
    slot.id = slotId;
    std::string error;
    if (!WrapMasterKey(kekBytes, master, &slot, &error)) return fail(error);
    if (slot.verifier.size() != AV_VERIFIER_BYTES || slot.wrapped.size() != AV_WRAPPED_BYTES) {
        return fail("unexpected wrapped key size");
    }
    std::memcpy(outVerifier32, slot.verifier.data(), AV_VERIFIER_BYTES);
    std::memcpy(outWrapped60, slot.wrapped.data(), AV_WRAPPED_BYTES);
    return ok();
}

int av_keybox_unwrap(const uint8_t *kek, size_t kekLength, const char *slotId,
                     const uint8_t *verifier32, const uint8_t *wrapped60,
                     uint8_t *outMaster32) {
    SecretBytes kekBytes;
    kekBytes.assign(kek, kekLength);
    Slot slot;
    slot.id = slotId;
    slot.verifier.assign(verifier32, verifier32 + AV_VERIFIER_BYTES);
    slot.wrapped.assign(wrapped60, wrapped60 + AV_WRAPPED_BYTES);
    SecretBytes master;
    std::string error;
    switch (UnwrapMasterKey(kekBytes, slot, &master, &error)) {
        case UnwrapResult::Ok:
            std::memcpy(outMaster32, master.data(), 32);
            ok();
            return 1;
        case UnwrapResult::WrongSecret:
            g_error = error;
            return 0;
        case UnwrapResult::Damaged:
            g_error = error;
            return -1;
    }
    return -1;
}

// ---- whole files -----------------------------------------------------------------------

namespace {

struct FileCloser {
    void operator()(FILE *file) const {
        if (file != nullptr) std::fclose(file);
    }
};
using File = std::unique_ptr<FILE, FileCloser>;

constexpr size_t kChunk = 1u << 20;

}  // namespace

int av_encrypt_file(const char *sourcePath, const char *destinationPath, const uint8_t *key,
                    size_t keyLength, const char *associatedData) {
    File input(std::fopen(sourcePath, "rb"));
    if (!input) return fail("could not read the source file");
    File output(std::fopen(destinationPath, "wb"));
    if (!output) return fail("could not write the destination file");

    FILE *out = output.get();
    bool writeFailed = false;
    auto sink = [out, &writeFailed](const uint8_t *data, std::size_t length) {
        if (std::fwrite(data, 1, length, out) != length) writeFailed = true;
        return !writeFailed;
    };

    std::string error;
    auto encryptor = StreamEncryptor::Create(key, keyLength, associatedData, sink, &error);
    if (!encryptor) return fail(error);

    Bytes buffer(kChunk);
    while (true) {
        const size_t read = std::fread(buffer.data(), 1, buffer.size(), input.get());
        if (read > 0 && !encryptor->Write(buffer.data(), read, &error)) return fail(error);
        if (read < buffer.size()) {
            if (std::ferror(input.get())) return fail("could not read the source file");
            break;
        }
    }
    if (!encryptor->Finish(&error)) return fail(error);
    if (writeFailed || std::fflush(out) != 0) return fail("could not write the destination file");
    return ok();
}

// ---- random access ---------------------------------------------------------------------

struct av_reader {
    File file;
    std::unique_ptr<SeekableStreamReader> reader;
};

av_reader *av_reader_open(const char *path, const uint8_t *key, size_t keyLength,
                          const char *associatedData) {
    auto handle = std::make_unique<av_reader>();
    handle->file.reset(std::fopen(path, "rb"));
    if (!handle->file) {
        fail("could not open the file");
        return nullptr;
    }
    FILE *file = handle->file.get();
    if (std::fseek(file, 0, SEEK_END) != 0) {
        fail("could not size the file");
        return nullptr;
    }
    const long size = std::ftell(file);
    if (size < 0) {
        fail("could not size the file");
        return nullptr;
    }

    auto read = [file](uint64_t offset, uint8_t *out, std::size_t length) {
        if (std::fseek(file, static_cast<long>(offset), SEEK_SET) != 0) return false;
        return std::fread(out, 1, length, file) == length;
    };
    std::string error;
    handle->reader = SeekableStreamReader::Open(key, keyLength, associatedData, read,
                                                static_cast<uint64_t>(size), &error);
    if (!handle->reader) {
        fail(error);
        return nullptr;
    }
    ok();
    return handle.release();
}

int64_t av_reader_size(av_reader *reader) {
    if (reader == nullptr || !reader->reader) return -1;
    return static_cast<int64_t>(reader->reader->PlaintextSize());
}

int64_t av_reader_read(av_reader *reader, uint64_t offset, uint8_t *out, size_t length) {
    if (reader == nullptr || !reader->reader) return -1;
    std::size_t got = 0;
    std::string error;
    if (!reader->reader->ReadAt(offset, out, length, &got, &error)) {
        g_error = error;
        return -1;
    }
    return static_cast<int64_t>(got);
}

void av_reader_close(av_reader *reader) { delete reader; }

int av_decrypt_file(const char *sourcePath, const char *destinationPath, const uint8_t *key,
                    size_t keyLength, const char *associatedData) {
    std::unique_ptr<av_reader> reader(av_reader_open(sourcePath, key, keyLength, associatedData));
    if (!reader) return 0;
    File output(std::fopen(destinationPath, "wb"));
    if (!output) return fail("could not write the destination file");

    const int64_t total = av_reader_size(reader.get());
    Bytes buffer(kChunk);
    uint64_t offset = 0;
    while (offset < static_cast<uint64_t>(total)) {
        const int64_t got = av_reader_read(reader.get(), offset, buffer.data(), buffer.size());
        if (got <= 0) return fail(g_error.empty() ? "the file ended early" : g_error);
        if (std::fwrite(buffer.data(), 1, static_cast<size_t>(got), output.get()) !=
            static_cast<size_t>(got)) {
            return fail("could not write the destination file");
        }
        offset += static_cast<uint64_t>(got);
    }
    if (std::fflush(output.get()) != 0) return fail("could not write the destination file");
    return ok();
}
