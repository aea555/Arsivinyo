#include "include/arsivinyo_crypto.h"

#include <cstring>
#include <string>

#include "aead_stream.h"
#include "argon2.h"
#include "hkdf.h"
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
