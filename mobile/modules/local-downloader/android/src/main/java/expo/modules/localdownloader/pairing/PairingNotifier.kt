package expo.modules.localdownloader.pairing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import expo.modules.localdownloader.R

/**
 * A notification while music goes to or comes from a paired device, so a transfer is seen
 * wherever the user is, not only on Devices: the device, how far, and "3 of 12" when the
 * sender said. Never what the tracks are called: what moves between devices is private.
 *
 * It follows the transfer rather than a timer. Updates are at most a few a second; when the
 * last of a batch is done it says so and goes away by itself.
 */
class PairingNotifier(private val context: Context) {
  private var lastUpdate = 0L
  /** Whether the transfer in flight is coming in; and the batch it said, for the summary. */
  private var receiving = false
  private var batch: Pair<Int, Int>? = null

  fun progress(receiving: Boolean, peer: String, done: Long, total: Long, batch: Pair<Int, Int>?) {
    this.receiving = receiving
    this.batch = batch
    val now = SystemClock.elapsedRealtime()
    if (done < total && now - lastUpdate < UPDATE_EVERY_MS) return
    lastUpdate = now
    val percent = if (total > 0) ((done * 100) / total).toInt() else 0
    post(builder()
      .setContentTitle(context.getString(
        if (receiving) R.string.ldl_pairing_receiving else R.string.ldl_pairing_sending, name(peer)))
      .setContentText(batch?.let { (index, count) -> context.getString(R.string.ldl_pairing_batch, index, count) })
      .setProgress(100, percent, total <= 0)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build())
  }

  /**
   * One file done. Coming in, the sender's batch says whether it was the last; going out,
   * [moreToSend] does, and [sendCount] is how many the batch had. One the other device
   * already had never showed progress, so it counts as going out.
   */
  fun completed(peer: String, moreToSend: Boolean, sendCount: Int) {
    val incoming = receiving
    val count: Int
    if (incoming) {
      val (index, total) = batch ?: (1 to 1)
      receiving = false
      if (index < total) return
      count = total
    } else {
      if (moreToSend) return
      count = sendCount.coerceAtLeast(1)
    }
    lastUpdate = 0
    batch = null
    post(builder()
      .setContentTitle(context.getString(
        if (incoming) R.string.ldl_pairing_received else R.string.ldl_pairing_sent, name(peer)))
      .setContentText(if (count > 1) context.resources.getQuantityString(R.plurals.ldl_pairing_tracks, count, count) else null)
      .setOngoing(false)
      .setAutoCancel(true)
      .setTimeoutAfter(DONE_SHOWN_MS)
      .build())
  }

  fun failed() {
    lastUpdate = 0
    batch = null
    runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
  }

  private fun name(peer: String) = peer.ifBlank { context.getString(R.string.ldl_pairing_device) }

  private fun builder(): NotificationCompat.Builder {
    ensureChannel()
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(context.applicationInfo.icon)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setSilent(true)
  }

  private fun post(notification: Notification) {
    // Without the notification permission this is simply not shown; Devices still has it.
    runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, context.getString(R.string.ldl_pairing_channel), NotificationManager.IMPORTANCE_LOW).apply {
        description = context.getString(R.string.ldl_pairing_channel_description)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
      })
  }

  private companion object {
    const val CHANNEL_ID = "arsivinyo_pairing"
    const val NOTIFICATION_ID = 7307
    const val UPDATE_EVERY_MS = 400L
    const val DONE_SHOWN_MS = 6_000L
  }
}
