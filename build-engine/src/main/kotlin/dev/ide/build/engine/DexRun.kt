package dev.ide.build.engine

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Runs a dexed console program on ART: loads `dexDir`'s `classes*.dex` with a `DexClassLoader`, invokes
 * `mainClass.main(args)`, streams stdout/stderr to `log`, and returns the exit code. Injected by
 * :ide-android — ide-core (which also compiles for the desktop JVM) cannot reference `dalvik.system.*`.
 */
interface DexRunner {
    suspend fun run(dexDir: Path, mainClass: String, args: List<String>, log: (String) -> Unit): Int
}

/**
 * `dexRun`: dex the module's runtime classpath into [outDex]. Class directories are jarred into
 * [stagingDir] first (D8 reads jars/class files, not raw dirs); library jars pass through. A normal
 * incremental task — re-dexes only when the runtime classpath content changes.
 */
internal class JavaDexTask(
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
internal class DexExecTask(
    override val name: TaskName,
    private val mainClass: String,
    private val dexDir: Path,
    private val programArgs: List<String>,
    private val runner: DexRunner,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.logger()("> Run (dex) $mainClass")
        val code = runner.run(dexDir, mainClass, programArgs, ctx.logger())
        return if (code == 0) TaskResult.Success else TaskResult.Failed("$mainClass exited with code $code")
    }
}
