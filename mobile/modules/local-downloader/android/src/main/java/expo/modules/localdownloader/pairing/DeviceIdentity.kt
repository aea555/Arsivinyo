package expo.modules.localdownloader.pairing

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * This device's permanent identity: one Ed25519 keypair, generated once and kept.
 *
 * The public key *is* the identity; its SHA-256 is the fingerprint shown to the user and
 * advertised over mDNS, so a known peer is recognised before a connection is opened. The
 * crypto itself is in [Ed25519Keys]; this class only decides where the bytes live.
 *
 * **Why not the Android Keystore.** The Keystore would be the obvious home, and it is
 * where the vault's keys live. It does not support Ed25519: `KeyProperties` offers EC
 * with the NIST curves and RSA, and an Ed25519 key cannot be imported into it either.
 * Choosing the curve to fit the Keystore would mean a different signature scheme on each
 * platform, which is a far worse trade than storing 32 bytes in app-private storage —
 * unreadable to other apps under Android's sandbox, and deleted when the app is.
 *
 * Losing the key means becoming a new device that must pair again. That is intended: it
 * is the same property that makes a wiped phone's old pairings useless.
 */
class DeviceIdentity(context: Context) : PairingIdentity {

  private val keyFile = File(context.filesDir, "pairing/device.key")
  private val nameFile = File(context.filesDir, "pairing/device.name")

  private var privateSeed: ByteArray = ByteArray(0)

  /** 32 bytes. The device's identity. Empty if the key could not be created. */
  override var publicKey: ByteArray = ByteArray(0)
    private set

  val ready: Boolean get() = publicKey.size == Ed25519Keys.PUBLIC_BYTES

  /** Hex SHA-256 of [publicKey]. */
  val fingerprint: String get() = Ed25519Keys.fingerprint(publicKey)

  override var deviceName: String
    get() = runCatching { nameFile.readText().trim() }.getOrNull()
      ?.takeIf { it.isNotEmpty() } ?: Build.MODEL
    set(value) {
      val trimmed = value.trim()
      if (trimmed.isEmpty()) return
      nameFile.parentFile?.mkdirs()
      runCatching { nameFile.writeText(trimmed) }
    }

  init {
    if (!load()) generate()
  }

  private fun load(): Boolean = runCatching {
    if (!keyFile.isFile) return false
    val raw = keyFile.readBytes()
    if (raw.size != Ed25519Keys.SEED_BYTES + Ed25519Keys.PUBLIC_BYTES) return false
    val seed = raw.copyOfRange(0, Ed25519Keys.SEED_BYTES)
    val stored = raw.copyOfRange(Ed25519Keys.SEED_BYTES, raw.size)
    // Re-derive rather than trust the file. A corrupted key would otherwise present as a
    // device whose signatures no peer can verify, with nothing on this end looking wrong.
    if (!Ed25519Keys.publicKeyFor(seed).contentEquals(stored)) {
      Log.w(TAG, "the stored identity is inconsistent; generating a new one")
      return false
    }
    privateSeed = seed
    publicKey = stored
    true
  }.getOrElse { false }

  private fun generate(): Boolean {
    val made = Ed25519Keys.generate() ?: run {
      Log.w(TAG, "could not generate a device identity")
      return false
    }
    return runCatching {
      keyFile.parentFile?.mkdirs()
      // Narrow the permissions before writing, so the key is never briefly readable.
      keyFile.createNewFile()
      keyFile.setReadable(false, false)
      keyFile.setReadable(true, true)
      keyFile.setWritable(false, false)
      keyFile.setWritable(true, true)
      keyFile.writeBytes(made.first + made.second)
      privateSeed = made.first
      publicKey = made.second
      true
    }.getOrElse {
      Log.w(TAG, "could not store the device identity: ${it.message}")
      false
    }
  }

  override fun sign(message: ByteArray): ByteArray = Ed25519Keys.sign(privateSeed, message)

  /** The six digits both devices must show. See shared/pairing/VECTORS.json. */
  fun pairingCodeWith(peerPublicKey: ByteArray): String =
    if (peerPublicKey.size != Ed25519Keys.PUBLIC_BYTES || !ready) ""
    else PairingWire.pairingCodeFor(publicKey, peerPublicKey)

  companion object {
    private const val TAG = "PairingIdentity"

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
      Ed25519Keys.verify(publicKey, message, signature)
  }
}
