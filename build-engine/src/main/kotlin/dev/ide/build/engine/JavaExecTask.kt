package dev.ide.build.engine

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/** Marker for tasks that must run every time (never up-to-date) — like Gradle's `JavaExec`/`run`. */
interface AlwaysRun

/**
 * Runs a console application — the equivalent of Gradle's `application` plugin `run` task (a `JavaExec`).
 * Launches `java -cp <runtimeClasspath> <mainClass> <args>` and streams the program's stdout/stderr to
 * the build log line by line. Always runs (an [AlwaysRun]); cooperatively cancellable (kills the process).
 */
class JavaExecTask(
    override val name: TaskName,
    private val mainClass: String,
    private val runtimeClasspath: () -> List<Path>,
    private val programArgs: List<String> = emptyList(),
    private val javaLauncher: () -> Path = { Paths.get(System.getProperty("java.home"), "bin", "java") },
) : Task, AlwaysRun {

    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        val cp = runtimeClasspath().joinToString(File.pathSeparator) { it.toString() }
        val command = listOf(javaLauncher().toString(), "-cp", cp, mainClass) + programArgs
        ctx.logger()("> Run $mainClass")
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
    }
}
