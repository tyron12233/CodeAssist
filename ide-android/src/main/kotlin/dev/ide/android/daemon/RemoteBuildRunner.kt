package dev.ide.android.daemon

import android.content.Context
import dev.ide.core.BuildRunner
import dev.ide.core.IdeServices
import dev.ide.platform.log.Log
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.BuildStepUi
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.UiPermissionDecision
import dev.ide.ui.backend.UiPermissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes a project's build/run to the `:build` daemon process (build-process isolation,
 * docs/build-process-isolation.md). Implements [BuildRunner], so it drops into ide-core's BuildBackend
 * selection seam in place of the in-process runner.
 *
 * The task LIST is served LOCALLY from [services] (a cheap model query — the UI process already has the
 * model); EXECUTION (runBuild / runTask / stopBuild) goes to the daemon, whose streamed deltas are
 * reassembled here into the [StateFlow]s the console renders. A build OOM now kills only the daemon —
 * surfaced via [BuildDaemonClient]'s death recipient as a Failed state, IDE intact.
 *
 * Build AND interactive run both route here: the program runs in the daemon, and its stdio ([runConsole]),
 * stdin, and the run-sandbox [permissionRequest] prompts stream over the same channel (Phase 4).
 */
class RemoteBuildRunner(context: Context, private val services: IdeServices) : BuildRunner {
    private val log = Log.logger("ide.daemon")
    private val appContext = context.applicationContext
    private val workspaceRoot = services.workspaceRoot.toString()

    private val _buildState = MutableStateFlow(BuildState())
    private val _runConsole = MutableStateFlow<RunConsoleUi?>(null)
    private val _permissionRequest = MutableStateFlow<UiPermissionRequest?>(null)
    private val _appLog = MutableStateFlow(dev.ide.ui.backend.AppLogUi())
    override val buildState: StateFlow<BuildState> = _buildState.asStateFlow()
    override val runConsole: StateFlow<RunConsoleUi?> = _runConsole.asStateFlow()
    override val permissionRequest: StateFlow<UiPermissionRequest?> = _permissionRequest.asStateFlow()
    // App-log (Logcat tab) streaming across the :build boundary is wired in Phase 4 (onAppLog deltas +
    // reassembly here); until then this stays empty when the build daemon owns the engine.
    override val appLog: StateFlow<dev.ide.ui.backend.AppLogUi> = _appLog.asStateFlow()
    override fun clearAppLog() {
        _appLog.update { it.copy(lines = emptyList()) } // optimistic; the daemon also emits a reset
        runCatching { client.clearAppLog() }
    }

    @Volatile
    private var connected = false

    /** The build queued behind an in-flight daemon open, tagged with its open request's id so a reply to a
     *  SUPERSEDED open (a retry raced the first cold open) can't fire the newer request's action against an
     *  engine that request never opened. */
    private class PendingRun(val id: Int, val action: () -> Unit)

    @Volatile
    private var pending: PendingRun? = null
    private val pendingLock = Any()
    private val requestSeq = AtomicInteger()

    /** Atomically claim [pending] iff it belongs to [requestId] — the reply-to-request pairing. Touched from
     *  the UI thread (execute/stop), binder threads (onOpened), and the watchdog, so check-then-clear must be
     *  one step; the action itself runs outside the lock (it's a binder call). */
    private fun takePending(requestId: Int): PendingRun? = synchronized(pendingLock) {
        val p = pending
        if (p != null && p.id == requestId) {
            pending = null
            p
        } else null
    }

    /** Wall-clock of the last daemon sign of life (any delta/reply) — the watchdog's silence baseline. */
    @Volatile
    private var lastActivityMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var watchdog: Job? = null

    private fun noteActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    private val client = BuildDaemonClient(
        context,
        onOpened = { requestId, ok, err ->
            noteActivity()
            // Only the reply matching the CURRENT request may consume it; a stale reply is dropped (its
            // requester was superseded, and the newer open gets its own reply — the daemon serializes them).
            val p = takePending(requestId)
            if (p != null) {
                if (ok) {
                    p.action()
                } else {
                    watchdog?.cancel()
                    _buildState.update { it.copy(status = RunStatus.Failed, log = it.log + line("Build process couldn't open the project: $err")) }
                }
            }
        },
        onStatus = { status, module, elapsed ->
            noteActivity()
            _buildState.update {
                it.copy(status = runStatusOf(status, it.status), moduleName = module.ifEmpty { it.moduleName }, elapsedMs = elapsed)
            }
        },
        onStep = { name, status ->
            noteActivity()
            _buildState.update { it.withStep(name, stepStatusOf(status)) }
        },
        onLog = { msg ->
            noteActivity()
            _buildState.update { it.copy(log = it.log + line(msg)) }
        },
        onDiagnostic = { severity, message, kind, source, file, lineNo, column, detail, task ->
            noteActivity()
            _buildState.update {
                it.copy(diagnostics = it.diagnostics + BuildDiagnosticUi(severityOf(severity), message, kind, source, file, lineNo, column, detail, task))
            }
        },
        onRunConsole = { runId, module, mainClass, phase, acceptsInput, hasExit, exitCode ->
            if (runId < 0) {
                _runConsole.value = null
            } else {
                _runConsole.update { cur ->
                    val exit = if (hasExit) exitCode else null
                    if (cur == null || cur.id != runId) {
                        RunConsoleUi(runId, module, mainClass, runPhaseOf(phase), emptyList(), acceptsInput, exit)
                    } else {
                        cur.copy(moduleName = module, mainClass = mainClass, phase = runPhaseOf(phase), acceptsInput = acceptsInput, exitCode = exit)
                    }
                }
            }
        },
        onConsoleChunk = { runId, text, kind ->
            _runConsole.update { cur ->
                if (cur == null || cur.id != runId) return@update cur
                cur.copy(transcript = appendConsoleChunk(cur.transcript, text, chunkKindOf(kind)))
            }
        },
        onPermission = { reqId, category, detail ->
            _permissionRequest.value = if (reqId < 0) null else UiPermissionRequest(reqId, category, detail)
        },
        onLaunchPackage = { pkg ->
            // The daemon installed the APK in :build; launch it here in the UI process (it has a foreground
            // activity, so the activity launch isn't blocked) and surface the outcome in the build console.
            if (pkg.isNotEmpty()) {
                dev.ide.android.ApkLauncher.launch(appContext, pkg) { msg -> _buildState.update { it.copy(log = it.log + line(msg)) } }
            }
        },
        onAppLog = { level, tag, pid, tid, message, ts ->
            val line = dev.ide.ui.backend.AppLogLineUi(
                message = message,
                level = dev.ide.ui.backend.UiLogLevel.entries.getOrElse(level) { dev.ide.ui.backend.UiLogLevel.Info },
                tag = tag, pid = pid, tid = tid, timeLabel = appLogTimeLabel(ts), timestampMs = ts,
            )
            _appLog.update { ui ->
                val next = ui.lines + line
                ui.copy(lines = if (next.size > MAX_APP_LOG_LINES) next.subList(next.size - MAX_APP_LOG_LINES, next.size) else next)
            }
        },
        onAppLogState = { connectedNow, pkg, reset ->
            _appLog.update { ui ->
                if (reset) dev.ide.ui.backend.AppLogUi(connected = connectedNow, packageName = pkg.ifEmpty { null })
                else ui.copy(connected = connectedNow, packageName = pkg.ifEmpty { ui.packageName })
            }
        },
        onConnected = ::onDaemonConnected,
        onDeath = {
            _buildState.update {
                if (it.status == RunStatus.Running) {
                    it.copy(status = RunStatus.Failed, log = it.log + line("Build process stopped (out of memory?). The IDE is unaffected — press Run to retry."))
                } else {
                    it
                }
            }
            connected = false // the binding auto-restarts the service; onConnected re-drives any pending build
        },
    )

    init {
        // Bind eagerly when the project opens, so the daemon is up before the first build.
        client.bind {}
    }

    /** Task list is a pure model query — answer it locally; only execution crosses to the daemon. */
    override fun runTasks(): List<RunTaskOption> = runCatching { services.buildRunner.runTasks() }.getOrDefault(emptyList())

    override fun runBuild() = execute { client.runBuild() }
    override fun runTask(id: String) = execute { client.runTask(id) }

    override fun stopBuild() {
        // Drop a build still queued behind the daemon-open handshake — Stop must never let it fire later.
        synchronized(pendingLock) { pending = null }
        watchdog?.cancel()
        runCatching { client.stopBuild() }
        // If the daemon never acknowledged this build (still in the handshake — no steps yet), no terminal
        // status will ever stream back; resolve the optimistic Running locally so Stop visibly stops and the
        // next Run isn't blocked by the run-conflict gate. A real in-flight build (steps present) terminates
        // through the daemon's own Stopped/Failed delta instead.
        _buildState.update {
            if (it.status == RunStatus.Running && it.steps.isEmpty()) {
                it.copy(status = RunStatus.Failed, log = it.log + line("Stopped."))
            } else it
        }
    }

    override fun sendRunInput(text: String) { runCatching { client.sendRunInput(text) } }
    override fun closeRunInput() { runCatching { client.closeRunInput() } }
    override fun answerPermission(id: Int, decision: UiPermissionDecision) { runCatching { client.answerPermission(id, decision.ordinal) } }

    /** Fires on first connect AND on every auto-restart after a daemon death — (re)drive a pending build so a
     *  retry queued while the daemon was down isn't lost. (A method, not a constructor lambda, so it can
     *  reference [client] — which isn't yet assigned inside its own initializer.) */
    private fun onDaemonConnected() {
        connected = true
        pending?.let { p -> runCatching { client.open(workspaceRoot, services.modelGeneration, p.id) } }
    }

    /** Show Running immediately, then ensure the daemon has THIS project open (idempotent) and run [action].
     *  If the daemon isn't connected yet (or is mid-restart after a death), [onDaemonConnected] drives it once
     *  the binding (re)connects — so a build/retry queued while disconnected isn't lost. */
    private fun execute(action: () -> Unit) {
        // Persist the UI process's live editor buffers to disk FIRST. The daemon is a separate process with no
        // editor overlay of its own, so its `flushOpenDocuments()` is a no-op — without this it compiles
        // whatever was last saved and runs stale code on an unsaved edit. The in-process runner flushes inside
        // BuildService.runTask; the remote runner must flush here, in the process that actually holds the edits.
        runCatching { services.flushOpenDocuments() }
        val id = requestSeq.incrementAndGet()
        _buildState.value = BuildState(
            status = RunStatus.Running,
            log = listOf(line("Starting the build in the background build process…")),
        )
        synchronized(pendingLock) { pending = PendingRun(id, action) }
        noteActivity()
        // Pass the current model revision: if the UI committed a config change (e.g. minifyEnabled) since the
        // daemon last opened, this differs and the daemon reloads module.toml before building — otherwise the
        // build would run against the daemon's stale, frozen-at-first-open model.
        if (connected) runCatching { client.open(workspaceRoot, services.modelGeneration, id) } // → daemon onOpened → runs `pending`
        armWatchdog()
    }

    /** Guard the handshake window (execute → daemon open → first build deltas): if the daemon goes silent for
     *  [WATCHDOG_SILENCE_MS] before ANY step arrives, fail the build visibly instead of spinning forever —
     *  the historical stuck state ("Running…" + an empty Steps tab) that only a project re-open cleared.
     *  Once steps stream (or the status resolves) the build is live and the watchdog stands down. */
    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_POLL_MS)
                val st = _buildState.value
                if (st.status != RunStatus.Running || st.steps.isNotEmpty()) return@launch
                if (System.currentTimeMillis() - lastActivityMs > WATCHDOG_SILENCE_MS) {
                    synchronized(pendingLock) { pending = null }
                    _buildState.update {
                        if (it.status == RunStatus.Running && it.steps.isEmpty()) {
                            it.copy(
                                status = RunStatus.Failed,
                                log = it.log + line("The build process didn't respond for ${WATCHDOG_SILENCE_MS / 1000}s — press Run to try again."),
                            )
                        } else it
                    }
                    return@launch
                }
            }
        }
    }

    /** Append a streamed console fragment, coalescing into the trailing same-kind chunk (bounded per chunk)
     *  and capping the total transcript — mirrors the in-process [dev.ide.core.services.BuildService] so the
     *  daemon-backed transcript has the same shape and can't grow without limit on a chatty program. */
    private fun appendConsoleChunk(chunks: List<ConsoleChunk>, text: String, kind: ConsoleChunkKind): List<ConsoleChunk> {
        if (text.isEmpty()) return chunks
        val last = chunks.lastOrNull()
        val merged = if (last != null && last.kind == kind && last.text.length < CONSOLE_CHUNK_MAX) {
            chunks.dropLast(1) + ConsoleChunk(last.text + text, kind)
        } else {
            chunks + ConsoleChunk(text, kind)
        }
        var total = merged.sumOf { it.text.length }
        if (total <= CONSOLE_TRANSCRIPT_MAX) return merged
        val out = ArrayDeque(merged)
        while (total > CONSOLE_TRANSCRIPT_MAX && out.size > 1) total -= out.removeFirst().text.length
        return out.toList()
    }

    private fun line(msg: String) = BuildLogLine(msg)

    /** Local time-of-day (HH:mm:ss.SSS) for an app-log line — formatted UI-side (the daemon sends only the epoch ms). */
    private fun appLogTimeLabel(ms: Long): String = runCatching {
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
    }.getOrDefault("")
    private fun runStatusOf(name: String, fallback: RunStatus) = runCatching { RunStatus.valueOf(name) }.getOrDefault(fallback)
    private fun stepStatusOf(name: String) = runCatching { StepStatus.valueOf(name) }.getOrDefault(StepStatus.Pending)
    private fun runPhaseOf(ordinal: Int) = RunPhase.entries.getOrNull(ordinal) ?: RunPhase.Running
    private fun chunkKindOf(ordinal: Int) = ConsoleChunkKind.entries.getOrNull(ordinal) ?: ConsoleChunkKind.OUTPUT
    private fun severityOf(name: String) = runCatching { UiSeverity.valueOf(name) }.getOrDefault(UiSeverity.Error)

    private fun BuildState.withStep(name: String, status: StepStatus): BuildState {
        val idx = steps.indexOfFirst { it.name == name }
        val next = if (idx >= 0) steps.toMutableList().also { it[idx] = BuildStepUi(name, status) } else steps + BuildStepUi(name, status)
        return copy(steps = next)
    }

    private companion object {
        const val CONSOLE_CHUNK_MAX = 8192
        const val CONSOLE_TRANSCRIPT_MAX = 1_000_000
        const val MAX_APP_LOG_LINES = 5000

        // Handshake watchdog: how often to check, and how long the daemon may stay silent before the first
        // step arrives. Generous — a cold daemon open (model load + engine init, no index) is well under
        // this even on a slow phone, and the daemon logs "loading project…" at open start, resetting the clock.
        const val WATCHDOG_POLL_MS = 15_000L
        const val WATCHDOG_SILENCE_MS = 120_000L
    }
}
