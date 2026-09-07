#include "keybox.h"

#include <cstring>

#include "gcm.h"
#include "hkdf.h"
#include "subkeys.h"

namespace arsivinyo::crypto {

namespace {

constexpr std::size_t kMasterKeyBytes = 32;
constexpr std::size_t kSaltBytes = 16;
constexpr std::size_t kVerifierBytes = 32;
constexpr std::size_t kWrappedBytes = kGcmNonceBytes + kMasterKeyBytes + kGcmTagBytes;

/** Two independent subkeys from one key-encryption key. */
bool SplitKek(const SecretBytes &kek, SecretBytes *verifyKey, SecretBytes *wrapKey,
              std::string *error) {
    if (!HkdfSha256Label(kek, kInfoKeyboxVerify, kVerifierBytes, verifyKey, error)) return false;
    return HkdfSha256Label(kek, kInfoKeyboxWrap, kMasterKeyBytes, wrapKey, error);
}

std::string WrapAad(const std::string &slotId) { return std::string(kKeyboxWrapAadPrefix) + slotId; }

}  // namespace

std::string SlotKindName(SlotKind kind) {
    switch (kind) {
        case SlotKind::Passphrase: return "passphrase";
        case SlotKind::Keyfile: return "keyfile";
        case SlotKind::PlatformKeystore: return "platform-keystore";
        case SlotKind::Recovery: return "recovery";
    }
    return "passphrase";
}

bool SlotKindFromName(const std::string &name, SlotKind *out) {
    if (out == nullptr) return false;
    if (name == "passphrase") { *out = SlotKind::Passphrase; return true; }
    if (name == "keyfile") { *out = SlotKind::Keyfile; return true; }
    if (name == "platform-keystore") { *out = SlotKind::PlatformKeystore; return true; }
    if (name == "recovery") { *out = SlotKind::Recovery; return true; }
    return false;
}

bool NewMasterKey(SecretBytes *out, std::string *error) {
    Bytes raw;
    if (!RandomBytes(kMasterKeyBytes, &raw, error)) return false;
    out->assign(raw.data(), raw.size());
    SecureWipe(raw.data(), raw.size());
    return true;
}

bool PassphraseKek(const std::string &passphrase, const Bytes &salt,
                   const Argon2idParams &params, SecretBytes *out, std::string *error) {
    // The passphrase must already be UTF-8. Kotlin hands BouncyCastle a CharArray which it
    // encodes as UTF-8; anything else here derives a different key.
    return DeriveArgon2id(reinterpret_cast<const uint8_t *>(passphrase.data()), passphrase.size(),
                          salt.data(), salt.size(), params, kMasterKeyBytes, out, error);
}

bool KeyfileKek(const Bytes &keyfile, const Bytes &salt, SecretBytes *out, std::string *error) {
    if (keyfile.size() < 32) {
        if (error) *error = "the key file is too short";
        return false;
    }
    return HkdfSha256(keyfile.data(), keyfile.size(), salt.data(), salt.size(),
                      reinterpret_cast<const uint8_t *>(kInfoKeyboxKeyfile),
                      std::strlen(kInfoKeyboxKeyfile), kMasterKeyBytes, out, error);
}

bool WrapMasterKey(const SecretBytes &kek, const SecretBytes &masterKey, Slot *slot,
                   std::string *error) {
    if (slot == nullptr) return false;
    if (masterKey.size() != kMasterKeyBytes) {
        if (error) *error = "a master key must be 32 bytes";
        return false;
    }
    SecretBytes verifyKey;
    SecretBytes wrapKey;
    if (!SplitKek(kek, &verifyKey, &wrapKey, error)) return false;

    Bytes nonce;
    if (!RandomBytes(kGcmNonceBytes, &nonce, error)) return false;

    const std::string aad = WrapAad(slot->id);
    Bytes wrapped(kWrappedBytes);
    std::memcpy(wrapped.data(), nonce.data(), kGcmNonceBytes);
    if (!GcmSeal(wrapKey.data(), nonce.data(), reinterpret_cast<const uint8_t *>(aad.data()),
                 aad.size(), masterKey.data(), masterKey.size(), wrapped.data() + kGcmNonceBytes,
                 error)) {
        return false;
    }

    slot->verifier.assign(verifyKey.data(), verifyKey.data() + verifyKey.size());
    slot->wrapped = std::move(wrapped);
    return true;
}

UnwrapResult UnwrapMasterKey(const SecretBytes &kek, const Slot &slot, SecretBytes *masterKey,
                             std::string *error) {
    if (masterKey == nullptr) return UnwrapResult::Damaged;
    SecretBytes verifyKey;
    SecretBytes wrapKey;
    if (!SplitKek(kek, &verifyKey, &wrapKey, error)) return UnwrapResult::Damaged;

    // Checked first, and in constant time, so a mistyped passphrase is reported as a wrong
    // passphrase rather than as a damaged keybox.
    if (!ConstantTimeEquals(verifyKey.data(), verifyKey.size(), slot.verifier.data(),
                            slot.verifier.size())) {
        if (error) *error = "that passphrase is not correct";
        return UnwrapResult::WrongSecret;
    }
    if (slot.wrapped.size() != kWrappedBytes) {
        if (error) *error = "the stored key is the wrong size";
        return UnwrapResult::Damaged;
    }

    const std::string aad = WrapAad(slot.id);
    SecretBytes recovered(kMasterKeyBytes);
    if (!GcmOpen(wrapKey.data(), slot.wrapped.data(),
                 reinterpret_cast<const uint8_t *>(aad.data()), aad.size(),
                 slot.wrapped.data() + kGcmNonceBytes, kMasterKeyBytes + kGcmTagBytes,
                 recovered.data(), error)) {
        return UnwrapResult::Damaged;
    }
    *masterKey = std::move(recovered);
    return UnwrapResult::Ok;
}

}  // namespace arsivinyo::crypto
