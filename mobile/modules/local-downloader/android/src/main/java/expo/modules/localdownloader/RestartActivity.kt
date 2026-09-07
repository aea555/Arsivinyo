package expo.modules.localdownloader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Relaunches the app after killing it.
 *
 * The obvious approach — start the launcher activity, then exit — does not work: the
 * process dies before the system has finished bringing the activity up, so the app simply
 * closes. Android also refuses activity starts from a process that is not in the
 * foreground, and by then this one is gone.
 *
 * So the killing is done from somewhere that is not being killed. This activity runs in its
 * own process (`android:process=":restart"`), so it is still alive and foreground after the
 * main one is gone, and can start the launcher normally.
 *
 * The whole point is applying a downloaded yt-dlp: the bootstrap picks it up when Python
 * next starts, and Python starts with the process.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) {
            Process.killProcess(pid)
        }

        // A moment for the system to notice the process is gone, so the launch below
        // starts a new one rather than being folded into the dying task.
        Handler(Looper.getMainLooper()).postDelayed({
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch == null) {
                Log.w(TAG, "no launch intent; the app cannot be restarted")
            } else {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(launch)
            }
            finish()
            // This helper process has nothing left to do, and leaving it running would
            // keep a second process alive for the life of the app.
            Handler(Looper.getMainLooper()).postDelayed({
                Process.killProcess(Process.myPid())
            }, TEARDOWN_DELAY_MS)
        }, RELAUNCH_DELAY_MS)
    }

    companion object {
        private const val TAG = "RestartActivity"
        const val EXTRA_PID = "expo.modules.localdownloader.RESTART_PID"

        /** Long enough for the killed process to be reaped before the new one starts. */
        private const val RELAUNCH_DELAY_MS = 300L
        private const val TEARDOWN_DELAY_MS = 500L
    }
}
