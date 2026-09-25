package expo.modules.localdownloader.pairing

import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.json.JSONObject

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
 * **The ceremony.** Both ends show six digits derived from the two public keys and two
 * nonces, exchanged commit-then-reveal (see [PairingWire.pairingCodeV2]). Confirming they
 * match is what authenticates each key to the other. The commitment is what makes that
 * hold: with keys alone, a man in the middle could generate key pairs until both of its
 * legs showed the same six digits, where now it has to commit before it can aim.
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
  /** The commit-reveal exchange with [pendingLink], until it yields a code. */
  private var ceremony: Ceremony? = null
  private val random = SecureRandom()

  private class Ceremony(val role: Byte) {
    var clientNonce: ByteArray? = null
    var serverNonce: ByteArray? = null
    var commitment: ByteArray? = null
  }
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
      // Hold the connection while the two sides agree on a code and the user compares it.
      // Nothing is stored and no verb is served until confirmPairing().
      val refused = synchronized(lock) {
        if (pendingLink != null && pendingLink !== link) {
          true
        } else {
          pendingLink = link
          pendingKey = key
          ceremony = Ceremony(link.role)
          false
        }
      }
      if (refused) {
        // One ceremony at a time: two codes on the screen at once is how the wrong one
        // gets confirmed.
        onRefused?.invoke("another device is already pairing")
        link.close()
        drop(link)
        return
      }
      pendingName = name
      link.onControl = { message -> onCeremonyMessage(link, message) }
      if (link.role == PairingWire.ROLE_CLIENT) {
        val nonce = ByteArray(PairingWire.PAIRING_NONCE_BYTES).also { random.nextBytes(it) }
        synchronized(lock) { ceremony?.clientNonce = nonce }
        link.sendControl(JSONObject().put("t", "pair-commit").put("c", hex(PairingWire.commitment(nonce))))
      }
      return
    }

    // Both devices may reach each other at once, which makes two connections between the
    // same pair. Both ends keep the one opened by the device with the smaller fingerprint,
    // so they settle on the same connection without having to talk about it.
    val existing = sessionFor(fingerprint)
    if (existing != null && existing.link !== link) {
      val mine = Ed25519Keys.fingerprint(identity.publicKey)
      val openedBy = if (link.role == PairingWire.ROLE_CLIENT) mine else fingerprint
      if (openedBy == minOf(mine, fingerprint)) {
        existing.link.close()
        drop(existing.link)
      } else {
        link.close()
        drop(link)
        return
      }
    }

    registry.noteAddress(key, link.peerAddress)
    sessions.add(PeerSession(link, content))
    onPeerConnected?.invoke(fingerprint, name)
  }

  /** The commit-reveal exchange that produces the six digits. */
  private fun onCeremonyMessage(link: PeerLink, message: JSONObject) {
    val (current, key) = synchronized(lock) {
      if (pendingLink !== link) return
      (ceremony ?: return) to pendingKey
    }
    val kind = message.optString("t")
    val nonce = unhex(message.optString(if (kind == "pair-commit") "c" else "n"))
    val server = current.role == PairingWire.ROLE_SERVER

    when {
      server && kind == "pair-commit" && current.commitment == null -> {
        if (nonce.size != 32) return failCeremony(link, "the other device sent a malformed commitment")
        val serverNonce = ByteArray(PairingWire.PAIRING_NONCE_BYTES).also { random.nextBytes(it) }
        synchronized(lock) {
          current.commitment = nonce
          current.serverNonce = serverNonce
        }
        link.sendControl(JSONObject().put("t", "pair-nonce").put("n", hex(serverNonce)))
      }
      !server && kind == "pair-nonce" && current.serverNonce == null -> {
        val clientNonce = current.clientNonce ?: return
        if (nonce.size != PairingWire.PAIRING_NONCE_BYTES) {
          return failCeremony(link, "the other device sent a malformed nonce")
        }
        synchronized(lock) { current.serverNonce = nonce }
        link.sendControl(JSONObject().put("t", "pair-reveal").put("n", hex(clientNonce)))
        showCode(key, clientNonce, nonce)
      }
      server && kind == "pair-reveal" && current.clientNonce == null -> {
        val commitment = current.commitment ?: return
        val serverNonce = current.serverNonce ?: return
        if (nonce.size != PairingWire.PAIRING_NONCE_BYTES ||
          !MessageDigest.isEqual(PairingWire.commitment(nonce), commitment)
        ) {
          // The nonce revealed is not the one committed to. Either the device is broken or
          // something is trying to steer the code; neither gets a code to confirm.
          return failCeremony(link, "the other device did not keep to its commitment")
        }
        synchronized(lock) { current.clientNonce = nonce }
        showCode(key, nonce, serverNonce)
      }
      // Anything else during the ceremony is ignored: no verb is served until confirmed.
    }
  }

  private fun showCode(peerKey: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {
    pendingCode = PairingWire.pairingCodeV2(identity.publicKey, peerKey, clientNonce, serverNonce)
    onPendingChanged?.invoke(pendingCode, pendingName)
  }

  private fun failCeremony(link: PeerLink, reason: String) {
    onRefused?.invoke(reason)
    link.close()
    drop(link)
  }

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun unhex(text: String): ByteArray {
    if (text.length % 2 != 0) return ByteArray(0)
    return runCatching {
      ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrElse { ByteArray(0) }
  }

  /** The user confirmed the six digits match. */
  fun confirmPairing(): Boolean {
    val link: PeerLink
    val key: ByteArray
    synchronized(lock) {
      link = pendingLink ?: return false
      key = pendingKey
      // Only once the exchange has produced a code: there is nothing to confirm before.
      if (key.isEmpty() || pendingCode.isEmpty()) return false
      pendingLink = null
      pendingKey = ByteArray(0)
      ceremony = null
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
      ceremony = null
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
        ceremony = null
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
