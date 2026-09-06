package expo.modules.localdownloader.pairing

import java.security.MessageDigest

/**
 * The bytes on the wire, and the pairing code.
 *
 * The counterpart of `shared/pairing/wire.cpp`. Both are held to
 * `shared/pairing/VECTORS.json`, because a framing bug or an off-by-one in the code
 * derivation would surface as "pairing does not work" with nothing to read. The two
 * implementations exist because neither platform can call the other's, not because the
 * behaviour is allowed to differ.
 */
object PairingWire {

  /** Ed25519 public keys are 32 bytes. */
  const val PUBLIC_KEY_BYTES = 32

  const val TYPE_CONTROL: Byte = 0
  const val TYPE_BULK: Byte = 1

  /**
   * Refuse anything larger. A peer that announces four gigabytes would otherwise make
   * the receiver try to allocate it before a single byte of it arrives.
   */
  const val MAX_FRAME_BYTES = 8 * 1024 * 1024

  private const val HEADER_BYTES = 5

  sealed interface Decoded {
    /** [consumed] is how many bytes to drop from the front of the buffer. */
    data class Frame(val type: Byte, val payload: ByteArray, val consumed: Int) : Decoded
    /** A whole frame has not arrived; keep buffering. Nothing is consumed. */
    data object Incomplete : Decoded
    data object TooLarge : Decoded
    data object BadType : Decoded
  }

  /** @return null when the payload would exceed [MAX_FRAME_BYTES]. */
  fun encodeFrame(type: Byte, payload: ByteArray): ByteArray? {
    // The length covers the type byte and the payload, which is what the decoder reads.
    val bodyLen = payload.size.toLong() + 1
    if (bodyLen > MAX_FRAME_BYTES) return null

    val out = ByteArray(HEADER_BYTES + payload.size)
    val len = bodyLen.toInt()
    out[0] = (len ushr 24).toByte()
    out[1] = (len ushr 16).toByte()
    out[2] = (len ushr 8).toByte()
    out[3] = len.toByte()
    out[4] = type
    payload.copyInto(out, HEADER_BYTES)
    return out
  }

  fun decodeFrame(buffer: ByteArray, length: Int = buffer.size): Decoded {
    if (length < 4) return Decoded.Incomplete

    val bodyLen = ((buffer[0].toInt() and 0xff) shl 24) or
      ((buffer[1].toInt() and 0xff) shl 16) or
      ((buffer[2].toInt() and 0xff) shl 8) or
      (buffer[3].toInt() and 0xff)

    // Checked before the body is awaited, so an absurd length is refused immediately.
    if (bodyLen <= 0 || bodyLen > MAX_FRAME_BYTES) return Decoded.TooLarge
    if (length < 4 + bodyLen) return Decoded.Incomplete

    val type = buffer[4]
    if (type != TYPE_CONTROL && type != TYPE_BULK) return Decoded.BadType

    return Decoded.Frame(
      type = type,
      payload = buffer.copyOfRange(HEADER_BYTES, 4 + bodyLen),
      consumed = 4 + bodyLen,
    )
  }

  /**
   * The bytes to hash when deriving a pairing code: the two public keys, ordered.
   *
   * Sorted so both devices hash the same thing without agreeing who goes first.
   * Otherwise the two ends compute different codes and the user is told the keys do not
   * match when they do.
   */
  fun codeInput(keyA: ByteArray, keyB: ByteArray): ByteArray {
    val aFirst = compareUnsigned(keyA, keyB) < 0
    val first = if (aFirst) keyA else keyB
    val second = if (aFirst) keyB else keyA
    return first + second
  }

  /**
   * Six digits from a SHA-256 digest of [codeInput]: the first four bytes, big-endian,
   * modulo one million, zero-padded.
   */
  fun pairingCode(digest: ByteArray): String {
    if (digest.size < 4) return ""
    val value = ((digest[0].toLong() and 0xff) shl 24) or
      ((digest[1].toLong() and 0xff) shl 16) or
      ((digest[2].toLong() and 0xff) shl 8) or
      (digest[3].toLong() and 0xff)
    return "%06d".format(value % 1_000_000L)
  }

  /** Convenience: the code for a pair of public keys. */
  fun pairingCodeFor(keyA: ByteArray, keyB: ByteArray): String =
    pairingCode(MessageDigest.getInstance("SHA-256").digest(codeInput(keyA, keyB)))

  private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    val shared = minOf(a.size, b.size)
    for (i in 0 until shared) {
      val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
      if (diff != 0) return diff
    }
    return a.size - b.size
  }
}
