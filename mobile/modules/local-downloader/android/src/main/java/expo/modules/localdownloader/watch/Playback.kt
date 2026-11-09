package expo.modules.localdownloader.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import expo.modules.localdownloader.R

/**
 * The Watch player out of sight: the screen off, another app in front, or the small window of
 * picture-in-picture. It is a media session, so the lock screen, a headset and the small
 * window can pause and seek; the notification that goes with one; the foreground service
 * without which Android stops an app it cannot see; and the audio focus, so a call or another
 * player pauses the video. One player at a time: the last that began a video.
 *
 * Vault videos never come here. Out of sight they pause, and nothing names them.
 *
 * The main thread only.
 */
internal object Playback {
  data class State(
    val title: String,
    val subtitle: String?,
    val paused: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
  )

  const val SEEK_MS = 10_000.0
  private const val CHANNEL = "watch_playback"
  const val NOTIFICATION_ID = 4107
  const val ACTION_TOGGLE = "expo.modules.localdownloader.watch.TOGGLE"
  const val ACTION_BACK = "expo.modules.localdownloader.watch.BACK"
  const val ACTION_FORWARD = "expo.modules.localdownloader.watch.FORWARD"

  var current: MpvPlayerView? = null
    private set
  private var state: State? = null
  private var session: MediaSession? = null
  private var hasFocus = false
  /** Paused by a call or a moment of another app's sound, to carry on when it is over. */
  private var resumeOnFocus = false
  private var listening = false

  /** [player] began a video: from here it is the one the session speaks for. */
  fun begin(player: MpvPlayerView, state: State) {
    val context = player.context.applicationContext
    current?.takeIf { it !== player }?.let { end(it) }
    current = player
    if (session == null) {
      session = MediaSession(context, "ArsivinyoWatch").apply {
        setCallback(controls)
        isActive = true
      }
    }
    if (!listening) {
      ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
      listening = true
    }
    update(player, state)
    // Started while the player is on screen, which is when Android allows it; it then holds
    // for as long as the video is open.
    if (PlaybackService.instance == null) {
      runCatching { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)) }
    }
  }

  fun update(player: MpvPlayerView, state: State) {
    if (current !== player) return
    val before = this.state
    this.state = state
    val session = session ?: return
    if (before?.title != state.title || before?.subtitle != state.subtitle || before?.durationMs != state.durationMs) {
      session.setMetadata(
        MediaMetadata.Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
          .putString(MediaMetadata.METADATA_KEY_ARTIST, state.subtitle)
          .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
          .build(),
      )
    }
    session.setPlaybackState(
      PlaybackState.Builder()
        .setActions(
          PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD,
        )
        .setState(
          if (state.paused) PlaybackState.STATE_PAUSED else if (state.buffering) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PLAYING,
          state.positionMs,
          if (state.paused || state.buffering) 0f else state.speed,
          SystemClock.elapsedRealtime(),
        )
        .build(),
    )
    if (!state.paused) focus(player.context)
    if (before?.paused != state.paused || before?.title != state.title || before?.subtitle != state.subtitle) {
      PlaybackService.instance?.let { service ->
        runCatching { service.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(service)) }
      }
    }
  }

  /** [player] is gone, or its video closed. */
  fun end(player: MpvPlayerView) {
    if (current !== player) return
    val context = player.context.applicationContext
    current = null
    state = null
    resumeOnFocus = false
    session?.run {
      isActive = false
      release()
    }
    session = null
    if (hasFocus) {
      context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focusRequest)
      hasFocus = false
    }
    if (listening) {
      runCatching { context.unregisterReceiver(noisy) }
      listening = false
    }
    // One still on its way up finds no player in onStartCommand, and stops itself there.
    PlaybackService.instance?.finish()
  }

  // ---- the session's controls ------------------------------------------------------------------

  private val controls = object : MediaSession.Callback() {
    override fun onPlay() { current?.setPaused(false) }
    override fun onPause() { current?.setPaused(true) }
    override fun onStop() { current?.setPaused(true) }
    override fun onSeekTo(pos: Long) { current?.seek(pos.toDouble()) }
    override fun onRewind() { current?.seekBy(-SEEK_MS) }
    override fun onFastForward() { current?.seekBy(SEEK_MS) }
  }

  // ---- audio focus -----------------------------------------------------------------------------

  private val focusRequest: AudioFocusRequest by lazy {
    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
      )
      .setOnAudioFocusChangeListener({ change ->
        when (change) {
          AudioManager.AUDIOFOCUS_LOSS -> {
            hasFocus = false
            resumeOnFocus = false
            current?.setPaused(true)
          }
          AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
            resumeOnFocus = state?.paused == false
            current?.setPaused(true)
          }
          AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
            resumeOnFocus = false
            current?.setPaused(false)
          }
        }
      }, Handler(Looper.getMainLooper()))
      .build()
  }

  private fun focus(context: Context) {
    if (hasFocus) return
    val granted = context.getSystemService(AudioManager::class.java).requestAudioFocus(focusRequest)
    hasFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  /** Headphones pulled out: the sound would carry on from the speaker. */
  private val noisy = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      current?.setPaused(true)
    }
  }

  // ---- the notification ------------------------------------------------------------------------

  fun notification(context: Context): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL, context.getString(R.string.ldl_playback_channel), NotificationManager.IMPORTANCE_LOW).apply {
          description = context.getString(R.string.ldl_playback_channel_description)
          setShowBadge(false)
        },
      )
    }
    val state = state
    val builder = Notification.Builder(context, CHANNEL)
      .setSmallIcon(context.applicationInfo.icon)
      .setContentTitle(state?.title?.ifBlank { null } ?: context.getString(R.string.ldl_playback_channel))
      .setContentText(state?.subtitle)
      .setShowWhen(false)
      .setOngoing(state?.paused == false)
      .setCategory(Notification.CATEGORY_TRANSPORT)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
      builder.setContentIntent(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
    val session = session
    if (state != null && session != null) {
      builder.addAction(action(context, android.R.drawable.ic_media_rew, R.string.ldl_playback_back, ACTION_BACK))
      builder.addAction(
        if (state.paused) action(context, android.R.drawable.ic_media_play, R.string.ldl_playback_play, ACTION_TOGGLE)
        else action(context, android.R.drawable.ic_media_pause, R.string.ldl_playback_pause, ACTION_TOGGLE),
      )
      builder.addAction(action(context, android.R.drawable.ic_media_ff, R.string.ldl_playback_forward, ACTION_FORWARD))
      builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
    }
    return builder.build()
  }

  private fun action(context: Context, icon: Int, label: Int, action: String): Notification.Action {
    val intent = Intent(context, PlaybackService::class.java).setAction(action)
    val pending = PendingIntent.getService(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), pending).build()
  }
}

/** What keeps the app alive while [Playback] plays out of sight. It holds nothing itself. */
class PlaybackService : Service() {
  override fun onCreate() {
    super.onCreate()
    instance = this
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Always, and first: a service started as a foreground one has to become one, even when
    // all that is left for it is to stop.
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
    runCatching { ServiceCompat.startForeground(this, Playback.NOTIFICATION_ID, Playback.notification(this), type) }
    val player = Playback.current
    if (player == null) {
      finish()
      return START_NOT_STICKY
    }
    when (intent?.action) {
      Playback.ACTION_TOGGLE -> player.togglePaused()
      Playback.ACTION_BACK -> player.seekBy(-Playback.SEEK_MS)
      Playback.ACTION_FORWARD -> player.seekBy(Playback.SEEK_MS)
    }
    return START_NOT_STICKY
  }

  /** The app swiped away from the recents: nothing plays on with no way back to it. */
  override fun onTaskRemoved(rootIntent: Intent?) {
    Playback.current?.let {
      it.setPaused(true)
      Playback.end(it)
    }
    finish()
  }

  fun finish() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    if (instance === this) instance = null
    super.onDestroy()
  }

  companion object {
    var instance: PlaybackService? = null
      private set
  }
}
