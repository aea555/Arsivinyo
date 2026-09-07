#pragma once

// The `.avsbck` container, as far as framing goes.
//
// The pairing protocol names a backup as the only supported way to move vault contents
// between devices, and until now only the phone could write or read one. This is the second
// implementation of that format.
//
// JSON is deliberately absent: this file owns the magic, the big-endian integers, the chunk
// framing and the entry framing, and hands header and metadata through as opaque bytes. The
// caller parses them with whatever it has — the desktop uses QJsonDocument — which keeps this
// directory free of Qt. That split is only safe because the header is parsed and never
// compared byte for byte: nothing authenticates it, so key ordering does not matter.
//
// Layout:
//
//   magic "AVSBCK\0" | formatVersion u16 | headerLength u32 | header JSON   <- all plaintext
//   then, per section, in the order the header lists them:
//     repeat { chunkLength u32 | chunk }  terminated by u32 0
//     -- the concatenated chunks are ONE AesGcmHkdfStreaming stream, AAD = the section id
//   and inside that stream:
//     repeat {
//       entryHeaderLength u32 | entry header JSON
//       repeat { chunkLength u32 | chunk } terminated by u32 0     <- the payload
//       trailerLength u32 | trailer JSON
//     } terminated by u32 0
//
// Sections carry no offsets: a section's ciphertext length is not known until it has been
// written, and the output stream it is written to is not always seekable. Self-delimiting
// chunks cost four bytes a megabyte and make both directions forward-only.

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

#include "argon2.h"
#include "secret.h"

namespace arsivinyo::crypto {

inline constexpr std::size_t kBackupMagicBytes = 7;
inline constexpr uint16_t kBackupFormatVersion = 1;
inline constexpr std::size_t kMaxBackupHeaderBytes = 1u << 20;
inline constexpr std::size_t kMaxEntryHeaderBytes = 1u << 16;
inline constexpr std::size_t kMaxChunkBytes = 64u << 20;

/**
 * Derives the master key from a passphrase and checks it against the slot's recorded
 * verifier, in constant time, before any payload is touched — so a mistyped passphrase is
 * reported as such rather than surfacing as corruption megabytes in.
 */
enum class SlotOpenResult { Ok, WrongSecret, Failed };
SlotOpenResult OpenBackupSlot(const std::string &passphrase, const Bytes &salt,
                              const Bytes &verifier, const Argon2idParams &params,
                              SecretBytes *masterKey, std::string *error);

class BackupReader {
 public:
    /** Sequential read over the file. `got` below `length` means end of file. */
    using Read = std::function<bool(uint8_t *out, std::size_t length, std::size_t *got)>;
    /** Pulls an entry's payload. `got` below `length` means the entry is finished. */
    using PayloadReader = std::function<bool(uint8_t *out, std::size_t length, std::size_t *got)>;
    /** Called per entry. Return false to stop the walk. Unread payload is drained. */
    using OnEntry = std::function<bool(const std::string &headerJson, const PayloadReader &payload)>;

    /** Reads the plaintext preamble. No secret is needed, which is what drives the preview. */
    static std::unique_ptr<BackupReader> Open(Read read, std::string *headerJson,
                                              std::string *error);

    /**
     * Walks the next section. Its id is both the associated data and what names its key, so
     * a section's ciphertext moved into another's position fails the tag.
     *
     * Each entry's recorded size and SHA-256 are checked as its payload streams past, and an
     * entry the writer marked incomplete is refused — a truncated file whose hash matches its
     * own truncated bytes is exactly the failure worth designing against.
     */
    bool ReadSection(const std::string &sectionId, const SecretBytes &sectionKey,
                     const OnEntry &onEntry, std::string *error);

    /** Steps over the next section without decrypting it, for a partial restore. */
    bool SkipSection(std::string *error);

 private:
    BackupReader() = default;
    Read m_read;
};

}  // namespace arsivinyo::crypto
