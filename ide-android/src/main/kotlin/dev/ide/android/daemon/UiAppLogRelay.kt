package dev.ide.android.daemon

/**
 * Process-local hand-off that lets the UI-process app-log sink deliver frames to the build daemon's channel.
 *
 * The exported `AppLogSinkService` the built debug app binds to has no `android:process`, so it always runs
 * in the UI (main) process. But under build-process isolation (docs/build-process-isolation.md) the run — and
 * so the `AppLogChannel` that was `start()`ed for it — lives in the separate `:build` daemon. The sink's
 * `AppLogSinkRegistry.active` (a per-process singleton) is therefore null in the UI process, and frames were
 * being dropped. This bridge forwards them across: when the daemon is bound, its `IBuildDaemon` is registered
 * here (by [BuildDaemonClient], which runs in the UI process alongside the sink), and the sink relays each
 * batch to the daemon over `submitAppLogFrames`, where the daemon feeds its own `AppLogSinkRegistry.active`.
 *
 * When isolation is OFF no daemon is bound, [deliverFrames] returns false, and the sink falls back to the
 * local UI-process channel (which the in-process runner started) — the path that already worked.
 */
object UiAppLogRelay {
    @Volatile
    private var daemon: IBuildDaemon? = null

    /** Registered by [BuildDaemonClient] on connect (with the live daemon), cleared (null) on death/unbind. */
    fun setDaemon(d: IBuildDaemon?) {
        daemon = d
    }

    /** Forward [frames] to the daemon's channel. Returns true if handed off (the caller must NOT also feed the
     *  local channel); false when no daemon is bound (isolation off) or this call failed. A failure fails only
     *  this batch — the binding is cleared by [BuildDaemonClient]'s death recipient, not here, so a transient
     *  error (e.g. a momentarily full oneway buffer) doesn't permanently disable relaying to a live daemon. */
    fun deliverFrames(frames: List<String>): Boolean {
        val d = daemon ?: return false
        return runCatching { d.submitAppLogFrames(frames.toTypedArray()); true }.getOrDefault(false)
    }

    /** Forward a client-gone (built app unbound) signal to the daemon's channel; same return contract. */
    fun deliverClientGone(): Boolean {
        val d = daemon ?: return false
        return runCatching { d.appLogClientGone(); true }.getOrDefault(false)
    }
}
