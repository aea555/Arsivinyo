package expo.modules.localdownloader.watch

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import java.io.File
import org.json.JSONArray

/**
 * libmpv and what it plays: a queue of one item or of many, the place in each written down,
 * and the next begun when one ends. It draws into whatever surface it is handed and plays
 * on with none, so nothing here knows of a screen: [MpvPlayerView] is the screen's side.
 *
 * Two kinds. A player's own ([own]) lives and dies with its view: an add-on's stream, a
 * vault video. The [shared] one is the process's, for the app's own library, and outlives
 * the views that show it.
 *
 * With [background] it plays on out of sight through [Playback]. The queue is here and not
 * in TypeScript for that: React Native runs nothing while the activity is paused, and the
 * next item has to begin there.
 *
 * Nothing about what plays is logged. The main thread only, but for what mpv calls.
 */
class MpvEngine private constructor(val context: Context, private val shared: Boolean) : MPVLib.EventObserver {

  /**
   * One thing to play. [place], when there is one, is asked for where to start as the item
   * opens, and [startMs] is not used: a queue is made long before its later items are reached.
   * [record] is told where it is: every few seconds, at a pause, at its end, as it is left.
   */
  class Item(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val startMs: Long = 0L,
    /** The audio as a separate file, played with the video. */
    val audioUrl: String? = null,
    /** What the notification calls it, and what it puts under that. */
    val title: String = "",
    val subtitle: String? = null,
    val record: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    val place: (() -> Long)? = null,
    /** What the app's library knows it as, for an item of the library. */
    val id: String? = null,
  )

  /** The screen's side. Called on the main thread. */
  interface Listener {
    fun progress(event: Map<String, Any>)
    fun tracks(event: Map<String, Any>)
    /** The last item of the queue ended. */
    fun ended()
    fun failed(code: String)
    /** Another item of the queue is the one playing. */
    fun item(index: Int)
    /** Whether it plays, or the picture's shape, may have changed. */
    fun playing()
  }

  var listener: Listener? = null

  private val main = Handler(Looper.getMainLooper())
  private var mpv: MPVLib? = null
  private var items: List<Item> = emptyList()
  private var index = 0
  /** Which queue [items] is, where the one who set it said. */
  private var key: String? = null
  /** Asked for, and waiting for a surface to draw its first frame into. */
  private var pending: Int? = null
  private var loaded: Item? = null
  private var surfaceReady = false
  /** The surface drawn into, so that one already replaced cannot take the picture away. */
  private var surface: Surface? = null
  private var lastProgressAt = 0L
  private var recordedAt = 0L
  /** From asking for a file until its first frame: what the screen shows as loading. */
  @Volatile private var starting = false
  /** The video track, while it is off because nothing shows it. */
  private var videoBefore: String? = null
  /** Playing on with no surface: the screen off, another app in front. */
  private var unseen = false

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

  /**
   * What happens at the end: "one" plays the item again, and again; "all" goes from the last
   * item of the queue to its first; "off", the queue ends where it ends.
   */
  var repeat = "off"
    set(value) {
      field = value
      // mpv's own, so the item goes round with no gap and never reaches an end.
      mpv?.setPropertyString("loop-file", if (value == "one") "inf" else "no")
    }

  /**
   * To the next of them, from the system's media control: off, then round the queue where
   * there is one, then this item alone. The screen is told with the next progress.
   */
  fun cycleRepeat() {
    repeat = when (repeat) {
      "off" -> if (key != null) "all" else "one"
      "all" -> "one"
      else -> "off"
    }
    reportProgress()
    reportPlayback()
  }

  /**
   * The picture fills its surface, cut at the sides or top and bottom where its shape is
   * not the surface's: for the small player, where bars around it would leave little of it.
   * Otherwise it is shown whole.
   */
  var fill = false
    set(value) {
      field = value
      mpv?.setPropertyDouble("panscan", if (value) 1.0 else 0.0)
    }

  init {
    mpv = create(context)
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
    addObserver(this@MpvEngine)
  }

  // ---- the queue -------------------------------------------------------------------------------

  /**
   * Plays [items] from the one at [index]: this is the queue from here on. A queue that is
   * already the one playing (the same [key]) is not begun again: a screen come back to what
   * plays on finds it where it is, and is told so.
   */
  fun play(items: List<Item>, index: Int, key: String? = null) {
    if (items.isEmpty()) return
    val at = index.coerceIn(items.indices)
    // Wherever in it [index] says: a screen's copy of that can be older than the queue's
    // own, which has moved on by itself. Going to another item of it is [skipTo]'s.
    if (key != null && key == this.key && loaded != null) {
      report()
      return
    }
    this.items = items
    this.key = key
    open(at, tell = false)
  }

  /** Everything a screen just arrived needs to draw what is already playing. */
  private fun report() {
    listener?.item(index)
    reportProgress()
    reportTracks()
    listener?.playing()
  }

  /** To another item of the queue. */
  fun skipTo(index: Int) {
    if (index in items.indices) open(index, tell = true)
  }

  val hasNext get() = index < items.lastIndex
  val hasPrevious get() = index > 0

  fun next() = skipTo(index + 1)

  /**
   * A little way into an item, "previous" is its own beginning; at its beginning, the one
   * before it. As every player's button does.
   */
  fun previous() {
    val mpv = mpv ?: return
    val positionMs = (mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000
    if (hasPrevious && positionMs < RESTART_AFTER_MS) skipTo(index - 1) else seek(0.0)
  }

  private fun open(index: Int, tell: Boolean) {
    // Where the item it replaces is, before that one goes: coming back to it starts there.
    keepPlace()
    this.index = index
    pending = index
    // Out of sight nothing waits for a surface: there is none to come, and only sound plays.
    if (surfaceReady || unseen) load()
    if (tell) listener?.item(index)
  }

  private fun load() {
    val item = pending?.let { items.getOrNull(it) } ?: return
    val mpv = mpv ?: return
    pending = null
    loaded = item
    starting = true
    // Each preferred language by every code a file may use for it.
    val codes = languages.flatMap { code -> Addons.languages.firstOrNull { it.code == code }?.codes.orEmpty().sortedBy { it != code } }
      .joinToString(",")
    mpv.setPropertyString("slang", codes)
    mpv.setPropertyString("alang", codes)
    // One header at a time, so a comma in a value cannot split it.
    mpv.command(arrayOf("change-list", "http-header-fields", "clr", ""))
    item.headers.forEach { (name, value) -> mpv.command(arrayOf("change-list", "http-header-fields", "append", "$name: $value")) }
    val startMs = item.place?.let { place -> runCatching { place() }.getOrDefault(0L) } ?: item.startMs
    val options = listOfNotNull(
      if (startMs > 0) "start=${startMs / 1000.0}" else null,
      // Length-quoted, so a comma in the URL cannot end the option.
      item.audioUrl?.let { "audio-file=%${it.toByteArray().size}%$it" },
    ).joinToString(",")
    // One source of sound in the app: a player of its own beginning quiets the shared one.
    if (!shared) sharedEngine?.setPaused(true)
    val url = opened(item.url)
    mpv.command(if (options.isEmpty()) arrayOf("loadfile", url) else arrayOf("loadfile", url, "replace", "-1", options))
    // A new item plays. mpv pauses itself at the end of one (keep-open), and that would carry
    // over to the next. After the load, not before: unpaused at the end, it pauses again.
    mpv.setPropertyBoolean("pause", false)
    if (background) Playback.begin(this, state(mpv))
    listener?.playing()
    tellChanged()
  }

  /**
   * A video of the phone's own (`content://`) as mpv can open it: mpv knows nothing of
   * Android's content providers, so it is handed the open file, which it closes itself.
   */
  private fun opened(url: String): String {
    if (!url.startsWith("content://")) return url
    val fd = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(url), "r")?.detachFd() }.getOrNull()
    return if (fd == null) url else "fdclose://$fd"
  }

  // ---- what the screen asks --------------------------------------------------------------------

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

  /** Something is loaded and not paused. */
  val playing get() = loaded != null && mpv?.getPropertyBoolean("pause") == false

  /** The picture's width over its height, once it is known. */
  val aspect get() = mpv?.getPropertyDouble("video-params/aspect")?.takeIf { it > 0 }

  // ---- the surface -----------------------------------------------------------------------------

  fun attach(surface: Surface) {
    val mpv = mpv ?: return
    this.surface = surface
    mpv.attachSurface(surface)
    mpv.setOptionString("force-window", "yes")
    mpv.setPropertyString("vo", "gpu")
    surfaceReady = true
    unseen = false
    // Last, with the output in place: the track comes back into a sound that never stopped.
    videoBefore?.let { mpv.setPropertyString("vid", it) }
    videoBefore = null
    load()
  }

  fun resize(width: Int, height: Int) {
    mpv?.setPropertyString("android-surface-size", "${width}x$height")
  }

  /** [surface] is gone. Nothing happens if it is no longer the one drawn into. */
  fun detach(surface: Surface) {
    val mpv = mpv ?: return
    if (!surfaceReady || surface !== this.surface) return
    surfaceReady = false
    this.surface = null
    // Out of sight (the app sent to the background, the screen off) is paused, not playing
    // on unseen; unless this player plays on there, and then only its sound does. The video
    // track is turned off first: taking the output from under a playing track makes mpv seek
    // to where it is, which is heard as a gap; with no track there is nothing to seek for.
    if (background) {
      if (videoBefore == null) videoBefore = mpv.getPropertyString("vid") ?: "auto"
      mpv.setPropertyString("vid", "no")
      unseen = loaded != null
    } else {
      mpv.setPropertyBoolean("pause", true)
    }
    mpv.setPropertyString("vo", "null")
    mpv.setOptionString("force-window", "no")
    mpv.detachSurface()
  }

  // ---- the end ---------------------------------------------------------------------------------

  /**
   * Nothing plays from here. A player's own engine is gone with it; the shared one stays,
   * empty, for the next to play.
   */
  fun close() {
    keepPlace()
    Playback.end(this)
    listener = null
    items = emptyList()
    index = 0
    key = null
    pending = null
    loaded = null
    starting = false
    unseen = false
    val mpv = mpv ?: return
    if (shared) {
      runCatching { mpv.command(arrayOf("stop")) }
      tellChanged()
      return
    }
    this.mpv = null
    runCatching {
      mpv.removeObserver(this)
      mpv.destroy()
    }
  }

  // ---- what mpv says ---------------------------------------------------------------------------

  override fun eventProperty(property: String) {
    if (property == "track-list") main.post { reportTracks() }
  }

  override fun eventProperty(property: String, value: Long) = Unit

  override fun eventProperty(property: String, value: Double) {
    if (property == "time-pos") {
      // A few times a second is enough for a seek bar.
      val now = System.currentTimeMillis()
      if (now - lastProgressAt < 250) return
      lastProgressAt = now
    }
    if (property == "time-pos" || property == "duration") main.post { reportProgress() }
    if (property == "duration" || property == "speed") main.post { reportPlayback() }
  }

  override fun eventProperty(property: String, value: Boolean) {
    when (property) {
      "eof-reached" -> if (value) main.post { finished() }
      "pause", "paused-for-cache", "seeking" -> main.post {
        reportProgress()
        reportPlayback()
        if (property == "pause") {
          if (value) keepPlace()
          listener?.playing()
          tellChanged()
        }
      }
    }
  }

  override fun eventProperty(property: String, value: String) {
    if (property == "sid" || property == "aid") main.post { reportTracks() }
  }

  override fun event(eventId: Int) {
    if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART && starting) {
      starting = false
      main.post {
        // Where it resumed from is not where it begins: gone round, it starts at its start.
        mpv?.setPropertyString("start", "none")
        reportProgress()
        reportPlayback()
        // The picture's shape is known from here.
        listener?.playing()
        tellChanged()
      }
    }
    if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
      // keep-open holds a finished item at its end, and a replaced one is followed by the
      // next: so a file that ends with mpv going idle is one that would not play.
      main.post {
        val mpv = mpv ?: return@post
        if (loaded != null && pending == null && mpv.getPropertyBoolean("idle-active") == true) {
          listener?.failed("PLAYBACK_FAILED")
        }
      }
    }
  }

  /** An item played to its end: the next of the queue begins, or the queue is over. */
  private fun finished() {
    // Said again by mpv for an item already left: only the one loaded, and only at its end.
    if (loaded == null || starting || pending != null || mpv?.getPropertyBoolean("eof-reached") != true) return
    keepPlace(finished = true)
    if (hasNext) open(index + 1, tell = true) else if (repeat == "all") open(0, tell = true) else listener?.ended()
  }

  /**
   * What the shared engine plays, for the app's small player: which queue and which item of
   * it, by the library's ids, and whether it is paused. `active` is false when nothing is.
   */
  fun snapshot(): Map<String, Any?> {
    val mpv = mpv
    val item = loaded
    if (mpv == null || item == null) return mapOf("active" to false)
    return mapOf(
      "active" to true,
      "ids" to items.mapNotNull { it.id },
      "index" to index,
      "title" to item.title,
      "subtitle" to item.subtitle,
      "paused" to (mpv.getPropertyBoolean("pause") ?: false),
      "positionMs" to ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000),
      "durationMs" to ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000),
    )
  }

  private fun tellChanged() {
    if (shared) changed?.invoke(snapshot())
  }

  /** Opening, waiting for the network, or finding the place after a seek. */
  private fun buffering(mpv: MPVLib) =
    starting || mpv.getPropertyBoolean("paused-for-cache") == true || mpv.getPropertyBoolean("seeking") == true

  private fun reportProgress() {
    val mpv = mpv ?: return
    val paused = mpv.getPropertyBoolean("pause") ?: false
    listener?.progress(mapOf(
      "positionMs" to ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000),
      "durationMs" to ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000),
      "paused" to paused,
      "buffering" to buffering(mpv),
      "repeat" to repeat,
    ))
    if (!paused && System.currentTimeMillis() - recordedAt >= RECORD_EVERY_MS) keepPlace()
  }

  private fun state(mpv: MPVLib) = Playback.State(
    title = loaded?.title.orEmpty(),
    subtitle = loaded?.subtitle,
    paused = mpv.getPropertyBoolean("pause") ?: false,
    buffering = buffering(mpv),
    positionMs = ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong(),
    durationMs = ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000).toLong(),
    speed = (mpv.getPropertyDouble("speed") ?: 1.0).toFloat(),
    hasPrevious = hasPrevious,
    hasNext = hasNext,
    repeat = repeat,
  )

  /** What the session shows; it counts the position on by itself between these. */
  private fun reportPlayback() {
    val mpv = mpv ?: return
    if (!background || loaded == null) return
    val state = state(mpv)
    // The session may be another player's by now, or gone with it: an add-on's stream
    // played since, and closed. Playing again, this one takes it back; without it there is
    // no notification, no picture-in-picture and nothing to keep the app alive unseen.
    if (Playback.current === this) Playback.update(this, state) else if (!state.paused) Playback.begin(this, state)
  }

  /**
   * Not before the first frame: until then the position is not where the item resumes.
   * Written as the item that is loaded, not as one on its way in.
   */
  private fun keepPlace(finished: Boolean = false) {
    val record = loaded?.record ?: return
    val mpv = mpv ?: return
    if (starting) return
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
    listener?.tracks(mapOf("tracks" to tracks))
  }

  companion object {
    /** How often the position is written down while playing. */
    private const val RECORD_EVERY_MS = 10_000L
    /** This far into an item, "previous" starts it over. */
    private const val RESTART_AFTER_MS = 3_000.0

    private var sharedEngine: MpvEngine? = null

    /** Told what the shared engine plays, each time that changes. On the main thread. */
    var changed: ((Map<String, Any?>) -> Unit)? = null

    /** The process's engine, if it has been made. */
    val current: MpvEngine? get() = sharedEngine

    /** An engine of a player's own. Throws where mpv cannot be made. */
    fun own(context: Context) = MpvEngine(context.applicationContext, shared = false)

    /** The process's engine, made the first time it is asked for. */
    fun shared(context: Context): MpvEngine =
      sharedEngine ?: MpvEngine(context.applicationContext, shared = true).also { sharedEngine = it }

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
