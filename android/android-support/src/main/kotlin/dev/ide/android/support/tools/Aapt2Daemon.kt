package dev.ide.android.support.tools

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.Timer
import java.util.TimerTask

/**
 * One long-lived `aapt2 daemon` process that runs commands sent over its standard input, for a caller that
 * runs aapt2 over and over on small inputs: the layout preview relinks one file on every edit, and launching a
 * fresh process for the compile and again for the link was most of what an edit cost.
 *
 * The protocol is aapt2's own: after it prints `Ready`, each line is one argument and an empty line runs the
 * command; it then prints `Done`, preceded by `Error` when the command failed. Everything else it prints is the
 * command's output.
 *
 * [run] returns null whenever the daemon cannot give an answer (it would not start, it died, or an argument
 * cannot be sent on one line), and the caller then launches the process the ordinary way: the daemon is only
 * ever a faster route to the same result. The process is stopped after [idleMillis] without a command, so
 * nothing is left running once the preview is no longer being edited.
 */
class Aapt2Daemon(
    private val command: List<String>,
    private val idleMillis: Long = 60_000,
) : AutoCloseable {

    constructor(aapt2: Path) : this(listOf(aapt2.toString(), "daemon"))

    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    private var idleTimer: Timer? = null
    private var idleTask: TimerTask? = null

    /** Commands answered by the daemon, and commands it handed back to be run as a process (diagnostics). */
    @Volatile var served = 0; private set
    @Volatile var declined = 0; private set

    /** Run aapt2 with [args] (the subcommand first, without the binary), or null to run it as a process instead. */
    @Synchronized
    fun run(args: List<String>): ToolResult? =
        runOrNull(args).also { if (it != null) served++ else declined++ }

    private fun runOrNull(args: List<String>): ToolResult? {
        if (args.isEmpty() || args.any { it.isEmpty() || '\n' in it || '\r' in it }) return null
        // A daemon left over from the last command may have been killed since; one that was only just started
        // and still fails is not retried.
        repeat(2) {
            val reused = process != null
            if (!reused && !start()) return null
            try {
                return exchange(args).also { scheduleIdleStop() }
            } catch (_: Exception) {
                stop()
                if (!reused) return null
            }
        }
        return null
    }

    private fun exchange(args: List<String>): ToolResult {
        val w = input!!
        for (a in args) { w.write(a); w.write("\n") }
        w.write("\n")
        w.flush()
        val log = ArrayList<String>()
        var failed = false
        while (true) {
            val line = output!!.readLine() ?: throw java.io.EOFException("aapt2 daemon exited")
            when (line) {
                DONE -> return if (failed) ToolResult(false, log) else ToolResult.ok(log)
                ERROR -> failed = true
                else -> log += line
            }
        }
    }

    private fun start(): Boolean = try {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val reader = p.inputStream.bufferedReader()
        var ready = false
        while (!ready) ready = (reader.readLine() ?: throw java.io.EOFException("no $READY from aapt2 daemon")) == READY
        process = p
        input = p.outputStream.bufferedWriter()
        output = reader
        true
    } catch (_: Exception) {
        stop()
        false
    }

    private fun scheduleIdleStop() {
        idleTask?.cancel()
        val timer = idleTimer ?: Timer("aapt2-daemon-idle", true).also { idleTimer = it }
        idleTask = object : TimerTask() {
            override fun run() = stopIfIdle(this)
        }.also { timer.schedule(it, idleMillis) }
    }

    @Synchronized
    private fun stopIfIdle(task: TimerTask) {
        if (idleTask === task) stop()
    }

    private fun stop() {
        idleTask?.cancel()
        idleTask = null
        runCatching { input?.close() } // end of input ends the daemon's read loop
        runCatching { process?.destroy() }
        process = null
        input = null
        output = null
    }

    @Synchronized
    override fun close() {
        stop()
        idleTimer?.cancel()
        idleTimer = null
    }

    private companion object {
        const val READY = "Ready"
        const val DONE = "Done"
        const val ERROR = "Error"
    }
}
