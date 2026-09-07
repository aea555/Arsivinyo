#pragma once

// The key hierarchy, as named functions rather than inline expressions.
//
// The labels below are wire format. A missing trailing slash in the section prefix, or a
// changed word anywhere, is invisible inside one implementation and fails only against the
// other — which is precisely the class of bug `TranscriptOrder` in shared/pairing/wire.h was
// extracted to prevent, and precisely why these are pinned in VECTORS.json.

#include <string>

#include "secret.h"

namespace arsivinyo::crypto {

// The .avsbck container's hierarchy. These must match BackupCrypto.kt exactly.
inline constexpr char kInfoBackupVerify[] = "avsbck/verify/v1";
inline constexpr char kInfoBackupSectionPrefix[] = "avsbck/section/v1/";

// The keybox's own hierarchy. Two subkeys from one key-encryption key, so the stored
// verifier can never act as an oracle on the key that actually unwraps the master key.
inline constexpr char kInfoKeyboxVerify[] = "arsivinyo/keybox/verify/v1";
inline constexpr char kInfoKeyboxWrap[] = "arsivinyo/keybox/wrap/v1";
inline constexpr char kInfoKeyboxKeyfile[] = "arsivinyo/keybox/keyfile/v1";
inline constexpr char kKeyboxWrapAadPrefix[] = "arsivinyo/keybox/v1/";

// What the master key is spent on. A leaked cookie key must not open the vault.
inline constexpr char kPurposeCookies[] = "cookies";
inline constexpr char kPurposeVault[] = "vault";
inline constexpr char kPurposeVaultIndex[] = "vault-index";
inline constexpr char kPurposeThumbs[] = "thumbs";

/** HKDF-SHA256(masterKey, info = "avsbck/verify/v1", 32). Compare in constant time. */
bool BackupVerifier(const SecretBytes &masterKey, SecretBytes *out, std::string *error);

/** HKDF-SHA256(masterKey, info = "avsbck/section/v1/<sectionId>", 32). */
bool BackupSectionKey(const SecretBytes &masterKey, const std::string &sectionId,
                      SecretBytes *out, std::string *error);

/** HKDF-SHA256(masterKey, info = "arsivinyo/key/v1/<purpose>", 32). */
bool PurposeKey(const SecretBytes &masterKey, const std::string &purpose, SecretBytes *out,
                std::string *error);

}  // namespace arsivinyo::crypto
