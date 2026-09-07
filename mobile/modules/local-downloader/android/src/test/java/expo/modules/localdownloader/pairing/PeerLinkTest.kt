package expo.modules.localdownloader.pairing

import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two links over a real TLS connection on loopback.
 *
 * The framing and the transcript are covered by the vectors; what this adds is that the
 * two halves actually talk — that a handshake completes, that both sides authenticate,
 * and above all that the failures fail. A device is only as safe as what it refuses.
 */
class PeerLinkTest {

  /** An identity backed by a freshly generated key, with no Context in sight. */
  private class TestIdentity(override val deviceName: String) : PairingIdentity {
    private val seed: ByteArray
    override val publicKey: ByteArray

    init {
      val (s, p) = Ed25519Keys.generate()!!
      seed = s
      publicKey = p
    }

    override fun sign(message: ByteArray): ByteArray = Ed25519Keys.sign(seed, message)
  }

  /** An identity that signs a transcript for some other connection. A man in the middle. */
  private class RelayingIdentity(private val real: PairingIdentity) : PairingIdentity {
    override val publicKey: ByteArray get() = real.publicKey
    override val deviceName: String get() = real.deviceName
    override fun sign(message: ByteArray): ByteArray {
      // Sign a transcript naming certificates that are not the ones in play here, which
      // is exactly what a signature relayed from another leg looks like.
      val elsewhere = PairingWire.authTranscript(
        PairingWire.ROLE_CLIENT,
        ByteArray(PairingWire.CERT_HASH_BYTES) { 0xAA.toByte() },
        ByteArray(PairingWire.CERT_HASH_BYTES) { 0xBB.toByte() })
      return real.sign(elsewhere)
    }
  }

  /**
   * A self-signed P-256 certificate and an SSLContext over it.
   *
   * On a device this comes from the Android Keystore, which issues its own certificate.
   * Here it is built with BouncyCastle's certificate builder, which is a test-only
   * dependency and never reaches the APK.
   */
  private fun sslContext(): SSLContext {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val name = X500Name("CN=arsivinyo")
    val now = System.currentTimeMillis()
    val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(
      JcaX509v3CertificateBuilder(
        name, BigInteger.valueOf(now), Date(now - 86_400_000), Date(now + 86_400_000),
        name, pair.public,
      ).build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)))

    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, null)
      setKeyEntry("session", pair.private, CHARS, arrayOf(certificate))
    }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      .apply { init(store, CHARS) }

    // Nothing is verified at this layer by design: the certificate says nothing about who
    // the peer is, and the Ed25519 transcript is what settles that.
    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<X509Certificate>?, type: String?) {}
      override fun checkServerTrusted(chain: Array<X509Certificate>?, type: String?) {}
      override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })
    return SSLContext.getInstance("TLS").apply {
      init(managers.keyManagers, trustAll, SecureRandom())
    }
  }

  /** Wire two links together over loopback and run [body] once both are authenticated. */
  private fun connected(
    serverIdentity: PairingIdentity = TestIdentity("Desktop"),
    clientIdentity: PairingIdentity = TestIdentity("Phone"),
    expectFailure: Boolean = false,
    body: (server: PeerLink, client: PeerLink) -> Unit,
  ) {
    val context = sslContext()
    val listener = context.serverSocketFactory
      .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
    listener.needClientAuth = true  // both certificates must exist for the transcript

    var server: PeerLink? = null
    val accepted = CountDownLatch(1)
    Thread {
      runCatching {
        val socket = listener.accept() as SSLSocket
        server = PeerLink(socket, PairingWire.ROLE_SERVER, serverIdentity).also {
          it.onAuthenticated = { _, _ -> }
          it.start()
        }
      }
      accepted.countDown()
    }.apply { isDaemon = true }.start()

    val clientSocket = context.socketFactory
      .createSocket(listener.inetAddress, listener.localPort) as SSLSocket
    val client = PeerLink(clientSocket, PairingWire.ROLE_CLIENT, clientIdentity)

    val ready = CountDownLatch(if (expectFailure) 1 else 2)
    val failures = mutableListOf<String>()
    client.onAuthenticated = { _, _ -> ready.countDown() }
    client.onFailed = { synchronized(failures) { failures.add(it) }; ready.countDown() }
    client.start()

    assertTrue("the server accepted", accepted.await(10, TimeUnit.SECONDS))
    server!!.onAuthenticated = { _, _ -> ready.countDown() }
    server!!.onFailed = { synchronized(failures) { failures.add(it) }; ready.countDown() }
    // The server may already have authenticated before its callback was attached.
    if (server!!.isAuthenticated) ready.countDown()

    ready.await(10, TimeUnit.SECONDS)
    try {
      body(server!!, client)
    } finally {
      client.close()
      server!!.close()
      listener.close()
    }
  }

  @Test
  fun bothEndsAuthenticate() {
    val serverIdentity = TestIdentity("Desktop")
    val clientIdentity = TestIdentity("Phone")
    connected(serverIdentity, clientIdentity) { server, client ->
      assertTrue("the client authenticated the server", client.isAuthenticated)
      assertTrue("the server authenticated the client", server.isAuthenticated)
      assertArrayEquals("each learned the other's real key",
        serverIdentity.publicKey, client.peerKey)
      assertArrayEquals(clientIdentity.publicKey, server.peerKey)
      assertEquals("Desktop", client.peerName)
      assertEquals("Phone", server.peerName)
    }
  }

  @Test
  fun bothEndsDeriveTheSamePairingCode() {
    val serverIdentity = TestIdentity("Desktop")
    val clientIdentity = TestIdentity("Phone")
    connected(serverIdentity, clientIdentity) { server, client ->
      val onServer = PairingWire.pairingCodeFor(serverIdentity.publicKey, server.peerKey)
      val onClient = PairingWire.pairingCodeFor(clientIdentity.publicKey, client.peerKey)
      assertEquals(6, onServer.length)
      assertEquals("the user is shown one code, not two", onServer, onClient)
    }
  }

  @Test
  fun aSignatureBoundToAnotherSessionIsRefused() {
    val honest = TestIdentity("Desktop")
    connected(honest, RelayingIdentity(TestIdentity("Impostor")), expectFailure = true) {
      server, _ ->
      assertFalse("a relayed signature does not authenticate", server.isAuthenticated)
      assertTrue("and the key is never learned", server.peerKey.isEmpty())
    }
  }

  @Test
  fun controlMessagesArriveOnlyAfterAuthentication() {
    connected { server, client ->
      val received = CountDownLatch(1)
      var seen: JSONObject? = null
      server.onControl = { seen = it; received.countDown() }

      assertTrue(client.sendControl(JSONObject().put("t", "list").put("kind", "music")))
      assertTrue("the message arrives", received.await(10, TimeUnit.SECONDS))
      assertEquals("list", seen!!.getString("t"))
      assertEquals("music", seen!!.getString("kind"))
    }
  }

  @Test
  fun bulkFramesCrossIntact() {
    connected { server, client ->
      // Larger than one read, so it exercises the buffer joining reads back together.
      val payload = ByteArray(400_000) { ((it * 7) and 0xff).toByte() }
      val received = CountDownLatch(1)
      val gathered = java.io.ByteArrayOutputStream()
      server.onBulk = {
        gathered.write(it)
        if (gathered.size() >= payload.size) received.countDown()
      }

      assertTrue(client.sendBulk(payload))
      assertTrue("the bulk frame arrives", received.await(20, TimeUnit.SECONDS))
      assertArrayEquals("byte for byte", payload, gathered.toByteArray())
    }
  }

  @Test
  fun aFrameOverTheCapTearsTheLinkDown() {
    connected { server, client ->
      val failed = CountDownLatch(1)
      var reason = ""
      server.onFailed = { reason = it; failed.countDown() }

      // Announce far more than the cap allows, without sending it.
      val header = byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0)
      client.javaClass.getDeclaredMethod("sendFrame", Byte::class.java, ByteArray::class.java)
      // Writing the raw header is the point: no legitimate sender can produce it.
      rawWrite(client, header)

      assertTrue("the receiver refuses it", failed.await(10, TimeUnit.SECONDS))
      assertTrue(reason, reason.contains("oversized"))
      assertFalse("and stops reading", server.isAuthenticated && reason.isEmpty())
    }
  }

  /**
   * A peer that completes TLS and then talks without ever proving who it is.
   *
   * [body] gets a socket already through the handshake. Nothing it writes should be acted
   * on: until the `auth` message verifies, the link has no idea who is on the other end.
   */
  private fun rawPeer(body: (socket: SSLSocket, server: () -> PeerLink?) -> Unit) {
    val context = sslContext()
    val listener = context.serverSocketFactory
      .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
    listener.needClientAuth = true

    var server: PeerLink? = null
    val accepted = CountDownLatch(1)
    Thread {
      runCatching {
        val socket = listener.accept() as SSLSocket
        server = PeerLink(socket, PairingWire.ROLE_SERVER, TestIdentity("Desktop"))
        server!!.start()
      }
      accepted.countDown()
    }.apply { isDaemon = true }.start()

    val socket = context.socketFactory
      .createSocket(listener.inetAddress, listener.localPort) as SSLSocket
    socket.startHandshake()
    assertTrue(accepted.await(10, TimeUnit.SECONDS))
    try {
      body(socket) { server }
    } finally {
      runCatching { socket.close() }
      server?.close()
      listener.close()
    }
  }

  @Test
  fun bulkBeforeAuthenticationIsRefused() {
    rawPeer { socket, server ->
      val failed = CountDownLatch(1)
      var reason = ""
      server()!!.onFailed = { reason = it; failed.countDown() }

      // A perfectly well-formed bulk frame from a device that has not said who it is.
      // Acting on it would mean writing an unknown device's bytes to disk.
      val frame = PairingWire.encodeFrame(PairingWire.TYPE_BULK, ByteArray(64))!!
      socket.outputStream.write(frame)
      socket.outputStream.flush()

      assertTrue("the link refuses it", failed.await(10, TimeUnit.SECONDS))
      assertTrue(reason, reason.contains("before authenticating"))
      assertFalse(server()!!.isAuthenticated)
    }
  }

  @Test
  fun aRequestBeforeAuthenticationIsRefused() {
    rawPeer { socket, server ->
      val failed = CountDownLatch(1)
      var reason = ""
      server()!!.onFailed = { reason = it; failed.countDown() }
      var served = false
      server()!!.onControl = { served = true }

      val frame = PairingWire.encodeFrame(PairingWire.TYPE_CONTROL,
        JSONObject().put("t", "list").put("kind", "music").toString().toByteArray())!!
      socket.outputStream.write(frame)
      socket.outputStream.flush()

      assertTrue("the link refuses it", failed.await(10, TimeUnit.SECONDS))
      assertFalse("and never hands the request on", served)
      assertFalse(server()!!.isAuthenticated)
    }
  }

  @Test
  fun anAuthMessageWithSomeoneElsesKeyIsRefused() {
    rawPeer { socket, server ->
      val failed = CountDownLatch(1)
      server()!!.onFailed = { failed.countDown() }

      // A real signature, but claiming a key that did not make it.
      val signer = TestIdentity("Impostor")
      val victim = TestIdentity("Victim")
      val own = MessageDigest.getInstance("SHA-256").digest(
        socket.session.localCertificates[0].encoded)
      val peer = MessageDigest.getInstance("SHA-256").digest(
        socket.session.peerCertificates[0].encoded)
      val transcript = PairingWire.authTranscript(PairingWire.ROLE_CLIENT, peer, own)
      val message = JSONObject()
        .put("t", "auth").put("v", 1)
        .put("key", victim.publicKey.joinToString("") { "%02x".format(it) })
        .put("name", "Impostor")
        .put("sig", signer.sign(transcript).joinToString("") { "%02x".format(it) })

      val frame = PairingWire.encodeFrame(
        PairingWire.TYPE_CONTROL, message.toString().toByteArray())!!
      socket.outputStream.write(frame)
      socket.outputStream.flush()

      assertTrue("claiming a key you do not hold is refused",
        failed.await(10, TimeUnit.SECONDS))
      assertFalse(server()!!.isAuthenticated)
    }
  }

  /** Write bytes straight onto the socket, below the framing, the way a hostile peer can. */
  private fun rawWrite(link: PeerLink, bytes: ByteArray) {
    val field = PeerLink::class.java.getDeclaredField("socket").apply { isAccessible = true }
    val socket = field.get(link) as SSLSocket
    socket.outputStream.write(bytes)
    socket.outputStream.flush()
  }

  private companion object {
    val CHARS = "session".toCharArray()
  }
}
