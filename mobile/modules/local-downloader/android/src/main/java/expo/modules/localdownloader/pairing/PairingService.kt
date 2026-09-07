package expo.modules.localdownloader.pairing

import java.net.InetAddress
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Listens for peers, opens connections to them, and runs the pairing ceremony.
 *
 * The counterpart of `desktop/src/PairingService.cpp`.
 *
 * **Who may talk to this device.** A connection that authenticates with a key this device
 * has not paired with is closed. The exception is pairing mode, which the user turns on
 * deliberately and which ends as soon as one device is paired or the window elapses. So an
 * idle device gives an unknown peer a TLS handshake, an identity check, and a disconnect.
 *
 * **The ceremony.** Both ends show six digits derived from the two public keys. Confirming
 * they match is what authenticates each key to the other: a man in the middle holds two
 * different key pairs and cannot make both ends show the same six digits.
 *
 * [sslContext] is a function rather than a value so the tests can supply their own — on a
 * device it is `SessionKeys::sslContext`, whose key lives in the Android Keystore and
 * cannot exist off one.
 */
class PairingService(
  private val identity: PairingIdentity,
  private val registry: PeerRegistry,
  private val content: PeerContent,
  private val sslContext: () -> SSLContext?,
) {

  var onPeerConnected: ((fingerprint: String, name: String) -> Unit)? = null
  var onPeerDisconnected: ((fingerprint: String) -> Unit)? = null
  var onPaired: ((fingerprint: String, name: String) -> Unit)? = null
  /** A connection was refused or lost. Never carries a file name. */
  var onRefused: ((reason: String) -> Unit)? = null
  /** The six digits changed: non-empty means the user is being asked to compare them. */
  var onPendingChanged: ((code: String, name: String) -> Unit)? = null

  @Volatile var pairingMode: Boolean = false
    private set
  @Volatile var pendingCode: String = ""
    private set
  @Volatile var pendingName: String = ""
    private set

  private val sessions = CopyOnWriteArrayList<PeerSession>()
  private val lock = Any()
  private var listener: SSLServerSocket? = null
  private var acceptor: Thread? = null
  private var pendingLink: PeerLink? = null
  private var pendingKey: ByteArray = ByteArray(0)
  private var pairingTimer: Timer? = null

  val port: Int get() = listener?.localPort ?: 0
  val isListening: Boolean get() = listener?.isClosed == false

  fun sessions(): List<PeerSession> = sessions.toList()

  fun sessionFor(fingerprint: String): PeerSession? =
    sessions.firstOrNull { Ed25519Keys.fingerprint(it.link.peerKey) == fingerprint }

  /** [port] of 0 asks the system for a free one. */
  fun listen(port: Int = 0): Boolean = synchronized(lock) {
    if (listener != null) return true
    val context = sslContext() ?: return false

    val socket = runCatching {
      (context.serverSocketFactory.createServerSocket(port, BACKLOG) as SSLServerSocket).apply {
        // Both certificates have to exist: the auth transcript names both of them.
        needClientAuth = true
      }
    }.getOrNull() ?: return false

    listener = socket
    acceptor = Thread({ acceptLoop(socket) }, "pairing-accept").apply {
      isDaemon = true
      start()
    }
    true
  }

  fun stop() {
    synchronized(lock) {
      runCatching { listener?.close() }
      listener = null
      acceptor = null
    }
    cancelPairing()
    for (session in sessions) session.link.close()
    sessions.clear()
  }

  private fun acceptLoop(socket: SSLServerSocket) {
    while (!socket.isClosed) {
      val accepted = runCatching { socket.accept() as SSLSocket }.getOrNull() ?: return
      adopt(PeerLink(accepted, PairingWire.ROLE_SERVER, identity))
    }
  }

  /** Open a connection to a peer. Used during pairing with the address it advertised. */
  fun connectToPeer(host: String, port: Int) {
    val context = sslContext()
    if (context == null) {
      onRefused?.invoke("this device has no session key")
      return
    }
    Thread({
      val socket = runCatching {
        context.socketFactory.createSocket(InetAddress.getByName(host), port) as SSLSocket
      }.getOrNull()
      if (socket == null) {
        onRefused?.invoke("could not reach that device")
        return@Thread
      }
      adopt(PeerLink(socket, PairingWire.ROLE_CLIENT, identity))
    }, "pairing-connect").apply { isDaemon = true }.start()
  }

  private fun adopt(link: PeerLink) {
    link.onAuthenticated = { key, name -> onAuthenticated(link, key, name) }
    link.onFailed = { reason ->
      onRefused?.invoke(reason)
      drop(link)
    }
    link.onClosed = { drop(link) }
    link.start()
  }

  private fun onAuthenticated(link: PeerLink, key: ByteArray, name: String) {
    val fingerprint = Ed25519Keys.fingerprint(key)

    if (!registry.isPaired(key)) {
      if (!pairingMode) {
        // The ordinary case for an unknown device: it proved it holds a key, and this
        // device has never agreed to trust that key.
        onRefused?.invoke("an unpaired device tried to connect")
        link.close()
        drop(link)
        return
      }
      // Hold the connection while the user compares the six digits. Nothing is stored and
      // no verb is served until confirmPairing().
      synchronized(lock) {
        pendingLink = link
        pendingKey = key
      }
      pendingName = name
      pendingCode = PairingWire.pairingCodeFor(identity.publicKey, key)
      onPendingChanged?.invoke(pendingCode, pendingName)
      return
    }

    registry.noteAddress(key, link.peerAddress)
    sessions.add(PeerSession(link, content))
    onPeerConnected?.invoke(fingerprint, name)
  }

  /** The user confirmed the six digits match. */
  fun confirmPairing(): Boolean {
    val link: PeerLink
    val key: ByteArray
    synchronized(lock) {
      link = pendingLink ?: return false
      key = pendingKey
      if (key.isEmpty()) return false
      pendingLink = null
      pendingKey = ByteArray(0)
    }

    val name = pendingName
    if (!registry.remember(key, name, link.peerAddress)) return false

    pendingCode = ""
    pendingName = ""
    pairingMode = false
    cancelTimer()
    onPendingChanged?.invoke("", "")

    val fingerprint = Ed25519Keys.fingerprint(key)
    sessions.add(PeerSession(link, content))
    onPaired?.invoke(fingerprint, name)
    onPeerConnected?.invoke(fingerprint, name)
    return true
  }

  /** Open pairing mode for [seconds]; an unknown peer may then present itself. */
  fun beginPairing(seconds: Int = DEFAULT_PAIRING_SECONDS) {
    pairingMode = true
    cancelTimer()
    // Pairing mode is a window the user opened, not a state the device sits in. It closes
    // on its own so a phone left alone does not stay open to the first key that asks.
    pairingTimer = Timer("pairing-window", true).apply {
      schedule(object : TimerTask() {
        override fun run() {
          if (pairingMode) cancelPairing()
        }
      }, seconds * 1000L)
    }
  }

  fun cancelPairing() {
    synchronized(lock) {
      pendingLink?.let {
        it.close()
        drop(it)
      }
      pendingLink = null
      pendingKey = ByteArray(0)
    }
    pendingCode = ""
    pendingName = ""
    pairingMode = false
    cancelTimer()
    onPendingChanged?.invoke("", "")
  }

  private fun cancelTimer() {
    pairingTimer?.cancel()
    pairingTimer = null
  }

  private fun drop(link: PeerLink) {
    val session = sessions.firstOrNull { it.link === link }
    if (session != null) {
      sessions.remove(session)
      val key = link.peerKey
      if (key.size == Ed25519Keys.PUBLIC_BYTES) {
        onPeerDisconnected?.invoke(Ed25519Keys.fingerprint(key))
      }
    }
    synchronized(lock) {
      if (pendingLink === link) {
        pendingLink = null
        pendingKey = ByteArray(0)
        pendingCode = ""
        onPendingChanged?.invoke("", "")
      }
    }
  }

  private companion object {
    const val BACKLOG = 4
    const val DEFAULT_PAIRING_SECONDS = 120
  }
}
