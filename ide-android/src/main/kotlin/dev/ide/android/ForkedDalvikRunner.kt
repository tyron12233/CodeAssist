package dev.ide.android

import android.content.Context
import dev.ide.build.engine.DexRunner
import dev.ide.build.engine.ProgramIo
import dev.ide.build.engine.StreamingTextDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * On-device [DexRunner] that runs a dexed console program in a **forked `dalvikvm` process** — the ART
 * counterpart to the desktop's `java` fork (`JavaExecTask`), and the preferred alternative to the in-process
 * [DexClassLoaderRunner]. Because the program runs in its own OS process:
 *
 *  - **Truly stoppable:** a runaway/infinite-loop program is killed by `Process.destroyForcibly()` when the
 *    run is cancelled (Stop) — no busy loop is left burning a thread in the IDE/daemon.
 *  - **Fully isolated:** a crash, `System.exit`, `StackOverflowError`, or OOM ends only the fork. The run dex
 *    is therefore left UN-instrumented ([isolatedProcess] = true → `dexRun` skips ExitGuard/SandboxGuard), so
 *    `System.exit(n)` exits the fork with code `n` naturally, and the run needs no in-process permission
 *    sandbox — the process boundary is the sandbox.
 *
 * The dex is staged read-only on internal storage first ([DexStaging]) — ART refuses a writable dex on a
 * command-line VM's classpath (W^X), and the build output lives on external FUSE storage where clearing the
 * write bit is a no-op. The forked VM inherits this app's `BOOTCLASSPATH` (the Android framework + libcore),
 * so `-cp <staged dexes> <mainClass>` resolves `java.*`/`android.*` while the program's own classes and
 * dependency libraries come from the staged dex.
 *
 * **Entry-point launch.** `dalvikvm`'s built-in launcher can only start a *static* `main(String[])`, but the
 * Kotlin/JVM spec (plus this IDE's instance-`main` convenience) admits several other shapes — a no-arg
 * `@JvmStatic fun main()` (which has no `(String[])` bridge), a plain class's instance `main`, etc. So instead
 * of `dalvikvm -cp <dexes> <mainClass>`, the run is launched through the bundled [dev.ide.build.engine.ReflectiveMainLauncher]:
 * `dalvikvm -cp <dexes> dev.ide.build.engine.ReflectiveMainLauncher <mainClass> <args>`. The launcher resolves
 * and invokes the correct entry point reflectively (the same class the desktop `java` fork uses), so every
 * runnable form works uniformly. Its dex ships as an asset ([LAUNCHER_ASSET]) — the app's own copy is buried
 * in secondary dexes a bare `-cp <run dexes>` won't load — extracted here and appended to the run container.
 * If that asset can't be extracted the run falls back to the native `dalvikvm -cp <dexes> <mainClass>` path,
 * which still covers the common static `main(String[])` case.
 *
 * @param context the app context — used to extract the bundled launcher dex asset.
 * @param cacheDir internal-storage scratch dir (e.g. `<internal cache>/dexrun-fork`) for the staged dex.
 */
class ForkedDalvikRunner(private val context: Context, private val cacheDir: File) : DexRunner {

    override val isolatedProcess: Boolean get() = true

    /** True on any device with a usable `dalvikvm` — the caller uses this to choose this runner over the
     *  in-process fallback at startup. */
    fun available(): Boolean = R8ForkSupport.launcher() != null

    override suspend fun run(
        dexDir: Path, mainClass: String, args: List<String>, io: ProgramIo
    ): Int = withContext(Dispatchers.IO) {
        val log: (String) -> Unit = { line -> io.stdout(line + "\n") }
        val userDexes = DexStaging.collectDexes(dexDir)
        if (userDexes.isEmpty()) {
            log("No dex to run."); return@withContext 1
        }
        val launcher = R8ForkSupport.launcher() ?: run {
            log("No dalvikvm launcher available to run the program."); return@withContext 1
        }
        // Prepend the reflective entry-point launcher (see the class doc) so any Kotlin/JVM main shape runs, not
        // just a static main(String[]). Its dex is appended to the container LAST so the user dexes keep their
        // load-order indices (stable checksums → ART oat reuse). If the launcher asset is missing, fall back to
        // dalvikvm's native launcher, which still starts a static main(String[]).
        val launcherDexes = extractLauncherDexes()
        val useLauncher = launcherDexes.isNotEmpty()
        val sources = if (useLauncher) userDexes + launcherDexes else userDexes
        // Stage the whole multidex set into ONE read-only container zip on internal storage. Read-only because
        // ART won't load a writable dex on the VM's classpath (W^X); a single container because passing every
        // `classes*.dex` as its own `-cp` path overflows the OS argument limit on a large dependency graph
        // ("Argument list too long" / E2BIG) — ART loads all of a zip's `classesN.dex` entries (multidex).
        val container = DexStaging.stageReadOnlyContainer(File(cacheDir, "staged/run.dex.jar"), sources, log)
            ?: return@withContext 1
        val command = if (useLauncher) {
            listOf(launcher, "-cp", container.absolutePath, LAUNCHER_CLASS, mainClass) + args
        } else {
            listOf(launcher, "-cp", container.absolutePath, mainClass) + args
        }

        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        io.started()
        // Kill the process the instant this run is cancelled (Stop). A program with no output blocks in a
        // native read that Thread.interrupt can't unblock, so the output loop alone wouldn't notice the
        // cancellation; this sibling coroutine wakes on cancellation and force-destroys the process — which
        // truly ends even a pure CPU busy loop — and then EOFs the reader so the run unwinds cleanly.
        val killer = launch { try { awaitCancellation() } finally { runCatching { process.destroyForcibly() } } }
        // Pump host stdin → the program's stdin on a daemon thread. ProgramIo.stdin blocks until the host
        // feeds input; on EOF/interrupt the read returns -1 and we close the program's stdin.
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
        }, "dalvik-run-stdin-pump").apply { isDaemon = true; start() }

        var code = -1
        try {
            // Forward raw output as it arrives (no line buffering, so prompts show immediately). runInterruptible
            // makes the blocking read cancellable — Stop then propagates as a CancellationException the finally
            // acts on (the killer has already destroyed the process, EOFing this read).
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
        } finally {
            killer.cancel() // stop the watcher so this scope can complete on the normal path
            pump.interrupt()
            runCatching { process.destroyForcibly() }
        }
        io.exited(code)
        code
    }

    /**
     * Extract the bundled [ReflectiveMainLauncher] dex from `assets/`[LAUNCHER_ASSET] into internal storage and
     * return its `.dex` file(s). Marker-guarded by the app's `lastUpdateTime`, so a new APK re-extracts; cached
     * otherwise. Only READ from here (its bytes are copied into the read-only run container, which is the file
     * ART actually loads), so — unlike the run container — these staged dexes need not be made read-only.
     * Returns empty on any failure; the caller then falls back to dalvikvm's native launcher.
     */
    private fun extractLauncherDexes(): List<Path> {
        val dir = File(cacheDir, "launcher")
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L).toString()
        val marker = File(dir, ".extracted")
        fun dexes(): List<Path> =
            dir.listFiles { f -> f.name.endsWith(".dex") }?.sortedBy { it.name }?.map { it.toPath() } ?: emptyList()
        dexes().takeIf { marker.exists() && marker.readText() == stamp && it.isNotEmpty() }?.let { return it }
        dir.mkdirs()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return runCatching {
            context.assets.open(LAUNCHER_ASSET).use { ins ->
                ZipInputStream(ins.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(".dex")) {
                            File(dir, File(e.name).name).outputStream().use { out -> zis.copyTo(out) }
                        }
                        e = zis.nextEntry
                    }
                }
            }
            marker.writeText(stamp)
            dexes()
        }.getOrDefault(emptyList())
    }

    private companion object {
        /** The dexed [dev.ide.build.engine.ReflectiveMainLauncher] asset (built by `:ide-android`'s
         *  `bundleReflectiveLauncherDex`), extracted onto the forked VM's run classpath. */
        const val LAUNCHER_ASSET = "reflective-launcher.dex.zip"

        /** The launcher's FQN — invoked as `dalvikvm -cp <container> <LAUNCHER_CLASS> <mainClass> <args>`. */
        const val LAUNCHER_CLASS = "dev.ide.build.engine.ReflectiveMainLauncher"
    }
}
