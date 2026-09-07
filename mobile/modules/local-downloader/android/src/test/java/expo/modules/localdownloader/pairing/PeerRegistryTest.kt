package expo.modules.localdownloader.pairing

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Who this device trusts, and that the answer survives a restart. */
class PeerRegistryTest {

  private lateinit var dir: File
  private lateinit var file: File

  @Before fun setUp() {
    dir = Files.createTempDirectory("peers").toFile()
    file = File(dir, "pairing/peers.json")
  }

  @After fun tearDown() {
    dir.deleteRecursively()
  }

  private fun key(seed: Int) = ByteArray(Ed25519Keys.PUBLIC_BYTES) { (seed * 31 + it).toByte() }

  @Test
  fun anUnknownKeyIsNotPaired() {
    val registry = PeerRegistry(file)
    assertFalse(registry.isPaired(key(1)))
    assertNull(registry.peerFor(key(1)))
    assertTrue(registry.all().isEmpty())
  }

  @Test
  fun aRememberedKeyIsPaired() {
    val registry = PeerRegistry(file)
    assertTrue(registry.remember(key(1), "Desktop", "192.168.1.5:7441"))
    assertTrue(registry.isPaired(key(1)))
    assertFalse("a different key is still a stranger", registry.isPaired(key(2)))
    assertEquals("Desktop", registry.peerFor(key(1))!!.name)
  }

  @Test
  fun aMalformedKeyIsRefused() {
    val registry = PeerRegistry(file)
    assertFalse(registry.remember(ByteArray(0), "x", ""))
    assertFalse(registry.remember(ByteArray(31), "x", ""))
    assertTrue(registry.all().isEmpty())
    // And an odd-length key can never match one that was stored.
    assertFalse(registry.isPaired(ByteArray(31)))
  }

  @Test
  fun pairingsSurviveARestart() {
    PeerRegistry(file).apply {
      remember(key(1), "Desktop", "192.168.1.5:7441")
      remember(key(2), "Laptop", "192.168.1.6:7441")
    }
    val reopened = PeerRegistry(file)
    assertEquals(2, reopened.all().size)
    assertTrue(reopened.isPaired(key(1)))
    assertTrue(reopened.isPaired(key(2)))
    assertEquals("Laptop", reopened.peerFor(key(2))!!.name)
    assertEquals("192.168.1.6:7441", reopened.peerFor(key(2))!!.lastAddress)
  }

  @Test
  fun forgettingIsImmediateAndSurvivesARestart() {
    val registry = PeerRegistry(file)
    registry.remember(key(1), "Desktop", "")
    val fingerprint = registry.peerFor(key(1))!!.fingerprint
    assertEquals(Ed25519Keys.fingerprint(key(1)), fingerprint)

    assertTrue(registry.forget(fingerprint))
    assertFalse(registry.isPaired(key(1)))
    assertFalse("forgetting twice is not an error, but changes nothing",
      registry.forget(fingerprint))
    assertFalse(PeerRegistry(file).isPaired(key(1)))
  }

  @Test
  fun rePairingKeepsTheOriginalTime() {
    val registry = PeerRegistry(file)
    registry.remember(key(1), "Desktop", "a")
    val first = registry.peerFor(key(1))!!.pairedAt
    Thread.sleep(5)
    registry.remember(key(1), "Desktop renamed", "b")

    assertEquals(1, registry.all().size)
    assertEquals("re-pairing must not reorder the list", first, registry.peerFor(key(1))!!.pairedAt)
    assertEquals("Desktop renamed", registry.peerFor(key(1))!!.name)
    assertEquals("b", registry.peerFor(key(1))!!.lastAddress)
  }

  @Test
  fun anAddressIsRecordedWithoutDisturbingAnythingElse() {
    val registry = PeerRegistry(file)
    registry.remember(key(1), "Desktop", "old")
    registry.noteAddress(key(1), "new")
    assertEquals("new", registry.peerFor(key(1))!!.lastAddress)
    registry.noteAddress(key(1), "")
    assertEquals("an empty address is ignored", "new", registry.peerFor(key(1))!!.lastAddress)
    registry.noteAddress(key(2), "somewhere")
    assertFalse("an unpaired key is not created by noting an address", registry.isPaired(key(2)))
  }

  @Test
  fun aCorruptFileCostsNoMoreThanItHasTo() {
    file.parentFile?.mkdirs()
    file.writeText("this is not json")
    assertTrue("a broken file reads as no pairings", PeerRegistry(file).all().isEmpty())

    // One unusable entry must not take the others with it.
    val goodHex = key(1).joinToString("") { "%02x".format(it) }
    file.writeText("""[{"key":"zz","name":"not hex"},
                       {"key":"${goodHex.substring(2)}","name":"31 bytes, one short"},
                       {"key":"","name":"absent"},
                       {"key":"$goodHex","name":"good"}]""")
    val registry = PeerRegistry(file)
    assertEquals(1, registry.all().size)
    assertEquals("good", registry.peerFor(key(1))!!.name)
  }

  @Test
  fun theFileIsReplacedWholeRatherThanEditedInPlace() {
    val registry = PeerRegistry(file)
    registry.remember(key(1), "Desktop", "a")
    registry.remember(key(2), "Laptop", "b")
    registry.forget(Ed25519Keys.fingerprint(key(1)))
    // A leftover temp file would mean an interrupted save could resurrect a forgotten peer.
    assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    assertFalse(file.readText().contains("Desktop"))
  }
}
