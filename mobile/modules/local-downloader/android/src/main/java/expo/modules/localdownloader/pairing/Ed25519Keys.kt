package expo.modules.localdownloader.pairing

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 over raw 32-byte keys.
 *
 * Split out of [DeviceIdentity] because that class needs an Android `Context` to find its
 * storage and this does not. The crypto is the half that must agree with
 * `desktop/src/DeviceIdentity.cpp` byte for byte, so it lives where a plain JVM test can
 * reach it and be held to `shared/pairing/VECTORS.json`.
 *
 * The JDK speaks PKCS#8 and X.509; the wire and the fingerprint use the raw 32 bytes. The
 * fixed prefixes below convert between the two. Both are the shortest legal encoding of a
 * raw Ed25519 key, so the length bytes are constants rather than something to compute.
 */
object Ed25519Keys {

  const val SEED_BYTES = 32
  const val PUBLIC_BYTES = 32
  const val SIGNATURE_BYTES = 64

  /** PKCS#8 header for a raw Ed25519 seed, so the JDK will take the 32 bytes back. */
  private val PKCS8_PREFIX = byteArrayOf(
    0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
    0x04, 0x22, 0x04, 0x20,
  )

  /** X.509 header for a raw Ed25519 public key. */
  private val X509_PREFIX = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
  )

  /** A fresh identity: the 32-byte seed and the 32-byte public key it implies. */
  fun generate(): Pair<ByteArray, ByteArray>? = runCatching {
    val pair = KeyPairGenerator.getInstance("Ed25519").apply {
      initialize(NamedParameterSpec.ED25519, SecureRandom())
    }.generateKeyPair()
    // The JDK hands these back encoded; the raw key is the tail of each form.
    val encodedPrivate = pair.private.encoded
    val encodedPublic = pair.public.encoded
    Pair(
      encodedPrivate.copyOfRange(encodedPrivate.size - SEED_BYTES, encodedPrivate.size),
      encodedPublic.copyOfRange(encodedPublic.size - PUBLIC_BYTES, encodedPublic.size),
    )
  }.getOrNull()

  /** Empty on failure, which callers treat as "cannot prove who I am". */
  fun sign(seed: ByteArray, message: ByteArray): ByteArray = runCatching {
    if (seed.size != SEED_BYTES) return ByteArray(0)
    val key = KeyFactory.getInstance("Ed25519")
      .generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + seed))
    Signature.getInstance("Ed25519").run { initSign(key); update(message); sign() }
  }.getOrElse { ByteArray(0) }

  fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
    runCatching {
      if (publicKey.size != PUBLIC_BYTES || signature.size != SIGNATURE_BYTES) return false
      val key = KeyFactory.getInstance("Ed25519")
        .generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey))
      Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) }
    }.getOrElse { false }

  /** Hex SHA-256 of a public key: the fingerprint shown to the user. */
  fun fingerprint(publicKey: ByteArray): String =
    if (publicKey.isEmpty()) ""
    else MessageDigest.getInstance("SHA-256").digest(publicKey)
      .joinToString("") { "%02x".format(it) }
}
