#pragma once

// AES-GCM-HKDF streaming AEAD, wire-compatible with Tink's `AesGcmHkdfStreaming`.
//
// This is the one construction here with no library behind it: the phone gets it from Tink,
// and this is a second implementation of the same bytes. It is therefore the highest bug
// density in the module, and the reason VECTORS.json pins segment boundaries specifically.
// A subtly wrong implementation produces data that looks fine until it cannot be read.
//
// The format, verified against Tink's own output by hand-decrypting it with nothing but
// HKDF-SHA256 and AES-GCM:
//
//   header:  [0]      = headerLength = 1 + 32 + 7 = 40
//            [1..33)  = 32-byte random salt
//            [33..40) = 7-byte nonce prefix
//   key:     HKDF-SHA256(ikm = key, salt = headerSalt, info = associatedData, L = 32)
//            -- the associated data enters through HKDF *info*. Each segment's GCM AAD is
//               empty. Getting this wrong is invisible until the other end reads the file.
//   nonce:   noncePrefix(7) || segmentIndex(4, big-endian) || lastSegmentFlag(1)
//   segment: 1 MiB of ciphertext including a trailing 16-byte GCM tag
//
// Segment 0 is short by the header, so it carries 1048520 plaintext bytes against 1048560
// for every later one. An off-by-one there breaks seeking to a segment boundary and nothing
// else, which is why it would otherwise ship as "playback glitches near the 1 MB marks".

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

#include "secret.h"

namespace arsivinyo::crypto {

inline constexpr std::size_t kStreamKeyBytes = 32;
inline constexpr std::size_t kHeaderSaltBytes = 32;
inline constexpr std::size_t kNoncePrefixBytes = 7;
inline constexpr std::size_t kTagBytes = 16;
inline constexpr std::size_t kHeaderBytes = 1 + kHeaderSaltBytes + kNoncePrefixBytes;  // 40
inline constexpr std::size_t kCiphertextSegmentBytes = 1u << 20;                       // 1 MiB

/** Plaintext a segment carries: 1048520 for segment 0, 1048560 for the rest. */
std::size_t PlaintextSegmentBytes(uint64_t segmentIndex);

/** How many plaintext bytes a ciphertext of this size holds. 0 if the size is impossible. */
uint64_t PlaintextSizeFor(uint64_t ciphertextSize, bool *valid);

/** Build the 12-byte segment nonce. Exposed because VECTORS.json pins it directly. */
void SegmentNonce(const uint8_t *noncePrefix, uint64_t segmentIndex, bool last,
                  uint8_t out[12]);

/** Encrypts a byte run into a sink. The sink is called with ciphertext as it is produced. */
class StreamEncryptor {
 public:
    using Sink = std::function<bool(const uint8_t *, std::size_t)>;

    static std::unique_ptr<StreamEncryptor> Create(const uint8_t *key, std::size_t keyLength,
                                                   const std::string &associatedData, Sink sink,
                                                   std::string *error);

    /**
     * Testing only. Tink will not let a caller supply the header salt or nonce prefix, so a
     * vector generated from Tink can only be reproduced here if this side can be given the
     * same header. Without this the vectors could check decryption only, and an encoder bug
     * would ship.
     */
    static std::unique_ptr<StreamEncryptor> CreateWithHeader(
        const uint8_t *key, std::size_t keyLength, const std::string &associatedData,
        const uint8_t *headerSalt, const uint8_t *noncePrefix, Sink sink, std::string *error);

    ~StreamEncryptor();
    StreamEncryptor(const StreamEncryptor &) = delete;
    StreamEncryptor &operator=(const StreamEncryptor &) = delete;

    bool Write(const uint8_t *data, std::size_t length, std::string *error);
    /** Emits the final segment with the last-segment flag set. Required, even for no input. */
    bool Finish(std::string *error);

 private:
    StreamEncryptor() = default;
    bool EmitSegment(const uint8_t *plain, std::size_t length, bool last, std::string *error);

    SecretBytes m_streamKey;
    uint8_t m_noncePrefix[kNoncePrefixBytes]{};
    Bytes m_buffer;
    uint64_t m_segmentIndex = 0;
    Sink m_sink;
    bool m_finished = false;
};

/**
 * Forward-only decryption, for a stream whose length is not known ahead of time — a backup
 * section arrives as length-prefixed chunks and ends when the chunks do.
 *
 * A segment's nonce depends on whether it is the last one, so this cannot decrypt a segment
 * until it knows whether more follow. It reads one byte past each segment to find out.
 */
class StreamDecryptor {
 public:
    /** Sequential read. `got` below `length` means the source is exhausted. */
    using Source = std::function<bool(uint8_t *out, std::size_t length, std::size_t *got)>;

    static std::unique_ptr<StreamDecryptor> Create(const uint8_t *key, std::size_t keyLength,
                                                   const std::string &associatedData,
                                                   Source source, std::string *error);

    /** Reads up to `length` plaintext bytes. `got` below `length` means end of stream. */
    bool Read(uint8_t *out, std::size_t length, std::size_t *got, std::string *error);

 private:
    StreamDecryptor() = default;
    bool FillSegment(std::string *error);

    SecretBytes m_streamKey;
    uint8_t m_noncePrefix[kNoncePrefixBytes]{};
    Source m_source;
    Bytes m_pending;        // ciphertext read ahead of the current segment
    Bytes m_plain;          // the decrypted segment being handed out
    std::size_t m_offset = 0;
    uint64_t m_segmentIndex = 0;
    bool m_done = false;
};

/** Random access for playback. Decrypts whole segments and caches the most recent one. */
class SeekableStreamReader {
 public:
    /** Reads `length` ciphertext bytes at `offset`. False means a short or failed read. */
    using ReadCiphertext = std::function<bool(uint64_t offset, uint8_t *out, std::size_t length)>;

    static std::unique_ptr<SeekableStreamReader> Open(const uint8_t *key, std::size_t keyLength,
                                                      const std::string &associatedData,
                                                      ReadCiphertext readCiphertext,
                                                      uint64_t ciphertextSize,
                                                      std::string *error);

    uint64_t PlaintextSize() const { return m_plaintextSize; }

    /** Reads up to `length` plaintext bytes at `offset`. Short only at end of stream. */
    bool ReadAt(uint64_t offset, uint8_t *out, std::size_t length, std::size_t *got,
                std::string *error);

 private:
    SeekableStreamReader() = default;
    bool LoadSegment(uint64_t index, std::string *error);

    SecretBytes m_streamKey;
    uint8_t m_noncePrefix[kNoncePrefixBytes]{};
    ReadCiphertext m_read;
    uint64_t m_ciphertextSize = 0;
    uint64_t m_plaintextSize = 0;
    uint64_t m_segmentCount = 0;
    Bytes m_cache;
    uint64_t m_cachedIndex = UINT64_MAX;
};

/** Decrypts a whole buffer in one call. For small payloads such as an index or a cookie jar. */
bool DecryptBuffer(const uint8_t *key, std::size_t keyLength, const std::string &associatedData,
                   const uint8_t *ciphertext, std::size_t ciphertextLength, Bytes *out,
                   std::string *error);

/** Encrypts a whole buffer in one call. */
bool EncryptBuffer(const uint8_t *key, std::size_t keyLength, const std::string &associatedData,
                   const uint8_t *plaintext, std::size_t plaintextLength, Bytes *out,
                   std::string *error);

}  // namespace arsivinyo::crypto
