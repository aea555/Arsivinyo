#pragma once

// The keybox: a standalone keystore that depends on no operating-system API.
//
// A keystore is a container, not a source of secrecy. What a platform keystore actually
// contributes is a root secret the app cannot be tricked into surrendering — a TEE key on
// Android, a login-derived key in a desktop keyring. This file holds and organises keys just
// as well; what it cannot do is *be* a secret. So the master key is wrapped by pluggable
// slots, and each platform uses the strongest root it actually has:
//
//   desktop   passphrase        Argon2id-derived key-encryption key
//   desktop   keyfile           a random key in an owner-only file — convenience, not secrecy
//   Android   platform-keystore the existing non-exportable Keystore key
//
// The master key is 32 random bytes generated once and never derived from a passphrase.
// That is what makes adding a slot and changing a passphrase cheap: both re-wrap 32 bytes
// and touch no content. It is also why removing the last slot has to be refused — there
// would be nothing left that can unwrap it, and every encrypted file would be lost.
//
// A `platform-keystore` slot's wrapped blob is produced outside this file entirely, so the
// Slot record keeps it opaque and Android needs no format change.

#include <cstdint>
#include <string>
#include <vector>

#include "argon2.h"
#include "secret.h"

namespace arsivinyo::crypto {

enum class SlotKind { Passphrase, Keyfile, PlatformKeystore, Recovery };

std::string SlotKindName(SlotKind kind);
bool SlotKindFromName(const std::string &name, SlotKind *out);

struct Slot {
    std::string id;
    SlotKind kind = SlotKind::Passphrase;
    Argon2idParams kdf;     // meaningful for Passphrase and Recovery
    Bytes salt;             // 16 bytes
    Bytes verifier;         // 32 bytes
    Bytes wrapped;          // nonce(12) || ciphertext(32) || tag(16)
};

enum class UnwrapResult { Ok, WrongSecret, Damaged };

/** 32 fresh random bytes. Generated once for the life of a keybox. */
bool NewMasterKey(SecretBytes *out, std::string *error);

/** Argon2id over the passphrase. `salt` must already be 16 bytes. */
bool PassphraseKek(const std::string &passphrase, const Bytes &salt, const Argon2idParams &params,
                   SecretBytes *out, std::string *error);

/**
 * HKDF over a key file's contents. The file alone is not the key-encryption key: rotating a
 * slot's salt kills that slot without touching the file.
 */
bool KeyfileKek(const Bytes &keyfile, const Bytes &salt, SecretBytes *out, std::string *error);

/** Wraps `masterKey` under `kek`, filling `slot`'s salt, verifier and wrapped fields. */
bool WrapMasterKey(const SecretBytes &kek, const SecretBytes &masterKey, Slot *slot,
                   std::string *error);

/**
 * Unwraps into `masterKey`.
 *
 * WrongSecret and Damaged are deliberately distinct: the verifier says the key-encryption key
 * is wrong before the wrapped blob is touched, so a mistyped passphrase is reported as such
 * rather than as corruption.
 */
UnwrapResult UnwrapMasterKey(const SecretBytes &kek, const Slot &slot, SecretBytes *masterKey,
                             std::string *error);

}  // namespace arsivinyo::crypto
