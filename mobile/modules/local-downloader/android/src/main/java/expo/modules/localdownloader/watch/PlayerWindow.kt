package expo.modules.localdownloader.watch

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Fullscreen for as long as a player is on screen, held the way the viewer chose.
 *
 * Kept by count, not by each player for itself. At the next episode the new player can be on
 * screen before the old one has left it; each used to put back what it had found, so the old
 * one leaving turned the screen upright and brought the bars back under the new one, and the
 * new one later "restored" landscape for the rest of the app. Only the last to leave restores.
 *
 * The main thread only.
 */
internal object PlayerWindow {
  private val players = LinkedHashSet<View>()
  private var orientationBefore: Int? = null

  /**
   * Which way the screen is held while a player is the whole of it: "landscape", "portrait",
   * or "auto", which follows the phone as it is turned, where Android's own auto-rotate is
   * on (with that off, the phone stays as it is locked). The viewer's choice, the same for
   * every video: the screen is never turned for a video's own shape, which turned it under
   * the viewer each time a queue went from an upright video to a wide one.
   */
  var orientation = "auto"

  fun enter(player: View, activity: Activity) {
    if (players.isEmpty()) orientationBefore = activity.requestedOrientation
    players.add(player)
    keep(activity)
  }

  fun leave(player: View, activity: Activity?) {
    if (!players.remove(player)) return
    if (players.isNotEmpty()) {
      activity?.let { keep(it) }
      return
    }
    val before = orientationBefore
    orientationBefore = null
    activity ?: return
    before?.let { activity.requestedOrientation = it }
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
  }

  /**
   * Asked again whenever the window comes back or the bars show by themselves: hiding them
   * once does not hold on every phone, and they stayed over the picture until the next video.
   * A swipe from the edge still shows them for a moment; that is the system's, and passes.
   */
  fun keep(activity: Activity) {
    if (players.isEmpty() || activity.isInPictureInPictureMode) return
    val wanted = when (orientation) {
      "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
    if (activity.requestedOrientation != wanted) activity.requestedOrientation = wanted
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars())
    }
  }
}
