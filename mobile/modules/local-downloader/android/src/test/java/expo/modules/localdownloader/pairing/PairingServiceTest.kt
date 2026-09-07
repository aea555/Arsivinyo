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
    override fun accepted(path: String, kind: String) {}
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
