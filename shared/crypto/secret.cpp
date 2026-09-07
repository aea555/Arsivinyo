#include "secret.h"

#include <algorithm>

#include <openssl/crypto.h>
#include <openssl/rand.h>

namespace arsivinyo::crypto {

void SecureWipe(void *data, std::size_t length) {
    if (data == nullptr || length == 0) return;
    // OPENSSL_cleanse, not memset: a compiler is entitled to remove a memset whose result
    // is never read, and for a key buffer about to be freed that is exactly the case.
    OPENSSL_cleanse(data, length);
}

SecretBytes &SecretBytes::operator=(SecretBytes &&other) noexcept {
    if (this != &other) {
        clear();
        m_data = std::move(other.m_data);
        other.m_data.clear();
    }
    return *this;
}

void SecretBytes::assign(const uint8_t *source, std::size_t length) {
    clear();
    m_data.resize(length);
    if (length > 0 && source != nullptr) {
        std::copy(source, source + length, m_data.begin());
    }
}

void SecretBytes::clear() {
    if (!m_data.empty()) {
        SecureWipe(m_data.data(), m_data.size());
        m_data.clear();
    }
}

bool RandomBytes(std::size_t length, Bytes *out, std::string *error) {
    if (out == nullptr) return false;
    out->assign(length, 0);
    if (length == 0) return true;
    // A caller that ignores this failure ships a zero key, and the app works perfectly.
    if (RAND_bytes(out->data(), static_cast<int>(length)) != 1) {
        SecureWipe(out->data(), out->size());
        out->clear();
        if (error) *error = "the random number generator failed";
        return false;
    }
    return true;
}

bool ConstantTimeEquals(const uint8_t *a, std::size_t aLen, const uint8_t *b, std::size_t bLen) {
    if (aLen != bLen) return false;
    if (aLen == 0) return true;
    return CRYPTO_memcmp(a, b, aLen) == 0;
}

}  // namespace arsivinyo::crypto
