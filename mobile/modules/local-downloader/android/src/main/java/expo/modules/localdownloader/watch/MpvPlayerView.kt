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
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView

/**
 * The player on the screen (`shared/watch/CONTRACT.md`, "The player"): a SurfaceView that an
 * [MpvEngine] draws into, so MKV, HEVC, AC3/DTS and styled subtitles all play, which the
 * system player cannot do. What plays and the queue are the engine's; the window, its
 * orientation and picture-in-picture are this view's.
 *
 * The screen drives it through props and the view functions the module defines; it reports
 * position, tracks, the end and failures as events. Nothing about what plays is logged.
 *
 * A [Source] plays in an engine of the view's own, gone with it. A queue of the app's own
 * library ([setQueue]) plays in the process's shared engine.
 *
 * With [background] (never the vault's player) it plays on out of sight and in
 * picture-in-picture, through [Playback]. React Native runs no timers and lays nothing out
 * while the activity is paused, which it is in both, so what has to happen there happens in
 * Kotlin: the engine writes the position down and begins the next item, and in the small
 * window this view hides everything but the picture, and holds the picture to its size.
 */
class MpvPlayerView(context: Context, appContext: AppContext) : ExpoView(context, appContext),
  SurfaceHolder.Callback, MpvEngine.Listener {

  val onProgress by EventDispatcher()
  val onTracks by EventDispatcher()
  val onEnded by EventDispatcher()
  val onFailed by EventDispatcher()
  val onItem by EventDispatcher()

  private val surface = SurfaceView(context)
  private var engine: MpvEngine? = null
  /** [engine] is the process's, not this view's own. */
  private var shared = false
  private var surfaceLive = false
  private var source: Source? = null
  private var queueKey: String? = null
  /** React Native is done with the view: nothing asked of it earlier begins now. */
  private var released = false

  /** Subtitle and audio languages, most preferred first, as ISO 639-2 codes. */
  var languages: List<String> = emptyList()
    set(value) {
      field = value
      engine?.languages = value
    }

  /** Plays on out of sight, and in picture-in-picture. */
  var background = false
    set(value) {
      field = value
      engine?.background = value
    }

  /**
   * How large subtitles are drawn against the default, and whether in bold: the viewer's own
   * setting. A styled (ASS) subtitle is scaled too, but keeps the weight it was made with.
   */
  var subtitleScale = 1.0
    set(value) {
      field = value
      engine?.subtitleScale = value
    }
  var subtitleBold = false
    set(value) {
      field = value
      engine?.subtitleBold = value
    }

  /** "off", "one" (this video again and again) or "all" (the queue from its start again). */
  var repeat = "off"
    set(value) {
      field = value
      // Not set again when it is what the engine has: it may have come from the engine.
      if (engine?.repeat != value) engine?.repeat = value
    }

  /** The video is taller than it is wide, and the screen stays upright for it. */
  var upright = false
    set(value) {
      field = value
      if (!isAttachedToWindow || mini) return
      PlayerWindow.upright = value
      appContext.currentActivity?.let { PlayerWindow.keep(it) }
    }

  /**
   * Small, in the app's bar, not the whole screen: the picture and nothing of the window.
   * The screen stays as it is, awake or not, and there is no picture-in-picture from here.
   * Going between the two only resizes the surface, so the video does not stop.
   */
  var mini = false
    set(value) {
      if (field == value) return
      field = value
      engine?.fill = value
      surface.keepScreenOn = !value
      val activity = appContext.currentActivity
      if (!isAttachedToWindow || activity == null) return
      if (value) {
        offerPictureInPicture()
        PlayerWindow.leave(this, activity)
      } else {
        PlayerWindow.upright = upright
        PlayerWindow.enter(this, activity)
        offerPictureInPicture()
      }
    }

  /** What the notification calls the video, and what it puts under that: the series. */
  var title = ""
  var subtitle: String? = null

  /**
   * Writes down where the video is: every few seconds as it plays, when it pauses, at its
   * end and on the way out. Null for a video that is not kept (a trailer, the vault's).
   */
  var record: ((positionMs: Long, durationMs: Long) -> Unit)? = null

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
    if (!background || mini || activity == null) return@OnPreDrawListener true
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
    surface.keepScreenOn = !mini
    // The bars back by themselves (not by a swipe, which never gets here): hidden again.
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
      if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
        removeCallbacks(keepWindow)
        postDelayed(keepWindow, KEEP_WINDOW_AFTER_MS)
      }
      insets
    }
  }

  /**
   * The engine this view shows: its own or the process's. Made when first played into, and
   * handed the surface if there is one already.
   */
  private fun engine(shared: Boolean): MpvEngine? {
    engine?.let { if (this.shared == shared) return it }
    leaveEngine()
    val made = runCatching { if (shared) MpvEngine.shared(context) else MpvEngine.own(context) }.getOrNull()
    if (made == null) {
      onFailed(mapOf("code" to "PLAYER_UNAVAILABLE"))
      return null
    }
    this.shared = shared
    engine = made
    made.listener = this
    made.languages = languages
    made.background = background
    made.subtitleScale = subtitleScale
    made.subtitleBold = subtitleBold
    made.fill = mini
    made.repeat = repeat
    if (surfaceLive) {
      made.attach(surface.holder.surface)
      if (surface.width > 0 && surface.height > 0) made.resize(surface.width, surface.height)
    }
    return made
  }

  /**
   * Done with the engine. Its own is gone with it. The shared one plays on with no screen,
   * as it does with the app out of sight: the app's small player is what stops it.
   */
  private fun leaveEngine() {
    val engine = engine ?: return
    this.engine = null
    // This view's surface is no longer the engine's, whoever the engine listens to now: it
    // may already have been stopped, or taken by another view.
    if (shared && surfaceLive) engine.detach(surface.holder.surface)
    if (engine.listener !== this) return
    if (shared) engine.listener = null else engine.close()
  }

  // ---- what the screen sets ----------------------------------------------------------------

  fun setSource(source: Source?) {
    if (source == null || source == this.source) return
    this.source = source
    queueKey = null
    // After the rest of this batch of props, so the title, the record and the languages are in place.
    post {
      if (released || this.source !== source) return@post
      engine(shared = false)?.play(
        listOf(MpvEngine.Item(source.url, source.headers, source.startMs, source.audioUrl, title, subtitle, record)), 0,
      )
    }
  }

  /**
   * A queue of the app's own library, played from the item at [index]. [key] says which
   * queue it is: the same one set again changes nothing.
   */
  fun setQueue(key: String, items: List<MpvEngine.Item>, index: Int) {
    if (key == queueKey || items.isEmpty()) return
    queueKey = key
    source = null
    post {
      if (released || queueKey != key) return@post
      engine(shared = true)?.play(items, index, key)
    }
  }

  fun skipTo(index: Int) = engine?.skipTo(index)

  fun setPaused(paused: Boolean) = engine?.setPaused(paused)

  fun seek(ms: Double) = engine?.seek(ms)

  fun seekBy(ms: Double) = engine?.seekBy(ms)

  fun setTrack(kind: String, id: String) = engine?.setTrack(kind, id)

  fun addSubtitle(url: String, title: String, lang: String, select: Boolean) = engine?.addSubtitle(url, title, lang, select)

  fun setSubtitleDelay(ms: Double) = engine?.setSubtitleDelay(ms)

  fun setSpeed(speed: Double) = engine?.setSpeed(speed)

  // ---- what the engine says ----------------------------------------------------------------

  override fun progress(event: Map<String, Any>) = onProgress(event)

  override fun tracks(event: Map<String, Any>) = onTracks(event)

  override fun ended() = onEnded(emptyMap())

  override fun failed(code: String) = onFailed(mapOf("code" to code))

  override fun item(index: Int) = onItem(mapOf("index" to index))

  override fun playing() = offerPictureInPicture()

  // ---- the surface -------------------------------------------------------------------------

  override fun surfaceCreated(holder: SurfaceHolder) {
    surfaceLive = true
    engine?.attach(holder.surface)
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    engine?.resize(width, height)
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    surfaceLive = false
    engine?.detach(holder.surface)
  }

  // ---- the window --------------------------------------------------------------------------

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    val activity = appContext.currentActivity ?: return
    if (!mini) {
      PlayerWindow.upright = upright
      PlayerWindow.enter(this, activity)
    }
    (activity as? ComponentActivity)?.addOnPictureInPictureModeChangedListener(pictureInPicture)
    viewTreeObserver.addOnPreDrawListener(alone)
    offerPictureInPicture()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    removeCallbacks(keepWindow)
    viewTreeObserver.removeOnPreDrawListener(alone)
    showAside()
    // The player's own screen gone, not the app out of sight: nothing of its own plays on
    // from there. The shared engine does, as sound, for the app's small player.
    if (background && !shared) engine?.setPaused(true)
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
    engine?.aspect?.let { shape = it }
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
    engine != null && Playback.current === engine && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

  /**
   * Tells Android whether leaving the app now should shrink it into the small window (from
   * Android 12 it does that by itself): only while this player is on screen and playing.
   * The setting is the activity's, so it is taken back as the player leaves.
   */
  private fun offerPictureInPicture(onScreen: Boolean = isAttachedToWindow) {
    val activity = appContext.currentActivity ?: return
    if (!canPictureInPicture(activity)) return
    val playing = onScreen && !mini && engine?.playing == true
    runCatching { activity.setPictureInPictureParams(pictureInPictureParams(auto = playing)) }
  }

  /** Into the small window now: the player's own button. */
  fun enterPictureInPicture() {
    val activity = appContext.currentActivity ?: return
    if (mini || !canPictureInPicture(activity) || activity.isInPictureInPictureMode) return
    runCatching { activity.enterPictureInPictureMode(pictureInPictureParams(auto = true)) }
  }

  /** The user is leaving the app (home, recents). Before Android 12 nothing enters by itself. */
  fun userLeaves() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && engine?.playing == true) enterPictureInPicture()
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
    released = true
    showAside()
    // Before the session lets go of this player: after it, the offer is no longer its to take back.
    offerPictureInPicture(onScreen = false)
    leaveEngine()
  }

  companion object {
    private const val KEEP_WINDOW_AFTER_MS = 300L
  }
}
