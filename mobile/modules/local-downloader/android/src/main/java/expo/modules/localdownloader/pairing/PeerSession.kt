package expo.modules.localdownloader.pairing

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The four verbs, over one authenticated link.
 *
 * The counterpart of `desktop/src/PeerSession.cpp`. One transfer at a time per connection:
 * the framing lets control and bulk share a connection so a transfer can be cancelled
 * mid-flight without tearing it down, but interleaving two would need a transfer id on
 * every bulk frame — a cost paid on every chunk to support something the app never asks
 * for.
 *
 * **Integrity.** A received file is written to a `.part` beside its destination, hashed as
 * it arrives, and moved into place only once the declared size *and* SHA-256 both match. A
 * truncated transfer therefore never appears in the library, and it cannot slip through by
 * hashing to its own truncated bytes, because the size is checked too.
 *
 * **Threading.** Callbacks arrive on the link's reader thread; sending a file runs on one
 * of its own, because hashing a multi-gigabyte file and streaming it must not block the
 * reader — a `cancel` from the peer arrives on that thread and has to be heard.
 */
class PeerSession(
  val link: PeerLink,
  private val content: PeerContent,
  /**
   * False straight after this side confirmed a pairing the other side has not confirmed yet.
   * Until its `pair-confirm` arrives the other device ignores everything but the ceremony,
   * so requests wait here rather than vanishing.
   */
  peerReady: Boolean = true,
) {

  @Volatile private var ready = peerReady
  private val waiting = mutableListOf<() -> Unit>()

  /** Runs [request] now, or once the other device has confirmed the pairing. */
  private fun whenReady(request: () -> Unit): Boolean {
    synchronized(waiting) {
      if (!ready) {
        waiting.add(request)
        return true
      }
    }
    request()
    return true
  }

  private fun peerConfirmed() {
    val queued = synchronized(waiting) {
      ready = true
      waiting.toList().also { waiting.clear() }
    }
    queued.forEach { it() }
  }

  var onListing: ((kind: String, items: JSONArray) -> Unit)? = null
  var onTransferStarted: ((sizeBytes: Long) -> Unit)? = null
  var onTransferProgress: ((done: Long, total: Long) -> Unit)? = null
  /** [path] is where the file landed. Never logged: names are private. */
  var onFileReceived: ((path: String, kind: String) -> Unit)? = null
  var onTransferFailed: ((reason: String) -> Unit)? = null
  var onTransferComplete: (() -> Unit)? = null

  private class Receiving(
    val kind: String,
    val finalPath: File,
    val partPath: File,
    val total: Long,
    val expectedHash: ByteArray,
    /** The cover that came with the offer, held until the file itself verifies. */
    val artwork: ByteArray?,
    val artworkName: String,
  ) {
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    val stream = partPath.outputStream()
    var received: Long = 0
  }

  private val lock = Any()
  private var receiving: Receiving? = null
  @Volatile private var sending: ItemSource? = null
  private var accepted = CountDownLatch(0)
  @Volatile private var cancelled = false

  val isTransferring: Boolean
    get() = synchronized(lock) { receiving != null } || sending != null

  init {
    link.onControl = { onControl(it) }
    link.onBulk = { onBulk(it) }
    // Chained, not replaced: the service's handler is what drops this session when the
    // link dies. Replacing it left a dead connection listed as connected, and everything
    // sent to that device went nowhere.
    val serviceHandler = link.onFailed
    link.onFailed = { reason ->
      abortReceiving(reason)
      abortSending(reason)
      serviceHandler?.invoke(reason)
    }
  }

  // ---- outgoing requests -------------------------------------------------------------

  fun requestListing(kind: String): Boolean =
    whenReady { link.sendControl(JSONObject().put("t", "list").put("kind", kind)) }

  fun requestItem(id: String): Boolean {
    if (isTransferring) return false
    return whenReady { link.sendControl(JSONObject().put("t", "get").put("id", id)) }
  }

  fun requestDownload(url: String, mediaKind: String): Boolean =
    whenReady {
      link.sendControl(JSONObject().put("t", "download").put("url", url).put("mediaKind", mediaKind))
    }

  /** Offer a local file to the peer. Returns once the send has been started, not finished. */
  fun sendFile(file: File, kind: String): Boolean {
    if (!file.isFile) return false
    return send(ItemSource(file.name, file.length()) { file.inputStream() }, kind)
  }

  /** Offer anything readable to the peer — on this platform, usually a MediaStore item. */
  fun send(source: ItemSource, kind: String): Boolean {
    if (isTransferring) return false
    sending = source
    cancelled = false
    accepted = CountDownLatch(1)
    return whenReady {
      Thread({ streamSource(source, kind) }, "pairing-send").apply { isDaemon = true }.start()
    }
  }

  private fun streamSource(source: ItemSource, kind: String) {
    val digest = hashSource(source)
    if (digest == null) {
      abortSending("could not read the item")
      return
    }

    val total = source.sizeBytes
    val offer = JSONObject()
      .put("t", "put").put("name", source.name).put("kind", kind)
      .put("sizeBytes", total).put("sha256", hex(digest))
    // The cover rides in the offer: small, optional, and ignored by a receiver that does not
    // know it. Over the cap it is left out rather than making the offer huge.
    source.artwork?.takeIf { it.isFile && it.length() in 1..MAX_ARTWORK_BYTES }?.let { art ->
      runCatching { art.readBytes() }.getOrNull()?.let { bytes ->
        offer.put("artwork", expo.modules.localdownloader.backup.BackupFormat.Base64Codec.encode(bytes))
          .put("artworkName", art.name)
      }
    }
    if (!link.sendControl(offer)) {
      abortSending("the connection went away")
      return
    }

    // A peer that never answers must not leave a send hanging for the life of the app.
    if (!accepted.await(ACCEPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      abortSending("the device did not answer")
      return
    }
    if (cancelled || sending == null) return

    var sent = 0L
    val stream = runCatching { source.open() }.getOrNull()
    if (stream == null) {
      abortSending("could not read the item")
      return
    }
    stream.use { input ->
      val chunk = ByteArray(CHUNK_BYTES)
      while (sent < total) {
        if (cancelled) return
        val read = input.read(chunk)
        if (read <= 0) {
          abortSending("the file ended early")
          return
        }
        if (!link.sendBulk(chunk, read)) {
          abortSending("the connection went away")
          return
        }
        sent += read
        onTransferProgress?.invoke(sent, total)
      }
    }

    // The digest computed before the offer, not a second pass over the file.
    link.sendControl(JSONObject().put("t", "complete").put("sha256", hex(digest)))
    sending = null
    onTransferComplete?.invoke()
  }

  // ---- incoming ----------------------------------------------------------------------

  /** A message the service routed here, having reached the link as its ceremony ended. */
  internal fun receive(message: JSONObject) = onControl(message)

  private fun onControl(message: JSONObject) {
    when (message.optString("t")) {
      "pair-confirm" -> peerConfirmed()
      "list" -> {
        val kind = message.optString("kind")
        link.sendControl(JSONObject().put("t", "listing").put("kind", kind)
          .put("items", content.listing(kind)))
      }
      "listing" -> onListing?.invoke(message.optString("kind"),
        message.optJSONArray("items") ?: JSONArray())
      "get" -> handleGet(message)
      "put" -> handlePut(message)
      "accept" -> accepted.countDown()
      "reject" -> abortSending(message.optString("reason").ifEmpty { "refused" })
      "complete" -> handleComplete(message)
      "cancel" -> {
        abortReceiving("the peer cancelled")
        abortSending("the peer cancelled")
      }
      "download" -> content.download(message.optString("url"), message.optString("mediaKind"))
      "error" -> onTransferFailed?.invoke(message.optString("code"))
    }
  }

  private fun handleGet(message: JSONObject) {
    if (isTransferring) {
      link.sendControl(JSONObject().put("t", "reject").put("reason", "busy"))
      return
    }
    val source = content.openItem(message.optString("id"))
    if (source == null || !send(source, "music")) {
      link.sendControl(JSONObject().put("t", "error").put("code", "NOT_FOUND")
        .put("message", "no such item"))
    }
  }

  private fun handlePut(message: JSONObject) {
    if (isTransferring) {
      link.sendControl(JSONObject().put("t", "reject").put("reason", "busy"))
      return
    }

    val size = message.optLong("sizeBytes", -1)
    val expected = unhex(message.optString("sha256"))
    if (size < 0 || expected.size != SHA256_BYTES) {
      link.sendControl(JSONObject().put("t", "reject").put("reason", "malformed"))
      return
    }

    // The receiver picks the path. A name from the peer is never joined onto a directory,
    // so a `../` or an absolute path in it cannot steer this write.
    val kind = message.optString("kind")
    val destination = content.destinationFor(message.optString("name"), kind)
    if (destination == null) {
      link.sendControl(JSONObject().put("t", "reject").put("reason", "refused"))
      return
    }

    val artwork = message.optString("artwork").takeIf { it.isNotEmpty() }?.let {
      runCatching { expo.modules.localdownloader.backup.BackupFormat.Base64Codec.decode(it) }.getOrNull()
    }?.takeIf { it.size in 1..MAX_ARTWORK_BYTES }
    // Only the extension is taken from the peer's name, and only a plain one.
    val artworkExtension = message.optString("artworkName").substringAfterLast('.', "jpg").lowercase()
      .takeIf { it.matches(Regex("^[a-z0-9]{1,5}$")) } ?: "jpg"

    val started = runCatching {
      val finalPath = File(destination)
      finalPath.parentFile?.mkdirs()
      Receiving(kind, finalPath, File("$destination.part"), size, expected, artwork, "cover.$artworkExtension")
    }.getOrNull()
    if (started == null) {
      link.sendControl(JSONObject().put("t", "reject").put("reason", "unwritable"))
      return
    }

    synchronized(lock) { receiving = started }
    link.sendControl(JSONObject().put("t", "accept")
      .put("transferId", UUID.randomUUID().toString()))
    onTransferStarted?.invoke(size)
  }

  private fun onBulk(chunk: ByteArray) {
    val current = synchronized(lock) { receiving } ?: return
    // Refuse to write past the declared size rather than trusting the sender to stop.
    if (current.received + chunk.size > current.total) {
      abortReceiving("the peer sent more than it declared")
      return
    }
    runCatching {
      current.stream.write(chunk)
      current.digest.update(chunk)
      current.received += chunk.size
      onTransferProgress?.invoke(current.received, current.total)
    }.onFailure { abortReceiving("could not write the file") }
  }

  private fun handleComplete(message: JSONObject) {
    val current = synchronized(lock) { receiving } ?: return
    runCatching { current.stream.close() }

    // Size and hash both, deliberately. A truncated file hashes correctly to its own
    // truncated bytes, so the hash alone would accept it.
    val declared = unhex(message.optString("sha256"))
    val actual = current.digest.digest()
    if (current.received != current.total ||
      !actual.contentEquals(current.expectedHash) ||
      !actual.contentEquals(declared)
    ) {
      abortReceiving("the transfer did not verify")
      return
    }

    current.finalPath.delete()
    if (!current.partPath.renameTo(current.finalPath)) {
      abortReceiving("could not store the file")
      return
    }

    synchronized(lock) { receiving = null }
    val artworkPath = current.artwork?.let { bytes ->
      runCatching {
        File("${current.finalPath.path}.${current.artworkName}").apply { writeBytes(bytes) }.path
      }.getOrNull()
    }
    content.accepted(current.finalPath.path, current.kind, artworkPath)
    onFileReceived?.invoke(current.finalPath.path, current.kind)
    onTransferComplete?.invoke()
  }

  /** Stop whatever is running and tell the peer, so it is not left waiting. */
  fun cancel() {
    if (!isTransferring) return
    link.sendControl(JSONObject().put("t", "cancel"))
    abortReceiving("cancelled")
    abortSending("cancelled")
  }

  private fun abortReceiving(reason: String) {
    val current = synchronized(lock) { receiving.also { receiving = null } } ?: return
    runCatching { current.stream.close() }
    // The partial file goes away rather than being left for someone to find later.
    current.partPath.delete()
    onTransferFailed?.invoke(reason)
  }

  private fun abortSending(reason: String) {
    if (sending == null) return
    sending = null
    cancelled = true
    accepted.countDown()  // release the sender thread if it is waiting
    onTransferFailed?.invoke(reason)
  }

  private fun hashSource(source: ItemSource): ByteArray? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    source.open().use { input ->
      val buffer = ByteArray(CHUNK_BYTES)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    digest.digest()
  }.getOrNull()

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun unhex(text: String): ByteArray {
    if (text.length % 2 != 0) return ByteArray(0)
    return runCatching {
      ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrElse { ByteArray(0) }
  }

  private companion object {
    /** Big enough that per-frame overhead is noise, small enough to stay responsive. */
    const val CHUNK_BYTES = 256 * 1024
    const val SHA256_BYTES = 32
    const val ACCEPT_TIMEOUT_SECONDS = 60L
    /** A cover is a few hundred kilobytes at most; anything bigger is not sent along. */
    const val MAX_ARTWORK_BYTES = 1L * 1024 * 1024
  }
}
