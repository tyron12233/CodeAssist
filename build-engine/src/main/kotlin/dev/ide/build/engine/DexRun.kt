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
 * Dex backend for the console-run path. Supplied by the host (D8 in-process, from :android-support) — the
 * mirror of [JavaCompile]. Unlike a single monolithic dex of the whole runtime classpath (which re-dexes
 * `kotlin-stdlib` + every dependency on EVERY source edit — the old behavior and the dominant `dexRun` cost),
 * this lets the host dex the run's classpath SCOPE-AWARE and CACHED: immutable library jars are content-hash
 * cached (dexed once, reused across builds and projects), so an edit re-dexes only the changed user classes.
 */
fun interface RunDexBackend {
    /** Dex [request]'s runtime classpath into `request.outDex` — a flat dir of `.dex` files a `DexClassLoader`
     *  loads (multidex: the runner joins every `.dex` under it onto the load path). */
    fun dexForRun(request: RunDexRequest): DexResult
}

/**
 * A console run's dex request. [userClassDirs] (the module's own class output, incl. its `kotlin-classes`)
 * change on every edit and are re-dexed each build; [libJars] (stdlib + resolved dependency jars) are
 * immutable and content-hash cached. [instrument] is build-engine's run-sandbox bytecode rewrite
 * ([ExitGuard] + [SandboxGuard]) applied to BOTH scopes before dexing — passed as a function so the
 * Android-free engine keeps owning the sandbox while the host owns D8. [guardVersion] keys the library dex
 * cache, so a guard-logic change invalidates it (and so the run's INSTRUMENTED dex never aliases the
 * uninstrumented APK dex cache). [stagingDir] is scratch the backend may keep between builds.
 */
class RunDexRequest(
    val userClassDirs: List<Path>,
    val libJars: List<Path>,
    val minApi: Int,
    val instrument: (entryName: String, bytes: ByteArray) -> ByteArray,
    val guardVersion: String,
    val stagingDir: Path,
    val outDex: Path,
)

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
 * `dexRun`: dex the module's runtime classpath into [outDex]. Splits the classpath into the mutable user
 * class output (directories) and the immutable library jars, and hands both to the [RunDexBackend], which
 * content-hash caches the libraries so an edit re-dexes only the user classes. A normal incremental task at
 * the graph level too — re-dexes nothing when the whole runtime classpath is unchanged.
 */
class JavaDexTask(
    override val name: TaskName,
    private val runtimeClasspath: () -> List<Path>,
    private val minApi: Int,
    private val stagingDir: Path,
    private val outDex: Path,
    private val dexBackend: RunDexBackend,
) : Task {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        val cp = runtimeClasspath()
        dirPaths("classes", cp.filter { Files.isDirectory(it) })
        filePaths("libs", cp.filter { !Files.isDirectory(it) })
        property("minApi", minApi)
        // A guard change rewrites the instrumented dex, so re-run even when the classpath is unchanged.
        property("guardVersion", GUARD_VERSION)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("dex", outDex) }

    override suspend fun execute(ctx: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        ctx.checkCanceled()
        Files.createDirectories(stagingDir)
        Files.createDirectories(outDex)
        val cp = runtimeClasspath().filter { Files.exists(it) }
        if (cp.isEmpty()) return@withContext TaskResult.Failed("nothing to dex for run")
        // Instrument everything on the run's classpath (user classes AND libraries): ExitGuard so a program's
        // exit ends the run not the IDE, and SandboxGuard so its network/file/reflection/exec calls are
        // mediated by the permission broker. Both pre-scan and no-op classes that can't match, so this stays
        // cheap; for libraries it is paid once and cached with the dex.
        val request = RunDexRequest(
            userClassDirs = cp.filter { Files.isDirectory(it) },
            libJars = cp.filter { !Files.isDirectory(it) },
            minApi = minApi,
            instrument = { entry, bytes ->
                if (entry.endsWith(".class")) SandboxGuard.instrument(ExitGuard.instrument(bytes)) else bytes
            },
            guardVersion = GUARD_VERSION,
            stagingDir = stagingDir,
            outDex = outDex,
        )
        val r = dexBackend.dexForRun(request)
        r.log.forEach(ctx.logger())
        if (r.success) TaskResult.Success else TaskResult.Failed("dex (run) failed")
    }

    companion object {
        /** Identity of the run-sandbox instrumentation; part of the library dex cache key. */
        val GUARD_VERSION: String = "${ExitGuard.VERSION}.${SandboxGuard.VERSION}"
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
