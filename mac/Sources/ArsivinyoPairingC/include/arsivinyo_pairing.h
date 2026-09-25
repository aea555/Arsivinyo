#pragma once

// A C boundary for pairing: the wire format from shared/pairing (the same C++ the vectors
// pin), Ed25519, and TLS over OpenSSL.
//
// TLS is OpenSSL's rather than Network.framework's because the session certificate has to
// be made in memory, per process, and never stored. Network.framework only takes an
// identity from the keychain, and putting one there would leave a second private key at
// rest, which the protocol rules out.

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// The last failure on this thread.
const char *av_pair_last_error(void);

// MARK: - Wire format (shared/pairing/wire.cpp)

/// Encodes one frame. Returns its length, or 0 if the payload is over the cap. `out` must
/// hold `length + 5` bytes.
size_t av_pair_encode_frame(uint8_t type, const uint8_t *payload, size_t length, uint8_t *out);
/// 1 frame decoded, 0 incomplete, -1 too large, -2 bad type. On 1, the payload is
/// `buffer[5 .. consumed)`.
int av_pair_decode_frame(const uint8_t *buffer, size_t length, uint8_t *type, size_t *consumed);
/// The two keys sorted and joined: 64 bytes into `out`.
void av_pair_code_input(const uint8_t *keyA, const uint8_t *keyB, uint8_t out[64]);
/// Six digits and a NUL from a digest.
void av_pair_code(const uint8_t *digest, size_t length, char out[7]);
/// The auth transcript: 26 + 32 + 32 + 1 bytes into `out`. Returns its length.
size_t av_pair_auth_transcript(uint8_t role, const uint8_t serverCertSha256[32],
                               const uint8_t clientCertSha256[32], uint8_t *out);
/// Pairing v2. Each returns the length written.
size_t av_pair_commitment_input(const uint8_t nonce[32], uint8_t *out);
size_t av_pair_code_input_v2(const uint8_t *keyA, const uint8_t *keyB, const uint8_t clientNonce[32],
                             const uint8_t serverNonce[32], uint8_t *out);

// MARK: - Ed25519

int av_ed25519_public_key(const uint8_t seed[32], uint8_t publicKey[32]);
int av_ed25519_sign(const uint8_t seed[32], const uint8_t *message, size_t length, uint8_t signature[64]);
int av_ed25519_verify(const uint8_t publicKey[32], const uint8_t *message, size_t length,
                      const uint8_t signature[64]);

// MARK: - TLS

typedef struct av_tls_context av_tls_context;
typedef struct av_tls_conn av_tls_conn;

/// A fresh P-256 key and a self-signed certificate over it, for this process only.
av_tls_context *av_tls_context_new(void);
void av_tls_context_free(av_tls_context *context);

/// Listens on every address, IPv6 and IPv4, on `port` (0 for any). Returns the socket, or
/// -1, and the port it got in `boundPort`.
int av_tls_listen(uint16_t port, uint16_t *boundPort);
/// Waits for one connection and completes the handshake as the server. The peer must
/// present a certificate. NULL when the listener was closed.
av_tls_conn *av_tls_accept(av_tls_context *context, int listener);
/// Connects and completes the handshake as the client.
av_tls_conn *av_tls_connect(av_tls_context *context, const char *host, uint16_t port, int timeoutSeconds);

/// SHA-256 of this side's and the peer's certificate, which the auth transcript names.
int av_tls_local_cert_sha256(av_tls_conn *conn, uint8_t out[32]);
int av_tls_peer_cert_sha256(av_tls_conn *conn, uint8_t out[32]);
/// "address:port" of the peer, for the registry.
int av_tls_peer_address(av_tls_conn *conn, char *out, size_t capacity);

/// Reads what has arrived, waiting for some. > 0 bytes, 0 closed, < 0 failed.
long av_tls_read(av_tls_conn *conn, uint8_t *out, size_t capacity);
/// Writes all of it. Safe to call while another thread reads.
int av_tls_write(av_tls_conn *conn, const uint8_t *data, size_t length);
/// Wakes a blocked reader and ends the connection. The handle stays valid until freed.
void av_tls_shutdown(av_tls_conn *conn);
void av_tls_free(av_tls_conn *conn);
/// Closes a listening socket, which ends a blocked av_tls_accept.
void av_tls_close_listener(int listener);

#ifdef __cplusplus
}
#endif
