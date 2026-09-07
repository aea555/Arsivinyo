#pragma once

// One-shot AES-256-GCM. The streaming AEAD uses it per segment with no associated data;
// the keybox uses it with the slot id as associated data, so one slot's wrapped key pasted
// into another slot's entry fails the tag rather than silently unwrapping.

#include <cstddef>
#include <cstdint>
#include <string>

namespace arsivinyo::crypto {

inline constexpr std::size_t kGcmNonceBytes = 12;
inline constexpr std::size_t kGcmTagBytes = 16;

/** Writes `plainLength + 16` bytes to `out`: the ciphertext followed by the tag. */
bool GcmSeal(const uint8_t *key, const uint8_t nonce[kGcmNonceBytes], const uint8_t *aad,
             std::size_t aadLength, const uint8_t *plain, std::size_t plainLength, uint8_t *out,
             std::string *error);

/** Reads `cipherLength` bytes (ciphertext then tag), writes `cipherLength - 16` to `out`. */
bool GcmOpen(const uint8_t *key, const uint8_t nonce[kGcmNonceBytes], const uint8_t *aad,
             std::size_t aadLength, const uint8_t *cipher, std::size_t cipherLength,
             uint8_t *out, std::string *error);

}  // namespace arsivinyo::crypto
