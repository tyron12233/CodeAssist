package dev.ide.android.daemon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import dev.ide.platform.log.Log

/**
 * UI-process client for [BuildDaemonService]. Binds the `:build` daemon, registers a stream-back callback,
 * and — the load-bearing part for build-process isolation — links an [IBinder.DeathRecipient] so that when
 * the daemon dies (e.g. a build OOM) the UI is NOTIFIED and keeps running instead of crashing with it. The
 * callback deltas are surfaced through the [onOpened]/[onStatus]/[onStep]/[onLog] hooks, invoked on Binder
 * threads. In Phase 3b this becomes the core of `RemoteBuildRunner` (reassembling a `StateFlow<BuildState>`);
 * for now it also drives the Phase-3a proof ([BuildDaemonProof]).
 */
class BuildDaemonClient(
    context: Context,
    private val onOpened: (ok: Boolean, error: String?) -> Unit = { _, _ -> },
    private val onStatus: (status: String, moduleName: String, elapsedMs: Long) -> Unit = { _, _, _ -> },
    private val onStep: (name: String, status: String) -> Unit = { _, _ -> },
    private val onLog: (message: String) -> Unit = {},
    private val onDiagnostic: (severity: String, message: String, kind: String, source: String, file: String?, line: Int, column: Int, detail: String?, task: String?) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    private val onRunConsole: (runId: Int, moduleName: String, mainClass: String, phase: Int, acceptsInput: Boolean, hasExit: Boolean, exitCode: Int) -> Unit = { _, _, _, _, _, _, _ -> },
    private val onConsoleChunk: (runId: Int, text: String, kind: Int) -> Unit = { _, _, _ -> },
    private val onPermission: (reqId: Int, category: String, detail: String) -> Unit = { _, _, _ -> },
    /** The daemon installed an android-app APK; launch it here in the UI process (foreground-activity rules). */
    private val onLaunchPackage: (packageName: String) -> Unit = {},
    /** Fires on EVERY (re)connect — including the auto-restart after the daemon dies — so a client can
     *  re-drive in-flight work. (Distinct from [bind]'s one-shot `onReady`, which fires only the first time.) */
    private val onConnected: () -> Unit = {},
    private val onDeath: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val log = Log.logger("ide.daemon")

    @Volatile
    private var daemon: IBuildDaemon? = null
    private var onReady: ((IBuildDaemon) -> Unit)? = null

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            log.warn("ui(pid=${Process.myPid()}): daemon died (binderDied) — IDE SURVIVED.")
            daemon = null
            onDeath()
        }
    }

    private val callback = object : IBuildCallback.Stub() {
        override fun onOpened(ok: Boolean, error: String?) = onOpened.invoke(ok, error)
        override fun onStatus(status: String?, moduleName: String?, elapsedMs: Long) =
            onStatus.invoke(status ?: "", moduleName ?: "", elapsedMs)
        override fun onStep(name: String?, status: String?) = onStep.invoke(name ?: "", status ?: "")
        override fun onLog(message: String?) = onLog.invoke(message ?: "")
        override fun onDiagnostic(severity: String?, message: String?, kind: String?, source: String?, file: String?, line: Int, column: Int, detail: String?, task: String?) =
            onDiagnostic.invoke(severity ?: "", message ?: "", kind ?: "", source ?: "", file, line, column, detail, task)
        override fun onRunConsole(runId: Int, moduleName: String?, mainClass: String?, phase: Int, acceptsInput: Boolean, hasExit: Boolean, exitCode: Int) =
            onRunConsole.invoke(runId, moduleName ?: "", mainClass ?: "", phase, acceptsInput, hasExit, exitCode)
        override fun onConsoleChunk(runId: Int, text: String?, kind: Int) = onConsoleChunk.invoke(runId, text ?: "", kind)
        override fun onPermission(reqId: Int, category: String?, detail: String?) = onPermission.invoke(reqId, category ?: "", detail ?: "")
        override fun onLaunchPackage(packageName: String?) = onLaunchPackage.invoke(packageName ?: "")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val d = IBuildDaemon.Stub.asInterface(service)
            daemon = d
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            runCatching { d.registerCallback(callback) }
            val daemonPid = runCatching { d.pid() }.getOrDefault(-1)
            log.info(
                "ui(pid=${Process.myPid()}): connected to daemon(pid=$daemonPid) — " +
                    "separate process = ${daemonPid != Process.myPid()}",
            )
            onReady?.invoke(d)
            onReady = null
            onConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log.warn("ui(pid=${Process.myPid()}): daemon disconnected")
            daemon = null
        }
    }

    /** Bind the daemon; [onReady] runs once connected, with the live [IBuildDaemon]. */
    fun bind(onReady: (IBuildDaemon) -> Unit) {
        this.onReady = onReady
        val ok = appContext.bindService(
            Intent(appContext, BuildDaemonService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        log.info("ui(pid=${Process.myPid()}): bindService = $ok")
    }

    fun open(workspaceDir: String, modelGeneration: Int) = runCatching { daemon?.open(workspaceDir, modelGeneration) }
    fun runTasks(): List<String> = runCatching { daemon?.runTasks()?.toList() }.getOrNull().orEmpty()
    fun runTask(id: String) = runCatching { daemon?.runTask(id) }
    fun runBuild() = runCatching { daemon?.runBuild() }
    fun stopBuild() = runCatching { daemon?.stopBuild() }
    fun sendRunInput(text: String) = runCatching { daemon?.sendRunInput(text) }
    fun closeRunInput() = runCatching { daemon?.closeRunInput() }
    fun answerPermission(id: Int, decision: Int) = runCatching { daemon?.answerPermission(id, decision) }
    fun unbind() = runCatching { appContext.unbindService(connection) }
}
