package expo.modules.localdownloader.watch

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
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
 *
 * With [background] (the Watch player, never the vault's) it plays on out of sight and in
 * picture-in-picture, through [Playback]. React Native runs no timers and lays nothing out
 * while the activity is paused, which it is in both, so what has to happen there happens
 * here: the position is written down ([record]), and in the small window everything but
 * the picture is hidden, and the picture held to the window's size.
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
  /** From asking for a file until its first frame: what the screen shows as loading. */
  @Volatile private var starting = false

  /** Subtitle and audio languages, most preferred first, as ISO 639-2 codes. */
  var languages: List<String> = emptyList()

  /** Plays on out of sight, and in picture-in-picture. */
  var background = false

  /**
   * How large subtitles are drawn against the default, and whether in bold: the viewer's own
   * setting. A styled (ASS) subtitle is scaled too, but keeps the weight it was made with.
   */
  var subtitleScale = 1.0
    set(value) {
      field = value
      mpv?.setPropertyDouble("sub-scale", value)
    }
  var subtitleBold = false
    set(value) {
      field = value
      mpv?.setPropertyString("sub-bold", if (value) "yes" else "no")
    }

  /** What the notification calls the video, and what it puts under that: the series. */
  var title = ""
  var subtitle: String? = null

  /**
   * Writes down where the video is: every few seconds as it plays, when it pauses, at its
   * end and on the way out. Null for a video that is not kept (a trailer, the vault's).
   */
  var record: ((positionMs: Long, durationMs: Long) -> Unit)? = null
  /** [record] as it was when the video now loaded was: a new video's arrives before it does. */
  private var recordLoaded: ((positionMs: Long, durationMs: Long) -> Unit)? = null
  private var recordedAt = 0L

  /** The video track, while it is off because nothing shows it. */
  private var videoBefore: String? = null

  /** The picture's width over its height, as last known: the small window's shape. */
  private var shape = 16.0 / 9.0

  /** What is hidden while the window is the small one, to show again after it. */
  private val aside = mutableListOf<View>()

  /**
   * In the small window only the picture shows, and it fills the window. Held before every
   * frame of the window, not done once as the window shrinks: React Native makes the controls
   * anew each time they appear, and the swipe that leaves the app begins as a tap that shows
   * them; and its layout for the window's new size can wait behind the paused activity, the
   * picture left a size behind. The surface stays the one it was, so the video does not stop.
   */
  private val alone = ViewTreeObserver.OnPreDrawListener {
    val activity = appContext.currentActivity
    if (!background || activity == null) return@OnPreDrawListener true
    if (!activity.isInPictureInPictureMode) {
      showAside()
      return@OnPreDrawListener true
    }
    val root = activity.findViewById<ViewGroup>(android.R.id.content)
    // This view and each one it is in, up to the window's root; beside each, what to hide.
    val within = mutableListOf<View>(this)
    var inside: View = this
    var around = inside.parent as? ViewGroup
    while (around != null) {
      for (i in 0 until around.childCount) {
        val other = around.getChildAt(i)
        if (other !== inside && other.visibility == View.VISIBLE) {
          other.visibility = View.INVISIBLE
          aside += other
        }
      }
      if (around === root) break
      within += around
      inside = around
      around = inside.parent as? ViewGroup
    }
    if (root != null && root.width > 0 && root.height > 0 && (width != root.width || height != root.height)) {
      val wide = MeasureSpec.makeMeasureSpec(root.width, MeasureSpec.EXACTLY)
      val tall = MeasureSpec.makeMeasureSpec(root.height, MeasureSpec.EXACTLY)
      within.asReversed().forEach {
        it.measure(wide, tall)
        it.layout(0, 0, root.width, root.height)
      }
    }
    true
  }
  private val pictureInPicture = Consumer<PictureInPictureModeChangedInfo> { onPictureInPicture(it.isInPictureInPictureMode) }
  private val keepWindow = Runnable { appContext.currentActivity?.let { PlayerWindow.keep(it) } }

  /** [audioUrl]: the audio as a separate file, played with the video. */
  data class Source(val url: String, val headers: Map<String, String>, val startMs: Long, val audioUrl: String? = null)

  init {
    addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    surface.holder.addCallback(this)
    surface.keepScreenOn = true
    mpv = runCatching { create(context) }.getOrNull()
    if (mpv == null) post { onFailed(mapOf("code" to "PLAYER_UNAVAILABLE")) }
    // The bars back by themselves (not by a swipe, which never gets here): hidden again.
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
      if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
        removeCallbacks(keepWindow)
        postDelayed(keepWindow, KEEP_WINDOW_AFTER_MS)
      }
      insets
    }
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
    // yt-dlp runs in the app, not inside mpv.
    setOptionString("ytdl", "no")
    setOptionString("sub-auto", "fuzzy")
    // libass has no font provider on Android: without fonts of its own it draws text subtitles
    // in a fallback with no Turkish letters, the spaces lost and ı, ş, ğ set apart.
    fonts(context)?.let {
      setOptionString("sub-fonts-dir", it.path)
      setOptionString("osd-fonts-dir", it.path)
      setOptionString("sub-font", "Noto Sans")
    }
    setOptionString("keep-open", "yes")
    setOptionString("idle", "yes")
    init()
    observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    observeProperty("seeking", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
    observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    addObserver(this@MpvPlayerView)
  }

  // ---- what the screen sets ----------------------------------------------------------------

  fun setSource(source: Source?) {
    if (source == null || source == loaded) return
    // Where the video it replaces is, before that one goes: coming back to it starts there.
    keepPlace()
    pending = source
    // After the rest of this batch of props, so the languages are in place first.
    post { if (surfaceReady) loadPending() }
  }

  private fun loadPending() {
    val source = pending ?: return
    val mpv = mpv ?: return
    pending = null
    loaded = source
    recordLoaded = record
    starting = true
    // Each preferred language by every code a file may use for it.
    val codes = languages.flatMap { code -> Addons.languages.firstOrNull { it.code == code }?.codes.orEmpty().sortedBy { it != code } }
      .joinToString(",")
    mpv.setPropertyString("slang", codes)
    mpv.setPropertyString("alang", codes)
    // One header at a time, so a comma in a value cannot split it.
    mpv.command(arrayOf("change-list", "http-header-fields", "clr", ""))
    source.headers.forEach { (name, value) -> mpv.command(arrayOf("change-list", "http-header-fields", "append", "$name: $value")) }
    val options = listOfNotNull(
      if (source.startMs > 0) "start=${source.startMs / 1000.0}" else null,
      // Length-quoted, so a comma in the URL cannot end the option.
      source.audioUrl?.let { "audio-file=%${it.toByteArray().size}%$it" },
    ).joinToString(",")
    mpv.command(if (options.isEmpty()) arrayOf("loadfile", source.url) else arrayOf("loadfile", source.url, "replace", "-1", options))
    // A new video plays. mpv pauses itself at the end of one (keep-open), and that would carry
    // over to the next episode. After the load, not before: unpaused at the end, it pauses again.
    mpv.setPropertyBoolean("pause", false)
    if (background) {
      Playback.begin(this, state(mpv))
      offerPictureInPicture()
    }
  }

  fun setPaused(paused: Boolean) = mpv?.setPropertyBoolean("pause", paused)

  fun togglePaused() = mpv?.let { it.setPropertyBoolean("pause", it.getPropertyBoolean("pause") != true) }

  fun seek(ms: Double) = mpv?.command(arrayOf("seek", "${ms / 1000.0}", "absolute"))

  /**
   * Exactly that far. A plain relative seek goes to a keyframe: near the end that is the last
   * one, some seconds back, to be watched out again, where the skip should end the video.
   */
  fun seekBy(ms: Double) = mpv?.command(arrayOf("seek", "${ms / 1000.0}", "relative+exact"))

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
    // Last, with the output in place: the track comes back into a sound that never stopped.
    videoBefore?.let { mpv.setPropertyString("vid", it) }
    videoBefore = null
    loadPending()
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    mpv?.setPropertyString("android-surface-size", "${width}x$height")
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    val mpv = mpv ?: return
    surfaceReady = false
    // Out of sight (the app sent to the background, the screen off) is paused, not playing
    // on unseen; unless this player plays on there, and then only its sound does. The video
    // track is turned off first: taking the output from under a playing track makes mpv seek
    // to where it is, which is heard as a gap; with no track there is nothing to seek for.
    if (background) {
      if (videoBefore == null) videoBefore = mpv.getPropertyString("vid") ?: "auto"
      mpv.setPropertyString("vid", "no")
    } else {
      mpv.setPropertyBoolean("pause", true)
    }
    mpv.setPropertyString("vo", "null")
    mpv.setOptionString("force-window", "no")
    mpv.detachSurface()
  }

  // ---- the window --------------------------------------------------------------------------

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    val activity = appContext.currentActivity ?: return
    PlayerWindow.enter(this, activity)
    (activity as? ComponentActivity)?.addOnPictureInPictureModeChangedListener(pictureInPicture)
    viewTreeObserver.addOnPreDrawListener(alone)
    offerPictureInPicture()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    removeCallbacks(keepWindow)
    viewTreeObserver.removeOnPreDrawListener(alone)
    showAside()
    // The player's own screen gone, not the app out of sight: nothing plays on from there.
    if (background) mpv?.setPropertyBoolean("pause", true)
    val activity = appContext.currentActivity
    (activity as? ComponentActivity)?.removeOnPictureInPictureModeChangedListener(pictureInPicture)
    offerPictureInPicture(onScreen = false)
    PlayerWindow.leave(this, activity)
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus) keepWindow.run()
  }

  // ---- picture-in-picture ------------------------------------------------------------------

  private fun pictureInPictureParams(auto: Boolean): PictureInPictureParams {
    // The picture's own shape, within what Android takes for the small window (1:2.39 to 2.39:1).
    mpv?.getPropertyDouble("video-params/aspect")?.takeIf { it > 0 }?.let { shape = it }
    val ratio = shape.coerceIn(0.42, 2.38)
    val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
    // Where the picture is on the screen now, so the window shrinks from it.
    val bounds = Rect()
    if (getGlobalVisibleRect(bounds) && !bounds.isEmpty) {
      val wide = minOf(bounds.width(), (bounds.height() * ratio).toInt())
      val tall = minOf(bounds.height(), (bounds.width() / ratio).toInt())
      bounds.inset((bounds.width() - wide) / 2, (bounds.height() - tall) / 2)
      builder.setSourceRectHint(bounds)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(auto)
    return builder.build()
  }

  private fun canPictureInPicture(activity: Activity) =
    Playback.current === this && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

  /**
   * Tells Android whether leaving the app now should shrink it into the small window (from
   * Android 12 it does that by itself): only while this player is on screen and playing.
   * The setting is the activity's, so it is taken back as the player leaves.
   */
  private fun offerPictureInPicture(onScreen: Boolean = isAttachedToWindow) {
    val activity = appContext.currentActivity ?: return
    if (!canPictureInPicture(activity)) return
    val playing = onScreen && loaded != null && mpv?.getPropertyBoolean("pause") == false
    runCatching { activity.setPictureInPictureParams(pictureInPictureParams(auto = playing)) }
  }

  /** Into the small window now: the player's own button. */
  fun enterPictureInPicture() {
    val activity = appContext.currentActivity ?: return
    if (!canPictureInPicture(activity) || activity.isInPictureInPictureMode) return
    runCatching { activity.enterPictureInPictureMode(pictureInPictureParams(auto = true)) }
  }

  /** The user is leaving the app (home, recents). Before Android 12 nothing enters by itself. */
  fun userLeaves() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && mpv?.getPropertyBoolean("pause") == false) enterPictureInPicture()
  }

  /** Into the small window or out of it. What shows in it is [alone]'s, frame by frame. */
  private fun onPictureInPicture(active: Boolean) {
    val activity = appContext.currentActivity ?: return
    if (!background) return
    invalidate()
    if (active) return
    // Closed, not opened again: its own close button asks for the video to stop.
    val shown = (activity as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false
    if (!shown) setPaused(true)
    keepWindow.run()
  }

  private fun showAside() {
    aside.forEach { it.visibility = View.VISIBLE }
    aside.clear()
  }

  /** When React Native is done with the view; a detach alone may be followed by a reattach. */
  fun release() {
    showAside()
    keepPlace()
    // Before the session lets go of this player: after it, the offer is no longer its to take back.
    offerPictureInPicture(onScreen = false)
    Playback.end(this)
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
    if (property == "duration" || property == "speed") post { reportPlayback() }
  }

  override fun eventProperty(property: String, value: Boolean) {
    when (property) {
      "eof-reached" -> if (value) post {
        keepPlace(finished = true)
        onEnded(emptyMap())
      }
      "pause", "paused-for-cache", "seeking" -> post {
        reportProgress()
        reportPlayback()
        if (property == "pause") {
          if (value) keepPlace()
          offerPictureInPicture()
        }
      }
    }
  }

  override fun eventProperty(property: String, value: String) {
    if (property == "sid" || property == "aid") post { reportTracks() }
  }

  override fun event(eventId: Int) {
    if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART && starting) {
      starting = false
      post {
        reportProgress()
        reportPlayback()
        // The picture's shape is known from here.
        offerPictureInPicture()
      }
    }
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

  /** Opening, waiting for the network, or finding the place after a seek. */
  private fun buffering(mpv: MPVLib) =
    starting || mpv.getPropertyBoolean("paused-for-cache") == true || mpv.getPropertyBoolean("seeking") == true

  private fun reportProgress() {
    val mpv = mpv ?: return
    val paused = mpv.getPropertyBoolean("pause") ?: false
    onProgress(mapOf(
      "positionMs" to ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000),
      "durationMs" to ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000),
      "paused" to paused,
      "buffering" to buffering(mpv),
    ))
    if (!paused && System.currentTimeMillis() - recordedAt >= RECORD_EVERY_MS) keepPlace()
  }

  private fun state(mpv: MPVLib) = Playback.State(
    title = title,
    subtitle = subtitle,
    paused = mpv.getPropertyBoolean("pause") ?: false,
    buffering = buffering(mpv),
    positionMs = ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong(),
    durationMs = ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000).toLong(),
    speed = (mpv.getPropertyDouble("speed") ?: 1.0).toFloat(),
  )

  /** What the session shows; it counts the position on by itself between these. */
  private fun reportPlayback() {
    val mpv = mpv ?: return
    if (background) Playback.update(this, state(mpv))
  }

  /**
   * Not before the first frame: until then the position is not where the video resumes.
   * Written as the video that is loaded, not as one on its way in, whose [record] is already
   * here while the position is still this one's.
   */
  private fun keepPlace(finished: Boolean = false) {
    val record = recordLoaded ?: return
    val mpv = mpv ?: return
    if (starting || loaded == null) return
    val durationMs = ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
    if (durationMs <= 0) return
    recordedAt = System.currentTimeMillis()
    record(if (finished) durationMs else ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong(), durationMs)
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
    /** How often the position is written down while playing. */
    private const val RECORD_EVERY_MS = 10_000L
    private const val KEEP_WINDOW_AFTER_MS = 300L

    /**
     * Android's own trusted certificates as one PEM file, for FFmpeg's TLS: the PEM blocks of
     * every file in the system store. Made once per app version.
     */
    @Volatile private var bundle: File? = null
    @Volatile private var fontDir: File? = null

    /**
     * The subtitle fonts (Noto Sans, OFL, in assets/subfonts) as files, where mpv can read
     * them. Copied once per app version.
     */
    fun fonts(context: Context): File? {
      fontDir?.let { return it }
      return runCatching {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(context.filesDir, "mpv-fonts/$version")
        if (!dir.isDirectory) {
          dir.parentFile?.deleteRecursively()
          dir.mkdirs()
          context.assets.list("subfonts").orEmpty().filter { it.endsWith(".ttf") }.forEach { name ->
            context.assets.open("subfonts/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
          }
        }
        dir.also { fontDir = it }
      }.getOrNull()
    }

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
