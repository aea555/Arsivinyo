package expo.modules.localdownloader.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Android half of the key box: real Keystore keys, and the one place the platform's
 * exceptions are turned into something the pure state machine can reason about.
 *
 * Two slots exist here. `keystore-v2` is the key the vault has always used, which anything
 * running as the app could use freely — the fingerprint prompt was a screen, not a condition.
 * `keystore-v3` requires authentication, so the Keystore itself refuses without a recent
 * unlock.
 *
 * Deliberate choice: **biometric enrolment does not invalidate the key.** Enabling that would
 * defend against someone adding their own fingerprint to a phone they can already unlock, and
 * would destroy the vault every time the owner legitimately re-enrols a finger. Device
 * credential is in the allowed set for the same reason. What no setting can defend against is
 * the screen lock being removed entirely, which deletes the key outright — that is what the
 * recovery passphrase is for, and why the old key is kept until one exists.
 */
class VaultKeystoreKeys : VaultKeyBox.MasterKeyProvider {

  private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  private fun aliasFor(slot: String): String = when (slot) {
    VaultKeyBox.SLOT_KEYSTORE_V2 -> ALIAS_V2
    VaultKeyBox.SLOT_KEYSTORE_V3 -> ALIAS_V3
    else -> throw IllegalArgumentException("not a platform key slot: $slot")
  }

  override fun hasSlot(slot: String): Boolean =
    runCatching { keyStore().containsAlias(aliasFor(slot)) }.getOrDefault(false)

  override fun dropSlot(slot: String) {
    runCatching { keyStore().deleteEntry(aliasFor(slot)) }
  }

  override fun ensureSlot(slot: String): Boolean {
    if (hasSlot(slot)) return true
    return runCatching {
      when (slot) {
        VaultKeyBox.SLOT_KEYSTORE_V2 -> generate(ALIAS_V2, authBound = false, strongBox = false)
        VaultKeyBox.SLOT_KEYSTORE_V3 -> {
          // StrongBox is a separate security chip and not every phone has one. Ask, and fall
          // back rather than failing the upgrade over it.
          runCatching { generate(ALIAS_V3, authBound = true, strongBox = true) }
            .getOrElse { error ->
              if (error is StrongBoxUnavailableException) {
                generate(ALIAS_V3, authBound = true, strongBox = false)
              } else {
                throw error
              }
            }
        }
        else -> return false
      }
      true
    }.getOrDefault(false)
  }

  private fun generate(alias: String, authBound: Boolean, strongBox: Boolean) {
    val builder = KeyGenParameterSpec.Builder(
      alias,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      // The Keystore picks the IV, and refuses one from the caller.
      .setRandomizedEncryptionRequired(true)

    if (authBound) {
      builder.setUserAuthenticationRequired(true)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // A time window rather than one-use-per-prompt. One-use would mean every unwrap had to
        // carry its own CryptoObject through BiometricPrompt; a window lets the prompt the app
        // already shows authorise the unwrap that follows it. The key is touched once per
        // unlock, so the window can be short.
        builder.setUserAuthenticationParameters(
          AUTH_WINDOW_SECONDS,
          KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
        )
      } else {
        @Suppress("DEPRECATION")
        builder.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        // See the class comment: a new fingerprint must not destroy the vault.
        builder.setInvalidatedByBiometricEnrollment(false)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        builder.setUnlockedDeviceRequired(true)
        if (strongBox) builder.setIsStrongBoxBacked(true)
      }
    }

    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
      .apply { init(builder.build()) }
      .generateKey()
  }

  private fun secretKey(slot: String): SecretKey {
    val alias = aliasFor(slot)
    return (keyStore().getKey(alias, null) as? SecretKey)
      ?: throw VaultKeyBox.MasterKeyError.Invalidated
  }

  override fun wrap(plaintext: ByteArray, slot: String): ByteArray = translate {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey(slot))
    val sealed = cipher.doFinal(plaintext)
    // The Keystore chose the IV, so it is read back rather than supplied.
    cipher.iv + sealed
  }

  override fun unwrap(blob: ByteArray, slot: String): ByteArray = translate {
    if (blob.size <= IV_BYTES) throw IllegalStateException("PRIVATE_KEYBOX_UNREADABLE")
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE, secretKey(slot),
      GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)),
    )
    cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
  }

  /**
   * The platform's failures, as something the state machine can branch on.
   *
   * The distinction that matters is invalidated — gone for good — against needs-a-prompt,
   * because one of those is recoverable by asking the user and the other is not.
   */
  private fun <T> translate(body: () -> T): T = try {
    body()
  } catch (invalidated: KeyPermanentlyInvalidatedException) {
    throw VaultKeyBox.MasterKeyError.Invalidated
  } catch (needsAuth: UserNotAuthenticatedException) {
    throw VaultKeyBox.MasterKeyError.AuthRequired
  } catch (alreadyOurs: VaultKeyBox.MasterKeyError) {
    throw alreadyOurs
  } catch (other: Throwable) {
    throw VaultKeyBox.MasterKeyError.Backend(other)
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** The unbound key the vault has always used. */
    const val ALIAS_V2 = "arsivinyo.local.private.master.v2"

    /** The authentication-bound one. */
    const val ALIAS_V3 = "arsivinyo.local.private.master.v3"

    /**
     * How long a prompt authorises use of the bound key.
     *
     * Short on purpose: the key is unwrapped once, immediately after the prompt, and the
     * session's own window is what governs everything afterwards.
     */
    const val AUTH_WINDOW_SECONDS = 30
  }
}
