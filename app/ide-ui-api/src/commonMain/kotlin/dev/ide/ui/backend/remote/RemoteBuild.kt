package dev.ide.ui.backend.remote

import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.BuildStepUi
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.UiLogLevel
import dev.ide.ui.backend.UiSeverity

/**
 * Build and run across a byte channel: the Run picker's tasks, starting and stopping one, and the console's
 * state as it grows.
 *
 * A build's state is a flow on the hosts that own their engine. Across a channel that only answers, the near
 * side asks for it while a build runs, and says how much of the transcript it already has so each answer
 * carries only the lines since. Both ends are here: [Client] writes the questions and reads the answers,
 * [Server] answers them from a real [BuildService].
 */
object RemoteBuild {
    private const val MAGIC = 0x424C4431 // "BLD1"
    private const val TASKS = 1
    private const val RUN_TASK = 2
    private const val RUN_BUILD = 3
    private const val STOP = 4
    private const val STATE = 5

    /** Whether [request] is one of this protocol's. */
    fun handles(request: ByteArray): Boolean = request.size >= 8 && RemoteIn(request).int() == MAGIC

    /** The answer of a far side that cannot answer, which [Client] raises as an error with [message]. */
    fun failure(message: String): ByteArray = RemoteOut().apply { bool(false); string(message) }.done()

    /** The near side. It keeps the transcript it has read, so each [state] asks only for what is new. */
    class Client(private val channel: (ByteArray) -> ByteArray) {
        private var log: List<BuildLogLine> = emptyList()
        private var diagnostics: List<BuildDiagnosticUi> = emptyList()

        private fun ask(op: Int, write: RemoteOut.() -> Unit = {}): RemoteIn {
            val reply = RemoteIn(channel(RemoteOut().apply { int(MAGIC); int(op); write() }.done()))
            if (!reply.bool()) error(reply.string() ?: "The backend did not answer.")
            return reply
        }

        fun tasks(): List<RunTaskOption> = ask(TASKS).run { list { RunTaskOption(string()!!, string()!!, string()!!) } }
        fun runTask(id: String) { ask(RUN_TASK) { string(id) } }
        fun runBuild() { ask(RUN_BUILD) }
        fun stop() { ask(STOP) }

        /** The build's state now. Not to be called from two threads at once: it keeps what it has read. */
        fun state(): BuildState {
            val r = ask(STATE) { int(log.size); int(diagnostics.size) }
            val status = RunStatus.entries[r.int()]
            val module = r.string()!!
            val steps = r.list { BuildStepUi(r.string()!!, StepStatus.entries[r.int()]) }
            val logFrom = r.int()
            val newLines = r.list { BuildLogLine(r.string()!!, UiLogLevel.entries[r.int()], r.string(), r.string()!!, r.long()) }
            val diagnosticsFrom = r.int()
            val newDiagnostics = r.list {
                BuildDiagnosticUi(
                    UiSeverity.entries[r.int()], r.string()!!, r.string()!!, r.string()!!, r.string(), r.int(), r.int(), r.string(), r.string(),
                )
            }
            log = if (newLines.isEmpty() && logFrom == log.size) log else log.take(logFrom) + newLines
            diagnostics = if (newDiagnostics.isEmpty() && diagnosticsFrom == diagnostics.size) diagnostics else diagnostics.take(diagnosticsFrom) + newDiagnostics
            return BuildState(status, module, steps, log, diagnostics, r.long(), r.string(), r.bool())
        }
    }

    /** Answers [Client]'s questions from [build]. A failure of any kind is an answer too. */
    class Server(private val build: BuildService) {
        fun handle(request: ByteArray): ByteArray = try {
            val input = RemoteIn(request)
            input.int()
            val out = RemoteOut().apply { bool(true) }
            when (val op = input.int()) {
                TASKS -> out.list(build.runTasks()) { out.string(it.id); out.string(it.label); out.string(it.group) }
                RUN_TASK -> build.runTask(input.string()!!)
                RUN_BUILD -> build.runBuild()
                STOP -> build.stopBuild()
                STATE -> {
                    val state = build.buildState.value
                    // A transcript shorter than what the asker holds is another build's: it gets it whole.
                    val logFrom = input.int().let { if (it > state.log.size) 0 else it }
                    val diagnosticsFrom = input.int().let { if (it > state.diagnostics.size) 0 else it }
                    out.int(state.status.ordinal); out.string(state.moduleName)
                    out.list(state.steps) { out.string(it.name); out.int(it.status.ordinal) }
                    out.int(logFrom)
                    out.list(state.log.subList(logFrom, state.log.size)) {
                        out.string(it.message); out.int(it.level.ordinal); out.string(it.task); out.string(it.timeLabel); out.long(it.timestampMs)
                    }
                    out.int(diagnosticsFrom)
                    out.list(state.diagnostics.subList(diagnosticsFrom, state.diagnostics.size)) {
                        out.int(it.severity.ordinal); out.string(it.message); out.string(it.kind); out.string(it.source); out.string(it.file)
                        out.int(it.line); out.int(it.column); out.string(it.detail); out.string(it.task)
                    }
                    out.long(state.elapsedMs); out.string(state.banner); out.bool(state.stopped)
                }
                else -> error("unknown build call $op")
            }
            out.done()
        } catch (t: Throwable) {
            failure(t.message ?: t.toString())
        }
    }
}
