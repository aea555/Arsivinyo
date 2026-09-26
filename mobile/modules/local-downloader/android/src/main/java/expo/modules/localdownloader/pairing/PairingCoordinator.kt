package expo.modules.localdownloader.pairing

import android.content.Context
import android.os.Build
import expo.modules.localdownloader.sounds.SoundsStore
import java.io.File
import org.json.JSONArray

/**
 * Everything pairing needs, in one object the module can hold.
 *
 * The pieces below are each testable on their own — the wire format and the ceremony in
 * the JVM harness, the Keystore key and discovery on a device. This assembles them and
 * turns their callbacks into one state snapshot, because a React screen wants a value to
 * render, not six listeners to reconcile.
 */
class PairingCoordinator(
  private val context: Context,
  private val store: SoundsStore,
  /** Called whenever anything a screen renders has changed. */
  private val onChanged: () -> Unit,
  /** A peer sent a URL for this phone to fetch. Shown to the user, never started. */
  private val onDownloadRequested: (url: String, mediaKind: String) -> Unit,
  /** A meme arrived, verified, with what the sender said about it. */
  private val onMemeReceived: (file: File, meme: org.json.JSONObject?) -> Unit = { file, _ -> file.delete() },
) {

  val identity = DeviceIdentity(context)

  private val registry = PeerRegistry(File(context.filesDir, "pairing/peers.json"))
  private val content = SoundsContent(context, store, onDownloadRequested, onMemeReceived)
  private val service = PairingService(identity, registry, content, SessionKeys::sslContext)
  private val discovery = Discovery(context)

  /** The last listing a peer sent back, so a browse screen has something to show. */
  @Volatile private var lastListing: JSONArray = JSONArray()
  @Volatile private var lastListingFrom: String = ""
  @Volatile private var lastMessage: String = ""
  @Volatile private var transferDone: Long = 0
  @Volatile private var transferTotal: Long = 0

  init {
    service.onRefused = { reason ->
      lastMessage = reason
      onChanged()
    }
    service.onPendingChanged = { _, _ -> onChanged() }
    service.onPeerConnected = { _, _ -> attachSessionCallbacks(); onChanged() }
    service.onPeerDisconnected = { _ -> onChanged() }
    service.onPaired = { _, name ->
      lastMessage = "Paired with $name"
      onChanged()
    }
    discovery.onPeerFound = { found ->
      reconnect(found)
      onChanged()
    }
    discovery.onPeerLost = { onChanged() }
  }

  /** When each paired device was last tried, so a device that refuses is not hammered. */
  private val lastAttempt = java.util.concurrent.ConcurrentHashMap<String, Long>()
  private var reconnectTimer: java.util.Timer? = null

  /**
   * Connects to a paired device the network says is here, if there is no connection yet.
   *
   * Before this, the phone only ever connected while pairing, so a paired device became
   * "connected" only if it happened to connect first, and Browse, Send and Link stayed
   * greyed out on a device sitting right there.
   */
  private fun reconnect(found: Discovery.Found) {
    if (registry.all().none { it.fingerprint == found.fingerprint }) return
    if (service.sessionFor(found.fingerprint) != null) return
    val now = System.currentTimeMillis()
    val last = lastAttempt[found.fingerprint] ?: 0L
    if (now - last < RECONNECT_INTERVAL_MS) return
    lastAttempt[found.fingerprint] = now
    service.connectToPeer(found.host, found.port)
  }

  /** Start listening and announce this device. Safe to call more than once. */
  fun start(): Boolean {
    if (!service.listen()) return false
    discovery.start(identity.fingerprint, identity.deviceName, service.port)
    // A connection that dropped comes back once the device is still being announced.
    if (reconnectTimer == null) {
      reconnectTimer = java.util.Timer("pairing-reconnect", true).apply {
        schedule(object : java.util.TimerTask() {
          override fun run() { discovery.peers().forEach { reconnect(it) } }
        }, RECONNECT_INTERVAL_MS, RECONNECT_INTERVAL_MS)
      }
    }
    onChanged()
    return true
  }

  fun stop() {
    reconnectTimer?.cancel()
    reconnectTimer = null
    discovery.stop()
    service.stop()
    onChanged()
  }

  /** Everything a screen renders, in one map. */
  fun state(): Map<String, Any?> = mapOf(
    "fingerprint" to identity.fingerprint,
    "deviceName" to identity.deviceName,
    "ready" to identity.ready,
    "listening" to service.isListening,
    "port" to service.port,
    "pairingMode" to service.pairingMode,
    "pendingCode" to service.pendingCode,
    "pendingName" to service.pendingName,
    "message" to lastMessage,
    "transferDone" to transferDone,
    "transferTotal" to transferTotal,
    "peers" to registry.all().map {
      mapOf(
        "fingerprint" to it.fingerprint,
        "name" to it.name,
        "lastAddress" to it.lastAddress,
        "connected" to (service.sessionFor(it.fingerprint) != null),
      )
    },
    "discovered" to discovery.peers()
      // A device already paired belongs in the paired list, not offered for pairing again.
      .filter { found -> registry.all().none { it.fingerprint == found.fingerprint } }
      .map {
        mapOf(
          "fingerprint" to it.fingerprint,
          "name" to it.name,
          "host" to it.host,
          "port" to it.port,
        )
      },
    "listingFrom" to lastListingFrom,
    "listing" to listingAsMaps(),
  )

  fun beginPairing(seconds: Int) {
    service.beginPairing(seconds)
    onChanged()
  }

  fun cancelPairing() = service.cancelPairing()

  fun confirmPairing(): Boolean {
    val ok = service.confirmPairing()
    if (!ok) lastMessage = "The pairing could not be saved"
    onChanged()
    return ok
  }

  fun connectToPeer(host: String, port: Int) = service.connectToPeer(host, port)

  fun forgetPeer(fingerprint: String): Boolean {
    val ok = registry.forget(fingerprint)
    if (!ok) lastMessage = "The device could not be forgotten"
    onChanged()
    return ok
  }

  fun setDeviceName(name: String) {
    identity.deviceName = name
    // Re-announce so peers see the new name rather than the one they cached.
    discovery.start(identity.fingerprint, identity.deviceName, service.port)
    onChanged()
  }

  fun browsePeer(fingerprint: String): Boolean =
    service.sessionFor(fingerprint)?.requestListing("music") ?: false

  fun fetchItem(fingerprint: String, id: String): Boolean =
    service.sessionFor(fingerprint)?.requestItem(id) ?: false

  fun sendUrlToPeer(fingerprint: String, url: String, mediaKind: String): Boolean =
    service.sessionFor(fingerprint)?.requestDownload(url, mediaKind) ?: false

  /** Send a track from this phone's library to a paired device. */
  fun sendItemToPeer(fingerprint: String, songId: String): Boolean {
    val session = service.sessionFor(fingerprint) ?: return false
    val source = content.openItem(songId) ?: return false
    return session.send(source, "music")
  }

  /** A meme, with its labels by name. Private memes never come here: the vault does not travel. */
  fun sendMemeToPeer(fingerprint: String, source: ItemSource): Boolean {
    val session = service.sessionFor(fingerprint) ?: return false
    return session.send(source, "meme")
  }

  fun cancelTransfer(fingerprint: String) {
    service.sessionFor(fingerprint)?.cancel()
  }

  /** A new session needs its callbacks; there is no hook for "a session appeared". */
  private fun attachSessionCallbacks() {
    for (session in service.sessions()) {
      if (session.onTransferProgress != null) continue
      session.onTransferProgress = { done, total ->
        transferDone = done
        transferTotal = total
        onChanged()
      }
      session.onTransferComplete = {
        transferDone = 0
        transferTotal = 0
        lastMessage = "Transfer complete"
        onChanged()
      }
      session.onTransferFailed = { reason ->
        transferDone = 0
        transferTotal = 0
        lastMessage = reason
        onChanged()
      }
      session.onListing = { _, items ->
        lastListing = items
        lastListingFrom = Ed25519Keys.fingerprint(session.link.peerKey)
        onChanged()
      }
      session.onFileReceived = { _, _ ->
        // The name is deliberately absent: what arrived is private.
        lastMessage = "A track arrived"
        onChanged()
      }
    }
  }

  private fun listingAsMaps(): List<Map<String, Any?>> {
    val items = lastListing
    val out = mutableListOf<Map<String, Any?>>()
    for (i in 0 until items.length()) {
      val item = items.optJSONObject(i) ?: continue
      out.add(mapOf(
        "id" to item.optString("id"),
        "title" to item.optString("title"),
        "artist" to item.optString("artist").ifBlank { null },
        "durationSec" to item.optDouble("durationSec", 0.0),
        "sizeBytes" to item.optLong("sizeBytes", 0L),
      ))
    }
    return out
  }

  companion object {
    private const val RECONNECT_INTERVAL_MS = 20_000L

    /** MediaStore's owner model, which the music library needs, is API 29 and up. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
  }
}
