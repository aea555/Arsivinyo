package expo.modules.localdownloader.vault

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Loopback HTTP server for streaming-decrypt vault playback and thumbnail delivery.
 *
 * Bound exclusively to 127.0.0.1 on an ephemeral port. Two route families:
 *   GET /v/{token}/{entryId}  → video, supports HTTP Range
 *   GET /t/{token}/{entryId}  → thumbnail (whole file, no Range)
 *
 * Tokens are 32 random URL-safe bytes. Video tokens are per-session (one entry, short
 * lifetime). Thumbnail tokens are global to the server process and rotate on every
 * server start.
 *
 * Lifecycle is managed by the caller (typically [LocalDownloaderModule]):
 *   - Lazy [ensureStarted] on first prepare/playback.
 *   - [stop] explicitly on app pause or cleanup.
 *   - Server self-stops if [idleTimeoutMs] elapses with zero active video sessions
 *     AND no new thumbnail requests within the timeout. Recovery is automatic on the
 *     next [ensureStarted].
 *
 * SECURITY NOTES
 *   - Treat the URL itself as a secret. Do not log it, do not include it in crash
 *     reports, do not surface it in diagnostics.
 *   - Tokens are checked by constant-time comparison ([safeEquals]) to avoid timing
 *     channels — although the attack window is effectively zero since the port is
 *     loopback-only.
 *   - Unknown tokens get 404, not 401. This avoids leaking "token exists vs token
 *     doesn't" to a curious sibling app on the device.
 */
class VaultLoopbackServer(
  private val provider: VaultLoopbackProvider,
  private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
  private val evictionGraceMs: Long = DEFAULT_EVICTION_GRACE_MS,
  private val onAutoStopped: (() -> Unit)? = null,
) : NanoHTTPD(LOOPBACK_HOST, EPHEMERAL_PORT) {

  private val secureRandom = SecureRandom()
  private val videoSessions = ConcurrentHashMap<String, VaultVideoSession>()
  @Volatile private var thumbnailTokenInternal: String? = null
  @Volatile private var lastActivityAt: Long = 0L
  private var idleExecutor: ScheduledExecutorService? = null
  private var idleTask: ScheduledFuture<*>? = null

  companion object {
    const val LOOPBACK_HOST = "127.0.0.1"
    const val EPHEMERAL_PORT = 0
    const val DEFAULT_IDLE_TIMEOUT_MS: Long = 30_000L
    const val DEFAULT_EVICTION_GRACE_MS: Long = 5_000L
    private const val IDLE_CHECK_INTERVAL_MS = 5_000L
    private const val READ_BUFFER_BYTES = 64 * 1024
    const val MIME_VIDEO_MP4 = "video/mp4"
    const val MIME_JPEG = "image/jpeg"

    private val STATUS_PARTIAL_CONTENT: Response.IStatus = Response.Status.PARTIAL_CONTENT
    private val STATUS_RANGE_NOT_SATISFIABLE: Response.IStatus = Response.Status.RANGE_NOT_SATISFIABLE
    private val STATUS_NOT_FOUND: Response.IStatus = Response.Status.NOT_FOUND
    private val STATUS_BAD_REQUEST: Response.IStatus = Response.Status.BAD_REQUEST
    private val STATUS_METHOD_NOT_ALLOWED: Response.IStatus = Response.Status.METHOD_NOT_ALLOWED
    private val STATUS_GONE: Response.IStatus = object : Response.IStatus {
      override fun getDescription(): String = "410 Gone"
      override fun getRequestStatus(): Int = 410
    }
  }

  @Synchronized
  fun ensureStarted() {
    if (!isAlive) {
      thumbnailTokenInternal = newToken()
      start(SOCKET_READ_TIMEOUT, /* daemon */ true)
      startIdleWatcher()
    }
    lastActivityAt = System.currentTimeMillis()
  }

  override fun stop() {
    runCatching { super.stop() }
    synchronized(this) {
      idleTask?.cancel(false)
      idleTask = null
      idleExecutor?.shutdownNow()
      idleExecutor = null
      videoSessions.clear()
      thumbnailTokenInternal = null
    }
  }

  /** Issue a new playback token for the given entry. Starts the server if needed. */
  @Synchronized
  fun registerVideoSession(entryId: String): VaultVideoSession {
    ensureStarted()
    val session = VaultVideoSession(
      token = newToken(),
      entryId = entryId,
      createdAt = System.currentTimeMillis(),
    )
    videoSessions[session.token] = session
    return session
  }

  /** Invalidate a video session. It is retained briefly so in-flight 206s return 410. */
  fun invalidateVideoSession(token: String) {
    val existing = videoSessions[token] ?: return
    existing.evictedAt = System.currentTimeMillis()
  }

  /**
   * Issues a new thumbnail token, so every URL handed out before now stops working.
   *
   * The token was minted once per server start, so a thumbnail URL given to the image loader
   * kept resolving after the vault locked. Locking has to take those back.
   */
  @Synchronized
  fun rotateThumbnailToken() {
    if (thumbnailTokenInternal != null) thumbnailTokenInternal = newToken()
  }

  fun invalidateAllVideoSessions() {
    val now = System.currentTimeMillis()
    videoSessions.values.forEach { it.evictedAt = now }
  }

  fun thumbnailUrl(entryId: String): String? {
    val token = thumbnailTokenInternal ?: return null
    val port = listeningPort
    if (port <= 0) return null
    return "http://$LOOPBACK_HOST:$port/t/$token/$entryId"
  }

  fun videoUrl(session: VaultVideoSession): String? {
    val port = listeningPort
    if (port <= 0) return null
    return "http://$LOOPBACK_HOST:$port/v/${session.token}/${session.entryId}"
  }

  fun snapshot(): VaultLoopbackSnapshot {
    val now = System.currentTimeMillis()
    val active = videoSessions.values.count { it.evictedAt == null }
    val evicted = videoSessions.values.count { it.evictedAt != null }
    return VaultLoopbackSnapshot(
      isRunning = isAlive,
      port = listeningPort.takeIf { isAlive && it > 0 },
      activeVideoSessions = active,
      evictedVideoSessions = evicted,
      thumbnailTokenIssued = thumbnailTokenInternal != null,
      lastActivityAt = lastActivityAt.takeIf { it > 0L },
    )
  }

  override fun serve(httpSession: IHTTPSession): Response {
    lastActivityAt = System.currentTimeMillis()
    pruneEvictedSessions(lastActivityAt)

    if (httpSession.method != Method.GET && httpSession.method != Method.HEAD) {
      return newFixedLengthResponse(STATUS_METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
    }

    val parts = httpSession.uri
      .trim('/')
      .split('/')
      .filter { it.isNotEmpty() }
    if (parts.size != 3) {
      return newFixedLengthResponse(STATUS_NOT_FOUND, MIME_PLAINTEXT, "")
    }
    val (route, token, entryId) = Triple(parts[0], parts[1], parts[2])

    return when (route) {
      "v" -> handleVideo(token, entryId, httpSession)
      "t" -> handleThumbnail(token, entryId)
      else -> newFixedLengthResponse(STATUS_NOT_FOUND, MIME_PLAINTEXT, "")
    }
  }

  private fun handleVideo(token: String, entryId: String, httpSession: IHTTPSession): Response {
    val session = videoSessions[token]
    if (session == null || !safeEquals(session.token, token) || session.entryId != entryId) {
      return newFixedLengthResponse(STATUS_NOT_FOUND, MIME_PLAINTEXT, "")
    }
    val evictedAt = session.evictedAt
    if (evictedAt != null && System.currentTimeMillis() - evictedAt <= evictionGraceMs) {
      return newFixedLengthResponse(STATUS_GONE, MIME_PLAINTEXT, "")
    }
    if (evictedAt != null) {
      videoSessions.remove(token)
      return newFixedLengthResponse(STATUS_NOT_FOUND, MIME_PLAINTEXT, "")
    }

    val resource = try {
      provider.openVideoResource(entryId)
    } catch (t: Throwable) {
      null
    } ?: return newFixedLengthResponse(STATUS_NOT_FOUND, MIME_PLAINTEXT, "")

    val total = resource.plaintextLength
    val rangeHeader = httpSession.headers["range"]
    val parsed = parseRange(rangeHeader, total)

    if (parsed == null && rangeHeader != null) {
      resource.close()
      return rangeNotSatisfiable(total)
    }

    val start = parsed?.first ?: 0L
    val endInclusive = parsed?.second ?: (total - 1L)
    val length = endInclusive - start + 1L

    val input: InputStream = ChannelRangeInputStream(resource.channel, start, length, autoClose = true)

    return if (parsed != null) {
      val response = newFixedLengthResponse(STATUS_PARTIAL_CONTENT, resource.contentType, input, length)
      response.addHeader("Accept-Ranges", "bytes")
      response.addHeader("Content-Range", "bytes $start-$endInclusive/$total")
      response.addHeader("Cache-Control", "no-store")
      response
    } else {
      val response = newFixedLengthResponse(Response.Status.OK, resource.contentType, input, total)
      response.addHeader("Accept-Ranges", "bytes")
      response.addHeader("Cache-Control", "no-store")
      response
    }
  }

  private fun handleThumbnail(token: String, entryId: String): Response {
    val active = thumbnailTokenInternal
    if (active == null || !safeEquals(active, token)) {
      return newFixedLengthResponse(STATUS_NOT_FOUND, MIME_PLAINTEXT, "")
    }
    val resource = try {
      provider.loadThumbnailResource(entryId)
    } catch (t: Throwable) {
      null
    } ?: return newFixedLengthResponse(STATUS_NOT_FOUND, MIME_PLAINTEXT, "")

    val response = newFixedLengthResponse(
      Response.Status.OK,
      resource.contentType,
      ByteArrayInputStream(resource.data),
      resource.data.size.toLong(),
    )
    response.addHeader("Cache-Control", "private, max-age=86400")
    return response
  }

  private fun rangeNotSatisfiable(total: Long): Response {
    val response = newFixedLengthResponse(STATUS_RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
    response.addHeader("Content-Range", "bytes */$total")
    return response
  }

  /**
   * Parse a single-range `Range: bytes=N-M` (or `bytes=N-`, or `bytes=-S` suffix range)
   * header value. Multi-range requests (`bytes=N-M,X-Y`) are explicitly rejected as null
   * since ExoPlayer never issues them for progressive media.
   */
  private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
    if (header.isNullOrBlank() || !header.startsWith("bytes=")) return null
    val spec = header.removePrefix("bytes=").trim()
    if (spec.contains(',')) return null
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()
    return try {
      when {
        first.isEmpty() && last.isNotEmpty() -> {
          val suffix = last.toLong().coerceAtLeast(0L)
          val start = (total - suffix).coerceAtLeast(0L)
          start to (total - 1L)
        }
        last.isEmpty() && first.isNotEmpty() -> {
          val start = first.toLong()
          if (start >= total) return null
          start to (total - 1L)
        }
        first.isNotEmpty() && last.isNotEmpty() -> {
          val start = first.toLong()
          val end = last.toLong()
          if (start > end || start >= total) return null
          start to end.coerceAtMost(total - 1L)
        }
        else -> null
      }
    } catch (e: NumberFormatException) {
      null
    }
  }

  private fun pruneEvictedSessions(now: Long) {
    val expiredKeys = videoSessions.entries.mapNotNull { (key, session) ->
      val evictedAt = session.evictedAt ?: return@mapNotNull null
      if (now - evictedAt > evictionGraceMs) key else null
    }
    for (key in expiredKeys) {
      videoSessions.remove(key)
    }
  }

  private fun startIdleWatcher() {
    if (idleExecutor != null) return
    val executor = ScheduledThreadPoolExecutor(1) { runnable ->
      Thread(runnable, "vault-loopback-idle").apply { isDaemon = true }
    }
    idleExecutor = executor
    idleTask = executor.scheduleAtFixedRate(
      {
        val now = System.currentTimeMillis()
        if (videoSessions.isEmpty() && now - lastActivityAt > idleTimeoutMs) {
          stop()
          onAutoStopped?.invoke()
        }
      },
      IDLE_CHECK_INTERVAL_MS,
      IDLE_CHECK_INTERVAL_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  private fun newToken(): String {
    val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }

  private fun safeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
      diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
  }

  init {
    require(InetAddress.getByName(LOOPBACK_HOST).isLoopbackAddress) {
      "VaultLoopbackServer host did not resolve to loopback"
    }
  }
}

/** Snapshot of internal state for diagnostics. */
data class VaultLoopbackSnapshot(
  val isRunning: Boolean,
  val port: Int?,
  val activeVideoSessions: Int,
  val evictedVideoSessions: Int,
  val thumbnailTokenIssued: Boolean,
  val lastActivityAt: Long?,
)

/**
 * Range-bounded view of a [SeekableByteChannel]. Positions the channel at [start] on
 * construction and surfaces exactly [length] bytes through the `InputStream` contract.
 * Closing the stream closes the underlying channel.
 */
private class ChannelRangeInputStream(
  private val channel: SeekableByteChannel,
  private val start: Long,
  private val length: Long,
  private val autoClose: Boolean,
) : InputStream() {
  private val singleByteBuffer: ByteBuffer = ByteBuffer.allocate(1)
  private var consumed: Long = 0L
  private var initialized: Boolean = false

  private fun ensurePositioned() {
    if (!initialized) {
      channel.position(start)
      initialized = true
    }
  }

  override fun read(): Int {
    if (consumed >= length) return -1
    ensurePositioned()
    singleByteBuffer.clear()
    val read = channel.read(singleByteBuffer)
    if (read <= 0) return -1
    consumed += 1
    singleByteBuffer.flip()
    return singleByteBuffer.get().toInt() and 0xFF
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    if (consumed >= length) return -1
    ensurePositioned()
    val remaining = length - consumed
    val toRead = minOf(len.toLong(), remaining).toInt()
    if (toRead <= 0) return -1
    val buffer = ByteBuffer.wrap(b, off, toRead)
    val read = channel.read(buffer)
    if (read <= 0) return -1
    consumed += read
    return read
  }

  override fun available(): Int = (length - consumed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

  override fun close() {
    if (autoClose) {
      runCatching { channel.close() }
    }
  }
}
