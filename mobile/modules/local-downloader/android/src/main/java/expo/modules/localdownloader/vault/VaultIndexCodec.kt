package expo.modules.localdownloader.vault

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.subtle.Hkdf
import java.io.ByteArrayOutputStream

/**
 * Seals the private vault's listing.
 *
 * The videos in the vault have always been encrypted. `index.json` beside them was not, so
 * every title, tag, folder name, duration and size was readable by anything that could read
 * the app's storage — most of what a listing would have told someone, without touching the
 * crypto at all. The backup container has always kept names inside its encrypted region; this
 * brings the vault up to the same standard.
 *
 * The construction is the one the desktop uses for the same file, and both are held to
 * `shared/crypto/VECTORS.json`. Deliberately no Android imports, so it runs under
 * `scripts/run-kotlin-tests.sh` without a device.
 *
 * The key comes from the vault's own DEK rather than a new Keystore alias. That means it
 * inherits whatever protects the DEK — including, later, an auth-bound master key — and there
 * is no second key to migrate when that lands.
 */
object VaultIndexCodec {

  /** Associated data. Binds this blob to being a vault index and nothing else. */
  const val AAD = "vault/index/v1"

  /** The same label the desktop derives its index key under. */
  const val INFO_INDEX_KEY = "arsivinyo/key/v1/vault-index"

  /** Plaintext is padded up to a multiple of this, so the file size does not count items. */
  const val PAD_BOUNDARY = 4096

  private const val SEGMENT_SIZE = 1 shl 20
  private const val KEY_BYTES = 32
  private const val HKDF_MAC = "HmacSha256"

  fun indexKey(dek: ByteArray): ByteArray =
    Hkdf.computeHkdf("HMACSHA256", dek, null, INFO_INDEX_KEY.toByteArray(Charsets.UTF_8), KEY_BYTES)

  /** `u32 length | content | zeros to the next boundary`. */
  fun pad(content: ByteArray): ByteArray {
    val framed = 4 + content.size
    val total = ((framed + PAD_BOUNDARY - 1) / PAD_BOUNDARY) * PAD_BOUNDARY
    val out = ByteArray(if (total == 0) PAD_BOUNDARY else total)
    out[0] = ((content.size ushr 24) and 0xff).toByte()
    out[1] = ((content.size ushr 16) and 0xff).toByte()
    out[2] = ((content.size ushr 8) and 0xff).toByte()
    out[3] = (content.size and 0xff).toByte()
    System.arraycopy(content, 0, out, 4, content.size)
    return out
  }

  fun unpad(padded: ByteArray): ByteArray {
    require(padded.size >= 4) { "the padded block is too short" }
    val length = ((padded[0].toInt() and 0xff) shl 24) or
      ((padded[1].toInt() and 0xff) shl 16) or
      ((padded[2].toInt() and 0xff) shl 8) or
      (padded[3].toInt() and 0xff)
    require(length >= 0 && length + 4 <= padded.size) {
      "the padded block declares more content than it holds"
    }
    return padded.copyOfRange(4, 4 + length)
  }

  fun seal(dek: ByteArray, json: String): ByteArray {
    val key = indexKey(dek)
    try {
      val streaming = AesGcmHkdfStreaming(key, HKDF_MAC, KEY_BYTES, SEGMENT_SIZE, 0)
      val out = ByteArrayOutputStream()
      streaming.newEncryptingStream(out, AAD.toByteArray(Charsets.UTF_8)).use {
        it.write(pad(json.toByteArray(Charsets.UTF_8)))
      }
      return out.toByteArray()
    } finally {
      key.fill(0)
    }
  }

  /**
   * @throws GeneralSecurityException or IOException when the blob will not open. The caller
   *   must never treat that as an empty index: the objects are still on disk, and an empty
   *   listing written back over a real one orphans every one of them.
   */
  fun open(dek: ByteArray, blob: ByteArray): String {
    val key = indexKey(dek)
    try {
      val streaming = AesGcmHkdfStreaming(key, HKDF_MAC, KEY_BYTES, SEGMENT_SIZE, 0)
      val padded = streaming
        .newDecryptingStream(blob.inputStream(), AAD.toByteArray(Charsets.UTF_8))
        .use { it.readBytes() }
      return String(unpad(padded), Charsets.UTF_8)
    } finally {
      key.fill(0)
    }
  }
}
