#pragma once

#include <cstdint>
#include <string>
#include <utility>
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

/** Which end of the connection signed. */
enum class AuthRole : uint8_t {
    Server = 'S',
    Client = 'C',
};

/** Length of a SHA-256 digest, which is what the transcript carries. */
inline constexpr size_t kCertHashBytes = 32;

/**
 * The exact bytes each side signs with its identity key to prove who it is.
 *
 * The identity key is *not* the TLS certificate key. Android's TLS stack does not accept
 * Ed25519 certificates, and this app supports API 24, so a certificate carrying the
 * identity — what the first draft of PROTOCOL.md specified — cannot be implemented on the
 * phone at all. Instead each side uses an ordinary self-signed certificate for TLS and
 * then signs a transcript naming *both* certificates of this particular connection.
 *
 * That is what keeps the man-in-the-middle out. An attacker terminating TLS on both legs
 * sees different certificates on each, so a signature produced for one leg does not
 * verify on the other, and it cannot forge one without the Ed25519 key.
 *
 * The server hash always comes first, so both ends build the same bytes without
 * negotiating an order. The trailing role byte is what stops a signature captured from
 * one direction being replayed as the other's.
 *
 * Returns empty if either hash is not [kCertHashBytes] long.
 */
/**
 * Put this connection's two certificate hashes in the order [AuthTranscript] requires.
 *
 * The server's hash comes first, always. Swapping this consistently on both ends of one
 * platform is invisible — the two agree with each other and every test passes — and fails
 * only against the other platform. That is why it is a named function pinned to the shared
 * vectors rather than an expression inlined at each call site.
 *
 * [role] is the *local* device's role. Returns {serverCertSha256, clientCertSha256}.
 */
std::pair<std::vector<uint8_t>, std::vector<uint8_t>> TranscriptOrder(
    AuthRole role, const std::vector<uint8_t>& ownCertSha256,
    const std::vector<uint8_t>& peerCertSha256);

std::vector<uint8_t> AuthTranscript(AuthRole role,
                                    const std::vector<uint8_t>& serverCertSha256,
                                    const std::vector<uint8_t>& clientCertSha256);

/**
 * Six digits from a SHA-256 digest of [CodeInput].
 *
 * The first four bytes, big-endian, modulo one million, zero-padded. About a million
 * possibilities: enough that an attacker who has to make both ends agree cannot simply
 * try, and short enough to read aloud.
 */
std::string PairingCode(const uint8_t* digest, size_t digestLen);

// Pairing v2: commit, then reveal.
//
// v1 derived the six digits from the two public keys alone. Both keys are known before
// anyone compares digits, so a man in the middle could generate key pairs until its two
// legs showed the same code: about a million tries, seconds of work. v2 has the client
// commit to a random nonce before it sees the server's, and derives the code from both
// keys and both nonces. An attacker now has to commit before it learns what it would need
// to aim at, which leaves it a one-in-a-million guess.
//
//   client -> {"t":"pair-commit","c": hex(sha256(CommitmentInput(clientNonce)))}
//   server -> {"t":"pair-nonce","n": hex(serverNonce)}
//   client -> {"t":"pair-reveal","n": hex(clientNonce)}   server checks it against "c"
//
// Both sides then show PairingCode(sha256(CodeInputV2(...))). Nonces are 32 bytes.

inline constexpr size_t kPairingNonceBytes = 32;

/** "arsivinyo-pairing-commit-v2\0" || client nonce. The caller hashes it. */
std::vector<uint8_t> CommitmentInput(const std::vector<uint8_t>& clientNonce);

/** "arsivinyo-pairing-code-v2\0" || CodeInput(keyA, keyB) || client nonce || server nonce. */
std::vector<uint8_t> CodeInputV2(const std::vector<uint8_t>& keyA, const std::vector<uint8_t>& keyB,
                                 const std::vector<uint8_t>& clientNonce,
                                 const std::vector<uint8_t>& serverNonce);

}  // namespace arsivinyo::pairing
