package expo.modules.localdownloader.pairing

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery on a real device, which is the only place `NsdManager` exists.
 *
 * Two instances in one process: one announces, the other looks. That is enough to catch
 * the things that are easy to get wrong and invisible until a second device is in the
 * room — a malformed service type, TXT records that never arrive because the service was
 * not resolved, and a device listing itself.
 *
 * Needs a network interface. On a phone with Wi-Fi off there is nothing for mDNS to run
 * over and this will fail rather than pass quietly, which is the honest outcome.
 */
class DiscoveryInstrumentedTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private var announcer: Discovery? = null
  private var finder: Discovery? = null

  @After fun tearDown() {
    announcer?.stop()
    finder?.stop()
  }

  @Test
  fun oneDeviceFindsAnother() {
    // Distinct fingerprints, so the finder does not dismiss the announcer as itself.
    val announced = Ed25519Keys.fingerprint(Ed25519Keys.generate()!!.second)
    val looking = Ed25519Keys.fingerprint(Ed25519Keys.generate()!!.second)

    announcer = Discovery(context)
    assertTrue("the device could not announce itself",
      announcer!!.start(announced, "Announcer", 7441))

    val seen = CountDownLatch(1)
    var peer: Discovery.Found? = null
    finder = Discovery(context).apply {
      onPeerFound = { found ->
        if (found.fingerprint == announced) {
          peer = found
          seen.countDown()
        }
      }
    }
    // Port 0: this one only listens, which is what a device not accepting connections does.
    assertTrue(finder!!.start(looking, "Finder", 0))

    assertTrue("the announcing device was never found", seen.await(45, TimeUnit.SECONDS))
    assertNotNull(peer)
    assertEquals("the name is carried in the TXT record", "Announcer", peer!!.name)
    assertEquals("and so is the port", 7441, peer!!.port)
    assertTrue("with an address to connect to", peer!!.host.isNotEmpty())

    assertTrue("a device must never list itself",
      finder!!.peers().none { it.fingerprint == looking })
  }

  @Test
  fun stoppingClearsWhatWasFound() {
    val announced = Ed25519Keys.fingerprint(Ed25519Keys.generate()!!.second)
    announcer = Discovery(context)
    assertTrue(announcer!!.start(announced, "Announcer", 7442))

    val seen = CountDownLatch(1)
    finder = Discovery(context).apply {
      onPeerFound = { if (it.fingerprint == announced) seen.countDown() }
    }
    assertTrue(finder!!.start(
      Ed25519Keys.fingerprint(Ed25519Keys.generate()!!.second), "Finder", 0))
    assertTrue("the announcing device was never found", seen.await(45, TimeUnit.SECONDS))

    finder!!.stop()
    assertTrue("stopping leaves no stale peers behind", finder!!.peers().isEmpty())
  }
}
