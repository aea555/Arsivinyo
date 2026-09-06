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

  const val ROLE_SERVER: Byte = 'S'.code.toByte()
  const val ROLE_CLIENT: Byte = 'C'.code.toByte()

  /** A SHA-256 digest, which is what the transcript carries. */
  const val CERT_HASH_BYTES = 32

  private val AUTH_LABEL = "arsivinyo-pairing-auth-v1".toByteArray(Charsets.US_ASCII) + 0

  /**
   * The exact bytes each side signs with its identity key to prove who it is.
   *
   * The identity key is *not* the TLS certificate key. Android's TLS stack does not
   * accept Ed25519 certificates and this app supports API 24, so a certificate carrying
   * the identity cannot be implemented here at all. Each side uses an ordinary
   * self-signed certificate for TLS and then signs a transcript naming *both*
   * certificates of this particular connection.
   *
   * That is what keeps the man-in-the-middle out. An attacker terminating TLS on both
   * legs sees different certificates on each, so a signature made for one leg does not
   * verify on the other, and it cannot forge one without the Ed25519 key.
   *
   * The server hash always comes first, so both ends build the same bytes without
   * negotiating an order. The trailing role byte stops a signature captured from one
   * direction being replayed as the other's.
   *
   * @return empty if either hash is the wrong length.
   */
  fun authTranscript(role: Byte, serverCertSha256: ByteArray, clientCertSha256: ByteArray): ByteArray {
    if (serverCertSha256.size != CERT_HASH_BYTES) return ByteArray(0)
    if (clientCertSha256.size != CERT_HASH_BYTES) return ByteArray(0)
    return AUTH_LABEL + serverCertSha256 + clientCertSha256 + role
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
