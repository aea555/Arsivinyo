#include "hkdf.h"

#include <vector>

#include <openssl/core_names.h>
#include <openssl/kdf.h>
#include <openssl/params.h>

namespace arsivinyo::crypto {

namespace {
constexpr std::size_t kSha256Bytes = 32;
}

bool HkdfSha256(const uint8_t *ikm, std::size_t ikmLength, const uint8_t *salt,
                std::size_t saltLength, const uint8_t *info, std::size_t infoLength,
                std::size_t outLength, SecretBytes *out, std::string *error) {
    if (out == nullptr) return false;
    if (outLength == 0) {
        if (error) *error = "hkdf output length must be positive";
        return false;
    }

    EVP_KDF *kdf = EVP_KDF_fetch(nullptr, "HKDF", nullptr);
    if (kdf == nullptr) {
        if (error) *error = "this build of OpenSSL has no HKDF";
        return false;
    }
    EVP_KDF_CTX *ctx = EVP_KDF_CTX_new(kdf);
    EVP_KDF_free(kdf);
    if (ctx == nullptr) {
        if (error) *error = "could not create the HKDF context";
        return false;
    }

    // Tink's null salt is a zero-filled block of the digest length. Passing those bytes
    // explicitly says so in the code rather than relying on two libraries defaulting alike.
    const std::vector<uint8_t> zeroSalt(kSha256Bytes, 0);
    const uint8_t *effectiveSalt = (salt != nullptr && saltLength > 0) ? salt : zeroSalt.data();
    const std::size_t effectiveSaltLength =
        (salt != nullptr && saltLength > 0) ? saltLength : zeroSalt.size();

    // Same null-pointer trap as argon2.cpp: an empty info string is legal, a null one is not
    // a parameter at all.
    static const uint8_t kEmpty[1] = {0};
    if (ikm == nullptr) ikm = kEmpty;
    if (info == nullptr) info = kEmpty;

    char digest[] = "SHA256";
    const OSSL_PARAM osslParams[] = {
        OSSL_PARAM_construct_utf8_string(OSSL_KDF_PARAM_DIGEST, digest, 0),
        OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_KEY, const_cast<uint8_t *>(ikm),
                                          ikmLength),
        OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_SALT,
                                          const_cast<uint8_t *>(effectiveSalt),
                                          effectiveSaltLength),
        OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_INFO, const_cast<uint8_t *>(info),
                                          infoLength),
        OSSL_PARAM_construct_end(),
    };

    SecretBytes derived(outLength);
    const int rc = EVP_KDF_derive(ctx, derived.data(), outLength, osslParams);
    EVP_KDF_CTX_free(ctx);
    if (rc != 1) {
        if (error) *error = "HKDF derivation failed";
        return false;
    }
    *out = std::move(derived);
    return true;
}

bool HkdfSha256Label(const SecretBytes &ikm, const std::string &label, std::size_t outLength,
                     SecretBytes *out, std::string *error) {
    return HkdfSha256(ikm.data(), ikm.size(), nullptr, 0,
                      reinterpret_cast<const uint8_t *>(label.data()), label.size(), outLength,
                      out, error);
}

}  // namespace arsivinyo::crypto
