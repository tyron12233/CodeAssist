package dev.ide.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Notices the main thread being stuck and says whose code it was stuck in.
 *
 * A daemon thread posts a no-op to the main looper every [TICK_MS] and waits for it to run. When it has not run
 * after [STALL_MS], the main thread is blocked: its stack is sampled right then (the work in progress is what
 * holds it), and once the no-op finally runs, the stall's real length goes to [report] with that sample.
 *
 * Why it exists: the system's exit records ([dev.ide.platform.AnrTrace]) only describe ANRs that ended the
 * process, while Play counts every one the user waited out, so most of what Play reported had no stack here at
 * all. And an installed plugin runs on this main thread too, so a plugin that blocks it makes the IDE look
 * broken; the sample is what lets the host name the plugin (see `IdeServicesBackend.reportMainStall`).
 *
 * Only ticks while one of this app's activities is started: a backgrounded app is not answering input, and
 * waking it twice a second for nothing would cost battery. Not while a debugger is attached either, since a
 * breakpoint on the main thread is a stall by design.
 */
internal class MainThreadWatchdog(
    application: Application,
    private val report: (frames: List<StackTraceElement>, durationMs: Long, uptimeMs: Long, foreground: Boolean) -> Unit,
    /**
     * When set, a stall still going after this long calls [onStop] with a fresh sample instead of being waited
     * out. The plugin sandbox uses it to end itself before Android's 5 s input timeout makes it an ANR that Play
     * would count against the app. [onStop] is not expected to return.
     */
    private val stopAfterMs: Long? = null,
    private val onStop: (frames: List<StackTraceElement>, stalledMs: Long) -> Unit = { _, _ -> },
) {
    private val main = Handler(Looper.getMainLooper())
    private val mainThread = Looper.getMainLooper().thread
    private val lock = Object()

    @Volatile private var started = 0
    @Volatile private var acked = true
    @Volatile private var ackedAt = 0L
    private val ack = Runnable {
        ackedAt = SystemClock.uptimeMillis()
        acked = true
    }

    init {
        // Started after the first activity already is (bootstrap runs once MainActivity is up), so the callbacks
        // below would never see that start: seed the count from the process's importance right now.
        started = runCatching {
            val info = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(info)
            if (info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) 1 else 0
        }.getOrDefault(1)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                synchronized(lock) { lock.notifyAll() }
            }
            override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun start() {
        android.util.Log.i(TAG, "watching the main thread (activities started: $started)")
        Thread(::loop, "main-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }.start()
    }

    private fun loop() {
        var reports = 0
        // When this watch (re)started: a stall is never timed from before it. The main thread's last answer can
        // be from before the app went to the background, and timing from there turned the first slow frame after
        // coming back into a "stall" as long as the whole time away (77 s, and a sandbox stopped for it).
        var watchingSince = SystemClock.uptimeMillis()
        while (reports < MAX_REPORTS_PER_PROCESS) {
            try {
                if (started == 0) {
                    // Parked until an activity starts: no ticks for an app in the background.
                    synchronized(lock) { if (started == 0) lock.wait(IDLE_RECHECK_MS) }
                    watchingSince = SystemClock.uptimeMillis()
                    continue
                }
                // The last moment the main thread was seen answering. A stall started after it, so timing from
                // here can read up to one tick long but never short: a threshold met by this measure is met.
                val aliveAt = maxOf(ackedAt, watchingSince)
                acked = false
                main.post(ack)
                if (!pause(TICK_MS)) {
                    watchingSince = SystemClock.uptimeMillis()
                    continue
                }
                if (acked) continue
                // Not answered yet: wait out the threshold, then sample while it is still stuck.
                var paused = false
                while (!acked && SystemClock.uptimeMillis() - aliveAt < STALL_MS) {
                    if (!pause(POLL_MS)) {
                        paused = true
                        break
                    }
                }
                if (paused) {
                    watchingSince = SystemClock.uptimeMillis()
                    continue
                }
                if (acked || Debug.isDebuggerConnected()) continue
                val frames = mainThread.stackTrace.toList()
                val foreground = started > 0
                // A stall that never ends is an ANR the system reports itself (AnrTrace, next launch), unless
                // this watchdog was told to stop the process first.
                while (!acked) {
                    val stalled = SystemClock.uptimeMillis() - aliveAt
                    if (stopAfterMs != null && stalled >= stopAfterMs && started > 0) {
                        onStop(mainThread.stackTrace.toList(), stalled)
                    }
                    if (!pause(POLL_MS)) {
                        paused = true
                        break
                    }
                }
                if (paused) {
                    // The process itself was stopped part-way (frozen, or a debugger): how long the main thread
                    // was really blocked cannot be told apart from that, so this one goes unreported.
                    watchingSince = SystemClock.uptimeMillis()
                    continue
                }
                val duration = ackedAt - aliveAt
                reports++
                android.util.Log.w(TAG, "main thread was blocked for about $duration ms at ${frames.firstOrNull()}")
                runCatching { report(frames, duration, uptime(), foreground) }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // Never let the watchdog itself be what fails.
            }
        }
    }

    /**
     * Sleep [ms] and say whether this thread really was only away that long. False when it came back much later:
     * the whole process was not running (Android froze it, or a debugger suspended it), which is not the main
     * thread being blocked, and every clock reading spanning it is meaningless as a stall.
     */
    private fun pause(ms: Long): Boolean {
        val before = SystemClock.uptimeMillis()
        Thread.sleep(ms)
        return SystemClock.uptimeMillis() - before < ms + PROCESS_PAUSE_MS
    }

    private fun uptime(): Long = SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()

    private companion object {
        const val TAG = "MainThreadWatchdog"
        /**
         * A stall is timed from the last tick the main thread answered, at most one tick before it stopped, so
         * this is also the measuring error (always long, never short).
         */
        const val TICK_MS = 500L
        const val POLL_MS = 100L
        /** Input dispatch ANRs at 5 s; 2.5 s catches the stalls that come close, and every one that gets there. */
        const val STALL_MS = 2_500L
        const val IDLE_RECHECK_MS = 30_000L
        /** Oversleeping a short sleep by this much means the process was not running, not that it was busy: a
         *  busy device delays a minimum-priority thread by tens of milliseconds, not seconds. */
        const val PROCESS_PAUSE_MS = 1_500L
        /** A device stuck in a stall storm should not flood the analytics queue. */
        const val MAX_REPORTS_PER_PROCESS = 20
    }
}
