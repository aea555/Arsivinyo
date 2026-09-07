package expo.modules.localdownloader.pairing

import java.io.IOException
import java.security.MessageDigest
import javax.net.ssl.SSLSocket
import org.json.JSONObject

/**
 * One connection to one peer: TLS, identity, and framing.
 *
 * The counterpart of `desktop/src/PeerLink.cpp`. The link is not usable until
 * [onAuthenticated] fires; before that it will accept nothing but the peer's own `auth`
 * message, so an unpaired device that connects and starts issuing requests gets no
 * further than the handshake.
 *
 * **How the peer is identified.** TLS here is encryption only — the certificates are
 * self-signed and nothing checks a name or a chain, because no certificate authority is
 * involved and the pairing *is* the trust. Identity is settled by the `auth` message,
 * which signs a transcript naming both certificates of *this* connection. See
 * [PairingWire.authTranscript].
 *
 * **Threading.** Reading happens on one thread of its own, and every callback is invoked
 * on it. A caller that touches the UI has to hop to the main thread itself; doing it here
 * would mean this class needing a looper and being untestable off a device.
 */
class PeerLink(
  private val socket: SSLSocket,
  /** This device's own role: [PairingWire.ROLE_SERVER] or `ROLE_CLIENT`. */
  val role: Byte,
  private val identity: PairingIdentity,
) {

  var onAuthenticated: ((publicKey: ByteArray, name: String) -> Unit)? = null
  var onControl: ((message: JSONObject) -> Unit)? = null
  var onBulk: ((chunk: ByteArray) -> Unit)? = null
  /** Fatal. The socket is closed by the time this runs. */
  var onFailed: ((reason: String) -> Unit)? = null
  var onClosed: (() -> Unit)? = null

  @Volatile var isAuthenticated: Boolean = false
    private set
  @Volatile var peerKey: ByteArray = ByteArray(0)
    private set
  @Volatile var peerName: String = ""
    private set

  val peerAddress: String
    get() = runCatching { "${socket.inetAddress.hostAddress}:${socket.port}" }.getOrElse { "" }

  private val writeLock = Any()
  private var reader: Thread? = null
  @Volatile private var closed = false

  private var inbox = ByteArray(64 * 1024)
  private var inboxSize = 0

  /** Perform the handshake, send this device's `auth`, then read until closed. */
  fun start() {
    if (reader != null) return
    reader = Thread({ run() }, "pairing-link").apply { isDaemon = true; start() }
  }

  private fun run() {
    try {
      // Explicit rather than implicit: the certificates are needed before anything can be
      // sent, and a failure here should be reported as a handshake failure.
      socket.startHandshake()
      if (!sendAuth()) return
      readLoop()
    } catch (e: IOException) {
      fail("could not reach the device: ${e.message}")
    } catch (e: Exception) {
      fail("the connection failed: ${e.message}")
    }
  }

  private fun sendAuth(): Boolean {
    val own = certificateHash(local = true)
    val peer = certificateHash(local = false)
    if (own == null || peer == null) {
      fail("the peer presented no certificate")
      return false
    }

    val (serverHash, clientHash) = PairingWire.transcriptOrder(role, own, peer)
    val message = PairingAuth.build(
      identity = identity,
      role = role,
      serverCertSha256 = serverHash,
      clientCertSha256 = clientHash,
    )
    if (message == null) {
      fail("could not sign the session transcript")
      return false
    }
    if (!sendControl(JSONObject(message))) {
      fail("could not send this device's identity")
      return false
    }
    return true
  }

  /** SHA-256 of a DER certificate, which is what the auth transcript names. */
  private fun certificateHash(local: Boolean): ByteArray? = runCatching {
    val chain = if (local) socket.session.localCertificates else socket.session.peerCertificates
    val first = chain?.firstOrNull() ?: return null
    MessageDigest.getInstance("SHA-256").digest(first.encoded)
  }.getOrNull()

  private fun readLoop() {
    val stream = socket.inputStream
    val chunk = ByteArray(64 * 1024)
    while (!closed) {
      val read = stream.read(chunk)
      if (read < 0) break
      append(chunk, read)
      if (!drainFrames()) return
    }
    if (!closed) onClosed?.invoke()
  }

  private fun append(bytes: ByteArray, length: Int) {
    // A frame can never exceed the cap, so the buffer never needs to grow past it plus
    // the four length bytes the decoder reads before it knows the size.
    val needed = inboxSize + length
    if (needed > inbox.size) {
      val grown = ByteArray(minOf(maxOf(needed, inbox.size * 2), PairingWire.MAX_FRAME_BYTES + 8))
      inbox.copyInto(grown, 0, 0, inboxSize)
      inbox = grown
    }
    bytes.copyInto(inbox, inboxSize, 0, length)
    inboxSize += length
  }

  /** @return false when the link has been torn down and reading must stop. */
  private fun drainFrames(): Boolean {
    while (true) {
      when (val decoded = PairingWire.decodeFrame(inbox, inboxSize)) {
        is PairingWire.Decoded.Incomplete -> return true
        is PairingWire.Decoded.TooLarge -> {
          fail("the peer announced an oversized frame")
          return false
        }
        is PairingWire.Decoded.BadType -> {
          fail("the peer sent an unknown frame type")
          return false
        }
        is PairingWire.Decoded.Frame -> {
          // Compact before dispatching, so a callback that sends does not see a buffer
          // still holding the frame it is answering.
          inbox.copyInto(inbox, 0, decoded.consumed, inboxSize)
          inboxSize -= decoded.consumed

          if (decoded.type == PairingWire.TYPE_CONTROL) {
            if (!handleControl(decoded.payload)) return false
          } else if (!isAuthenticated) {
            // Bulk before the peer has proved who it is would mean writing an unknown
            // device's bytes to disk.
            fail("the peer sent data before authenticating")
            return false
          } else {
            onBulk?.invoke(decoded.payload)
          }
        }
      }
    }
  }

  private fun handleControl(payload: ByteArray): Boolean {
    val text = String(payload, Charsets.UTF_8)
    if (!isAuthenticated) {
      val own = certificateHash(local = true)
      val peer = certificateHash(local = false)
      if (own == null || peer == null) {
        fail("the peer presented no certificate")
        return false
      }
      // The ordering does not depend on who is verifying, only on the roles, so the same
      // call serves here as when sending. What changes is whose role is claimed.
      val (serverHash, clientHash) = PairingWire.transcriptOrder(role, own, peer)
      val verified = PairingAuth.verify(
        message = text,
        peerRole = if (role == PairingWire.ROLE_SERVER) PairingWire.ROLE_CLIENT
                   else PairingWire.ROLE_SERVER,
        serverCertSha256 = serverHash,
        clientCertSha256 = clientHash,
      )
      if (verified == null) {
        // Either the peer does not hold the key it claims, or something is sitting in the
        // middle terminating TLS — the transcript names this connection's certificates.
        fail("the peer could not prove its identity")
        return false
      }
      peerKey = verified.publicKey
      peerName = verified.name
      isAuthenticated = true
      onAuthenticated?.invoke(peerKey, peerName)
      return true
    }

    val message = runCatching { JSONObject(text) }.getOrNull()
    if (message == null) {
      fail("the peer sent a malformed control message")
      return false
    }
    onControl?.invoke(message)
    return true
  }

  fun sendControl(message: JSONObject): Boolean =
    sendFrame(PairingWire.TYPE_CONTROL, message.toString().toByteArray(Charsets.UTF_8))

  fun sendBulk(chunk: ByteArray, length: Int = chunk.size): Boolean =
    sendFrame(PairingWire.TYPE_BULK,
      if (length == chunk.size) chunk else chunk.copyOfRange(0, length))

  private fun sendFrame(type: Byte, payload: ByteArray): Boolean {
    val frame = PairingWire.encodeFrame(type, payload) ?: return false
    return synchronized(writeLock) {
      runCatching {
        if (closed) return false
        socket.outputStream.write(frame)
        socket.outputStream.flush()
        true
      }.getOrElse { false }
    }
  }

  private fun fail(reason: String) {
    if (closed) return
    closed = true
    runCatching { socket.close() }
    onFailed?.invoke(reason)
  }

  fun close() {
    if (closed) return
    closed = true
    runCatching { socket.close() }
    onClosed?.invoke()
  }

  companion object {
    /** The largest bulk payload one frame may carry, leaving room for the header. */
    const val MAX_BULK_CHUNK = PairingWire.MAX_FRAME_BYTES - 1
  }
}
