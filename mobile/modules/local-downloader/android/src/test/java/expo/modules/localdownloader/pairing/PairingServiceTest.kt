package expo.modules.localdownloader.pairing

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two devices pairing, over real TLS on loopback.
 *
 * The behaviour worth pinning is what happens to a device that has *not* paired: it gets a
 * handshake, an identity check, and a disconnection, and never reaches a verb.
 */
class PairingServiceTest {

  private class EmptyContent(private val dir: File) : PeerContent {
    init { dir.mkdirs() }
    override fun listing(kind: String) = JSONArray()
    override fun openItem(id: String): ItemSource? = null
    override fun destinationFor(name: String, kind: String) = File(dir, File(name).name).path
    override fun accepted(path: String, kind: String, artworkPath: String?) {}
    override fun download(url: String, mediaKind: String) {}
  }

  private lateinit var root: File
  private lateinit var alice: PairingService
  private lateinit var bob: PairingService
  private lateinit var aliceIdentity: PairingIdentity
  private lateinit var bobIdentity: PairingIdentity
  private lateinit var aliceRegistry: PeerRegistry
  private lateinit var bobRegistry: PeerRegistry

  @Before fun setUp() {
    root = Files.createTempDirectory("pairing").toFile()
    aliceIdentity = LoopbackPeers.TestIdentity("Alice")
    bobIdentity = LoopbackPeers.TestIdentity("Bob")
    aliceRegistry = PeerRegistry(File(root, "alice/peers.json"))
    bobRegistry = PeerRegistry(File(root, "bob/peers.json"))
    alice = PairingService(aliceIdentity, aliceRegistry, EmptyContent(File(root, "alice/files")),
      LoopbackPeers::sslContext)
    bob = PairingService(bobIdentity, bobRegistry, EmptyContent(File(root, "bob/files")),
      LoopbackPeers::sslContext)
  }

  @After fun tearDown() {
    alice.stop()
    bob.stop()
    root.deleteRecursively()
  }

  /** Wait for [condition], polling, so a test does not depend on callback ordering. */
  private fun waitFor(seconds: Long = 15, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + seconds * 1000
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    return condition()
  }

  @Test
  fun anUnpairedDeviceIsRefused() {
    assertTrue(alice.listen())
    val refused = CountDownLatch(1)
    var reason = ""
    alice.onRefused = { reason = it; refused.countDown() }

    bob.connectToPeer("127.0.0.1", alice.port)

    assertTrue("the connection is refused", refused.await(20, TimeUnit.SECONDS))
    assertTrue(reason, reason.contains("unpaired"))
    assertTrue("and gets no session", alice.sessions().isEmpty())
    assertNull(alice.sessionFor(Ed25519Keys.fingerprint(bobIdentity.publicKey)))
    assertFalse("nothing is remembered", aliceRegistry.isPaired(bobIdentity.publicKey))
  }

  @Test
  fun theCeremonyPairsBothDevices() {
    assertTrue(alice.listen())
    alice.beginPairing(60)
    bob.beginPairing(60)
    bob.connectToPeer("127.0.0.1", alice.port)

    assertTrue("both devices offer a code",
      waitFor { alice.pendingCode.isNotEmpty() && bob.pendingCode.isNotEmpty() })
    assertEquals(6, alice.pendingCode.length)
    assertEquals("the user is shown one code, not two", alice.pendingCode, bob.pendingCode)
    assertEquals("Bob", alice.pendingName)
    assertEquals("Alice", bob.pendingName)

    assertTrue(bob.confirmPairing())
    assertTrue(alice.confirmPairing())

    assertTrue("each now trusts the other", aliceRegistry.isPaired(bobIdentity.publicKey))
    assertTrue(bobRegistry.isPaired(aliceIdentity.publicKey))
    assertFalse("and pairing mode closes itself", alice.pairingMode)
    assertEquals("", alice.pendingCode)
    assertNotNull(alice.sessionFor(Ed25519Keys.fingerprint(bobIdentity.publicKey)))
  }

  @Test
  fun aPairedDeviceReconnectsWithoutTheCeremony() {
    // Pair once, then drop the connection and come back.
    pair()
    alice.sessions().forEach { it.link.close() }
    bob.sessions().forEach { it.link.close() }
    assertTrue(waitFor { alice.sessions().isEmpty() })

    val connected = CountDownLatch(1)
    alice.onPeerConnected = { _, _ -> connected.countDown() }
    bob.connectToPeer("127.0.0.1", alice.port)

    assertTrue("a known device just connects", connected.await(20, TimeUnit.SECONDS))
    assertEquals("and is not asked to pair again", "", alice.pendingCode)
    assertNotNull(alice.sessionFor(Ed25519Keys.fingerprint(bobIdentity.publicKey)))
  }

  @Test
  fun aForgottenDeviceIsRefusedAgain() {
    pair()
    alice.sessions().forEach { it.link.close() }
    assertTrue(waitFor { alice.sessions().isEmpty() })

    assertTrue(aliceRegistry.forget(Ed25519Keys.fingerprint(bobIdentity.publicKey)))
    val refused = CountDownLatch(1)
    alice.onRefused = { refused.countDown() }
    bob.connectToPeer("127.0.0.1", alice.port)

    assertTrue("forgetting takes effect immediately", refused.await(20, TimeUnit.SECONDS))
    assertTrue(alice.sessions().isEmpty())
  }

  @Test
  fun cancellingTheCeremonyPairsNothing() {
    assertTrue(alice.listen())
    alice.beginPairing(60)
    bob.beginPairing(60)
    bob.connectToPeer("127.0.0.1", alice.port)
    assertTrue(waitFor { alice.pendingCode.isNotEmpty() })

    alice.cancelPairing()
    assertEquals("", alice.pendingCode)
    assertFalse(alice.pairingMode)
    assertFalse("the key is not remembered", aliceRegistry.isPaired(bobIdentity.publicKey))
    assertFalse("and confirming afterwards does nothing", alice.confirmPairing())
    assertTrue(alice.sessions().isEmpty())
  }

  @Test
  fun confirmingWithNoPendingDeviceDoesNothing() {
    assertTrue(alice.listen())
    assertFalse(alice.confirmPairing())
    assertTrue(alice.sessions().isEmpty())
  }

  @Test
  fun aPairingThatCannotBeStoredIsNotReportedAsPaired() {
    assertTrue(alice.listen())
    alice.beginPairing(60)
    bob.beginPairing(60)
    bob.connectToPeer("127.0.0.1", alice.port)
    assertTrue(waitFor { alice.pendingCode.isNotEmpty() })

    // Block the file the registry saves through. Confirming can no longer be written down.
    val peersFile = File(root, "alice/peers.json")
    peersFile.parentFile?.mkdirs()
    File(peersFile.parentFile, "${peersFile.name}.tmp").mkdirs()

    assertFalse("confirming must fail when the pairing cannot be stored",
      alice.confirmPairing())
    assertFalse(aliceRegistry.isPaired(bobIdentity.publicKey))
    assertTrue("and no session is opened for a device that is not really paired",
      alice.sessions().isEmpty())
  }

  @Test
  fun aPairedDeviceCanMoveAFile() {
    pair()
    val source = File(root, "bob/files/track.m4a")
    source.parentFile?.mkdirs()
    val bytes = ByteArray(120_000) { (it and 0xff).toByte() }
    source.writeBytes(bytes)

    val session = bob.sessionFor(Ed25519Keys.fingerprint(aliceIdentity.publicKey))
    assertNotNull("the sender has a session", session)

    val landed = CountDownLatch(1)
    alice.sessions().first().onFileReceived = { _, _ -> landed.countDown() }
    assertTrue(session!!.sendFile(source, "music"))
    assertTrue("the file arrives", landed.await(30, TimeUnit.SECONDS))
    assertArrayEquals(bytes, File(root, "alice/files/track.m4a").readBytes())
  }

  /**
   * A client that follows the protocol until the reveal, then reveals a different nonce
   * from the one it committed to: what steering the code would look like.
   */
  @Test
  fun aRevealThatBreaksItsCommitmentIsRefused() {
    assertTrue(alice.listen())
    alice.beginPairing(60)
    val refused = CountDownLatch(1)
    alice.onRefused = { refused.countDown() }

    val mallory = LoopbackPeers.TestIdentity("Mallory")
    val socket = LoopbackPeers.sslContext().socketFactory
      .createSocket("127.0.0.1", alice.port) as javax.net.ssl.SSLSocket
    val link = PeerLink(socket, PairingWire.ROLE_CLIENT, mallory)
    val committed = ByteArray(PairingWire.PAIRING_NONCE_BYTES) { 1 }
    val revealed = ByteArray(PairingWire.PAIRING_NONCE_BYTES) { 2 }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    link.onAuthenticated = { _, _ ->
      link.sendControl(org.json.JSONObject().put("t", "pair-commit")
        .put("c", hex(PairingWire.commitment(committed))))
    }
    link.onControl = { message ->
      if (message.optString("t") == "pair-nonce") {
        link.sendControl(org.json.JSONObject().put("t", "pair-reveal").put("n", hex(revealed)))
      }
    }
    link.start()

    assertTrue("the broken commitment is refused", refused.await(15, TimeUnit.SECONDS))
    assertEquals("and no code is offered for it", "", alice.pendingCode)
    assertFalse(alice.confirmPairing())
    link.close()
  }

  @Test
  fun bothDevicesShowACodeThatDependsOnMoreThanTheKeys() {
    assertTrue(alice.listen())
    alice.beginPairing(60)
    bob.beginPairing(60)
    bob.connectToPeer("127.0.0.1", alice.port)
    assertTrue(waitFor { alice.pendingCode.isNotEmpty() && bob.pendingCode.isNotEmpty() })
    assertEquals(alice.pendingCode, bob.pendingCode)
    // With fresh nonces on every run the code is not the keys-only v1 code, bar a one in a
    // million coincidence: it is the nonces that take away a man in the middle's aim.
    val keysOnly = PairingWire.pairingCodeFor(aliceIdentity.publicKey, bobIdentity.publicKey)
    val second = run {
      alice.cancelPairing()
      bob.cancelPairing()
      assertTrue(waitFor { alice.pendingCode.isEmpty() && bob.pendingCode.isEmpty() })
      alice.beginPairing(60)
      bob.beginPairing(60)
      bob.connectToPeer("127.0.0.1", alice.port)
      assertTrue(waitFor { alice.pendingCode.isNotEmpty() && bob.pendingCode.isNotEmpty() })
      alice.pendingCode
    }
    assertTrue("two ceremonies between the same keys do not give the same code, nor the keys-only one",
      second != keysOnly || alice.pendingCode != keysOnly)
  }

  @Test
  fun twoConnectionsAtOnceSettleOnOne() {
    pair()
    assertTrue(bob.listen())
    // Each connects to the other at the same moment, as both would on finding each other.
    alice.connectToPeer("127.0.0.1", bob.port)
    bob.connectToPeer("127.0.0.1", alice.port)
    Thread.sleep(1500)
    // Waited for as a whole: on the way there each side can briefly hold a different one.
    assertTrue("each ends with one connection to the other, the same one seen from both ends",
      waitFor {
        alice.sessions().size == 1 && bob.sessions().size == 1 &&
          alice.sessions().single().link.role != bob.sessions().single().link.role
      })
  }

  @Test
  fun aConnectionThatFailsIsNoLongerListed() {
    pair()
    assertTrue(waitFor { alice.sessions().size == 1 && bob.sessions().size == 1 })
    // A frame no peer may send: bob's end tears the link down as a failure, not a close.
    val aliceLink = alice.sessions().single().link
    val output = aliceLink.javaClass.getDeclaredField("socket").apply { isAccessible = true }
      .get(aliceLink) as javax.net.ssl.SSLSocket
    output.outputStream.write(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f, 0))
    output.outputStream.flush()
    assertTrue("the failed connection is dropped rather than shown as connected",
      waitFor { bob.sessions().isEmpty() })
  }

  /**
   * One user confirms and asks for a listing at once, while the other is still reading the
   * code. The request has to wait for the other confirmation, not vanish into its ceremony.
   */
  @Test
  fun aRequestMadeBeforeTheOtherSideConfirmsIsAnsweredOnceItDoes() {
    assertTrue(alice.listen())
    alice.beginPairing(60)
    bob.beginPairing(60)
    bob.connectToPeer("127.0.0.1", alice.port)
    assertTrue(waitFor { alice.pendingCode.isNotEmpty() && bob.pendingCode.isNotEmpty() })

    assertTrue(bob.confirmPairing())
    val answered = CountDownLatch(1)
    val session = bob.sessions().single()
    session.onListing = { _, _ -> answered.countDown() }
    assertTrue(session.requestListing("music"))
    Thread.sleep(500)
    assertEquals("nothing comes back while the other side is still deciding", 1L, answered.count)

    assertTrue(alice.confirmPairing())
    assertTrue("the listing arrives once it confirms", answered.await(15, TimeUnit.SECONDS))
  }

  private fun pair() {
    assertTrue(alice.listen())
    alice.beginPairing(60)
    bob.beginPairing(60)
    bob.connectToPeer("127.0.0.1", alice.port)
    assertTrue("both devices offer a code",
      waitFor { alice.pendingCode.isNotEmpty() && bob.pendingCode.isNotEmpty() })
    assertTrue(bob.confirmPairing())
    assertTrue(alice.confirmPairing())
  }
}
