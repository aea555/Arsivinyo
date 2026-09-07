package expo.modules.localdownloader.pairing

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * The TLS certificate this device presents to a peer.
 *
 * Its key is deliberately **not** the device's identity key. Ed25519 certificates are not
 * accepted by Android's TLS stack, so a certificate carrying the identity is
 * unimplementable here. This is an ordinary P-256 certificate whose only job is to encrypt
 * the channel; who is on the other end is settled separately, by an Ed25519 signature over
 * a transcript naming both certificates of the connection.
 *
 * **Where the key lives.** In the Android Keystore, hardware-backed where the device has
 * one, and never extractable. That is the opposite of the identity key, which cannot go
 * there because the Keystore has no Ed25519 — see [DeviceIdentity]. It costs nothing here
 * because a generated Keystore key comes with a self-signed certificate already issued,
 * which is exactly what the transcript needs to name.
 *
 * **Why nothing is verified.** [trustEverything] accepts any certificate, which is safe
 * only because nothing downstream trusts the result: no certificate authority is involved
 * by design, and [PeerLink] refuses the connection unless the peer signs the session
 * transcript with a key this device has paired with.
 */
object SessionKeys {

  private const val TAG = "PairingSession"
  private const val ALIAS = "arsivinyo-pairing-session"

  /** Null if the key could not be created, which leaves the device unable to pair. */
  fun sslContext(): SSLContext? = runCatching {
    val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (!store.containsAlias(ALIAS) && !generate()) return null

    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      .apply { init(store, null) }

    SSLContext.getInstance("TLS").apply {
      init(managers.keyManagers, trustEverything(), SecureRandom())
    }
  }.getOrElse {
    Log.w(TAG, "could not prepare the session key: ${it.message}")
    null
  }

  /** Forget the session key, so the next connection uses a fresh certificate. */
  fun reset() {
    runCatching {
      KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
    }
  }

  private fun generate(): Boolean = runCatching {
    val notBefore = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }

    val builder = KeyGenParameterSpec.Builder(
      ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
      .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
      // DIGEST_NONE is the one that matters. Conscrypt hashes the handshake itself and
      // asks the Keystore to sign the finished digest, so a key that only authorises
      // SHA-256 cannot be used for TLS at all — the handshake dies with nothing more
      // informative than a read error at both ends.
      .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                  KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
      // The subject is not meaningful — nothing verifies a name here — but a certificate
      // has to carry one.
      .setCertificateSubject(X500Principal("CN=arsivinyo"))
      .setCertificateNotBefore(notBefore.time)
      .setCertificateNotAfter(notAfter.time)
      .setUserAuthenticationRequired(false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      // Keep working while the screen is locked: a transfer may well be running then.
      builder.setUnlockedDeviceRequired(false)
    }

    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
      .apply { initialize(builder.build()) }
      .generateKeyPair()
    true
  }.getOrElse {
    Log.w(TAG, "could not generate a session key: ${it.message}")
    false
  }

  /**
   * A trust manager that accepts every certificate.
   *
   * Read the class note before reusing this anywhere else. It is correct here *only*
   * because the pairing identity, not the certificate, decides who may talk to this
   * device, and that check happens in [PeerLink] before any request is read.
   */
  private fun trustEverything(): Array<TrustManager> = arrayOf(
    object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
      override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
      override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })

  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
