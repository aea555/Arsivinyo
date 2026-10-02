package expo.modules.localdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

internal data class BackgroundNotificationState(
  val activeTaskId: String?,
  val phase: String,
  val message: String?,
  val progressPercent: Double?,
  val queueSize: Int,
  val privateModeEnabled: Boolean,
  val audioModeEnabled: Boolean = false,
  val pinned: Boolean = false,
  /**
   * How many downloads are running. A foreground service may post only one notification,
   * so when this is above one the notification reports the set — the count in the title
   * and their combined progress — rather than picking one download to represent them all.
   */
  val activeCount: Int = if (activeTaskId.isNullOrBlank()) 0 else 1,
  /** Torrents downloading, and how far along they are together. Never their names. */
  val torrentCount: Int = 0,
  val torrentPercent: Double? = null,
) {
  val hasWork: Boolean
    get() = activeCount > 0 || queueSize > 0 || torrentCount > 0

  val shouldRunForeground: Boolean
    get() = hasWork || pinned
}

internal object DownloadNotificationController {
  const val CHANNEL_ID = "arsivinyo_downloads"
  const val CHANNEL_NAME = "Background Downloads"
  const val CHANNEL_DESCRIPTION = "Shows download progress and controls"
  const val NOTIFICATION_ID = 7441

  const val ACTION_SYNC = "expo.modules.localdownloader.action.NOTIFICATION_SYNC"
  const val ACTION_STOP = "expo.modules.localdownloader.action.NOTIFICATION_STOP"
  const val REMOTE_INPUT_URL_KEY = "remote_input_url"

  private const val EXTRA_ACTIVE_TASK_ID = "extra_active_task_id"
  private const val EXTRA_PHASE = "extra_phase"
  private const val EXTRA_MESSAGE = "extra_message"
  private const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"
  private const val EXTRA_QUEUE_SIZE = "extra_queue_size"
  private const val EXTRA_PRIVATE_MODE_ENABLED = "extra_private_mode_enabled"
  private const val EXTRA_AUDIO_MODE_ENABLED = "extra_audio_mode_enabled"
  private const val EXTRA_PINNED = "extra_pinned"
  private const val EXTRA_ACTIVE_COUNT = "extra_active_count"
  private const val EXTRA_TORRENT_COUNT = "extra_torrent_count"
  private const val EXTRA_TORRENT_PERCENT = "extra_torrent_percent"

  fun startOrUpdate(context: Context, state: BackgroundNotificationState) {
    ensureChannel(context)
    val intent = Intent(context, DownloadForegroundService::class.java).apply {
      action = ACTION_SYNC
      putExtra(EXTRA_ACTIVE_TASK_ID, state.activeTaskId)
      putExtra(EXTRA_PHASE, state.phase)
      putExtra(EXTRA_MESSAGE, state.message)
      putExtra(EXTRA_PROGRESS_PERCENT, state.progressPercent)
      putExtra(EXTRA_QUEUE_SIZE, state.queueSize)
      putExtra(EXTRA_PRIVATE_MODE_ENABLED, state.privateModeEnabled)
      putExtra(EXTRA_AUDIO_MODE_ENABLED, state.audioModeEnabled)
      putExtra(EXTRA_PINNED, state.pinned)
      putExtra(EXTRA_ACTIVE_COUNT, state.activeCount)
      putExtra(EXTRA_TORRENT_COUNT, state.torrentCount)
      state.torrentPercent?.let { putExtra(EXTRA_TORRENT_PERCENT, it) }
    }
    ContextCompat.startForegroundService(context, intent)
  }

  fun stop(context: Context) {
    val intent = Intent(context, DownloadForegroundService::class.java).apply {
      action = ACTION_STOP
    }
    context.startService(intent)
  }

  fun parseState(intent: Intent?): BackgroundNotificationState {
    return BackgroundNotificationState(
      activeTaskId = intent?.getStringExtra(EXTRA_ACTIVE_TASK_ID),
      phase = intent?.getStringExtra(EXTRA_PHASE).orEmpty().ifBlank { "idle" },
      message = intent?.getStringExtra(EXTRA_MESSAGE),
      progressPercent = intent?.getDoubleExtra(EXTRA_PROGRESS_PERCENT, Double.NaN)?.takeIf { !it.isNaN() },
      queueSize = intent?.getIntExtra(EXTRA_QUEUE_SIZE, 0) ?: 0,
      privateModeEnabled = intent?.getBooleanExtra(EXTRA_PRIVATE_MODE_ENABLED, false) ?: false,
      audioModeEnabled = intent?.getBooleanExtra(EXTRA_AUDIO_MODE_ENABLED, false) ?: false,
      pinned = intent?.getBooleanExtra(EXTRA_PINNED, false) ?: false,
      activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 0) ?: 0,
      torrentCount = intent?.getIntExtra(EXTRA_TORRENT_COUNT, 0) ?: 0,
      torrentPercent = intent?.getDoubleExtra(EXTRA_TORRENT_PERCENT, Double.NaN)?.takeIf { !it.isNaN() },
    )
  }

  fun buildNotification(context: Context, state: BackgroundNotificationState): Notification {
    ensureChannel(context)

    // Torrents alone: "Downloading 2 torrents" and their progress, never a name (CONTRACT.md).
    // With other downloads running, those lead and torrents go on underneath.
    val torrentsOnly = state.activeCount == 0 && state.queueSize == 0 && state.torrentCount > 0
    val hasActiveTask = state.activeCount > 0 || torrentsOnly
    val title = when {
      torrentsOnly -> if (state.torrentCount == 1) context.getString(R.string.ldl_title_torrent)
        else context.getString(R.string.ldl_title_torrents, state.torrentCount)
      // Several at once gets its own title: "Downloading" beside a bar that is the mean
      // of three unrelated downloads would read as one download stalling and jumping.
      state.activeCount > 1 -> context.getString(R.string.ldl_title_downloading_many, state.activeCount)
      hasActiveTask -> context.getString(R.string.ldl_title_downloading)
      state.queueSize > 0 -> context.getString(R.string.ldl_title_queued)
      else -> context.getString(R.string.ldl_title_idle)
    }
    // Prefer the localized phase-based subtitle for known phases so the line stays
    // localized even when callers pass an English progress message; fall back to the
    // passed message for custom/idle states.
    val phaseSubtitle = if (torrentsOnly) context.getString(R.string.ldl_sub_downloading) else when (state.phase) {
      "starting" -> context.getString(R.string.ldl_sub_starting)
      "downloading" -> context.getString(R.string.ldl_sub_downloading)
      "processing" -> context.getString(R.string.ldl_sub_processing)
      "saving" -> context.getString(R.string.ldl_sub_saving)
      "completed" -> context.getString(R.string.ldl_sub_completed)
      "error" -> context.getString(R.string.ldl_sub_error)
      // A backup deliberately has its own subtitle rather than falling through to
      // state.message. The message is the item being handled, and this app holds a private
      // vault — a filename on the lock screen defeats the point of it being private.
      "exporting" -> context.getString(R.string.ldl_sub_exporting)
      "restoring" -> context.getString(R.string.ldl_sub_restoring)
      else -> null
    }
    val subtitle = phaseSubtitle
      ?: state.message?.takeIf { it.isNotBlank() }
      ?: if (state.queueSize > 0) context.getString(R.string.ldl_sub_queue_tap) else context.getString(R.string.ldl_sub_download_tap)
    // How many are waiting, with no "of N": the limit is a runaway guard set far above
    // anything reachable by hand, so counting against it told the user nothing.
    val queueLabel = context.getString(R.string.ldl_queue_label, state.queueSize)
    val modeLabel = if (state.privateModeEnabled) context.getString(R.string.ldl_mode_private) else context.getString(R.string.ldl_mode_public)
    val privateLabel = if (state.privateModeEnabled) context.getString(R.string.ldl_private_on) else context.getString(R.string.ldl_private_off)
    val audioLabel = if (state.audioModeEnabled) context.getString(R.string.ldl_audio_on) else context.getString(R.string.ldl_audio_off)

    val progress = (if (torrentsOnly) state.torrentPercent else state.progressPercent)?.toInt()?.coerceIn(0, 100)
    val showIndeterminate = hasActiveTask && (state.phase == "starting" || state.phase == "processing" || progress == null)
    val progressText = when {
      showIndeterminate -> "..."
      hasActiveTask -> "${progress ?: 0}%"
      state.queueSize > 0 -> "queued"
      else -> "idle"
    }

    val collapsed = RemoteViews(context.packageName, R.layout.local_downloader_notification_collapsed).apply {
      setTextViewText(R.id.notification_title, title)
      setTextViewText(R.id.notification_subtitle, subtitle)
      setTextViewText(R.id.notification_queue, queueLabel)
      setTextViewText(R.id.notification_mode, modeLabel)
      if (showIndeterminate) {
        setProgressBar(R.id.notification_progress, 100, 0, true)
        setTextViewText(R.id.notification_progress_text, progressText)
      } else {
        setProgressBar(R.id.notification_progress, 100, progress ?: 0, false)
        setTextViewText(R.id.notification_progress_text, progressText)
      }
      setOnClickPendingIntent(R.id.notification_action_quick, buildQuickCapturePendingIntent(context, 40))
      setOnClickPendingIntent(R.id.notification_action_audio, buildActionPendingIntent(context, DownloadActionReceiver.ACTION_TOGGLE_AUDIO_MODE, 47))
      setTextViewText(R.id.notification_action_audio, audioLabel)
      setOnClickPendingIntent(R.id.notification_action_private, buildActionPendingIntent(context, DownloadActionReceiver.ACTION_TOGGLE_PRIVATE_MODE, 45))
      setTextViewText(R.id.notification_action_private, privateLabel)
    }

    val expanded = RemoteViews(context.packageName, R.layout.local_downloader_notification_expanded).apply {
      setTextViewText(R.id.notification_title, title)
      setTextViewText(R.id.notification_subtitle, subtitle)
      setTextViewText(R.id.notification_queue, queueLabel)
      setTextViewText(R.id.notification_mode, modeLabel)
      if (showIndeterminate) {
        setProgressBar(R.id.notification_progress, 100, 0, true)
        setTextViewText(R.id.notification_progress_text, progressText)
      } else {
        setProgressBar(R.id.notification_progress, 100, progress ?: 0, false)
        setTextViewText(R.id.notification_progress_text, progressText)
      }
      // Cancel is the downloader's; torrents are paused and removed from their own screen.
      if (state.activeCount > 0) {
        setViewVisibility(R.id.notification_action_cancel, android.view.View.VISIBLE)
        setOnClickPendingIntent(R.id.notification_action_cancel, buildActionPendingIntent(context, DownloadActionReceiver.ACTION_CANCEL_ACTIVE, 41))
      } else {
        setViewVisibility(R.id.notification_action_cancel, android.view.View.GONE)
      }
      setOnClickPendingIntent(R.id.notification_action_quick, buildQuickCapturePendingIntent(context, 42))
      setOnClickPendingIntent(R.id.notification_action_audio, buildActionPendingIntent(context, DownloadActionReceiver.ACTION_TOGGLE_AUDIO_MODE, 48))
      setTextViewText(R.id.notification_action_audio, audioLabel)
      setOnClickPendingIntent(R.id.notification_action_private, buildActionPendingIntent(context, DownloadActionReceiver.ACTION_TOGGLE_PRIVATE_MODE, 46))
      setTextViewText(R.id.notification_action_private, privateLabel)
    }

    val addUrlRemoteInput = RemoteInput.Builder(REMOTE_INPUT_URL_KEY)
      .setLabel(context.getString(R.string.ldl_action_paste_url))
      .build()
    val addUrlAction = NotificationCompat.Action.Builder(
      android.R.drawable.ic_input_add,
      context.getString(R.string.ldl_action_add_url),
      buildMutableActionPendingIntent(context, DownloadActionReceiver.ACTION_ADD_URL_REMOTE_INPUT, 44)
    )
      .addRemoteInput(addUrlRemoteInput)
      .setAllowGeneratedReplies(false)
      .build()

    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(context.applicationInfo.icon)
      .setContentTitle(title)
      .setContentText(subtitle)
      .setOngoing(state.shouldRunForeground)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
      .setCustomContentView(collapsed)
      .setCustomBigContentView(expanded)
      .setContentIntent(buildQuickCapturePendingIntent(context, 43))
      .addAction(addUrlAction)
      .setAutoCancel(false)
      .build()
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    val existing = manager.getNotificationChannel(CHANNEL_ID)
    if (existing != null) {
      return
    }

    val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.ldl_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
      description = context.getString(R.string.ldl_channel_description)
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
    manager.createNotificationChannel(channel)
  }

  private fun buildQuickCapturePendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = Intent(context, QuickDownloadCaptureActivity::class.java).apply {
      putExtra(QuickDownloadCaptureActivity.EXTRA_AUTOSTART, true)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    return PendingIntent.getActivity(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun buildActionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
    val actionIntent = Intent(context, DownloadActionReceiver::class.java).apply {
      this.action = action
      setPackage(context.packageName)
    }

    return PendingIntent.getBroadcast(
      context,
      requestCode,
      actionIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun buildMutableActionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
    val actionIntent = Intent(context, DownloadActionReceiver::class.java).apply {
      this.action = action
      setPackage(context.packageName)
    }

    val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(
      context,
      requestCode,
      actionIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag
    )
  }
}
