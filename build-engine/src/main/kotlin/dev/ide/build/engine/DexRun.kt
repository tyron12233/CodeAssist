package dev.ide.build.engine

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The on-device Java *run* path (the dex twin of [JavaExecTask], which forks `java` — impossible on ART).
 * `JavaBuildSystem.createDexRunGraph` wires `compileJava` (closure) → [JavaDexTask] (dex the runtime
 * classpath) → [DexExecTask] (load + invoke `main`). Both tool steps are injected ports so build-engine
 * keeps no Android dependency — exactly as the build graph injects [JavaCompile].
 */

/**
 * Dex backend: turn a runtime classpath (class dirs + jars) into indexed `classes.dex` under `outDir`.
 * Supplied by the host (D8 in-process, from :android-support) — the mirror of [JavaCompile].
 */
fun interface DexBackend {
    fun dex(inputs: List<Path>, minApi: Int, outDir: Path): DexResult
}

data class DexResult(val success: Boolean, val log: List<String> = emptyList())

/**
 * The running program's standard I/O for an interactive console run, supplied by the host. [stdout]
 * receives the program's combined stdout/stderr as raw text — chunks may be partial lines, so a prompt
 * with no trailing newline (`print("Enter name: ")`) shows immediately. [stdin] is the program's standard
 * input; reads block until the host feeds input or signals end-of-input. [started]/[exited] bracket the
 * program's execution so the UI can enable input while it runs and record the exit code.
 */
interface ProgramIo {
    fun stdout(text: String)
    val stdin: InputStream
    fun started() {}
    fun exited(code: Int) {}
}

/**
 * Runs a dexed console program on ART: loads `dexDir`'s `classes*.dex` with a `DexClassLoader`, invokes
 * `mainClass.main(args)`, streams stdout/stderr to [io] and reads stdin from it, and returns the exit code.
 * Injected by :ide-android — ide-core (which also compiles for the desktop JVM) cannot reference
 * `dalvik.system.*`.
 */
interface DexRunner {
    suspend fun run(dexDir: Path, mainClass: String, args: List<String>, io: ProgramIo): Int
}

/**
 * Default [ProgramIo] for a non-interactive dex run: re-splits the program's raw output into lines for the
 * build [log] and offers an empty (immediately end-of-input) stdin. Used when no interactive console is
 * attached, preserving the original line-oriented build-log behavior.
 */
internal class LoggingProgramIo(private val log: (String) -> Unit) : ProgramIo {
    private val pending = StringBuilder()
    override val stdin: InputStream = object : InputStream() {
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    }

    @Synchronized
    override fun stdout(text: String) {
        pending.append(text)
        var nl = pending.indexOf("\n")
        while (nl >= 0) {
            log(pending.substring(0, nl).trimEnd('\r'))
            pending.delete(0, nl + 1)
            nl = pending.indexOf("\n")
        }
    }

    @Synchronized
    override fun exited(code: Int) {
        if (pending.isNotEmpty()) {
            log(pending.toString().trimEnd('\r')); pending.setLength(0)
        }
    }
}

/**
 * `dexRun`: dex the module's runtime classpath into [outDex]. Class directories are jarred into
 * [stagingDir] first (D8 reads jars/class files, not raw dirs); library jars pass through. A normal
 * incremental task — re-dexes only when the runtime classpath content changes.
 */
class JavaDexTask(
    override val name: TaskName,
    private val runtimeClasspath: () -> List<Path>,
    private val minApi: Int,
    private val stagingDir: Path,
    private val outDex: Path,
    private val dexBackend: DexBackend,
) : Task {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        val cp = runtimeClasspath()
        dirPaths("classes", cp.filter { Files.isDirectory(it) })
        filePaths("libs", cp.filter { !Files.isDirectory(it) })
        property("minApi", minApi)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("dex", outDex) }

    override suspend fun execute(ctx: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        ctx.checkCanceled()
        Files.createDirectories(stagingDir)
        Files.createDirectories(outDex)
        // Instrument everything on the run's classpath (user classes AND libraries): ExitGuard so a program's
        // exit ends the run not the IDE, and SandboxGuard so its network/file/reflection/exec calls are mediated
        // by the permission broker. Both pre-scan and no-op classes that can't match, so this stays cheap.
        val transform: (String, ByteArray) -> ByteArray = { name, bytes ->
            if (name.endsWith(".class")) SandboxGuard.instrument(ExitGuard.instrument(bytes)) else bytes
        }
        val cp = runtimeClasspath().filter { Files.exists(it) }
        val inputs = cp.mapIndexed { i, p ->
            when {
                Files.isDirectory(p) -> stagingDir.resolve("in$i.jar").also { writeJar(p, it, transform) }
                else -> stagingDir.resolve("lib$i.jar").also { copyJarTransformed(p, it, transform) }
            }
        }.filter { Files.exists(it) && Files.size(it) > 0L }
        if (inputs.isEmpty()) return@withContext TaskResult.Failed("nothing to dex for run")
        val r = dexBackend.dex(inputs, minApi, outDex)
        r.log.forEach(ctx.logger())
        if (r.success) TaskResult.Success else TaskResult.Failed("dex (run) failed")
    }
}

/**
 * `runDex`: the on-device console `run` — hand the dexed program to the injected [DexRunner]. Always runs
 * (an [AlwaysRun], like [JavaExecTask]); the runner streams output and returns the exit code.
 */
class DexExecTask(
    override val name: TaskName,
    private val mainClass: String,
    private val dexDir: Path,
    private val programArgs: List<String>,
    private val runner: DexRunner,
    private val programIo: ProgramIo? = null,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.logger()("> Run (dex) $mainClass")
        val io = programIo ?: LoggingProgramIo(ctx.logger())
        val code = runner.run(dexDir, mainClass, programArgs, io)
        return if (code == 0) TaskResult.Success else TaskResult.Failed("$mainClass exited with code $code")
    }
}
