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
  fun theRfc8032SignatureVerifies() {
    // Ed25519 keys must be handled as raw 32 bytes on both platforms. The JDK wants
    // X.509 and PKCS#8, so this is exactly where the two ends could diverge without
    // either looking wrong on its own.
    val cases = vectors.getJSONArray("ed25519")
    for (i in 0 until cases.length()) {
      val v = cases.getJSONObject(i)
      val pub = unhex(v.getString("publicKey"))
      val msg = unhex(v.getString("message"))
      val sig = unhex(v.getString("signature"))
      // The fingerprint is what the user compares on screen and what mDNS
      // advertises, so it must be the same digest on both platforms, not merely
      // self-consistent on each.
      assertEquals(v.getString("fingerprint"), Ed25519Keys.fingerprint(pub))
      assertTrue(v.getString("why"), Ed25519Keys.verify(pub, msg, sig))
      assertTrue("a changed message must not verify",
        !Ed25519Keys.verify(pub, msg + 'x'.code.toByte(), sig))
      assertTrue("a changed signature must not verify",
        !Ed25519Keys.verify(pub, msg, sig.clone().also { it[0] = (it[0] + 1).toByte() }))
    }
  }

  @Test
  fun signingIsDeterministicAndMatchesTheVector() {
    // Ed25519 has no nonce to vary, so the same seed and message must give the same 64
    // bytes here as the desktop's OpenSSL produced. Verification alone would still pass
    // if this side signed differently, which is the drift the vectors exist to catch.
    val cases = vectors.getJSONArray("ed25519")
    for (i in 0 until cases.length()) {
      val v = cases.getJSONObject(i)
      val seed = unhex(v.getString("seed"))
      val msg = unhex(v.getString("message"))
      assertEquals(v.getString("signature"), hex(Ed25519Keys.sign(seed, msg)))
    }
  }

  @Test
  fun aGeneratedKeyIsUsableAndDistinct() {
    val (seedA, pubA) = Ed25519Keys.generate()!!
    val (seedB, pubB) = Ed25519Keys.generate()!!
    assertEquals(Ed25519Keys.SEED_BYTES, seedA.size)
    assertEquals(Ed25519Keys.PUBLIC_BYTES, pubA.size)
    assertTrue("two identities must not collide", !pubA.contentEquals(pubB))

    val message = "pair with me".toByteArray()
    val signature = Ed25519Keys.sign(seedA, message)
    assertEquals(Ed25519Keys.SIGNATURE_BYTES, signature.size)
    assertTrue(Ed25519Keys.verify(pubA, message, signature))
    assertTrue("another device's key must not verify this signature",
      !Ed25519Keys.verify(pubB, message, signature))
    assertTrue("signing with the wrong seed must not verify",
      !Ed25519Keys.verify(pubA, message, Ed25519Keys.sign(seedB, message)))
  }

  @Test
  fun malformedKeyMaterialIsRefusedRatherThanThrowing() {
    val (seed, pub) = Ed25519Keys.generate()!!
    val message = "x".toByteArray()
    val signature = Ed25519Keys.sign(seed, message)

    assertTrue(!Ed25519Keys.verify(ByteArray(0), message, signature))
    assertTrue(!Ed25519Keys.verify(pub.copyOf(31), message, signature))
    assertTrue(!Ed25519Keys.verify(pub, message, ByteArray(0)))
    assertTrue(!Ed25519Keys.verify(pub, message, signature.copyOf(63)))
    // A short seed cannot sign, and the empty result must not then verify as anything.
    assertEquals(0, Ed25519Keys.sign(ByteArray(8), message).size)
    assertTrue(!Ed25519Keys.verify(pub, message, ByteArray(0)))
    assertEquals("", Ed25519Keys.fingerprint(ByteArray(0)))
  }

  @Test
  fun aFrameOverTheCapIsRefused() {
    assertEquals(null, PairingWire.encodeFrame(PairingWire.TYPE_BULK,
      ByteArray(PairingWire.MAX_FRAME_BYTES)))
  }
}
