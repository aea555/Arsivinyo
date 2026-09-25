#include "include/arsivinyo_pairing.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <openssl/ec.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>

#include "wire.h"

using namespace arsivinyo::pairing;

namespace {

thread_local std::string g_error;

int fail(const std::string &message) {
    g_error = message;
    return 0;
}

std::string openSSLError(const char *what) {
    const unsigned long code = ERR_get_error();
    char buffer[256] = {0};
    if (code != 0) ERR_error_string_n(code, buffer, sizeof(buffer));
    ERR_clear_error();
    return code != 0 ? std::string(what) + ": " + buffer : std::string(what);
}

void sha256(const uint8_t *data, size_t length, uint8_t out[32]) {
    unsigned int written = 0;
    EVP_Digest(data, length, out, &written, EVP_sha256(), nullptr);
}

}  // namespace

const char *av_pair_last_error(void) { return g_error.c_str(); }

// MARK: - Wire format

size_t av_pair_encode_frame(uint8_t type, const uint8_t *payload, size_t length, uint8_t *out) {
    std::vector<uint8_t> encoded;
    if (!EncodeFrame(static_cast<FrameType>(type), std::vector<uint8_t>(payload, payload + length), &encoded)) {
        return 0;
    }
    std::memcpy(out, encoded.data(), encoded.size());
    return encoded.size();
}

int av_pair_decode_frame(const uint8_t *buffer, size_t length, uint8_t *type, size_t *consumed) {
    // The C++ takes a whole vector; only the header decides the verdict, so the copy is
    // capped at one frame's worth.
    const size_t take = std::min(length, static_cast<size_t>(kMaxFrameBytes) + 4);
    std::vector<uint8_t> view(buffer, buffer + take);
    FrameType frameType = FrameType::Control;
    std::vector<uint8_t> payload;
    size_t used = 0;
    switch (DecodeFrame(view, &frameType, &payload, &used)) {
        case DecodeResult::Ok:
            *type = static_cast<uint8_t>(frameType);
            *consumed = used;
            return 1;
        case DecodeResult::Incomplete: return 0;
        case DecodeResult::TooLarge: return -1;
        case DecodeResult::BadType: return -2;
    }
    return -2;
}

void av_pair_code_input(const uint8_t *keyA, const uint8_t *keyB, uint8_t out[64]) {
    const auto joined = CodeInput(std::vector<uint8_t>(keyA, keyA + 32), std::vector<uint8_t>(keyB, keyB + 32));
    std::memcpy(out, joined.data(), 64);
}

void av_pair_code(const uint8_t *digest, size_t length, char out[7]) {
    const std::string code = PairingCode(digest, length);
    std::memset(out, 0, 7);
    std::memcpy(out, code.data(), std::min<size_t>(code.size(), 6));
}

size_t av_pair_auth_transcript(uint8_t role, const uint8_t serverCertSha256[32],
                               const uint8_t clientCertSha256[32], uint8_t *out) {
    const auto transcript = AuthTranscript(static_cast<AuthRole>(role),
                                           std::vector<uint8_t>(serverCertSha256, serverCertSha256 + 32),
                                           std::vector<uint8_t>(clientCertSha256, clientCertSha256 + 32));
    std::memcpy(out, transcript.data(), transcript.size());
    return transcript.size();
}

size_t av_pair_commitment_input(const uint8_t nonce[32], uint8_t *out) {
    const auto input = CommitmentInput(std::vector<uint8_t>(nonce, nonce + 32));
    std::memcpy(out, input.data(), input.size());
    return input.size();
}

size_t av_pair_code_input_v2(const uint8_t *keyA, const uint8_t *keyB, const uint8_t clientNonce[32],
                             const uint8_t serverNonce[32], uint8_t *out) {
    const auto input = CodeInputV2(std::vector<uint8_t>(keyA, keyA + 32), std::vector<uint8_t>(keyB, keyB + 32),
                                   std::vector<uint8_t>(clientNonce, clientNonce + 32),
                                   std::vector<uint8_t>(serverNonce, serverNonce + 32));
    std::memcpy(out, input.data(), input.size());
    return input.size();
}

// MARK: - Ed25519

int av_ed25519_public_key(const uint8_t seed[32], uint8_t publicKey[32]) {
    EVP_PKEY *key = EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, nullptr, seed, 32);
    if (key == nullptr) return fail(openSSLError("could not load the key"));
    size_t length = 32;
    const bool ok = EVP_PKEY_get_raw_public_key(key, publicKey, &length) == 1 && length == 32;
    EVP_PKEY_free(key);
    return ok ? 1 : fail(openSSLError("could not derive the public key"));
}

int av_ed25519_sign(const uint8_t seed[32], const uint8_t *message, size_t length, uint8_t signature[64]) {
    EVP_PKEY *key = EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, nullptr, seed, 32);
    if (key == nullptr) return fail(openSSLError("could not load the key"));
    EVP_MD_CTX *context = EVP_MD_CTX_new();
    size_t written = 64;
    const bool ok = EVP_DigestSignInit(context, nullptr, nullptr, nullptr, key) == 1 &&
                    EVP_DigestSign(context, signature, &written, message, length) == 1 && written == 64;
    EVP_MD_CTX_free(context);
    EVP_PKEY_free(key);
    return ok ? 1 : fail(openSSLError("could not sign"));
}

int av_ed25519_verify(const uint8_t publicKey[32], const uint8_t *message, size_t length,
                      const uint8_t signature[64]) {
    EVP_PKEY *key = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, nullptr, publicKey, 32);
    if (key == nullptr) return 0;
    EVP_MD_CTX *context = EVP_MD_CTX_new();
    const bool ok = EVP_DigestVerifyInit(context, nullptr, nullptr, nullptr, key) == 1 &&
                    EVP_DigestVerify(context, signature, 64, message, length) == 1;
    EVP_MD_CTX_free(context);
    EVP_PKEY_free(key);
    ERR_clear_error();
    return ok ? 1 : 0;
}

// MARK: - TLS

struct av_tls_context {
    SSL_CTX *server = nullptr;
    SSL_CTX *client = nullptr;
};

struct av_tls_conn {
    SSL *ssl = nullptr;
    int fd = -1;
    std::mutex lock;
    std::atomic<bool> closed{false};
};

namespace {

/// Every certificate is accepted here. It says nothing about who the peer is: the Ed25519
/// signature over this connection's two certificates is what settles that, and a
/// connection that fails it is closed before a request is read.
int acceptAnyCertificate(int, X509_STORE_CTX *) { return 1; }

SSL_CTX *makeContext(const SSL_METHOD *method, EVP_PKEY *key, X509 *certificate, bool server) {
    SSL_CTX *context = SSL_CTX_new(method);
    if (context == nullptr) return nullptr;
    // 1.2 is the floor because some of the Android releases the phone supports stop there.
    SSL_CTX_set_min_proto_version(context, TLS1_2_VERSION);
    SSL_CTX_set_mode(context, SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER);
    if (SSL_CTX_use_certificate(context, certificate) != 1 || SSL_CTX_use_PrivateKey(context, key) != 1) {
        SSL_CTX_free(context);
        return nullptr;
    }
    // Both sides present a certificate, because the transcript names both.
    SSL_CTX_set_verify(context, server ? SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT : SSL_VERIFY_PEER,
                       acceptAnyCertificate);
    return context;
}

void setBlocking(int fd, bool blocking) {
    const int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, blocking ? (flags & ~O_NONBLOCK) : (flags | O_NONBLOCK));
}

void setTimeout(int fd, int seconds) {
    timeval tv{seconds, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

/// The handshake runs blocking, with a timeout so a peer that stalls it cannot hold a
/// thread forever. Afterwards the socket goes non-blocking, which is what lets one thread
/// read while another writes.
av_tls_conn *finishHandshake(SSL_CTX *context, int fd, bool server) {
    setTimeout(fd, 15);
    SSL *ssl = SSL_new(context);
    if (ssl == nullptr) {
        ::close(fd);
        fail(openSSLError("could not start TLS"));
        return nullptr;
    }
    SSL_set_fd(ssl, fd);
    const int result = server ? SSL_accept(ssl) : SSL_connect(ssl);
    if (result != 1) {
        fail(openSSLError("the TLS handshake failed"));
        SSL_free(ssl);
        ::close(fd);
        return nullptr;
    }
    setTimeout(fd, 0);
    setBlocking(fd, false);
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
    auto *conn = new av_tls_conn();
    conn->ssl = ssl;
    conn->fd = fd;
    return conn;
}

bool certificateSha256(X509 *certificate, uint8_t out[32]) {
    if (certificate == nullptr) return false;
    unsigned char *der = nullptr;
    const int length = i2d_X509(certificate, &der);
    if (length <= 0) return false;
    sha256(der, static_cast<size_t>(length), out);
    OPENSSL_free(der);
    return true;
}

}  // namespace

av_tls_context *av_tls_context_new(void) {
    EVP_PKEY *key = EVP_EC_gen("P-256");
    if (key == nullptr) {
        fail(openSSLError("could not make a session key"));
        return nullptr;
    }
    X509 *certificate = X509_new();
    X509_set_version(certificate, 2);
    uint8_t serial[8];
    RAND_bytes(serial, sizeof(serial));
    serial[0] &= 0x7f;
    BIGNUM *number = BN_bin2bn(serial, sizeof(serial), nullptr);
    BN_to_ASN1_INTEGER(number, X509_get_serialNumber(certificate));
    BN_free(number);
    X509_gmtime_adj(X509_getm_notBefore(certificate), -24 * 3600);
    X509_gmtime_adj(X509_getm_notAfter(certificate), 10L * 365 * 24 * 3600);
    X509_set_pubkey(certificate, key);
    X509_NAME *name = X509_get_subject_name(certificate);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, reinterpret_cast<const unsigned char *>("arsivinyo"), -1, -1, 0);
    X509_set_issuer_name(certificate, name);
    if (X509_sign(certificate, key, EVP_sha256()) == 0) {
        X509_free(certificate);
        EVP_PKEY_free(key);
        fail(openSSLError("could not sign the session certificate"));
        return nullptr;
    }

    auto *context = new av_tls_context();
    context->server = makeContext(TLS_server_method(), key, certificate, true);
    context->client = makeContext(TLS_client_method(), key, certificate, false);
    // The contexts hold their own references now.
    X509_free(certificate);
    EVP_PKEY_free(key);
    if (context->server == nullptr || context->client == nullptr) {
        av_tls_context_free(context);
        fail(openSSLError("could not set up TLS"));
        return nullptr;
    }
    return context;
}

void av_tls_context_free(av_tls_context *context) {
    if (context == nullptr) return;
    if (context->server) SSL_CTX_free(context->server);
    if (context->client) SSL_CTX_free(context->client);
    delete context;
}

int av_tls_listen(uint16_t port, uint16_t *boundPort) {
    const int fd = ::socket(AF_INET6, SOCK_STREAM, 0);
    if (fd < 0) return fail("could not open a socket"), -1;
    int one = 1;
    int zero = 0;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    // Dual stack: the phone reaches this over whichever family its resolver gave it.
    setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &zero, sizeof(zero));
    sockaddr_in6 address{};
    address.sin6_family = AF_INET6;
    address.sin6_addr = in6addr_any;
    address.sin6_port = htons(port);
    if (::bind(fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0 || ::listen(fd, 8) != 0) {
        ::close(fd);
        return fail("could not listen"), -1;
    }
    socklen_t length = sizeof(address);
    getsockname(fd, reinterpret_cast<sockaddr *>(&address), &length);
    *boundPort = ntohs(address.sin6_port);
    return fd;
}

av_tls_conn *av_tls_accept(av_tls_context *context, int listener) {
    while (true) {
        const int fd = ::accept(listener, nullptr, nullptr);
        if (fd < 0) {
            if (errno == EINTR) continue;
            fail("the listener closed");
            return nullptr;
        }
        return finishHandshake(context->server, fd, true);
    }
}

av_tls_conn *av_tls_connect(av_tls_context *context, const char *host, uint16_t port, int timeoutSeconds) {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo *found = nullptr;
    const std::string service = std::to_string(port);
    if (getaddrinfo(host, service.c_str(), &hints, &found) != 0 || found == nullptr) {
        fail("could not find that device");
        return nullptr;
    }
    int fd = -1;
    for (addrinfo *candidate = found; candidate != nullptr; candidate = candidate->ai_next) {
        fd = ::socket(candidate->ai_family, candidate->ai_socktype, candidate->ai_protocol);
        if (fd < 0) continue;
        // A connect that nobody answers would otherwise take the system's minute or more.
        setBlocking(fd, false);
        int result = ::connect(fd, candidate->ai_addr, candidate->ai_addrlen);
        if (result != 0 && errno == EINPROGRESS) {
            pollfd waiting{fd, POLLOUT, 0};
            int error = 0;
            socklen_t size = sizeof(error);
            result = (::poll(&waiting, 1, timeoutSeconds * 1000) == 1 &&
                      getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) == 0 && error == 0) ? 0 : -1;
        }
        if (result == 0) {
            setBlocking(fd, true);
            break;
        }
        ::close(fd);
        fd = -1;
    }
    freeaddrinfo(found);
    if (fd < 0) {
        fail("could not reach that device");
        return nullptr;
    }
    return finishHandshake(context->client, fd, false);
}

int av_tls_local_cert_sha256(av_tls_conn *conn, uint8_t out[32]) {
    return certificateSha256(SSL_get_certificate(conn->ssl), out) ? 1 : fail("no local certificate");
}

int av_tls_peer_cert_sha256(av_tls_conn *conn, uint8_t out[32]) {
    X509 *peer = SSL_get1_peer_certificate(conn->ssl);
    const bool ok = certificateSha256(peer, out);
    if (peer) X509_free(peer);
    return ok ? 1 : fail("the peer presented no certificate");
}

int av_tls_peer_address(av_tls_conn *conn, char *out, size_t capacity) {
    sockaddr_storage address{};
    socklen_t length = sizeof(address);
    if (getpeername(conn->fd, reinterpret_cast<sockaddr *>(&address), &length) != 0) return 0;
    char host[INET6_ADDRSTRLEN] = {0};
    uint16_t port = 0;
    if (address.ss_family == AF_INET6) {
        auto *six = reinterpret_cast<sockaddr_in6 *>(&address);
        // An IPv4 peer on the dual-stack listener shows up as ::ffff:a.b.c.d.
        if (IN6_IS_ADDR_V4MAPPED(&six->sin6_addr)) {
            inet_ntop(AF_INET, &six->sin6_addr.s6_addr[12], host, sizeof(host));
        } else {
            inet_ntop(AF_INET6, &six->sin6_addr, host, sizeof(host));
        }
        port = ntohs(six->sin6_port);
    } else {
        auto *four = reinterpret_cast<sockaddr_in *>(&address);
        inet_ntop(AF_INET, &four->sin_addr, host, sizeof(host));
        port = ntohs(four->sin_port);
    }
    snprintf(out, capacity, "%s:%u", host, port);
    return 1;
}

long av_tls_read(av_tls_conn *conn, uint8_t *out, size_t capacity) {
    while (!conn->closed.load()) {
        int result;
        int error;
        {
            std::lock_guard<std::mutex> guard(conn->lock);
            result = SSL_read(conn->ssl, out, static_cast<int>(capacity));
            error = result > 0 ? SSL_ERROR_NONE : SSL_get_error(conn->ssl, result);
        }
        if (result > 0) return result;
        if (error == SSL_ERROR_ZERO_RETURN) return 0;
        if (error != SSL_ERROR_WANT_READ && error != SSL_ERROR_WANT_WRITE) {
            ERR_clear_error();
            return -1;
        }
        // Wait outside the lock, so a writer is never held up by a reader waiting for data.
        pollfd waiting{conn->fd, static_cast<short>(error == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT), 0};
        ::poll(&waiting, 1, 250);
        if (waiting.revents & (POLLHUP | POLLERR | POLLNVAL)) {
            if (!(waiting.revents & POLLIN)) return 0;
        }
    }
    return 0;
}

int av_tls_write(av_tls_conn *conn, const uint8_t *data, size_t length) {
    size_t sent = 0;
    while (sent < length) {
        if (conn->closed.load()) return fail("the connection closed");
        int result;
        int error;
        {
            std::lock_guard<std::mutex> guard(conn->lock);
            result = SSL_write(conn->ssl, data + sent, static_cast<int>(length - sent));
            error = result > 0 ? SSL_ERROR_NONE : SSL_get_error(conn->ssl, result);
        }
        if (result > 0) {
            sent += static_cast<size_t>(result);
            continue;
        }
        if (error != SSL_ERROR_WANT_READ && error != SSL_ERROR_WANT_WRITE) {
            ERR_clear_error();
            return fail("the connection went away");
        }
        pollfd waiting{conn->fd, static_cast<short>(error == SSL_ERROR_WANT_WRITE ? POLLOUT : POLLIN), 0};
        ::poll(&waiting, 1, 250);
    }
    return 1;
}

void av_tls_shutdown(av_tls_conn *conn) {
    if (conn->closed.exchange(true)) return;
    {
        std::lock_guard<std::mutex> guard(conn->lock);
        SSL_shutdown(conn->ssl);
    }
    ::shutdown(conn->fd, SHUT_RDWR);
}

void av_tls_free(av_tls_conn *conn) {
    if (conn == nullptr) return;
    av_tls_shutdown(conn);
    SSL_free(conn->ssl);
    ::close(conn->fd);
    delete conn;
}

void av_tls_close_listener(int listener) {
    ::shutdown(listener, SHUT_RDWR);
    ::close(listener);
}
