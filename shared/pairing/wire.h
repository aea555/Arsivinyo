#pragma once

#include <cstdint>
#include <string>
#include <vector>

/**
 * The bytes on the wire, and the pairing code.
 *
 * This is deliberately free of Qt, of Android, and of any crypto library: it is the one
 * part both apps must agree on exactly, so it is small, portable, and pinned by shared
 * test vectors. A framing bug or an off-by-one in the code derivation would show up as
 * "pairing just doesn't work", with nothing to read.
 *
 * Hashing is *not* here. Both platforms already ship a SHA-256 they trust, and vendoring
 * a third would add code to audit for no benefit. What is here is the exact input that
 * gets hashed and exactly how the digest becomes six digits.
 */
namespace arsivinyo::pairing {

/** Ed25519 public keys are 32 bytes. */
inline constexpr size_t kPublicKeyBytes = 32;

/** A frame is a length, a type byte, and a payload. */
enum class FrameType : uint8_t {
    Control = 0,   ///< UTF-8 JSON
    Bulk = 1,      ///< raw bytes of the transfer in progress
};

/**
 * Refuse anything larger. A control message is small and a bulk chunk is chosen by the
 * sender, so without a cap a peer could announce four gigabytes and make the receiver
 * try to allocate it before a single byte arrives.
 */
inline constexpr uint32_t kMaxFrameBytes = 8u * 1024u * 1024u;

/** Encode one frame. Returns false if the payload exceeds [kMaxFrameBytes]. */
bool EncodeFrame(FrameType type, const std::vector<uint8_t>& payload,
                 std::vector<uint8_t>* out);

enum class DecodeResult {
    Ok,
    Incomplete,   ///< a whole frame has not arrived yet; keep buffering
    TooLarge,     ///< announced length is over the cap; drop the connection
    BadType,      ///< unknown frame type; drop the connection
};

/**
 * Decode the first frame in [buffer].
 *
 * On Ok, [consumed] is how many bytes to drop from the front. On Incomplete nothing is
 * consumed and the caller waits for more.
 */
DecodeResult DecodeFrame(const std::vector<uint8_t>& buffer, FrameType* type,
                         std::vector<uint8_t>* payload, size_t* consumed);

/**
 * The bytes to hash when deriving a pairing code: the two public keys, ordered.
 *
 * Sorted so both devices hash the same thing without agreeing who is first — otherwise
 * the two ends compute different codes and the user is told the keys do not match when
 * they do.
 */
std::vector<uint8_t> CodeInput(const std::vector<uint8_t>& keyA,
                               const std::vector<uint8_t>& keyB);

/**
 * Six digits from a SHA-256 digest of [CodeInput].
 *
 * The first four bytes, big-endian, modulo one million, zero-padded. About a million
 * possibilities: enough that an attacker who has to make both ends agree cannot simply
 * try, and short enough to read aloud.
 */
std::string PairingCode(const uint8_t* digest, size_t digestLen);

}  // namespace arsivinyo::pairing
