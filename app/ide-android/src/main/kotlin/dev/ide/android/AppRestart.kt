package dev.ide.android

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import dev.ide.core.AppRestarter
import dev.ide.platform.log.Log

/**
 * Restarts the app so that plugin changes take effect: the UI process, the build daemon and the preview
 * process go down, and the launcher activity comes back up.
 *
 * The request has to come from a process that is not the one being killed. Killing our own process in the
 * same breath as asking the system to start us again races the activity start against the death of the
 * process that asked for it, and the start can be dropped. So [RestartActivity], in its own `:restart`
 * process, does both halves: it is the foreground activity while it runs (which is what Android's
 * background-activity-launch rules require of whoever starts the next one), it kills the app's other
 * processes, starts [MainActivity], and exits.
 */
class AppRestart(context: Context) : AppRestarter {

    private val appContext = context.applicationContext

    override fun restart() = RestartActivity.launch(appContext)
}

/**
 * Kills the app and starts it again. Runs in the `:restart` process (declared in the manifest), so it
 * survives the death of everything it kills. It has no UI: the theme is translucent and it finishes in
 * `onCreate`.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val self = Process.myPid()
        val pids = intent?.getIntArrayExtra(EXTRA_PIDS) ?: IntArray(0)
        // Kill first, then start: a task whose process is already gone is cold-started, whereas starting
        // first can resume the activity that is about to die.
        for (pid in pids) {
            if (pid != self) runCatching { Process.killProcess(pid) }
        }
        runCatching { startActivity(mainIntent(this)) }
            .onFailure { log.warn("could not start the IDE again after killing it", it) }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_PIDS = "dev.ide.restart.pids"

        private val log = Log.logger("RestartActivity")

        fun launch(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, RestartActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_PIDS, appProcessIds(context)),
                )
            }.onFailure { log.warn("could not start the restart activity", it) }
        }

        private fun mainIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        /**
         * Every process of this app. The build daemon and the preview renderer stand up engines of their own
         * (each loading the installed plugins for itself), so leaving one running would leave the code from
         * before the change live in it. `getRunningAppProcesses` reports only the caller's own processes.
         */
        private fun appProcessIds(context: Context): IntArray {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val running = runCatching { am?.runningAppProcesses }.getOrNull().orEmpty().map { it.pid }
            return (running + Process.myPid()).distinct().toIntArray()
        }
    }
}
