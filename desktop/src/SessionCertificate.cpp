#include "SessionCertificate.h"

#include <QCryptographicHash>

#include <openssl/asn1.h>
#include <openssl/bio.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>

namespace {

/** Ten years. The certificate is not a trust anchor, so a long life costs nothing. */
constexpr long kValiditySeconds = 10L * 365 * 24 * 3600;

QByteArray pemFromBio(BIO *bio) {
    char *data = nullptr;
    const long length = BIO_get_mem_data(bio, &data);
    return length > 0 ? QByteArray(data, static_cast<int>(length)) : QByteArray();
}

/** Generate a P-256 key and a self-signed certificate over it, both as PEM. */
bool generatePem(QByteArray *certPem, QByteArray *keyPem) {
    EVP_PKEY *pkey = EVP_EC_gen("P-256");
    if (!pkey) return false;

    X509 *cert = X509_new();
    if (!cert) {
        EVP_PKEY_free(pkey);
        return false;
    }

    bool ok = false;
    do {
        // Version 3. The field is zero-based, so 2 is v3.
        if (X509_set_version(cert, 2) != 1) break;
        if (ASN1_INTEGER_set(X509_get_serialNumber(cert), 1) != 1) break;
        if (!X509_gmtime_adj(X509_get_notBefore(cert), 0)) break;
        if (!X509_gmtime_adj(X509_get_notAfter(cert), kValiditySeconds)) break;
        if (X509_set_pubkey(cert, pkey) != 1) break;

        // Self-signed, so subject and issuer are the same and neither is meaningful:
        // nothing verifies this name. The peer is identified by its Ed25519 key.
        X509_NAME *name = X509_get_subject_name(cert);
        if (X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                                       reinterpret_cast<const unsigned char *>("arsivinyo"),
                                       -1, -1, 0) != 1) break;
        if (X509_set_issuer_name(cert, name) != 1) break;
        if (X509_sign(cert, pkey, EVP_sha256()) == 0) break;

        BIO *certBio = BIO_new(BIO_s_mem());
        BIO *keyBio = BIO_new(BIO_s_mem());
        if (certBio && keyBio && PEM_write_bio_X509(certBio, cert) == 1 &&
            PEM_write_bio_PrivateKey(keyBio, pkey, nullptr, nullptr, 0, nullptr, nullptr) == 1) {
            *certPem = pemFromBio(certBio);
            *keyPem = pemFromBio(keyBio);
            ok = !certPem->isEmpty() && !keyPem->isEmpty();
        }
        BIO_free(certBio);
        BIO_free(keyBio);
    } while (false);

    X509_free(cert);
    EVP_PKEY_free(pkey);
    return ok;
}

}  // namespace

QByteArray SessionCertificate::fingerprintOf(const QSslCertificate &certificate) {
    if (certificate.isNull()) return {};
    return QCryptographicHash::hash(certificate.toDer(), QCryptographicHash::Sha256);
}

SessionCertificate SessionCertificate::create() {
    QByteArray certPem, keyPem;
    SessionCertificate out;
    if (!generatePem(&certPem, &keyPem)) return out;
    out.certificate = QSslCertificate(certPem, QSsl::Pem);
    out.key = QSslKey(keyPem, QSsl::Ec, QSsl::Pem);
    return out;
}
