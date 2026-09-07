#include "argon2.h"

#include <openssl/core_names.h>
#include <openssl/kdf.h>
#include <openssl/params.h>

namespace arsivinyo::crypto {

namespace {
constexpr uint32_t kMinMemoryKiB = 8192;
constexpr uint32_t kMaxMemoryKiB = 1048576;
constexpr uint32_t kMinIterations = 1;
constexpr uint32_t kMaxIterations = 16;
constexpr uint32_t kMinParallelism = 1;
constexpr uint32_t kMaxParallelism = 16;
}  // namespace

bool Argon2idParams::Validate(std::string *error) const {
    const auto fail = [error](const char *why) {
        if (error) *error = why;
        return false;
    };
    if (version != 0x13) return fail("unsupported argon2 version");
    if (memoryKiB < kMinMemoryKiB || memoryKiB > kMaxMemoryKiB) return fail("argon2 memory cost out of range");
    if (iterations < kMinIterations || iterations > kMaxIterations) return fail("argon2 iterations out of range");
    if (parallelism < kMinParallelism || parallelism > kMaxParallelism) return fail("argon2 parallelism out of range");
    return true;
}

bool DeriveArgon2id(const uint8_t *secret, std::size_t secretLength, const uint8_t *salt,
                    std::size_t saltLength, const Argon2idParams &params,
                    std::size_t outLength, SecretBytes *out, std::string *error) {
    if (out == nullptr) return false;
    if (!params.Validate(error)) return false;
    if (saltLength < 16) {
        if (error) *error = "argon2 salt is too short";
        return false;
    }
    if (outLength == 0) {
        if (error) *error = "argon2 output length must be positive";
        return false;
    }

    EVP_KDF *kdf = EVP_KDF_fetch(nullptr, "ARGON2ID", nullptr);
    if (kdf == nullptr) {
        if (error) *error = "this build of OpenSSL has no Argon2id";
        return false;
    }
    EVP_KDF_CTX *ctx = EVP_KDF_CTX_new(kdf);
    EVP_KDF_free(kdf);
    if (ctx == nullptr) {
        if (error) *error = "could not create the Argon2id context";
        return false;
    }

    // An empty password is legal, and its Argon2 output is well defined. But an empty
    // std::vector yields a null data() pointer, and OSSL_PARAM rejects a null octet string —
    // so the password would be dropped rather than passed as zero bytes. Point at something.
    static const uint8_t kEmpty[1] = {0};
    if (secret == nullptr) secret = kEmpty;
    if (salt == nullptr) salt = kEmpty;

    uint32_t threads = 1;
    uint32_t iterations = params.iterations;
    uint32_t memoryKiB = params.memoryKiB;
    uint32_t lanes = params.parallelism;
    uint32_t version = params.version;

    // `threads` is set explicitly rather than left to default. The output is defined by
    // `lanes`; threads only decides whether OpenSSL computes the lanes in parallel, and it
    // refuses when threads exceed what the library context can supply. One thread is what
    // was measured byte-identical to BouncyCastle, which is single-threaded regardless.
    const OSSL_PARAM osslParams[] = {
        OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_PASSWORD,
                                          const_cast<uint8_t *>(secret), secretLength),
        OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_SALT, const_cast<uint8_t *>(salt),
                                          saltLength),
        OSSL_PARAM_construct_uint32(OSSL_KDF_PARAM_ITER, &iterations),
        OSSL_PARAM_construct_uint32(OSSL_KDF_PARAM_ARGON2_MEMCOST, &memoryKiB),
        OSSL_PARAM_construct_uint32(OSSL_KDF_PARAM_ARGON2_LANES, &lanes),
        OSSL_PARAM_construct_uint32(OSSL_KDF_PARAM_THREADS, &threads),
        OSSL_PARAM_construct_uint32(OSSL_KDF_PARAM_ARGON2_VERSION, &version),
        OSSL_PARAM_construct_end(),
    };

    SecretBytes derived(outLength);
    const int rc = EVP_KDF_derive(ctx, derived.data(), outLength, osslParams);
    EVP_KDF_CTX_free(ctx);
    if (rc != 1) {
        if (error) *error = "Argon2id derivation failed";
        return false;
    }
    *out = std::move(derived);
    return true;
}

}  // namespace arsivinyo::crypto
