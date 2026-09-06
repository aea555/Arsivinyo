package expo.modules.localdownloader.pairing

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auth handshake, which is what decides whether a connection is a paired device or a
 * stranger. Everything here is a pure function of the two certificate hashes, so the
 * cases that matter can be built directly rather than by pairing two real devices.
 */
class PairingAuthTest {

  private val vectors: JSONObject by lazy {
    // Walk up rather than count directories: the harness's working directory is not
    // fixed, and a hardcoded depth breaks silently when either tree moves.
    var dir = File(System.getProperty("user.dir"))
    while (!File(dir, "shared/pairing/VECTORS.json").exists()) {
      dir = dir.parentFile ?: error("shared/pairing/VECTORS.json not found above ${System.getProperty("user.dir")}")
    }
    JSONObject(File(dir, "shared/pairing/VECTORS.json").readText())
  }

  private fun unhex(s: String) = ByteArray(s.length / 2) {
    s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
  }

  private val serverHash = ByteArray(PairingWire.CERT_HASH_BYTES) { it.toByte() }
  private val clientHash = ByteArray(PairingWire.CERT_HASH_BYTES) { (it + 90).toByte() }

  private fun identity(): Triple<ByteArray, ByteArray, String> {
    val (seed, key) = Ed25519Keys.generate()!!
    return Triple(seed, key, "Phone")
  }

  @Test
  fun aClientProvesItselfToTheServer() {
    val (seed, key, name) = identity()
    val message = PairingAuth.build(
      seed, key, name, PairingWire.ROLE_CLIENT, serverHash, clientHash)
    assertNotNull(message)

    // The server verifies with the *peer's* role, which is the opposite of its own.
    val peer = PairingAuth.verify(message!!, PairingWire.ROLE_CLIENT, serverHash, clientHash)
    assertNotNull(peer)
    assertTrue(peer!!.publicKey.contentEquals(key))
    assertEquals("Phone", peer.name)
  }

  @Test
  fun aSignatureFromAnotherSessionDoesNotVerify() {
    // What a man in the middle holds: a real key and a real signature, made over the
    // certificates of the leg it terminated rather than the one it is relaying into.
    val (seed, key, name) = identity()
    val otherServer = ByteArray(PairingWire.CERT_HASH_BYTES) { 0xAA.toByte() }
    val otherClient = ByteArray(PairingWire.CERT_HASH_BYTES) { 0xBB.toByte() }

    val message = PairingAuth.build(
      seed, key, name, PairingWire.ROLE_CLIENT, otherServer, otherClient)!!
    assertNull(PairingAuth.verify(message, PairingWire.ROLE_CLIENT, serverHash, clientHash))
  }

  @Test
  fun aSignatureFromTheOtherDirectionDoesNotVerify() {
    val (seed, key, name) = identity()
    val asServer = PairingAuth.build(
      seed, key, name, PairingWire.ROLE_SERVER, serverHash, clientHash)!!
    // Replaying the server's own message back at it, claiming to be the client.
    assertNull(PairingAuth.verify(asServer, PairingWire.ROLE_CLIENT, serverHash, clientHash))
    assertNotNull(PairingAuth.verify(asServer, PairingWire.ROLE_SERVER, serverHash, clientHash))
  }

  @Test
  fun aSwappedCertificateOrderDoesNotVerify() {
    val (seed, key, name) = identity()
    val message = PairingAuth.build(
      seed, key, name, PairingWire.ROLE_CLIENT, serverHash, clientHash)!!
    assertNull(PairingAuth.verify(message, PairingWire.ROLE_CLIENT, clientHash, serverHash))
  }

  @Test
  fun anotherDevicesKeyDoesNotVerify() {
    val (seed, _, name) = identity()
    val (_, otherKey) = Ed25519Keys.generate()!!
    val message = PairingAuth.build(
      seed, otherKey, name, PairingWire.ROLE_CLIENT, serverHash, clientHash)!!
    // The message claims a key it did not sign with.
    assertNull(PairingAuth.verify(message, PairingWire.ROLE_CLIENT, serverHash, clientHash))
  }

  @Test
  fun aTamperedSignatureDoesNotVerify() {
    val (seed, key, name) = identity()
    val message = PairingAuth.build(
      seed, key, name, PairingWire.ROLE_CLIENT, serverHash, clientHash)!!
    val json = JSONObject(message)
    val sig = json.getString("sig")
    val flipped = (if (sig[0] == '0') "1" else "0") + sig.substring(1)
    assertNull(PairingAuth.verify(json.put("sig", flipped).toString(),
      PairingWire.ROLE_CLIENT, serverHash, clientHash))
  }

  @Test
  fun rubbishIsRefusedRatherThanThrowing() {
    for (bad in listOf("", "{", "null", "[]", "{\"t\":\"hello\"}",
                       "{\"t\":\"auth\",\"key\":\"zz\",\"sig\":\"\"}",
                       "{\"t\":\"auth\",\"key\":\"00\",\"sig\":\"00\"}")) {
      assertNull(bad, PairingAuth.verify(bad, PairingWire.ROLE_CLIENT, serverHash, clientHash))
    }
  }

  @Test
  fun aMisshapenCertificateHashProducesNoMessage() {
    val (seed, key, name) = identity()
    assertNull(PairingAuth.build(
      seed, key, name, PairingWire.ROLE_CLIENT, ByteArray(31), clientHash))
    assertNull(PairingAuth.build(
      seed, key, name, PairingWire.ROLE_CLIENT, serverHash, ByteArray(0)))
  }

  @Test
  fun theMessageCarriesTheSignatureTheVectorsFix() {
    // The same bytes the desktop produces, so the two ends agree before either has ever
    // seen the other.
    val seed = unhex(vectors.getString("auth_transcript_seed"))
    val publicKey = unhex(vectors.getJSONArray("ed25519").getJSONObject(0).getString("publicKey"))
    val cases = vectors.getJSONArray("auth_transcript")
    for (i in 0 until cases.length()) {
      val v = cases.getJSONObject(i)
      val role = if (v.getString("role") == "server") PairingWire.ROLE_SERVER
                 else PairingWire.ROLE_CLIENT
      val message = PairingAuth.build(
        seed, publicKey, "Vector", role,
        unhex(v.getString("serverCertSha256")), unhex(v.getString("clientCertSha256")))
      assertNotNull(message)
      assertEquals(v.getString("signature"), JSONObject(message!!).getString("sig"))
    }
  }
}
