#pragma once

#include <QSslCertificate>
#include <QSslKey>
#include <QString>

/**
 * The TLS certificate this device presents to a peer.
 *
 * Its key is deliberately **not** the device's identity key. Ed25519 certificates are not
 * accepted by Android's TLS stack, so a certificate carrying the identity — what the first
 * draft of the protocol specified — is unimplementable on the phone. This is an ordinary
 * P-256 certificate whose only job is to encrypt the channel; who is on the other end is
 * settled separately, by an Ed25519 signature over a transcript naming both certificates
 * of the connection (see `AuthTranscript` in shared/pairing/wire.h).
 *
 * It is generated per process and never written to disk. Keygen costs well under a
 * millisecond, so there is nothing to save by persisting it — and not persisting it means
 * there is no second private key lying in the data directory to worry about, and a
 * captured transcript signature is useless past the run that produced it.
 */
struct SessionCertificate {
    QSslCertificate certificate;
    QSslKey key;

    bool isValid() const { return !certificate.isNull() && !key.isNull(); }

    /** SHA-256 of the certificate's DER, which is what the auth transcript names. */
    QByteArray fingerprint() const { return fingerprintOf(certificate); }
    static QByteArray fingerprintOf(const QSslCertificate &certificate);

    /** A fresh P-256 key and a self-signed certificate over it. */
    static SessionCertificate create();
};
