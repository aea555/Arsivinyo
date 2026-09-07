package expo.modules.localdownloader.pairing

import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SessionKeys] on a real device, which is the only place it can run.
 *
 * `AndroidKeyStore` is a JCA provider backed by the platform's keystore daemon, so it does
 * not exist in the JVM harness that covers the rest of the pairing code, and the
 * `android.jar` the typecheck uses is a stub whose methods throw. Everything else about
 * pairing is tested off a device; this is the part that cannot be.
 *
 * The question it answers cannot be reasoned out: whether `KeyManagerFactory` will serve a
 * non-extractable Keystore key for TLS. If it will not, [SessionKeys.sslContext] hands back
 * a context that fails at handshake time and pairing does not work at all.
 *
 * Plain JUnit rather than `AndroidJUnit4`: nothing here needs a Context, so the test needs
 * no `androidx.test` beyond the runner that launches it.
 *
 * Run with:  ./gradlew :expo-local-downloader:connectedAndroidTest
 */
class SessionKeysInstrumentedTest {

  @Before fun setUp() = SessionKeys.reset()
  @After fun tearDown() = SessionKeys.reset()

  @Test
  fun aSessionKeyIsCreatedAndCarriesACertificate() {
    assertNotNull("the Keystore produced no usable key", SessionKeys.sslContext())

    // The certificate is what the auth transcript names, so it has to exist, and a
    // self-signed key has exactly one.
    val chain = keystore().getCertificateChain(ALIAS)
    assertNotNull("no certificate was issued for the key", chain)
    assertEquals(1, chain.size)
  }

  @Test
  fun theSameKeyComesBackOnASecondCall() {
    val first = certificateBytes()
    val second = certificateBytes()
    assertArrayEquals("the key must not be regenerated on every connection", first, second)
  }

  @Test
  fun resetProducesADifferentKey() {
    val first = certificateBytes()
    SessionKeys.reset()
    val second = certificateBytes()
    assertTrue("reset must actually replace the key", !first.contentEquals(second))
  }

  @Test
  fun twoLinksCompleteAHandshakeAndAuthenticate() {
    // The whole point of the class: a Keystore key that TLS can actually use, on both
    // ends, with client certificates required, because the transcript names both of them.
    val serverContext = SessionKeys.sslContext()
    val clientContext = SessionKeys.sslContext()
    assertNotNull(serverContext)
    assertNotNull(clientContext)

    val listener = serverContext!!.serverSocketFactory
      .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
    listener.needClientAuth = true

    val serverIdentity = FixedIdentity("Server")
    val clientIdentity = FixedIdentity("Client")

    var server: PeerLink? = null
    val ready = CountDownLatch(2)
    val failures = mutableListOf<String>()
    fun note(what: String) {
      synchronized(failures) { failures.add(what) }
      ready.countDown()
    }

    Thread {
      runCatching {
        val socket = listener.accept() as SSLSocket
        server = PeerLink(socket, PairingWire.ROLE_SERVER, serverIdentity).apply {
          onAuthenticated = { _, _ -> ready.countDown() }
          onFailed = { note("server: $it") }
          start()
        }
      }.onFailure { note("accept: ${it.message}") }
    }.apply { isDaemon = true }.start()

    val clientSocket = clientContext!!.socketFactory
      .createSocket(listener.inetAddress, listener.localPort) as SSLSocket
    val client = PeerLink(clientSocket, PairingWire.ROLE_CLIENT, clientIdentity).apply {
      onAuthenticated = { _, _ -> ready.countDown() }
      onFailed = { note("client: $it") }
      start()
    }

    val settled = ready.await(30, TimeUnit.SECONDS)
    try {
      assertTrue("neither end settled in time", settled)
      assertTrue("the handshake failed: $failures", failures.isEmpty())
      assertTrue("the client did not authenticate", client.isAuthenticated)
      assertTrue("the server did not authenticate", server!!.isAuthenticated)
      assertArrayEquals(serverIdentity.publicKey, client.peerKey)
      assertArrayEquals(clientIdentity.publicKey, server!!.peerKey)

      // And the code the user compares agrees on both screens.
      assertEquals(
        PairingWire.pairingCodeFor(serverIdentity.publicKey, server!!.peerKey),
        PairingWire.pairingCodeFor(clientIdentity.publicKey, client.peerKey))
    } finally {
      client.close()
      server?.close()
      listener.close()
    }
  }

  private fun keystore(): KeyStore =
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  private fun certificateBytes(): ByteArray {
    assertNotNull(SessionKeys.sslContext())
    return keystore().getCertificate(ALIAS).encoded
  }

  /** An Ed25519 identity held in memory, so this test needs no files and no Context. */
  private class FixedIdentity(override val deviceName: String) : PairingIdentity {
    private val seed: ByteArray
    override val publicKey: ByteArray

    init {
      val (s, p) = Ed25519Keys.generate()!!
      seed = s
      publicKey = p
    }

    override fun sign(message: ByteArray): ByteArray = Ed25519Keys.sign(seed, message)
  }

  private companion object {
    /** Must match the alias SessionKeys uses. */
    const val ALIAS = "arsivinyo-pairing-session"
  }
}
