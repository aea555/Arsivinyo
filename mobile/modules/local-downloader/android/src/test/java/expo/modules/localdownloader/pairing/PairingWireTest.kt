package expo.modules.localdownloader.pairing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Kotlin against `shared/pairing/VECTORS.json` — the same file the C++ implementation is
 * checked against.
 *
 * Two implementations of one wire format will drift unless something forces them not to.
 * Prose in a spec does not; a file that both test suites read does.
 */
class PairingWireTest {

  private val vectors: JSONObject by lazy {
    // Walk up to the repository root, which is wherever `shared/` lives.
    var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (!File(dir, "shared/pairing/VECTORS.json").exists()) {
      dir = dir.parentFile ?: error("shared/pairing/VECTORS.json not found above ${System.getProperty("user.dir")}")
    }
    JSONObject(File(dir, "shared/pairing/VECTORS.json").readText())
  }

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
  private fun unhex(s: String) = ByteArray(s.length / 2) {
    ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
  }

  @Test
  fun framesEncodeExactlyAsTheVectorsSay() {
    val frames = vectors.getJSONArray("frames")
    for (i in 0 until frames.length()) {
      val v = frames.getJSONObject(i)
      val payload = unhex(v.getString("payload"))
      val encoded = PairingWire.encodeFrame(v.getInt("type").toByte(), payload)
      assertEquals(v.getString("why"), v.getString("encoded"), hex(encoded!!))
    }
  }

  @Test
  fun framesSurviveARoundTrip() {
    val frames = vectors.getJSONArray("frames")
    for (i in 0 until frames.length()) {
      val v = frames.getJSONObject(i)
      val payload = unhex(v.getString("payload"))
      val encoded = PairingWire.encodeFrame(v.getInt("type").toByte(), payload)!!
      val decoded = PairingWire.decodeFrame(encoded)
      assertTrue(v.getString("why"), decoded is PairingWire.Decoded.Frame)
      decoded as PairingWire.Decoded.Frame
      assertEquals(v.getString("why"), hex(payload), hex(decoded.payload))
      assertEquals(v.getString("why"), encoded.size, decoded.consumed)
    }
  }

  @Test
  fun malformedFramesAreRejectedTheSameWay() {
    val cases = vectors.getJSONArray("decode_errors")
    for (i in 0 until cases.length()) {
      val v = cases.getJSONObject(i)
      val actual = when (PairingWire.decodeFrame(unhex(v.getString("input")))) {
        is PairingWire.Decoded.Frame -> "Ok"
        PairingWire.Decoded.Incomplete -> "Incomplete"
        PairingWire.Decoded.TooLarge -> "TooLarge"
        PairingWire.Decoded.BadType -> "BadType"
      }
      assertEquals(v.getString("why"), v.getString("expect"), actual)
    }
  }

  @Test
  fun keysAreSortedBeforeHashing() {
    val cases = vectors.getJSONArray("code_input")
    for (i in 0 until cases.length()) {
      val v = cases.getJSONObject(i)
      val input = PairingWire.codeInput(unhex(v.getString("keyA")), unhex(v.getString("keyB")))
      assertEquals(v.getString("why"), v.getString("sorted"), hex(input))
    }
  }

  @Test
  fun theCodeIsSixDigits() {
    val cases = vectors.getJSONArray("pairing_code")
    for (i in 0 until cases.length()) {
      val v = cases.getJSONObject(i)
      assertEquals(v.getString("why"), v.getString("code"),
        PairingWire.pairingCode(unhex(v.getString("digest"))))
    }
  }

  @Test
  fun bothEndsAgreeWhicheverOrderTheKeysArriveIn() {
    // The property the vectors cannot express: this must hold for every pair, or two
    // devices show different codes and the user is told a genuine key does not match.
    for (i in 0 until 64) {
      val a = ByteArray(PairingWire.PUBLIC_KEY_BYTES) { j -> ((i * 31 + j * 7) and 0xff).toByte() }
      val b = ByteArray(PairingWire.PUBLIC_KEY_BYTES) { j -> ((i * 17 + j * 13) and 0xff).toByte() }
      assertEquals(PairingWire.pairingCodeFor(a, b), PairingWire.pairingCodeFor(b, a))
    }
  }

  @Test
  fun aFrameOverTheCapIsRefused() {
    assertEquals(null, PairingWire.encodeFrame(PairingWire.TYPE_BULK,
      ByteArray(PairingWire.MAX_FRAME_BYTES)))
  }
}
