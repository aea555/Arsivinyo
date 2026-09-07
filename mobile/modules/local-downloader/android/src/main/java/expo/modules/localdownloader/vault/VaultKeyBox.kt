package expo.modules.localdownloader.vault

import com.google.crypto.tink.subtle.Hkdf
import expo.modules.localdownloader.backup.BackupCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Which keys can open the vault, and the move from a key that does not require your
 * fingerprint to one that does.
 *
 * The vault key is wrapped by slots, the same shape the desktop keybox uses — a
 * `platform-keystore` slot on Android, an `argon2id` recovery slot for when that is gone.
 * The wrap format is identical on both, and pinned in `shared/crypto/VECTORS.json`.
 *
 * The migration is the dangerous part of the whole feature, so it is a state machine with one
 * commit point, written against interfaces rather than the Keystore and the filesystem. That
 * is what lets every failure and every crash point be tested here instead of on a device with
 * a real vault in it.
 *
 * Nothing in this file imports Android.
 */
class VaultKeyBox(
  private val store: Store,
  private val keys: MasterKeyProvider,
  private val kdf: BackupCrypto.KdfParams = BackupCrypto.KdfParams(),
) {

  /** Files the key box owns. Small blobs only; the content files are never touched. */
  interface Store {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
    fun exists(name: String): Boolean
  }

  /**
   * The platform key store. `KeyGenParameterSpec` and `KeyPermanentlyInvalidatedException`
   * have no pure-JVM equivalent, so failures arrive as this sealed type and the Android side
   * translates.
   */
  interface MasterKeyProvider {
    fun ensureSlot(slot: String): Boolean
    fun hasSlot(slot: String): Boolean
    fun dropSlot(slot: String)
    fun wrap(plaintext: ByteArray, slot: String): ByteArray
    fun unwrap(blob: ByteArray, slot: String): ByteArray
  }

  sealed class MasterKeyError(message: String) : Exception(message) {
    /** The key is gone for good: a new fingerprint, or the screen lock was removed. */
    object Invalidated : MasterKeyError("PRIVATE_KEY_INVALIDATED")
    /** The key exists but this use needs a prompt first. */
    object AuthRequired : MasterKeyError("PRIVATE_AUTH_REQUIRED")
    class Backend(cause: Throwable) : MasterKeyError(cause.message ?: "PRIVATE_KEY_BACKEND")
  }

  enum class Outcome {
    /** Already there. */
    ALREADY_V3,

    /** Moved, and both wraps still exist until the new one has proved itself. */
    MIGRATED,

    /** Nothing was written. The old key still works. */
    FAILED,

    /** The new key stopped working and the old one took over again. */
    ROLLED_BACK,

    /** Neither key can open it and there is no recovery slot. */
    UNRECOVERABLE,
  }

  data class Result(val outcome: Outcome, val detail: String? = null)

  // ---- state ------------------------------------------------------------------------------

  private fun state(): JSONObject {
    val raw = store.read(STATE_FILE) ?: return JSONObject()
      .put("version", 1)
      .put("activeSlot", SLOT_KEYSTORE_V2)
      .put("slots", JSONArray().put(slotJson(SLOT_KEYSTORE_V2, TYPE_KEYSTORE, DEK_V4_FILE)))
    return runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrElse {
      throw IllegalStateException("PRIVATE_KEYBOX_UNREADABLE")
    }
  }

  private fun writeState(state: JSONObject) {
    store.write(STATE_FILE, state.toString().toByteArray(Charsets.UTF_8))
  }

  private fun slotJson(id: String, type: String, blobFile: String) =
    JSONObject().put("id", id).put("type", type).put("blobFile", blobFile)

  private fun slots(state: JSONObject): List<JSONObject> {
    val array = state.optJSONArray("slots") ?: JSONArray()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
  }

  private fun slot(state: JSONObject, id: String): JSONObject? = slots(state).firstOrNull {
    it.optString("id") == id
  }

  fun activeSlot(): String = state().optString("activeSlot").ifBlank { SLOT_KEYSTORE_V2 }

  fun hasRecoverySlot(): Boolean = slot(state(), SLOT_RECOVERY) != null

  /** True once the old, unbound key has been destroyed. */
  fun isFullyMigrated(): Boolean {
    val state = state()
    return state.optString("activeSlot") == SLOT_KEYSTORE_V3 && slot(state, SLOT_KEYSTORE_V2) == null
  }

  // ---- opening ----------------------------------------------------------------------------

  /** The vault key, through whichever slot is active. */
  fun dek(): ByteArray {
    val state = state()
    val active = state.optString("activeSlot").ifBlank { SLOT_KEYSTORE_V2 }
    val entry = slot(state, active) ?: throw IllegalStateException("PRIVATE_KEYBOX_UNREADABLE")
    val blob = store.read(entry.optString("blobFile"))
      ?: throw IllegalStateException("PRIVATE_KEYBOX_UNREADABLE")
    return keys.unwrap(blob, active)
  }

  // ---- the migration ----------------------------------------------------------------------

  /**
   * Move the vault key onto a Keystore key that requires authentication.
   *
   * Roughly sixty bytes move. The videos are never read, because only the wrapper changes.
   *
   * The order is: prove the new key works on throwaway bytes, write the new wrap under a new
   * name, read it back and compare, then flip the marker. Every step before the flip leaves
   * the old key active and the old wrap untouched, so a crash anywhere costs nothing.
   *
   * The old wrap is deliberately *not* destroyed here. See [finalise].
   */
  fun migrateToV3(): Result {
    val state = state()
    if (state.optString("activeSlot") == SLOT_KEYSTORE_V3) return Result(Outcome.ALREADY_V3)

    val dek = try {
      dek()
    } catch (error: MasterKeyError) {
      return Result(Outcome.FAILED, error.message)
    }

    try {
      if (!keys.ensureSlot(SLOT_KEYSTORE_V3)) {
        return Result(Outcome.FAILED, "PRIVATE_KEY_UPGRADE_FAILED")
      }

      // Prove the new key round-trips before anything is written. Some manufacturers accept a
      // key specification at generation and then refuse it at use; finding that out here costs
      // nothing, and finding it out later costs the vault.
      val probe = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val probeBack = runCatching { keys.unwrap(keys.wrap(probe, SLOT_KEYSTORE_V3), SLOT_KEYSTORE_V3) }
        .getOrElse {
          keys.dropSlot(SLOT_KEYSTORE_V3)
          return Result(Outcome.FAILED, "PRIVATE_KEY_UPGRADE_FAILED")
        }
      if (!MessageDigest.isEqual(probe, probeBack)) {
        keys.dropSlot(SLOT_KEYSTORE_V3)
        return Result(Outcome.FAILED, "PRIVATE_KEY_UPGRADE_FAILED")
      }

      // A new file name, so the old wrap is never in danger of being overwritten.
      val wrapped = runCatching { keys.wrap(dek, SLOT_KEYSTORE_V3) }.getOrElse {
        keys.dropSlot(SLOT_KEYSTORE_V3)
        return Result(Outcome.FAILED, "PRIVATE_KEY_UPGRADE_FAILED")
      }
      store.write(DEK_V5_FILE, wrapped)

      val readBack = runCatching { keys.unwrap(store.read(DEK_V5_FILE)!!, SLOT_KEYSTORE_V3) }
        .getOrNull()
      if (readBack == null || !MessageDigest.isEqual(dek, readBack)) {
        store.delete(DEK_V5_FILE)
        keys.dropSlot(SLOT_KEYSTORE_V3)
        return Result(Outcome.FAILED, "PRIVATE_KEY_UPGRADE_FAILED")
      }

      val updated = JSONObject(state.toString())
      val array = updated.optJSONArray("slots") ?: JSONArray()
      array.put(slotJson(SLOT_KEYSTORE_V3, TYPE_KEYSTORE, DEK_V5_FILE))
      updated.put("slots", array)
      updated.put("activeSlot", SLOT_KEYSTORE_V3)
      writeState(updated)   // <- the commit point
      return Result(Outcome.MIGRATED)
    } finally {
      dek.fill(0)
    }
  }

  /**
   * Destroy the old, unbound key.
   *
   * Held back until the new key has opened the vault on a cold start *and* there is a way back
   * in that does not depend on the device — a recovery passphrase — or the user has said they
   * understand there is not. Removing a screen lock deletes an authentication-bound key
   * permanently, and no setting prevents that.
   */
  fun finalise(force: Boolean = false): Result {
    val state = state()
    if (state.optString("activeSlot") != SLOT_KEYSTORE_V3) return Result(Outcome.FAILED)
    if (slot(state, SLOT_KEYSTORE_V2) == null) return Result(Outcome.ALREADY_V3)
    if (!force && slot(state, SLOT_RECOVERY) == null) {
      return Result(Outcome.FAILED, "PRIVATE_RECOVERY_SLOT_MISSING")
    }

    // Prove the new key still works before throwing the old one away.
    runCatching { dek() }.getOrElse { return Result(Outcome.FAILED, "PRIVATE_KEY_UPGRADE_FAILED") }
      .fill(0)

    val kept = JSONArray()
    slots(state).filter { it.optString("id") != SLOT_KEYSTORE_V2 }.forEach { kept.put(it) }
    val updated = JSONObject(state.toString()).put("slots", kept)
    writeState(updated)
    store.delete(DEK_V4_FILE)
    keys.dropSlot(SLOT_KEYSTORE_V2)
    return Result(Outcome.MIGRATED)
  }

  /**
   * What to do when the active key has been invalidated.
   *
   * While both wraps exist this is fully recoverable, which is the whole reason the old one is
   * kept for a while.
   */
  fun recoverFromInvalidatedKey(): Result {
    val state = state()
    if (slot(state, SLOT_KEYSTORE_V2) != null && store.exists(DEK_V4_FILE) &&
      keys.hasSlot(SLOT_KEYSTORE_V2)
    ) {
      val kept = JSONArray()
      slots(state).filter { it.optString("id") != SLOT_KEYSTORE_V3 }.forEach { kept.put(it) }
      val updated = JSONObject(state.toString())
        .put("slots", kept)
        .put("activeSlot", SLOT_KEYSTORE_V2)
      writeState(updated)
      store.delete(DEK_V5_FILE)
      runCatching { keys.dropSlot(SLOT_KEYSTORE_V3) }
      return Result(Outcome.ROLLED_BACK, "PRIVATE_KEY_UPGRADE_ROLLED_BACK")
    }
    if (slot(state, SLOT_RECOVERY) != null) {
      return Result(Outcome.FAILED, "PRIVATE_RECOVERY_REQUIRED")
    }
    return Result(Outcome.UNRECOVERABLE, "PRIVATE_VAULT_UNRECOVERABLE")
  }

  // ---- the recovery slot ------------------------------------------------------------------

  /**
   * A passphrase that opens the vault when the device key cannot.
   *
   * The only defence against a removed screen lock, which deletes an authentication-bound key
   * with no way back. Same wrap format as the desktop keybox, so the two are one design.
   */
  fun addRecoverySlot(passphrase: CharArray) {
    val dek = dek()
    val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
    val kek = BackupCrypto.deriveMasterKey(passphrase, salt, kdf)
    try {
      val verifier = Hkdf.computeHkdf("HMACSHA256", kek, null, INFO_VERIFY.toByteArray(Charsets.UTF_8), 32)
      val wrapKey = Hkdf.computeHkdf("HMACSHA256", kek, null, INFO_WRAP.toByteArray(Charsets.UTF_8), 32)
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
      cipher.updateAAD((AAD_PREFIX + SLOT_RECOVERY).toByteArray(Charsets.UTF_8))
      val sealed = cipher.doFinal(dek)

      store.write(DEK_RECOVERY_FILE, iv + sealed)
      val state = state()
      val kept = JSONArray()
      slots(state).filter { it.optString("id") != SLOT_RECOVERY }.forEach { kept.put(it) }
      kept.put(
        slotJson(SLOT_RECOVERY, TYPE_ARGON2ID, DEK_RECOVERY_FILE)
          .put("salt", android64(salt))
          .put("verifier", android64(verifier))
          .put("kdf", BackupCrypto.encodeKdfParams(kdf))
      )
      writeState(JSONObject(state.toString()).put("slots", kept))
      wrapKey.fill(0)
      verifier.fill(0)
    } finally {
      kek.fill(0)
      dek.fill(0)
    }
  }

  /** Returns the vault key, or throws with a reason the user can act on. */
  fun unlockWithRecovery(passphrase: CharArray): ByteArray {
    val entry = slot(state(), SLOT_RECOVERY)
      ?: throw IllegalStateException("PRIVATE_RECOVERY_SLOT_MISSING")
    val blob = store.read(entry.optString("blobFile"))
      ?: throw IllegalStateException("PRIVATE_RECOVERY_SLOT_MISSING")
    val salt = unandroid64(entry.optString("salt"))
    val verifier = unandroid64(entry.optString("verifier"))
    val params = BackupCrypto.decodeKdfParams(entry.optJSONObject("kdf") ?: JSONObject())

    val kek = BackupCrypto.deriveMasterKey(passphrase, salt, params)
    try {
      val computed = Hkdf.computeHkdf("HMACSHA256", kek, null, INFO_VERIFY.toByteArray(Charsets.UTF_8), 32)
      // Checked first, and in constant time, so a mistyped passphrase says so rather than
      // surfacing as a damaged key box.
      if (!MessageDigest.isEqual(computed, verifier)) {
        throw IllegalStateException("PRIVATE_RECOVERY_WRONG_SECRET")
      }
      val wrapKey = Hkdf.computeHkdf("HMACSHA256", kek, null, INFO_WRAP.toByteArray(Charsets.UTF_8), 32)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"),
        GCMParameterSpec(128, blob.copyOfRange(0, 12))
      )
      cipher.updateAAD((AAD_PREFIX + SLOT_RECOVERY).toByteArray(Charsets.UTF_8))
      val dek = cipher.doFinal(blob, 12, blob.size - 12)
      wrapKey.fill(0)
      return dek
    } finally {
      kek.fill(0)
    }
  }

  private fun android64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
  private fun unandroid64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)

  companion object {
    const val STATE_FILE = "keybox.json"
    const val DEK_V4_FILE = "dek.v4.bin"
    const val DEK_V5_FILE = "dek.v5.bin"
    const val DEK_RECOVERY_FILE = "dek.recovery.bin"

    const val SLOT_KEYSTORE_V2 = "keystore-v2"
    const val SLOT_KEYSTORE_V3 = "keystore-v3"
    const val SLOT_RECOVERY = "recovery"

    const val TYPE_KEYSTORE = "platform-keystore"
    const val TYPE_ARGON2ID = "argon2id"

    /** The desktop key box uses these exact labels. */
    const val INFO_VERIFY = "arsivinyo/keybox/verify/v1"
    const val INFO_WRAP = "arsivinyo/keybox/wrap/v1"
    const val AAD_PREFIX = "arsivinyo/keybox/v1/"

    const val SALT_BYTES = 16
  }
}
