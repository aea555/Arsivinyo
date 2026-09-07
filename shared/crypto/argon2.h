#pragma once

// Argon2id, the password-to-key step.
//
// This is the single most important choice in the whole scheme. A password is not a key;
// turning one into a key must be deliberately slow, or a short password falls to offline
// brute force no matter how good the cipher is.
//
// The parameters mirror `BackupCrypto.KdfParams` field for field, including its validation
// ranges, so a header written by either implementation is accepted or rejected identically.
// OpenSSL 3.5's ARGON2ID was checked byte-for-byte against BouncyCastle 1.79 at the shipped
// profile before this file was written.

#include <cstdint>
#include <string>

#include "secret.h"

namespace arsivinyo::crypto {

struct Argon2idParams {
    uint32_t version = 0x13;      // ARGON2_VERSION_13
    uint32_t memoryKiB = 65536;   // 64 MiB
    uint32_t iterations = 3;
    uint32_t parallelism = 4;

    /** Cheap parameters for tests. Never reachable from the app — there is no fast switch. */
    static Argon2idParams Fast() { return Argon2idParams{0x13, 8192, 1, 4}; }

    /**
     * The same bounds BackupCrypto.KdfParams.validate enforces. A backup header is
     * attacker-supplied, and an unchecked memory cost is a request to allocate a terabyte.
     */
    bool Validate(std::string *error) const;
};

/**
 * Derive `outLength` bytes from a secret.
 *
 * `secret` must already be UTF-8. Kotlin hands BouncyCastle a CharArray which it encodes as
 * UTF-8 internally; anything else here — UTF-16, Latin-1, a trimmed trailing space — yields
 * a different key and presents in the field as "my backup will not open on the other device".
 */
bool DeriveArgon2id(const uint8_t *secret, std::size_t secretLength, const uint8_t *salt,
                    std::size_t saltLength, const Argon2idParams &params,
                    std::size_t outLength, SecretBytes *out, std::string *error);

}  // namespace arsivinyo::crypto
