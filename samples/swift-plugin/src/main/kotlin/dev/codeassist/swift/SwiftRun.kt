package dev.codeassist.swift

import dev.ide.build.AlwaysRun
import dev.ide.build.BuildContext
import dev.ide.build.ProgramIo
import dev.ide.build.RunAction
import dev.ide.build.RunConsole
import dev.ide.build.RunTaskProvider
import dev.ide.build.RunTaskSpec
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskGraph
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.model.Module
import dev.ide.model.Project
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The Run picker's Swift rows: "Run <target>" for each executable target of a Swift package (a directory under
 * `Sources/` with a `main.swift`).
 *
 * A Run is one process: the compiler compiles the target's files and runs the result in its JIT (`-interpret`), so
 * what the compiler says and what the program prints arrive in the same Run console, and what is typed there is the
 * program's input. No linker, no executable on disk.
 */
class SwiftRunTaskProvider(private val toolchain: SwiftToolchain) : RunTaskProvider {

    override fun tasksFor(module: Module): List<RunTaskSpec> {
        if (module.type.id != SwiftModuleType.ID) return emptyList()
        val pkg = Paths.get(module.dir.path)
        return SwiftPackages.executables(pkg).map { target ->
            RunTaskSpec("$RUN_PREFIX${module.name}:$target", "Run $target", "run")
        }
    }

    override fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? {
        val prefix = "$RUN_PREFIX${module.name}:"
        if (!spec.id.startsWith(prefix)) return null
        val target = spec.id.removePrefix(prefix)
        val pkg = Paths.get(module.dir.path)
        if (target !in SwiftPackages.executables(pkg)) return null
        val console = RunConsole(target)
        val task = RunSwiftTask(TaskName(":${module.name}:run${target.replaceFirstChar(Char::uppercaseChar)}"), pkg, target, toolchain, console)
        return RunAction("> Run $target", SingleTaskGraph(task)).also { it.console = console }
    }

    companion object {
        const val RUN_PREFIX = "swiftRun:"
    }
}

private class SingleTaskGraph(task: Task) : TaskGraph {
    override val tasks: List<Task> = listOf(task)
    override fun dependencies(t: Task): List<Task> = emptyList()
    override fun topologicalLevels(): List<List<Task>> = listOf(tasks)
}

/** Compile and run [target] of the package at [pkg] in the JIT, in the Run [console]. */
internal class RunSwiftTask(
    override val name: TaskName,
    private val pkg: Path,
    private val target: String,
    private val toolchain: SwiftToolchain,
    private val console: RunConsole,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val status = toolchain.prepare()
        if (status is SwiftToolchain.Status.Unavailable) return TaskResult.Failed(status.reason)

        val sources = SwiftPackages.sources(pkg, target)
        val main = sources.firstOrNull { it.fileName.toString() == "main.swift" && it.parent == pkg.resolve(SwiftPackages.SOURCES).resolve(target) }
            ?: return TaskResult.Failed("$target has no main.swift")
        // The program's main.swift, with unbuffered output (SwiftPackages.runMain), compiled in place of the original.
        val generated = pkg.resolve(SwiftPackages.BUILD_DIR).resolve("run").resolve(target).resolve("main.swift")
        Files.createDirectories(generated.parent)
        Files.write(generated, SwiftPackages.runMain(String(Files.readAllBytes(main))).toByteArray())
        val files = sources.map { if (it == main) generated else it }

        val io: ProgramIo? = console.io
        val lines = LineSplitter(ctx.logger())
        val rawWrite: (String) -> Unit = io?.let { it::stdout } ?: lines::append
        // The compiler names the generated copy, where the user wrote the original; and every file by its absolute
        // path, which on a phone is mostly `/storage/emulated/0/Android/data/...`: relative to the package it reads
        // as the Project view shows it.
        val packagePrefix = pkg.toString().trimEnd('/') + "/"
        val write: (String) -> Unit = { text ->
            rawWrite(text.replace(generated.toString(), main.toString()).replace(packagePrefix, ""))
        }

        val command = toolchain.interpretCommand(files, SwiftPackages.moduleName(target))
        val code = killableTool { cancellation ->
            toolchain.withCompilerLock {
                val process = ProcessBuilder(command)
                    .directory(pkg.toFile())
                    .redirectErrorStream(true)
                    .apply { environment().putAll(toolchain.environment()) }
                    .start()
                cancellation.attach(process)
                io?.started()
                val stdin = io?.stdin
                if (stdin == null) runCatching { process.outputStream.close() } else pumpInput(stdin, process.outputStream)
                val decoder = Utf8Chunks()
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val n = process.inputStream.read(buffer)
                        if (n < 0) break
                        decoder.decode(buffer, n).takeIf { it.isNotEmpty() }?.let(write)
                    }
                } catch (e: IOException) {
                    // Killed by Stop: the read ends with the stream, which is how this run is meant to end.
                    if (!cancellation.isCancelled) throw e
                }
                decoder.finish().takeIf { it.isNotEmpty() }?.let(write)
                process.waitFor()
            }
        }
        lines.flush()
        if (io != null) io.exited(code) else ctx.logger()("Process finished with exit code $code")
        return if (code == 0) TaskResult.Success else TaskResult.Failed("$target exited with code $code")
    }

    /** Copy what the console sends into the program's stdin on a thread of its own, since both ends block. */
    private fun pumpInput(from: InputStream, to: OutputStream) {
        Thread({
            try {
                val buffer = ByteArray(4096)
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    to.write(buffer, 0, n)
                    to.flush()
                }
            } catch (_: IOException) {
                // The program exited and its stdin closed under us.
            } finally {
                runCatching { to.close() }
            }
        }, "swift-run-stdin").apply { isDaemon = true }.start()
    }
}
