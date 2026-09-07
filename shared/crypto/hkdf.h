#pragma once

// HKDF-SHA256, the key-hierarchy step.
//
// Tink's `Hkdf.computeHkdf(alg, ikm, null, info, L)` substitutes a zero-filled salt of the
// hash length when the salt is null, and OpenSSL's HKDF defaults to the same. That
// equivalence was checked against the real "avsbck/verify/v1" label before this was written,
// and it is pinned in VECTORS.json, because if the two ever diverged every subkey would.

#include <cstddef>
#include <cstdint>
#include <string>

#include "secret.h"

namespace arsivinyo::crypto {

/** `salt` may be null, which means Tink's null: a zero-filled block of the digest length. */
bool HkdfSha256(const uint8_t *ikm, std::size_t ikmLength, const uint8_t *salt,
                std::size_t saltLength, const uint8_t *info, std::size_t infoLength,
                std::size_t outLength, SecretBytes *out, std::string *error);

/** Convenience for the common "derive 32 bytes under a string label" case. */
bool HkdfSha256Label(const SecretBytes &ikm, const std::string &label, std::size_t outLength,
                     SecretBytes *out, std::string *error);

}  // namespace arsivinyo::crypto
