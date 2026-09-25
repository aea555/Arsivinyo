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
    /**
     * Called after each entry, once its trailer has been checked: whether the payload the
     * entry handed out was whole and matched its recorded size and hash, and if not, why.
     * A restore stages each payload and commits it only on a true here.
     */
    using OnVerdict = std::function<void(bool verified, const std::string &why)>;

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
    ///
    /// With `onVerdict`, an item that fails its check is reported there and the walk goes on
    /// to the next, which is what the phone does: one damaged video does not cost the rest of
    /// the vault. Without it, the first such item ends the walk with an error.
    bool ReadSection(const std::string &sectionId, const SecretBytes &sectionKey,
                     const OnEntry &onEntry, std::string *error,
                     const OnVerdict &onVerdict = nullptr);

    /** Steps over the next section without decrypting it, for a partial restore. */
    bool SkipSection(std::string *error);

 private:
    BackupReader() = default;
    Read m_read;
};

/**
 * Writes a backup, forward only, in the layout above.
 *
 * The header and every entry header are opaque JSON from the caller, as on the reading side.
 * What this owns is the framing, the encryption of each section, and each entry's trailer:
 * the payload's size and SHA-256 are taken as it streams through, so nothing is read twice.
 *
 *   Open → BeginSection → (BeginEntry → WritePayload… → EndEntry)… → EndSection → … → done
 */
class BackupWriter {
 public:
    /** Sequential write to the file. */
    using Write = std::function<bool(const uint8_t *data, std::size_t length)>;

    /** Writes the magic, the version and the plaintext header. */
    static std::unique_ptr<BackupWriter> Open(Write write, const std::string &headerJson,
                                              std::string *error);

    ~BackupWriter();
    BackupWriter(const BackupWriter &) = delete;
    BackupWriter &operator=(const BackupWriter &) = delete;

    /** The section id is both the associated data and what names its key, as on reading. */
    bool BeginSection(const std::string &sectionId, const SecretBytes &sectionKey,
                      std::string *error);
    bool BeginEntry(const std::string &entryHeaderJson, std::string *error);
    bool WritePayload(const uint8_t *data, std::size_t length, std::string *error);
    /**
     * Closes the entry with its trailer. `complete` false marks it as not fully read from
     * its source — the header was already written, so the entry has to be closed either way,
     * and without the mark its hash would verify against the truncated bytes.
     */
    bool EndEntry(bool complete, std::string *error);
    bool EndSection(std::string *error);

 private:
    BackupWriter() = default;
    bool PlainWrite(const uint8_t *data, std::size_t length, std::string *error);
    bool PlainU32(uint32_t value, std::string *error);
    bool FlushPayloadChunk(std::string *error);

    Write m_write;
    std::unique_ptr<class StreamEncryptor> m_encryptor;
    Bytes m_chunk;
    uint64_t m_payloadBytes = 0;
    void *m_digest = nullptr;  // EVP_MD_CTX, kept out of this header
    bool m_inEntry = false;
};

}  // namespace arsivinyo::crypto
