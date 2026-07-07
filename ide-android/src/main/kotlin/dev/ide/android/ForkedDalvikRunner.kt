package dev.ide.android

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
 * Invocation is `dalvikvm -cp <dexes> <mainClass> <args>`, which requires a static `main(String[])` — this
 * covers Java `public static void main` and Kotlin `fun main()`/`fun main(args)` (kotlinc emits the static
 * `main(String[])` bridge). A class whose only entry is a non-static instance `main` is not supported in
 * forked mode (dalvikvm reports it); such programs need the in-process fallback runner.
 *
 * @param cacheDir internal-storage scratch dir (e.g. `<internal cache>/dexrun-fork`) for the staged dex.
 */
class ForkedDalvikRunner(private val cacheDir: File) : DexRunner {

    override val isolatedProcess: Boolean get() = true

    /** True on any device with a usable `dalvikvm` — the caller uses this to choose this runner over the
     *  in-process fallback at startup. */
    fun available(): Boolean = R8ForkSupport.launcher() != null

    override suspend fun run(
        dexDir: Path, mainClass: String, args: List<String>, io: ProgramIo
    ): Int = withContext(Dispatchers.IO) {
        val log: (String) -> Unit = { line -> io.stdout(line + "\n") }
        val sources = DexStaging.collectDexes(dexDir)
        if (sources.isEmpty()) {
            log("No dex to run."); return@withContext 1
        }
        val launcher = R8ForkSupport.launcher() ?: run {
            log("No dalvikvm launcher available to run the program."); return@withContext 1
        }
        // Stage the dex read-only on internal storage — ART won't load a writable dex on the VM's classpath.
        val staged = DexStaging.stageReadOnly(File(cacheDir, "staged"), sources, log) ?: return@withContext 1
        val cp = staged.joinToString(File.pathSeparator) { it.absolutePath }
        val command = listOf(launcher, "-cp", cp, mainClass) + args

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
}
