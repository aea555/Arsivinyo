package expo.modules.localdownloader.watch

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jdtech.mpv.MPVLib
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.io.File
import org.json.JSONArray

/**
 * The player (`shared/watch/CONTRACT.md`, "The player"): libmpv drawing into a SurfaceView,
 * so MKV, HEVC, AC3/DTS and styled subtitles all play, which the system player cannot do.
 *
 * The screen drives it through props and the view functions the module defines; it reports
 * position, tracks, the end and failures as events. Nothing about what plays is logged.
 */
class MpvPlayerView(context: Context, appContext: AppContext) : ExpoView(context, appContext),
  SurfaceHolder.Callback, MPVLib.EventObserver {

  val onProgress by EventDispatcher()
  val onTracks by EventDispatcher()
  val onEnded by EventDispatcher()
  val onFailed by EventDispatcher()

  private val surface = SurfaceView(context)
  private var mpv: MPVLib? = null
  private var surfaceReady = false
  private var pending: Source? = null
  private var loaded: Source? = null
  private var lastProgressAt = 0L
  private var orientationBefore: Int? = null

  /** Subtitle and audio languages, most preferred first, as ISO 639-2 codes. */
  var languages: List<String> = emptyList()

  data class Source(val url: String, val headers: Map<String, String>, val startMs: Long)

  init {
    addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    surface.holder.addCallback(this)
    surface.keepScreenOn = true
    mpv = runCatching { create(context) }.getOrNull()
    if (mpv == null) post { onFailed(mapOf("code" to "PLAYER_UNAVAILABLE")) }
  }

  private fun create(context: Context): MPVLib = checkNotNull(MPVLib.create(context)).apply {
    setOptionString("vo", "gpu")
    setOptionString("gpu-context", "android")
    setOptionString("opengl-es", "yes")
    // Hardware decoding where the phone has it, copied back so subtitles and filters work.
    setOptionString("hwdec", "mediacodec-copy")
    setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
    setOptionString("ao", "audiotrack,opensles")
    // Streams: read ahead generously, so a seek within what has come does not wait.
    setOptionString("cache", "yes")
    setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
    setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
    // https through FFmpeg's mbedTLS, checked against what Android itself trusts.
    certificates(context)?.let {
      setOptionString("tls-verify", "yes")
      setOptionString("tls-ca-file", it.path)
    }
    setOptionString("sub-auto", "fuzzy")
    setOptionString("keep-open", "yes")
    setOptionString("idle", "yes")
    init()
    observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
    observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    addObserver(this@MpvPlayerView)
  }

  // ---- what the screen sets ----------------------------------------------------------------

  fun setSource(source: Source?) {
    if (source == null || source == loaded) return
    pending = source
    // After the rest of this batch of props, so the languages are in place first.
    post { if (surfaceReady) loadPending() }
  }

  private fun loadPending() {
    val source = pending ?: return
    val mpv = mpv ?: return
    pending = null
    loaded = source
    // Each preferred language by every code a file may use for it.
    val codes = languages.flatMap { code -> Addons.languages.firstOrNull { it.code == code }?.codes.orEmpty().sortedBy { it != code } }
      .joinToString(",")
    mpv.setPropertyString("slang", codes)
    mpv.setPropertyString("alang", codes)
    // One header at a time, so a comma in a value cannot split it.
    mpv.command(arrayOf("change-list", "http-header-fields", "clr", ""))
    source.headers.forEach { (name, value) -> mpv.command(arrayOf("change-list", "http-header-fields", "append", "$name: $value")) }
    val options = if (source.startMs > 0) "start=${source.startMs / 1000.0}" else ""
    mpv.command(if (options.isEmpty()) arrayOf("loadfile", source.url) else arrayOf("loadfile", source.url, "replace", "-1", options))
  }

  fun setPaused(paused: Boolean) = mpv?.setPropertyBoolean("pause", paused)

  fun seek(ms: Double) = mpv?.command(arrayOf("seek", "${ms / 1000.0}", "absolute"))

  fun seekBy(ms: Double) = mpv?.command(arrayOf("seek", "${ms / 1000.0}", "relative"))

  /** A track id, or "no" to turn subtitles off. */
  fun setTrack(kind: String, id: String) = mpv?.setPropertyString(if (kind == "audio") "aid" else "sid", id)

  fun addSubtitle(url: String, title: String, lang: String, select: Boolean) =
    mpv?.command(arrayOf("sub-add", url, if (select) "select" else "auto", title, lang))

  fun setSubtitleDelay(ms: Double) = mpv?.setPropertyDouble("sub-delay", ms / 1000.0)

  fun setSpeed(speed: Double) = mpv?.setPropertyDouble("speed", speed)

  // ---- the surface -------------------------------------------------------------------------

  override fun surfaceCreated(holder: SurfaceHolder) {
    val mpv = mpv ?: return
    mpv.attachSurface(holder.surface)
    mpv.setOptionString("force-window", "yes")
    mpv.setPropertyString("vo", "gpu")
    surfaceReady = true
    loadPending()
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    mpv?.setPropertyString("android-surface-size", "${width}x$height")
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    val mpv = mpv ?: return
    surfaceReady = false
    // Out of sight (the app sent to the background) is paused, not playing on unseen.
    mpv.setPropertyBoolean("pause", true)
    mpv.setPropertyString("vo", "null")
    mpv.setOptionString("force-window", "no")
    mpv.detachSurface()
  }

  // Fullscreen in landscape for as long as the player is on screen, as a film is watched.
  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    val activity = appContext.currentActivity ?: return
    orientationBefore = activity.requestedOrientation
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars())
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    appContext.currentActivity?.let { activity ->
      orientationBefore?.let { activity.requestedOrientation = it }
      WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }
  }

  /** When React Native is done with the view; a detach alone may be followed by a reattach. */
  fun release() {
    val mpv = mpv ?: return
    this.mpv = null
    runCatching {
      mpv.removeObserver(this)
      mpv.destroy()
    }
  }

  // ---- what mpv says -----------------------------------------------------------------------

  override fun eventProperty(property: String) {
    if (property == "track-list") post { reportTracks() }
  }

  override fun eventProperty(property: String, value: Long) = Unit

  override fun eventProperty(property: String, value: Double) {
    if (property == "time-pos") {
      // A few times a second is enough for a seek bar.
      val now = System.currentTimeMillis()
      if (now - lastProgressAt < 250) return
      lastProgressAt = now
    }
    if (property == "time-pos" || property == "duration") post { reportProgress() }
  }

  override fun eventProperty(property: String, value: Boolean) {
    when (property) {
      "eof-reached" -> if (value) post { onEnded(emptyMap()) }
      "pause", "paused-for-cache" -> post { reportProgress() }
    }
  }

  override fun eventProperty(property: String, value: String) {
    if (property == "sid" || property == "aid") post { reportTracks() }
  }

  override fun event(eventId: Int) {
    if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
      // keep-open holds a finished video at its end, and a replaced one is followed by the
      // next: so a file that ends with mpv going idle is one that would not play.
      post {
        val mpv = mpv ?: return@post
        if (loaded != null && pending == null && mpv.getPropertyBoolean("idle-active") == true) {
          onFailed(mapOf("code" to "PLAYBACK_FAILED"))
        }
      }
    }
  }

  private fun reportProgress() {
    val mpv = mpv ?: return
    onProgress(mapOf(
      "positionMs" to ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000),
      "durationMs" to ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000),
      "paused" to (mpv.getPropertyBoolean("pause") ?: false),
      "buffering" to (mpv.getPropertyBoolean("paused-for-cache") ?: false),
    ))
  }

  private fun reportTracks() {
    val mpv = mpv ?: return
    val raw = runCatching { JSONArray(mpv.getPropertyString("track-list") ?: "[]") }.getOrNull() ?: return
    val tracks = (0 until raw.length()).mapNotNull { i ->
      val t = raw.optJSONObject(i) ?: return@mapNotNull null
      val type = t.optString("type")
      if (type != "audio" && type != "sub") return@mapNotNull null
      mapOf(
        "kind" to if (type == "audio") "audio" else "subtitle",
        "id" to t.optInt("id").toString(),
        "title" to t.optString("title").ifBlank { null },
        // By its 639-2 code where it is a language the app knows, as the preferences are.
        "lang" to t.optString("lang").ifBlank { null }?.let { Addons.language(it) ?: it },
        "codec" to t.optString("codec").ifBlank { null },
        "external" to t.optBoolean("external"),
        "selected" to t.optBoolean("selected"),
      )
    }
    onTracks(mapOf("tracks" to tracks))
  }

  companion object {
    /**
     * Android's own trusted certificates as one PEM file, for FFmpeg's TLS: the PEM blocks of
     * every file in the system store. Made once per app version.
     */
    @Volatile private var bundle: File? = null

    fun certificates(context: Context): File? {
      bundle?.let { return it }
      return runCatching {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val out = File(context.filesDir, "mpv/cacert-$version.pem")
        if (!out.isFile) {
          out.parentFile?.listFiles()?.forEach { it.delete() }
          out.parentFile?.mkdirs()
          val pem = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")
          val text = File("/system/etc/security/cacerts").listFiles().orEmpty()
            .flatMap { file -> pem.findAll(runCatching { file.readText() }.getOrDefault("")).map { it.value } }
            .joinToString("\n")
          if (text.isEmpty()) return null
          out.writeText(text + "\n")
        }
        out.also { bundle = it }
      }.getOrNull()
    }
  }
}
