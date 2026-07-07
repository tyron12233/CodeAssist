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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    override val buildState: StateFlow<BuildState> = _buildState.asStateFlow()
    override val runConsole: StateFlow<RunConsoleUi?> = _runConsole.asStateFlow()
    override val permissionRequest: StateFlow<UiPermissionRequest?> = _permissionRequest.asStateFlow()

    @Volatile
    private var connected = false

    @Volatile
    private var pending: (() -> Unit)? = null

    private val client = BuildDaemonClient(
        context,
        onOpened = { ok, err ->
            if (ok) {
                pending?.invoke()
            } else {
                _buildState.update { it.copy(status = RunStatus.Failed, log = it.log + line("Build process couldn't open the project: $err")) }
            }
            pending = null
        },
        onStatus = { status, module, elapsed ->
            _buildState.update {
                it.copy(status = runStatusOf(status, it.status), moduleName = module.ifEmpty { it.moduleName }, elapsedMs = elapsed)
            }
        },
        onStep = { name, status -> _buildState.update { it.withStep(name, stepStatusOf(status)) } },
        onLog = { msg -> _buildState.update { it.copy(log = it.log + line(msg)) } },
        onDiagnostic = { severity, message, kind, source, file, lineNo, column, detail, task ->
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
    override fun stopBuild() { runCatching { client.stopBuild() } }

    override fun sendRunInput(text: String) { runCatching { client.sendRunInput(text) } }
    override fun closeRunInput() { runCatching { client.closeRunInput() } }
    override fun answerPermission(id: Int, decision: UiPermissionDecision) { runCatching { client.answerPermission(id, decision.ordinal) } }

    /** Fires on first connect AND on every auto-restart after a daemon death — (re)drive a pending build so a
     *  retry queued while the daemon was down isn't lost. (A method, not a constructor lambda, so it can
     *  reference [client] — which isn't yet assigned inside its own initializer.) */
    private fun onDaemonConnected() {
        connected = true
        if (pending != null) runCatching { client.open(workspaceRoot, services.modelGeneration) }
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
        _buildState.value = BuildState(status = RunStatus.Running)
        pending = action
        // Pass the current model revision: if the UI committed a config change (e.g. minifyEnabled) since the
        // daemon last opened, this differs and the daemon reloads module.toml before building — otherwise the
        // build would run against the daemon's stale, frozen-at-first-open model.
        if (connected) runCatching { client.open(workspaceRoot, services.modelGeneration) } // → daemon onOpened → runs `pending`
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
    }
}
