package dev.ide.build.engine

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/** Marker for tasks that must run every time (never up-to-date) — like Gradle's `JavaExec`/`run`. */
interface AlwaysRun

/**
 * Runs a console application — the equivalent of Gradle's `application` plugin `run` task (a `JavaExec`).
 * Launches `java -cp <runtimeClasspath> <mainClass> <args>`. Always runs (an [AlwaysRun]); cooperatively
 * cancellable (kills the process).
 *
 * With no [programIo] the program's combined stdout/stderr streams to the build log line by line (the
 * original non-interactive behavior). With a [programIo] the run is interactive: raw output is forwarded as
 * it arrives — so a prompt with no trailing newline shows at once — and a daemon thread pumps the program's
 * stdin from [ProgramIo.stdin], so the user can type input.
 */
class JavaExecTask(
    override val name: TaskName,
    private val mainClass: String,
    private val runtimeClasspath: () -> List<Path>,
    private val programArgs: List<String> = emptyList(),
    private val javaLauncher: () -> Path = { Paths.get(System.getProperty("java.home"), "bin", "java") },
    private val programIo: ProgramIo? = null,
) : Task, AlwaysRun {

    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        val cp = runtimeClasspath().joinToString(File.pathSeparator) { it.toString() }
        val command = listOf(javaLauncher().toString(), "-cp", cp, mainClass) + programArgs
        ctx.logger()("> Run $mainClass")
        val io = programIo
        if (io == null) {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        ctx.logger()(line)
                        if (!isActive) { process.destroy(); break }
                    }
                }
                val code = if (process.isAlive) process.waitFor() else process.exitValue()
                if (code == 0) TaskResult.Success else TaskResult.Failed("$mainClass exited with code $code")
            } catch (t: Throwable) {
                runCatching { process.destroy() }
                throw t
            }
        } else {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            io.started()
            // Kill the process the instant this run is cancelled (Stop). A program with no output blocks in a
            // native stdout read that Thread.interrupt can't unblock, so the read loop alone wouldn't notice
            // the cancellation; this sibling coroutine wakes on cancellation and force-destroys the process,
            // which then EOFs the reader so the run unwinds cleanly.
            val killer = launch { try { awaitCancellation() } finally { runCatching { process.destroyForcibly() } } }
            // Pump host stdin → the program's stdin on a daemon thread. ProgramIo.stdin blocks until the
            // host feeds input; on EOF/interrupt the read returns -1 and we close the program's stdin.
            val pump = Thread({
                val buf = ByteArray(4096)
                val dst = process.outputStream
                try {
                    while (true) {
                        val n = io.stdin.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n); dst.flush()
                    }
                } catch (_: Throwable) {
                    // Broken pipe (the process exited) or interrupted — stop pumping.
                } finally {
                    runCatching { dst.close() }
                }
            }, "run-stdin-pump").apply { isDaemon = true; start() }
            var code = -1
            try {
                // Forward raw output as it arrives (no line buffering, so prompts show immediately).
                // runInterruptible makes the blocking read cancellable — a program waiting on input is the
                // common case, and a plain readLine() loop would ignore coroutine cancellation.
                runInterruptible {
                    val decoder = StreamingTextDecoder()
                    val buf = ByteArray(4096)
                    val input = process.inputStream
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        io.stdout(decoder.decode(buf, 0, n))
                    }
                    val tail = decoder.flush()
                    if (tail.isNotEmpty()) io.stdout(tail)
                }
                code = process.waitFor()
                io.exited(code)
                if (code == 0) TaskResult.Success else TaskResult.Failed("$mainClass exited with code $code")
            } catch (t: Throwable) {
                runCatching { process.destroyForcibly() }
                throw t
            } finally {
                killer.cancel() // stop the watcher so this scope can complete on the normal path
                pump.interrupt()
                runCatching { process.destroyForcibly() }
            }
        }
    }
}
