package expo.modules.localdownloader.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 over raw 32-byte keys.
 *
 * Split out of [DeviceIdentity] because that class needs an Android `Context` to find its
 * storage and this does not. The crypto is the half that must agree with
 * `desktop/src/DeviceIdentity.cpp` byte for byte, so it lives where a plain JVM test can
 * reach it and be held to `shared/pairing/VECTORS.json`.
 *
 * **Why BouncyCastle and not the JDK.** `KeyPairGenerator.getInstance("Ed25519")` needs
 * API 33; this app's minSdk is 24, so on Android 7 through 12 it throws and the device
 * would silently end up with no identity at all. BouncyCastle's low-level API has no such
 * floor. This follows what `BackupCrypto.kt` already does: use `org.bouncycastle.crypto.*`
 * directly and never register the JCE provider, so nothing else in the process changes
 * behaviour.
 *
 * A further benefit is that the raw 32 bytes are what BouncyCastle takes and returns, so
 * there is no PKCS#8 or X.509 wrapping to get wrong — the encoding is the wire's.
 */
object Ed25519Keys {

  const val SEED_BYTES = 32
  const val PUBLIC_BYTES = 32
  const val SIGNATURE_BYTES = 64

  /** A fresh identity: the 32-byte seed and the 32-byte public key it implies. */
  fun generate(): Pair<ByteArray, ByteArray>? = runCatching {
    // For Ed25519 the private key *is* 32 random bytes; the public key is derived.
    val seed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
    Pair(seed, publicKeyFor(seed))
  }.getOrNull()

  /**
   * The public key a seed implies. Storing both and re-deriving on load is what catches a
   * truncated or corrupted key file, which would otherwise present as a device whose
   * signatures nobody can verify.
   */
  fun publicKeyFor(seed: ByteArray): ByteArray {
    require(seed.size == SEED_BYTES) { "an Ed25519 seed is $SEED_BYTES bytes" }
    return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
  }

  /** Empty on failure, which callers treat as "cannot prove who I am". */
  fun sign(seed: ByteArray, message: ByteArray): ByteArray = runCatching {
    if (seed.size != SEED_BYTES) return ByteArray(0)
    Ed25519Signer().apply {
      init(true, Ed25519PrivateKeyParameters(seed, 0))
      update(message, 0, message.size)
    }.generateSignature()
  }.getOrElse { ByteArray(0) }

  fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
    runCatching {
      if (publicKey.size != PUBLIC_BYTES || signature.size != SIGNATURE_BYTES) return false
      Ed25519Signer().apply {
        init(false, Ed25519PublicKeyParameters(publicKey, 0))
        update(message, 0, message.size)
      }.verifySignature(signature)
    }.getOrElse { false }

  /** Hex SHA-256 of a public key: the fingerprint shown to the user. */
  fun fingerprint(publicKey: ByteArray): String =
    if (publicKey.isEmpty()) ""
    else MessageDigest.getInstance("SHA-256").digest(publicKey)
      .joinToString("") { "%02x".format(it) }
}
