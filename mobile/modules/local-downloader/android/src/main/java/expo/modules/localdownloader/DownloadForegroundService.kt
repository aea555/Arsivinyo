package expo.modules.localdownloader

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

class DownloadForegroundService : Service() {
  private var inForeground = false

  override fun onCreate() {
    super.onCreate()
    isRunning = true
  }

  override fun onBind(intent: Intent?): IBinder? = null

  /**
   * Every start is answered with startForeground, first, whatever it asks for; and the
   * service is stopped by the id of the start that asked, never outright.
   *
   * It is started with startForegroundService, and Android ends the app ("did not then call
   * Service.startForeground()") if a service started that way is stopped before it has gone
   * to the foreground. Both halves of that used to be possible here. A start that asked the
   * service to stop, or found nothing to show, stopped it without going to the foreground.
   * And it stopped itself outright, with stopSelf(): so a start that asked it to stop took
   * with it one that came in behind and asked it to run, which had not been answered yet.
   * The module starts and stops this from several threads as the app opens, a download
   * begins and one ends, so the two did meet: seen as the app closing about two seconds
   * after it was opened, several times in a row.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Null after Android restarted the service by itself: nothing to show, so it stops.
    val state = DownloadNotificationController.parseState(intent)
    val run = intent?.action != DownloadNotificationController.ACTION_STOP && state.shouldRunForeground
    val shown = runCatching {
      val notification = DownloadNotificationController.buildNotification(this, state)
      if (!inForeground) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
          0
        }
        ServiceCompat.startForeground(this, DownloadNotificationController.NOTIFICATION_ID, notification, serviceType)
        inForeground = true
      } else if (run) {
        NotificationManagerCompat.from(this).notify(DownloadNotificationController.NOTIFICATION_ID, notification)
      }
    }
    if (run && shown.isSuccess) return START_STICKY
    if (run) {
      // Android would not have it in the foreground (asked from behind another app, say).
      val error = shown.exceptionOrNull()
      Log.e(TAG, "Foreground notification update failed: ${error?.javaClass?.simpleName}")
      LocalDownloaderModule.reportBackgroundServiceStartFailure(error?.message ?: "unknown")
    }
    finish(startId)
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    isRunning = false
    super.onDestroy()
  }

  /** Out of the foreground, and stopped unless a later start is still to be answered. */
  private fun finish(startId: Int) {
    if (inForeground) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      inForeground = false
    }
    NotificationManagerCompat.from(this).cancel(DownloadNotificationController.NOTIFICATION_ID)
    stopSelf(startId)
  }

  companion object {
    private const val TAG = "DownloadFgService"

    @Volatile
    var isRunning: Boolean = false
      private set
  }
}
