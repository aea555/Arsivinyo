package expo.modules.localdownloader.pairing

import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
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

/**
 * Two links over a real TLS connection on loopback, for the tests that need one.
 *
 * Nothing here is mocked: a genuine handshake, genuine certificates, genuine sockets. The
 * only thing that differs from a device is where the TLS key comes from — the Android
 * Keystore there, BouncyCastle here, which is a test-only dependency.
 */
object LoopbackPeers {

  private val PASSWORD = "session".toCharArray()

  /** An identity backed by a freshly generated key, with no Context in sight. */
  class TestIdentity(override val deviceName: String) : PairingIdentity {
    private val seed: ByteArray
    override val publicKey: ByteArray

    init {
      val (s, p) = Ed25519Keys.generate()!!
      seed = s
      publicKey = p
    }

    override fun sign(message: ByteArray): ByteArray = Ed25519Keys.sign(seed, message)
  }

  /** A self-signed P-256 certificate and an SSLContext over it. */
  fun sslContext(): SSLContext {
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
      setKeyEntry("session", pair.private, PASSWORD, arrayOf(certificate))
    }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      .apply { init(store, PASSWORD) }

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

  fun listen(): SSLServerSocket {
    val listener = sslContext().serverSocketFactory
      .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
    // Both certificates have to exist, because the transcript names both of them.
    listener.needClientAuth = true
    return listener
  }

  /** Wire two links together and run [body] once both have authenticated. */
  fun connected(
    serverIdentity: PairingIdentity = TestIdentity("Desktop"),
    clientIdentity: PairingIdentity = TestIdentity("Phone"),
    expectFailure: Boolean = false,
    body: (server: PeerLink, client: PeerLink) -> Unit,
  ) {
    val listener = listen()
    var server: PeerLink? = null
    val accepted = CountDownLatch(1)
    Thread {
      runCatching {
        val socket = listener.accept() as SSLSocket
        server = PeerLink(socket, PairingWire.ROLE_SERVER, serverIdentity).also { it.start() }
      }
      accepted.countDown()
    }.apply { isDaemon = true }.start()

    val clientSocket = sslContext().socketFactory
      .createSocket(listener.inetAddress, listener.localPort) as SSLSocket
    val client = PeerLink(clientSocket, PairingWire.ROLE_CLIENT, clientIdentity)

    val ready = CountDownLatch(if (expectFailure) 1 else 2)
    client.onAuthenticated = { _, _ -> ready.countDown() }
    client.onFailed = { ready.countDown() }
    client.start()

    check(accepted.await(10, TimeUnit.SECONDS)) { "the server never accepted" }
    server!!.onAuthenticated = { _, _ -> ready.countDown() }
    server!!.onFailed = { ready.countDown() }
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
}
