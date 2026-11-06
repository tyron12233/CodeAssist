package dev.codeassist.dart

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
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The Run picker's Dart rows: "Run <file>" for each `.dart` file directly in a Dart package's `bin/`, and `pub get` /
 * `pub upgrade`.
 *
 * A run is three steps: `pub get` when the package config is missing or older than the pubspec, compile the
 * program to kernel with the SDK's kernel compiler, and run that on the JIT VM in the Run console. It is not
 * `dart run`, which cannot work from an installed app: it looks for its compilers beside the runtime's real
 * path, the read-only native library directory.
 */
class DartRunTaskProvider(private val sdk: DartSdk, private val pub: Pub, private val flutter: FlutterSdk) : RunTaskProvider {

    override fun tasksFor(module: Module): List<RunTaskSpec> {
        if (module.type.id != DartModuleType.ID) return emptyList()
        val pkg = Paths.get(module.dir.path)
        // A Flutter app runs in the player, hot-reloading; its bin/ (if any) is not what Run means.
        val runs = if (FlutterSdk.isFlutterPackage(pkg)) {
            listOf(
                RunTaskSpec("$FLUTTER_PREFIX${module.name}", "Run Flutter app · ${module.name}", "run"),
                RunTaskSpec("$PREVIEW_PREFIX${module.name}", "Preview widgets · ${module.name}", "run"),
            )
        } else DartPackages.executables(pkg).map { file ->
            RunTaskSpec("$RUN_PREFIX${module.name}:${pkg.relativize(file)}", "Run ${file.fileName}", "run")
        }
        return runs + listOf(
            RunTaskSpec("$PUB_PREFIX${module.name}:get", "pub get · ${module.name}", "build"),
            RunTaskSpec("$PUB_PREFIX${module.name}:upgrade", "pub upgrade · ${module.name}", "build"),
        )
    }

    override fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? {
        val pkg = Paths.get(module.dir.path)
        if (spec.id.startsWith("$PUB_PREFIX${module.name}:")) {
            val command = spec.id.substringAfterLast(':').takeIf { it == "get" || it == "upgrade" } ?: return null
            val task = PubTask(TaskName(":${module.name}:pub${command.replaceFirstChar(Char::uppercaseChar)}"), pkg, pub, command, onlyIfNeeded = false)
            return RunAction("> dart pub $command", LinearGraph(listOf(task)))
        }
        if (spec.id == "$PREVIEW_PREFIX${module.name}") {
            val console = RunConsole("${module.name} previews")
            val task = FlutterRunTask(TaskName(":${module.name}:flutterPreview"), pkg, sdk, flutter, pub, console, preview = true)
            return RunAction("> Flutter previews ${module.name}", LinearGraph(listOf(task))).also { it.console = console }
        }
        if (spec.id == "$FLUTTER_PREFIX${module.name}") {
            val console = RunConsole("${module.name} (Flutter)")
            val task = FlutterRunTask(TaskName(":${module.name}:flutterRun"), pkg, sdk, flutter, pub, console)
            return RunAction("> Flutter ${module.name}", LinearGraph(listOf(task))).also { it.console = console }
        }
        val prefix = "$RUN_PREFIX${module.name}:"
        if (!spec.id.startsWith(prefix)) return null
        val entry = pkg.resolve(spec.id.removePrefix(prefix)).normalize()
        if (!entry.startsWith(pkg) || !Files.isRegularFile(entry)) return null

        val stem = entry.fileName.toString().removeSuffix(".dart")
        val dill = pkg.resolve(".dart_tool/codeassist/run/$stem.dill")
        val console = RunConsole("${entry.fileName}")
        val tasks = listOf(
            PubTask(TaskName(":${module.name}:pubGet"), pkg, pub, "get", onlyIfNeeded = true, console = console),
            CompileTask(TaskName(":${module.name}:compileDart"), pkg, entry, dill, sdk, console),
            RunProgramTask(TaskName(":${module.name}:runDart"), pkg, dill, "${entry.fileName}", sdk, console),
        )
        return RunAction("> Run ${pkg.relativize(entry)}", LinearGraph(tasks)).also { it.console = console }
    }

    companion object {
        const val RUN_PREFIX = "dartRun:"
        const val PUB_PREFIX = "dartPub:"
        const val FLUTTER_PREFIX = "flutterRun:"
        const val PREVIEW_PREFIX = "flutterPreview:"
    }
}

/** A line logger that also writes to [console]'s output, when the Run opened one. */
internal fun alsoToConsole(log: (String) -> Unit, console: RunConsole?): (String) -> Unit = { line ->
    log(line)
    console?.io?.stdout(line + "\n")
}

/** Tasks run one after another, each depending on the one before. */
internal class LinearGraph(override val tasks: List<Task>) : TaskGraph {
    override fun dependencies(t: Task): List<Task> = tasks.indexOf(t).let { i -> if (i > 0) listOf(tasks[i - 1]) else emptyList() }
    override fun topologicalLevels(): List<List<Task>> = tasks.map { listOf(it) }
}

/**
 * `dart pub <command>`, its output in the build log, and in the Run [console] too when it is a step of a Run.
 * With [onlyIfNeeded], skipped while the package config is current.
 */
internal class PubTask(
    override val name: TaskName,
    private val pkg: Path,
    private val pub: Pub,
    private val command: String,
    private val onlyIfNeeded: Boolean,
    private val console: RunConsole? = null,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        if (onlyIfNeeded && !DartPackages.needsPubGet(pkg)) return TaskResult.Success
        val log = alsoToConsole(ctx.logger(), console)
        // As a step of a Run the header names the program, so say what this step is; run on its own, the
        // run's header already did.
        if (onlyIfNeeded) log("> dart pub $command")
        val code = killableTool { cancellation -> pub.run(pkg, command, cancellation = cancellation, out = log) }
        return if (code == 0) TaskResult.Success else TaskResult.Failed("pub $command failed (exit code $code)")
    }
}

/**
 * Compile [entry] to kernel at [dill] with the SDK's kernel compiler. The platform libraries are not linked
 * in (the VM carries its own), so the output is the program alone: a few hundred kilobytes, not nine MB.
 *
 * What the compiler says goes to the Run [console] as well as the build log: a Run that stops at a compile
 * error otherwise opened a console reading "(no output)", with the reason in a log the user was not looking at.
 */
internal class CompileTask(
    override val name: TaskName,
    private val pkg: Path,
    private val entry: Path,
    private val dill: Path,
    private val sdk: DartSdk,
    private val console: RunConsole? = null,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val status = sdk.prepare()
        if (status is DartSdk.Status.Unavailable) return TaskResult.Failed(status.reason)
        val log = alsoToConsole(ctx.logger(), console)
        val code = killableTool { cancellation -> compileKernel(sdk, pkg, entry, dill, cancellation, log) }
        if (code != 0 || !Files.isRegularFile(dill)) return TaskResult.Failed("${entry.fileName} did not compile")
        return TaskResult.Success
    }
}

/**
 * Compile [entry] of the package at [pkg] to kernel at [dill], passing each line the compiler prints to [out].
 * The exit code, or -1 when the SDK is missing. Blocking; [cancellation] kills the compiler.
 */
internal fun compileKernel(
    sdk: DartSdk,
    pkg: Path,
    entry: Path,
    dill: Path,
    cancellation: ToolCancellation?,
    out: (String) -> Unit,
): Int {
    val runtime = sdk.runtime ?: return -1
    val compiler = sdk.genKernel ?: return -1
    Files.createDirectories(dill.parent)
    Files.deleteIfExists(dill)
    val config = pkg.resolve(DartPackages.PACKAGE_CONFIG)
    val command = buildList {
        add(runtime.toString()); add(compiler.toString())
        add("--platform=${sdk.platformDill}")
        if (Files.isRegularFile(config)) add("--packages=$config")
        add("--no-link-platform")
        add("-o"); add(dill.toString())
        add(entry.toString())
    }
    val process = ProcessBuilder(command).directory(pkg.toFile()).redirectErrorStream(true)
        .apply { environment().putAll(sdk.environment()) }.start()
    cancellation?.attach(process)
    runCatching { process.outputStream.close() }
    process.inputStream.bufferedReader().forEachLine(out)
    return process.waitFor()
}

/** Run [dill] on the JIT VM in the Run console: its output there, what is typed there as its stdin. */
internal class RunProgramTask(
    override val name: TaskName,
    private val pkg: Path,
    private val dill: Path,
    private val title: String,
    private val sdk: DartSdk,
    private val console: RunConsole,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val vm = sdk.vm ?: return TaskResult.Failed(DartSdk.NO_SDK)
        val io: ProgramIo? = console.io
        val lines = LineSplitter(ctx.logger())
        val write: (String) -> Unit = io?.let { it::stdout } ?: lines::append
        val code = killableTool { cancellation ->
            val process = ProcessBuilder(listOf(vm.toString(), dill.toString()))
                .directory(pkg.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(sdk.environment()) }
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
        lines.flush()
        if (io != null) io.exited(code) else ctx.logger()("Process finished with exit code $code")
        return if (code == 0) TaskResult.Success else TaskResult.Failed("$title exited with code $code")
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
        }, "dart-run-stdin").apply { isDaemon = true }.start()
    }
}

/** Decodes UTF-8 arriving in arbitrary chunks, carrying a character split across two of them. */
internal class Utf8Chunks {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray, length: Int): String {
        val input = ByteBuffer.allocate(pending.size + length).put(pending).put(bytes, 0, length)
        input.flip()
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
    }

    fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val out = CharBuffer.allocate(pending.size + 1)
        decoder.decode(input, out, true)
        decoder.flush(out)
        pending = ByteArray(0)
        out.flip()
        return out.toString()
    }
}

/** Re-splits raw program output into lines for the build log, when there is no console to stream to. */
internal class LineSplitter(private val log: (String) -> Unit) {
    private val pending = StringBuilder()

    @Synchronized
    fun append(text: String) {
        pending.append(text)
        var nl = pending.indexOf("\n")
        while (nl >= 0) {
            log(pending.substring(0, nl).trimEnd('\r'))
            pending.delete(0, nl + 1)
            nl = pending.indexOf("\n")
        }
    }

    @Synchronized
    fun flush() {
        if (pending.isNotEmpty()) log(pending.toString().trimEnd('\r'))
        pending.setLength(0)
    }
}
