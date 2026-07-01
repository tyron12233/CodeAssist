package dev.ide.android.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import dev.ide.android.AndroidIde
import dev.ide.core.IdeServices
import dev.ide.core.ProjectManager
import dev.ide.platform.log.Log
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.UiPermissionDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The headless build/run daemon for build-process isolation (docs/build-process-isolation.md). Runs in the
 * `:build` OS process (`android:process=":build"` in the manifest) so the build's heap is SEPARATE from the
 * IDE's: a build OOM kills only this process and the UI's [BuildDaemonClient] DeathRecipient observes it.
 *
 * Hosts a real engine — the SAME [ProjectManager] + [IdeServices] the UI runs, built via
 * [AndroidIde.createProjectManager] — and drives [IdeServices.buildRunner]. The UI's build state
 * ([IdeServices.buildRunner].buildState, a snapshot StateFlow) is diffed here into onStatus/onStep/onLog
 * DELTAS streamed over [IBuildCallback]; the UI reassembles them. Run-user-code already executes here (the
 * engine holds the `dexRunner` port), so the interactive console is wired too: the program's stdio
 * (runConsole), stdin, and the run-sandbox permission prompts stream over the same channel.
 *
 * While a build or program is active it promotes to a FOREGROUND service (type `specialUse`) with an ongoing
 * "Building…" notification, so the OS won't kill `:build` if the user switches away mid-build; it demotes
 * back to a plain bound service when idle. Bound the rest of the time — the foreground activity's binding
 * keeps it alive while the user is in the IDE.
 */
class BuildDaemonService : Service() {
    private val log = Log.logger("ide.daemon")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Foreground-service state: active while a build is Running OR a program is executing; serialized on the
    // main thread (start/stopForeground touch the NotificationManager). See refreshForeground().
    @Volatile
    private var buildActive = false

    @Volatile
    private var runActive = false
    private var inForeground = false

    // Latest notification content for the build vs. the interactive run, updated as each stream advances.
    // refreshForeground() shows the run's when a program is executing, else the build's. @Volatile: written
    // from the stream coroutines, read on the main thread when (re)posting the notification.
    @Volatile
    private var buildNotif: NotifContent = NotifContent.PREPARING

    @Volatile
    private var runNotif: NotifContent = NotifContent.PREPARING

    @Volatile
    private var callback: IBuildCallback? = null

    @Volatile
    private var manager: ProjectManager? = null

    @Volatile
    private var services: IdeServices? = null

    @Volatile
    private var currentDir: String? = null

    // The UI model revision the open project was loaded at. A later open() with a higher revision means the
    // on-disk module.toml changed (e.g. minifyEnabled toggled) — reload rather than reuse the stale model.
    @Volatile
    private var currentGeneration: Int = -1
    private var streamJob: Job? = null

    private val binder = object : IBuildDaemon.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun registerCallback(cb: IBuildCallback?) {
            callback = cb
        }

        override fun open(workspaceDir: String?, modelGeneration: Int) {
            val dir = workspaceDir ?: return
            // Idempotent: re-opening the already-open project is a fast no-op — never pay the engine
            // re-create (model load + index). Lets the UI safely open-then-build on every Run. BUT only when
            // the model is also unchanged: a higher [modelGeneration] means the UI committed a config edit
            // (and saved module.toml), so the cached model is stale and must be reloaded from disk.
            if (dir == currentDir && modelGeneration == currentGeneration && services != null) {
                runCatching { callback?.onOpened(true, null) }
                return
            }
            scope.launch {
                runCatching {
                    val mgr = manager ?: AndroidIde.createProjectManager(this@BuildDaemonService).also { manager = it }
                    services?.let { old -> runCatching { old.close() } }
                    // buildOnly: this is a headless build engine — skip the editor index + Kotlin warm-ups so
                    // that baseline heap is free for the dexer/R8 (the build's real ceiling).
                    mgr.open(dir, buildOnly = true).also {
                        services = it
                        currentDir = dir
                        currentGeneration = modelGeneration
                        startStreaming(it)
                    }
                }.onSuccess {
                    log.info("daemon(pid=${Process.myPid()}): opened $dir")
                    runCatching { callback?.onOpened(true, null) }
                }.onFailure { e ->
                    currentDir = null
                    log.warn("daemon(pid=${Process.myPid()}): open failed: ${e.message}", e)
                    runCatching { callback?.onOpened(false, e.message ?: e.toString()) }
                }
            }
        }

        override fun runTasks(): Array<String> {
            val svc = services ?: return emptyArray()
            return runCatching {
                svc.buildRunner.runTasks().map { "${it.id}\t${it.label}\t${it.group}" }.toTypedArray()
            }.getOrDefault(emptyArray())
        }

        override fun runTask(id: String?) {
            id ?: return
            buildActive = true; refreshForeground() // promote before work starts, so :build is protected at once
            services?.buildRunner?.runTask(id)
        }

        override fun runBuild() {
            buildActive = true; refreshForeground()
            services?.buildRunner?.runBuild()
        }

        override fun stopBuild() {
            services?.buildRunner?.stopBuild()
        }

        // --- Phase 4: interactive run. The program runs in this process; these forward stdin + the sandbox
        // answer to the engine (whose PermissionBroker.check is blocking the program thread on a queue).
        override fun sendRunInput(text: String?) {
            text ?: return
            services?.buildRunner?.sendRunInput(text)
        }

        override fun closeRunInput() {
            services?.buildRunner?.closeRunInput()
        }

        override fun answerPermission(id: Int, decision: Int) {
            val dec = UiPermissionDecision.entries.getOrNull(decision) ?: return
            services?.buildRunner?.answerPermission(id, dec)
        }
    }

    /** Collect the engine's snapshot flows and stream the changes as deltas to the registered callback:
     *  build state (steps/log/status), the interactive run console (program stdio + lifecycle), and the
     *  run-sandbox permission prompt. One job with three children so a re-open cancels them all together. */
    private fun startStreaming(svc: IdeServices) {
        streamJob?.cancel()
        streamJob = scope.launch {
            launch { streamBuildState(svc) }
            launch { streamRunConsole(svc) }
            launch { streamPermission(svc) }
        }
    }

    private suspend fun streamBuildState(svc: IdeServices) {
        var lastStatus: RunStatus? = null
        var lastLogCount = 0
        var lastDiagCount = 0
        val lastStep = HashMap<String, StepStatus>()
        svc.buildRunner.buildState.collect { bs ->
            // A new build resets the engine's log/steps/diagnostics; reset the diff baselines so we re-emit
            // them (else a second build's smaller list never exceeds the prior count and is dropped).
            if (bs.status == RunStatus.Running && lastStatus != RunStatus.Running) {
                lastLogCount = 0
                lastDiagCount = 0
                lastStep.clear()
            }
            val cb = callback
            if (bs.status != lastStatus) {
                lastStatus = bs.status
                runCatching { cb?.onStatus(bs.status.name, bs.moduleName, bs.elapsedMs) }
            }
            for (step in bs.steps) {
                if (lastStep[step.name] != step.status) {
                    lastStep[step.name] = step.status
                    runCatching { cb?.onStep(step.name, step.status.name) }
                }
            }
            if (bs.log.size > lastLogCount) {
                for (i in lastLogCount until bs.log.size) runCatching { cb?.onLog(bs.log[i].message) }
                lastLogCount = bs.log.size
            }
            if (bs.diagnostics.size > lastDiagCount) {
                for (i in lastDiagCount until bs.diagnostics.size) {
                    val d = bs.diagnostics[i]
                    runCatching { cb?.onDiagnostic(d.severity.name, d.message, d.kind, d.source, d.file, d.line, d.column, d.detail, d.task) }
                }
                lastDiagCount = bs.diagnostics.size
            }
            // Keep the ongoing notification in step with the build's progress (current step + a determinate
            // bar over the settled/total steps; indeterminate until the task graph has materialized).
            val total = bs.steps.size
            val settled = bs.steps.count {
                it.status != StepStatus.Pending && it.status != StepStatus.Running
            }
            val running = bs.steps.firstOrNull { it.status == StepStatus.Running }?.name
            val module = bs.moduleName.ifBlank { "project" }
            buildNotif = NotifContent(
                title = "Building $module",
                text = when {
                    running != null -> humanizeStep(running)
                    total > 0 -> "$settled of $total steps"
                    else -> "Preparing…"
                },
                progress = settled,
                progressMax = total,
                indeterminate = total == 0,
            )
            // Foreground while the build runs; release on a terminal status (a run task keeps it via runActive).
            when (bs.status) {
                RunStatus.Running -> buildActive = true
                RunStatus.Succeeded, RunStatus.Failed -> buildActive = false
                RunStatus.Idle -> {} // leave as-is (don't demote between runBuild and the first Running emit)
            }
            refreshForeground()
        }
    }

    private suspend fun streamRunConsole(svc: IdeServices) {
        var lastId = Int.MIN_VALUE
        // Content cursor into the transcript, NOT a chunk count: BuildService COALESCES consecutive same-kind
        // output into the trailing chunk, so its text grows in place while transcript.size stays put. A
        // size-only diff over a conflated StateFlow would sample that chunk at one arbitrary growth stage and
        // then never re-read it — silently dropping everything printed afterward (the program's whole output
        // truncated to a non-deterministic prefix). Track how far into the trailing chunk we've streamed and
        // emit only its new tail.
        var sentCount = 0 // leading chunks fully streamed
        var sentTail = 0  // chars of transcript[sentCount] already streamed (the still-growing trailing chunk)
        svc.buildRunner.runConsole.collect { rc ->
            val cb = callback
            if (rc == null) {
                lastId = Int.MIN_VALUE
                sentCount = 0; sentTail = 0
                runActive = false; refreshForeground()
                runCatching { cb?.onRunConsole(-1, "", "", 0, false, false, 0) }
                return@collect
            }
            // Stay foreground while a program is executing (so a long/interactive run survives backgrounding).
            val runModule = rc.moduleName.ifBlank { "project" }
            runNotif = when (rc.phase) {
                RunPhase.Building -> NotifContent("Building $runModule", "Preparing to run…", 0, 0, true)
                RunPhase.Running -> NotifContent(
                    title = "Running $runModule",
                    text = rc.mainClass.substringAfterLast('.').ifBlank { "Running…" },
                    progress = 0, progressMax = 0, indeterminate = true,
                )
                RunPhase.Finished -> NotifContent.PREPARING
            }
            runActive = rc.phase != RunPhase.Finished; refreshForeground()
            if (rc.id != lastId) { lastId = rc.id; sentCount = 0; sentTail = 0 }
            runCatching {
                cb?.onRunConsole(rc.id, rc.moduleName, rc.mainClass, rc.phase.ordinal, rc.acceptsInput, rc.exitCode != null, rc.exitCode ?: 0)
            }
            val t = rc.transcript
            while (sentCount < t.size) {
                val chunk = t[sentCount]
                if (chunk.text.length > sentTail) {
                    runCatching { cb?.onConsoleChunk(rc.id, chunk.text.substring(sentTail), chunk.kind.ordinal) }
                }
                if (sentCount < t.size - 1) {
                    // A later chunk exists, so this one is finalized (it can't grow anymore) — advance.
                    sentCount++; sentTail = 0
                } else {
                    // Trailing chunk: it may still grow, so remember how far we've streamed and stop here.
                    sentTail = chunk.text.length
                    break
                }
            }
        }
    }

    private suspend fun streamPermission(svc: IdeServices) {
        svc.buildRunner.permissionRequest.collect { pr ->
            val cb = callback
            runCatching { if (pr == null) cb?.onPermission(-1, "", "") else cb?.onPermission(pr.id, pr.category, pr.detail) }
        }
    }

    /** Promote to / demote from a foreground service to match build/run activity, and keep the ongoing
     *  notification's progress in step with the active stream. Serialized on the main thread (start/stop and
     *  NotificationManager.notify); reads the latest [buildActive]/[runActive] + the *Notif content. */
    private fun refreshForeground() {
        mainHandler.post {
            when {
                !(buildActive || runActive) -> exitForeground()
                !inForeground -> enterForeground()
                else -> updateNotification()
            }
        }
    }

    /** The notification to show right now: the program's while a run is executing, else the build's. */
    private fun currentContent(): NotifContent = if (runActive) runNotif else buildNotif

    private fun enterForeground() {
        if (inForeground) return
        inForeground = true
        runCatching {
            val notif = buildNotification(currentContent())
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure {
            // e.g. ForegroundServiceStartNotAllowedException — degrade gracefully: the build still runs while
            // the IDE is foreground (bound), just unprotected if backgrounded. Don't crash :build over it.
            inForeground = false
            log.warn("daemon(pid=${Process.myPid()}): startForeground failed: ${it.message}")
        }
    }

    /** Re-post the ongoing notification with the latest progress (foreground stays active). */
    private fun updateNotification() {
        if (!inForeground) return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(currentContent()))
        }
    }

    private fun exitForeground() {
        if (!inForeground) return
        inForeground = false
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
    }

    private fun buildNotification(content: NotifContent): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Builds", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while CodeAssist is building or running your project"
                },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // progress updates re-post often; never buzz/peek after the first
            .apply {
                if (content.progressMax > 0 || content.indeterminate) {
                    setProgress(content.progressMax, content.progress, content.indeterminate)
                }
                open?.let { setContentIntent(it) }
            }
            .build()
    }

    /** Map a build-task id to a short human phrase for the notification (e.g. `mergeResources` → "Merge resources"). */
    private fun humanizeStep(step: String): String {
        val words = step.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().trim()
        return words.replaceFirstChar { it.uppercaseChar() }
    }

    override fun onCreate() {
        super.onCreate()
        log.info("daemon(pid=${Process.myPid()}): service created in :build process")
        // The engine's ApkInstaller runs in THIS process; after an install it asks the bridge to launch the
        // app in the UI process (where a foreground activity makes the launch legal). Forward over the live
        // callback; returning false (no UI bound) makes the installer fall back to launching locally.
        PackageLaunchBridge.setForwarder { pkg ->
            val cb = callback ?: return@setForwarder false
            runCatching { cb.onLaunchPackage(pkg); true }.getOrDefault(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        PackageLaunchBridge.setForwarder(null)
        callback = null
        exitForeground()
        runCatching { services?.close() }
        scope.cancel()
    }

    /** Snapshot of the ongoing notification's content — title, a one-line status, and an optional progress
     *  bar ([progressMax] == 0 && !indeterminate hides the bar). */
    private data class NotifContent(
        val title: String,
        val text: String,
        val progress: Int,
        val progressMax: Int,
        val indeterminate: Boolean,
    ) {
        companion object {
            val PREPARING = NotifContent("CodeAssist", "Preparing…", 0, 0, true)
        }
    }

    private companion object {
        const val NOTIF_ID = 4711
        const val CHANNEL_ID = "build"
    }
}
