#ifndef ARSIVINYO_CRYPTO_H
#define ARSIVINYO_CRYPTO_H

// A flat C boundary over shared/crypto.
//
// Swift can call C++ directly, but that core's surface is std::function sinks and
// unique_ptr factories, which interop handles badly. A C ABI is smaller to get right, and
// the Swift side above it reads like Swift rather than like C++ wearing a hat.
//
// Every function returns 1 on success and 0 on failure. On failure av_last_error() holds a
// message; it is thread-local, so a failure on one thread cannot overwrite another's.
//
// Nothing here allocates on the caller's behalf. The caller sizes its own buffers, using
// the av_*_len helpers where the size is not obvious.

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** The last failure on this thread, or "" if the last call succeeded. Never NULL. */
const char *av_last_error(void);

// ---- randomness ---------------------------------------------------------------------

/** Fails rather than returning predictable bytes; a caller that ignores this ships a zero key. */
int av_random(uint8_t *out, size_t length);

// ---- key derivation -----------------------------------------------------------------

/** `secret` must already be UTF-8: the Android side hashes UTF-8 and the two must agree. */
int av_argon2id(const uint8_t *secret, size_t secretLength,
                const uint8_t *salt, size_t saltLength,
                uint32_t memoryKiB, uint32_t iterations, uint32_t parallelism, uint32_t version,
                uint8_t *out, size_t outLength);

/** `salt` may be NULL, which means Tink's null: a zero-filled block of the digest length. */
int av_hkdf_sha256(const uint8_t *ikm, size_t ikmLength,
                   const uint8_t *salt, size_t saltLength,
                   const uint8_t *info, size_t infoLength,
                   uint8_t *out, size_t outLength);

/** HKDF(master, "arsivinyo/key/v1/<purpose>") -> 32 bytes. */
int av_purpose_key(const uint8_t *master, size_t masterLength, const char *purpose, uint8_t *out32);

/** HKDF(master, "avsbck/section/v1/<sectionId>") -> 32 bytes. */
int av_backup_section_key(const uint8_t *master, size_t masterLength, const char *sectionId,
                          uint8_t *out32);

/** HKDF(master, "avsbck/verify/v1") -> 32 bytes. Compare with av_constant_time_equals. */
int av_backup_verifier(const uint8_t *master, size_t masterLength, uint8_t *out32);

/** For verifiers and tokens. Comparing those with memcmp leaks by timing. */
int av_constant_time_equals(const uint8_t *a, size_t aLength, const uint8_t *b, size_t bLength);

// ---- the streaming AEAD, whole-buffer -------------------------------------------------

/** Ciphertext length for a plaintext of this size. */
size_t av_aead_sealed_length(size_t plaintextLength);

/** Plaintext length a ciphertext of this size holds, or -1 if the size is impossible. */
int64_t av_aead_opened_length(uint64_t ciphertextLength);

int av_aead_seal(const uint8_t *key, size_t keyLength, const char *associatedData,
                 const uint8_t *plaintext, size_t plaintextLength, uint8_t *out);

int av_aead_open(const uint8_t *key, size_t keyLength, const char *associatedData,
                 const uint8_t *ciphertext, size_t ciphertextLength, uint8_t *out);

/**
 * Testing only. The vectors record a header Tink chose, and reproducing their ciphertext
 * byte for byte is the check that the two implementations agree in both directions.
 */
int av_aead_seal_with_header(const uint8_t *key, size_t keyLength, const char *associatedData,
                             const uint8_t *headerSalt32, const uint8_t *noncePrefix7,
                             const uint8_t *plaintext, size_t plaintextLength, uint8_t *out);

// ---- concealment padding ---------------------------------------------------------------

/** Padded length for this content: `u32 length | content | zeros` to a 4096 boundary. */
size_t av_pad_length(size_t contentLength);
void av_pad(const uint8_t *content, size_t contentLength, uint8_t *out);
/** Content length, or -1 if the block lies about how much it holds. */
int64_t av_unpad(const uint8_t *padded, size_t paddedLength, uint8_t *out);

// ---- the key box --------------------------------------------------------------------

/** A wrapped master key: nonce(12) || ciphertext(32) || tag(16). */
#define AV_WRAPPED_BYTES 60
#define AV_VERIFIER_BYTES 32

/** HKDF over a key file's contents. The file alone is not the key-encryption key. */
int av_keyfile_kek(const uint8_t *keyfile, size_t keyfileLength,
                   const uint8_t *salt, size_t saltLength, uint8_t *out32);

/** Wraps `masterKey32` under `kek`, writing the verifier and the wrapped blob. */
int av_keybox_wrap(const uint8_t *kek, size_t kekLength, const uint8_t *masterKey32,
                   const char *slotId, uint8_t *outVerifier32, uint8_t *outWrapped60);

/**
 * 1 unwrapped, 0 the secret is wrong, -1 the stored key is damaged.
 *
 * Wrong and damaged are kept apart on purpose: the verifier says the key-encryption key is
 * wrong before the wrapped blob is touched, so a mistyped passphrase is reported as one
 * rather than as corruption.
 */
int av_keybox_unwrap(const uint8_t *kek, size_t kekLength, const char *slotId,
                     const uint8_t *verifier32, const uint8_t *wrapped60,
                     uint8_t *outMaster32);

// ---- whole files ----------------------------------------------------------------------

/**
 * Encrypts a file into another, a megabyte at a time.
 *
 * Vault items are video. Reading one into memory to seal it would mean a gigabyte of
 * resident memory for a file the machine is only copying.
 */
int av_encrypt_file(const char *sourcePath, const char *destinationPath,
                    const uint8_t *key, size_t keyLength, const char *associatedData);

int av_decrypt_file(const char *sourcePath, const char *destinationPath,
                    const uint8_t *key, size_t keyLength, const char *associatedData);

// ---- random access, for playback --------------------------------------------------------

/**
 * A seekable reader over an encrypted file.
 *
 * What playback needs: a player opens a file, jumps to the end for the container index, and
 * comes back. Nothing is ever decrypted to disk.
 */
typedef struct av_reader av_reader;

/** NULL if the file is missing or the key does not open it. */
av_reader *av_reader_open(const char *path, const uint8_t *key, size_t keyLength,
                          const char *associatedData);

/** How many plaintext bytes the file holds. */
int64_t av_reader_size(av_reader *reader);

/** Reads at a plaintext offset. Returns the count, or -1. Short only at end of file. */
int64_t av_reader_read(av_reader *reader, uint64_t offset, uint8_t *out, size_t length);

void av_reader_close(av_reader *reader);

#ifdef __cplusplus
}
#endif
#endif
