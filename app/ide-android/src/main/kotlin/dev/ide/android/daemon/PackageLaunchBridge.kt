package dev.ide.android.daemon

/**
 * Process-local hand-off so the android "Run" launch happens in the UI process, not in `:build`.
 *
 * Under build-process isolation (docs/build-process-isolation.md) the engine — and so the ApkInstaller
 * that installs the built APK — runs in the `:build` process, which has no foreground activity. Firing the
 * installed app's activity from there is blocked by Android's background-activity-launch rules. Instead the
 * installer asks this bridge to [forwardLaunch]; [BuildDaemonService] (same `:build` process) registers a
 * forwarder that ships the request to the UI over `IBuildCallback.onLaunchPackage`, where a visible activity
 * makes the launch legal.
 *
 * When isolation is OFF the engine runs in the UI process, no daemon exists, no forwarder is registered, and
 * [forwardLaunch] returns false — the installer then launches locally (which is fine; it's already in the UI).
 */
object PackageLaunchBridge {
    @Volatile
    private var forwarder: ((String) -> Boolean)? = null

    /** Registered by [BuildDaemonService] while it holds a live UI callback; cleared (null) when it doesn't. */
    fun setForwarder(f: ((String) -> Boolean)?) {
        forwarder = f
    }

    /** Try to forward the launch of [packageName] to the UI process. Returns true if it was handed off (the
     *  caller must NOT also launch locally); false if there's no UI to forward to. */
    fun forwardLaunch(packageName: String): Boolean = forwarder?.invoke(packageName) ?: false
}
