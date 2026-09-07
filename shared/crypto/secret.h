#pragma once

// Key material handling: allocation that wipes itself, and randomness.
//
// This directory is deliberately free of Qt, of Android, and of any framework: it is the
// part both apps must agree on byte for byte. The only dependency is OpenSSL's libcrypto,
// which the desktop already links for Ed25519 and certificate work.
//
// Everything here is measured against the Kotlin implementation through VECTORS.json.
// An implementation can be perfectly self-consistent and still disagree with the other
// end, and here a disagreement means unreadable data rather than a failed handshake.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace arsivinyo::crypto {

using Bytes = std::vector<uint8_t>;

/** Overwrite a buffer so a key does not outlive its use in freed memory. */
void SecureWipe(void *data, std::size_t length);

/**
 * A byte buffer that wipes itself when it goes out of scope.
 *
 * Copying is deliberately disabled: a copied key is a second thing to wipe, and forgetting
 * one is silent. Move it instead.
 */
class SecretBytes {
 public:
    SecretBytes() = default;
    explicit SecretBytes(std::size_t length) : m_data(length, 0) {}
    explicit SecretBytes(Bytes data) : m_data(std::move(data)) {}
    ~SecretBytes() { clear(); }

    SecretBytes(const SecretBytes &) = delete;
    SecretBytes &operator=(const SecretBytes &) = delete;
    SecretBytes(SecretBytes &&other) noexcept : m_data(std::move(other.m_data)) { other.m_data.clear(); }
    SecretBytes &operator=(SecretBytes &&other) noexcept;

    uint8_t *data() { return m_data.data(); }
    const uint8_t *data() const { return m_data.data(); }
    std::size_t size() const { return m_data.size(); }
    bool empty() const { return m_data.empty(); }
    const Bytes &bytes() const { return m_data; }

    void assign(const uint8_t *source, std::size_t length);
    void clear();

 private:
    Bytes m_data;
};

/** Cryptographically secure random bytes. Returns false if the RNG failed — never silently. */
bool RandomBytes(std::size_t length, Bytes *out, std::string *error);

/** Constant-time comparison, for verifiers and tokens. */
bool ConstantTimeEquals(const uint8_t *a, std::size_t aLen, const uint8_t *b, std::size_t bLen);

}  // namespace arsivinyo::crypto
