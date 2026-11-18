package dev.codeassist.csharp.build.tasks

import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.util.LineSplitter
import dev.codeassist.csharp.util.Utf8Chunks
import dev.codeassist.csharp.util.killableTool
import dev.ide.build.AlwaysRun
import dev.ide.build.ProgramIo
import dev.ide.build.RunConsole
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/**
 * Runs [dll] in the Run console, sending program output to the console and forwarding console input to the program.
 *
 * [title] identifies the program in failure messages.
 *
 * Always runs because a program has no inputs that can establish whether it is up to date.
 */
internal class CsRunProgramTask(
    override val name: TaskName,
    private val dir: Path,
    private val dll: Path,
    private val title: String,
    private val sdk: DotnetSdk,
    private val console: RunConsole,
) : Task, AlwaysRun {

    override val inputs: TaskInputs
        get() = TaskInputsImpl()

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl()

    // Execution

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val launcher = sdk.launcher ?: return TaskResult.Failed(DotnetSdk.NO_DOTNET)
        val io: ProgramIo? = console.io
        val lineSplitter = LineSplitter(ctx.logger())

        // Write directly to the console when available. Otherwise, send output to the build log one line at a time.
        // The console lays out lines itself, so its text is passed through as it arrives.
        val writeOutput: (String) -> Unit = io?.let { it::stdout } ?: lineSplitter::append

        val exitCode = killableTool { cancellation ->
            val process = ProcessBuilder(listOf(launcher.toString(), dll.toString()))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(sdk.environment()) }
                .start()

            cancellation.attach(process)
            io?.started()

            val stdin = io?.stdin
            if (stdin == null) {
                runCatching { process.outputStream.close() }
            } else {
                pumpInput(stdin, process.outputStream)
            }

            val decoder = Utf8Chunks()
            val buffer = ByteArray(OUTPUT_BUFFER_BYTES)

            try {
                while (true) {
                    val bytesRead = process.inputStream.read(buffer)
                    if (bytesRead < 0) break

                    decoder.decode(buffer, bytesRead)
                        .takeIf { it.isNotEmpty() }
                        ?.let(writeOutput)
                }
            } catch (e: IOException) {
                // Stop closes the stream. Treat the resulting read failure as expected cancellation.
                if (!cancellation.isCancelled) throw e
            }

            decoder.finish()
                .takeIf { it.isNotEmpty() }
                ?.let(writeOutput)

            process.waitFor()
        }

        lineSplitter.flush()

        if (io != null) {
            io.exited(exitCode)
        } else {
            ctx.logger()("Process finished with exit code $exitCode")
        }

        return if (exitCode == 0) {
            TaskResult.Success
        } else {
            TaskResult.Failed("$title exited with code $exitCode")
        }
    }

    // Standard input

    /** Forwards console input to the program's stdin on a separate thread because both streams can block. */
    private fun pumpInput(from: InputStream, to: OutputStream) {
        Thread({
            try {
                val buffer = ByteArray(INPUT_BUFFER_BYTES)

                while (true) {
                    val bytesRead = from.read(buffer)
                    if (bytesRead < 0) break

                    to.write(buffer, 0, bytesRead)
                    to.flush()
                }
            } catch (_: IOException) {
                // The program exited and closed its stdin.
            } finally {
                runCatching { to.close() }
            }
        }, "cs-run-stdin").apply { isDaemon = true }.start()
    }

    private companion object {
        const val OUTPUT_BUFFER_BYTES = 8192
        const val INPUT_BUFFER_BYTES = 4096
    }
}