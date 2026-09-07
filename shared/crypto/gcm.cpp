#include "gcm.h"

#include <openssl/evp.h>

namespace arsivinyo::crypto {

bool GcmSeal(const uint8_t *key, const uint8_t nonce[kGcmNonceBytes], const uint8_t *aad,
             std::size_t aadLength, const uint8_t *plain, std::size_t plainLength, uint8_t *out,
             std::string *error) {
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (ctx == nullptr) {
        if (error) *error = "could not create a cipher context";
        return false;
    }
    bool ok = EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) == 1 &&
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN,
                                  static_cast<int>(kGcmNonceBytes), nullptr) == 1 &&
              EVP_EncryptInit_ex(ctx, nullptr, nullptr, key, nonce) == 1;
    int written = 0;
    if (ok && aad != nullptr && aadLength > 0) {
        ok = EVP_EncryptUpdate(ctx, nullptr, &written, aad, static_cast<int>(aadLength)) == 1;
    }
    written = 0;
    if (ok && plainLength > 0) {
        ok = EVP_EncryptUpdate(ctx, out, &written, plain, static_cast<int>(plainLength)) == 1;
    }
    int finalWritten = 0;
    if (ok) ok = EVP_EncryptFinal_ex(ctx, out + written, &finalWritten) == 1;
    if (ok) {
        ok = EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_GET_TAG, static_cast<int>(kGcmTagBytes),
                                 out + plainLength) == 1;
    }
    EVP_CIPHER_CTX_free(ctx);
    if (!ok && error) *error = "encryption failed";
    return ok;
}

bool GcmOpen(const uint8_t *key, const uint8_t nonce[kGcmNonceBytes], const uint8_t *aad,
             std::size_t aadLength, const uint8_t *cipher, std::size_t cipherLength,
             uint8_t *out, std::string *error) {
    if (cipherLength < kGcmTagBytes) {
        if (error) *error = "too short to hold a tag";
        return false;
    }
    const std::size_t plainLength = cipherLength - kGcmTagBytes;
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (ctx == nullptr) {
        if (error) *error = "could not create a cipher context";
        return false;
    }
    bool ok = EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) == 1 &&
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN,
                                  static_cast<int>(kGcmNonceBytes), nullptr) == 1 &&
              EVP_DecryptInit_ex(ctx, nullptr, nullptr, key, nonce) == 1;
    int written = 0;
    if (ok && aad != nullptr && aadLength > 0) {
        ok = EVP_DecryptUpdate(ctx, nullptr, &written, aad, static_cast<int>(aadLength)) == 1;
    }
    written = 0;
    if (ok && plainLength > 0) {
        ok = EVP_DecryptUpdate(ctx, out, &written, cipher, static_cast<int>(plainLength)) == 1;
    }
    if (ok) {
        ok = EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, static_cast<int>(kGcmTagBytes),
                                 const_cast<uint8_t *>(cipher + plainLength)) == 1;
    }
    int finalWritten = 0;
    // A failure here is an authentication failure: damaged data, or the wrong key.
    if (ok) ok = EVP_DecryptFinal_ex(ctx, out + written, &finalWritten) == 1;
    EVP_CIPHER_CTX_free(ctx);
    if (!ok && error) *error = "the data is damaged or was encrypted with a different key";
    return ok;
}

}  // namespace arsivinyo::crypto
