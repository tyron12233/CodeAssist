package dev.codeassist.ndk

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
import dev.ide.platform.log.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlin.io.path.extension

/**
 * "Run <module>" for a C or C++ console program: build it as an executable and run it in the Run console.
 *
 * Offered for a module that carries the `[ndk]` facet, is not an Android app or library (those run as an
 * APK), and has a `main` somewhere in its sources. The row's graph is two tasks: link the program (sharing
 * the build's objects, see [NdkCompileTask]) and run it ([NdkRunTask]).
 */
class NdkRunTaskProvider(
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : RunTaskProvider {

    override fun tasksFor(module: Module): List<RunTaskSpec> {
        val facet = module.facets.get(NdkFacet.KEY) ?: return emptyList()
        if (module.type.id.startsWith("android")) return emptyList()
        if (NativeMain.find(module, facet) == null) return emptyList()
        return listOf(RunTaskSpec(ID_PREFIX + module.name, "Run ${module.name}", "run"))
    }

    override fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? {
        if (spec.id != ID_PREFIX + module.name) return null
        val facet = module.facets.get(NdkFacet.KEY) ?: return null
        val buildDir = ctx.env.buildDir(module)
        val ndkDir = buildDir.resolve("intermediates").resolve("ndk")

        val link = NdkCompileTask(
            TaskName(":${module.name}:linkNativeProgram"), module, facet, buildDir, ndkDir.resolve("lib"),
            toolchain, log, executableDir = ndkDir.resolve("bin"),
        )
        // The console is asked for before anything else is built: on a host older than SPI 3.2.0 the class
        // itself does not exist, and the run then streams into the build log instead.
        val console = try {
            RunConsole(module.name)
        } catch (e: LinkageError) {
            log.info("this IDE has no Run console for plugins; the program's output goes to the build log")
            null
        }
        val run = NdkRunTask(TaskName(":${module.name}:runNative"), module, { link.outputFor(RUN_ABI) }, toolchain, console, log)
        val action = RunAction("> Run ${module.name}", TwoStepGraph(link, run))
        if (console != null) {
            try {
                action.console = console
            } catch (e: LinkageError) {
                log.info("this IDE cannot open a Run console for a plugin; the output goes to the build log")
            }
        }
        return action
    }

    companion object {
        const val ID_PREFIX = "ndkRun:"

        /** The ABI the bundled toolchain targets, and so the only program it can build to run here. */
        const val RUN_ABI = "arm64-v8a"
    }
}

/** Link, then run: the whole graph of a Run row. */
internal class TwoStepGraph(private val first: Task, private val second: Task) : TaskGraph {
    override val tasks: List<Task> = listOf(first, second)
    override fun dependencies(t: Task): List<Task> = if (t === second) listOf(first) else emptyList()
    override fun topologicalLevels(): List<List<Task>> = listOf(listOf(first), listOf(second))
}

/**
 * Runs the program [program] names, in the Run console when the host opened one ([console]'s `io`), or with
 * its output in the build log otherwise.
 *
 * **How it runs at all.** Since Android 10 an app may not `exec()` a file in its own storage. The system
 * linker may load one, so the program runs as `/system/bin/linker64 <file>` -- the same SELinux rule that
 * allows an app to `dlopen` its own files. It is copied into the IDE's internal cache first, because shared
 * storage is mounted without execute permission at all. On Android 9 and older, and on the desktop, it is
 * executed directly.
 *
 * **Why a launcher.** Through plain pipes C's stdio buffers stdout fully, and a prompt without a newline
 * never shows before the program reads. `libndkrun.so` runs it in a pseudo-terminal instead (see
 * tools/ndk-toolchain/daemon/ndkrun.c); a toolchain without it falls back to pipes.
 *
 * Stop kills the launcher, and the program dies with it (the launcher sets PR_SET_PDEATHSIG).
 */
internal class NdkRunTask(
    override val name: TaskName,
    private val module: Module,
    private val program: () -> Path,
    private val toolchain: () -> NdkToolchain?,
    private val console: RunConsole?,
    private val log: Logger,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val built = program()
        if (!Files.isRegularFile(built)) return TaskResult.Failed("ndk: no program was built at $built")
        val io: ProgramIo? = try {
            console?.io
        } catch (e: LinkageError) {
            null
        }
        val logLines = LineSplitter(ctx.logger())
        val write: (String) -> Unit = io?.let { it::stdout } ?: logLines::append

        val launch = try {
            NativeLaunch.prepare(built, module.name, toolchain()?.runLauncher)
        } catch (e: IOException) {
            return TaskResult.Failed("ndk: could not stage the program to run: ${e.message}", e)
        }
        ctx.logger()("> Run ${module.name}")

        val code = killableTool { cancellation ->
            val process = ProcessBuilder(launch.command)
                .directory(Paths.get(module.dir.path).toFile().takeIf { it.isDirectory })
                .redirectErrorStream(true)
                .apply { environment().putAll(launch.environment) }
                .start()
            cancellation.attach(process)
            io?.started()

            val stdin = io?.stdin
            if (stdin == null) runCatching { process.outputStream.close() }
            else pumpInput(stdin, process.outputStream)

            val decoder = Utf8Chunks()
            val buffer = ByteArray(8192)
            val out = process.inputStream
            try {
                while (true) {
                    val n = out.read(buffer)
                    if (n < 0) break
                    val text = decoder.decode(buffer, n)
                    if (text.isNotEmpty()) write(text)
                }
            } catch (e: IOException) {
                // Killed by Stop: the read ends with the stream, which is the expected end of this run.
                if (!cancellation.isCancelled) throw e
            }
            decoder.finish().takeIf { it.isNotEmpty() }?.let(write)
            process.waitFor()
        }

        logLines.flush()
        if (io != null) io.exited(code) else ctx.logger()("Process finished with exit code $code")
        return if (code == 0) TaskResult.Success else TaskResult.Failed("${module.name} exited with code $code")
    }

    /**
     * Copy what the console sends into the program's stdin on a thread of its own, since both ends block.
     * End-of-input from the console closes the program's stdin, which the launcher turns into a terminal EOF.
     */
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
            } catch (e: IOException) {
                // The program exited and its stdin closed under us: nothing left to deliver.
            } finally {
                runCatching { to.close() }
            }
        }, "ndk-run-stdin").apply { isDaemon = true }.start()
    }
}

/**
 * How to start a built program on this device: where it is staged, the command, and the environment.
 */
internal class NativeLaunch(val command: List<String>, val environment: Map<String, String>) {
    companion object {
        private const val SYSTEM_LINKER = "/system/bin/linker64"

        /**
         * Stage [built] where it can be run and say how to run it.
         *
         * On Android the copy goes under `java.io.tmpdir`, which ART points at the app's own cache directory
         * in internal storage: the one place an app's file can be mapped executable. The C++ runtime is copied
         * beside it when the program links it (`stl = c++_shared`), and `LD_LIBRARY_PATH` names that directory.
         */
        fun prepare(built: Path, moduleName: String, launcher: Path?): NativeLaunch {
            val android = Files.isRegularFile(Paths.get(SYSTEM_LINKER))
            val runnable = if (android) {
                val tmp = System.getProperty("java.io.tmpdir")?.takeIf { it.startsWith("/data/") }
                    ?: throw IOException("no internal storage to run from (java.io.tmpdir is not under /data)")
                val dir = Paths.get(tmp, "ndk-run", safeName(moduleName))
                Files.createDirectories(dir)
                val copy = dir.resolve(built.fileName.toString())
                Files.copy(built, copy, StandardCopyOption.REPLACE_EXISTING)
                built.resolveSibling(CXX_SHARED).takeIf { Files.isRegularFile(it) }
                    ?.let { Files.copy(it, dir.resolve(CXX_SHARED), StandardCopyOption.REPLACE_EXISTING) }
                copy.toFile().setExecutable(true, false)
                copy
            } else {
                built.toFile().setExecutable(true, false)
                built
            }

            val program = if (android && needsSystemLinker()) listOf(SYSTEM_LINKER, runnable.toString())
            else listOf(runnable.toString())
            val command = if (launcher != null) listOf(launcher.toString()) + program else program
            return NativeLaunch(
                command,
                mapOf(
                    "LD_LIBRARY_PATH" to runnable.parent.toString(),
                    // The console shows text; a program that colours its output for a terminal would fill it
                    // with escape codes.
                    "TERM" to "dumb",
                ),
            )
        }

        /**
         * Android 10 is where an app lost `exec()` on its own files, and where the linker learned to run a
         * program it is handed. Before it, the program is executed directly; the linker would only print
         * its usage.
         */
        private fun needsSystemLinker(): Boolean =
            runCatching { android.os.Build.VERSION.SDK_INT }.getOrDefault(0).let { it == 0 || it >= 29 }

        private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

        private const val CXX_SHARED = "libc++_shared.so"
    }
}

/** Finds a module's `main`, so Run is offered only for a module that has one. */
internal object NativeMain {

    /** `int main(` at the start of a line, the shape of every hosted C or C++ entry point. */
    private val MAIN = Regex("""(?m)^[ \t]*(?:int|auto|signed|signed\s+int)\s+main\s*\(""")
    private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
    private val LINE_COMMENT = Regex("""//[^\n]*""")

    /** Per file, its modification time and whether it defined `main` then; the Run picker asks often. */
    private val cache = ConcurrentHashMap<Path, Pair<Long, Boolean>>()

    /** The first source under [facet]'s directories that defines `main`, or null. */
    fun find(module: Module, facet: NdkFacet): Path? {
        val moduleDir = Paths.get(module.dir.path)
        return facet.sourceDirs.asSequence()
            .map { moduleDir.resolve(it) }
            .filter { Files.isDirectory(it) }
            .flatMap { dir ->
                Files.walk(dir).use { walk ->
                    walk.filter { Files.isRegularFile(it) && it.extension.lowercase() in SOURCES }
                        .collect(Collectors.toList())
                }.sorted().asSequence()
            }
            .firstOrNull { definesMain(it) }
    }

    fun definesMain(file: Path): Boolean {
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)
        cache[file]?.let { (at, answer) -> if (at == modified) return answer }
        val answer = runCatching { definesMain(String(Files.readAllBytes(file))) }.getOrDefault(false)
        cache[file] = modified to answer
        return answer
    }

    fun definesMain(text: String): Boolean =
        MAIN.containsMatchIn(LINE_COMMENT.replace(BLOCK_COMMENT.replace(text, " "), ""))

    private val SOURCES = setOf("c", "cc", "cpp", "cxx", "c++")
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
