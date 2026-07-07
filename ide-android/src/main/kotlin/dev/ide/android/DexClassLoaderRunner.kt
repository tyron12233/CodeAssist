package dev.ide.android

import dev.ide.build.engine.ControlledExit
import dev.ide.build.engine.DexRunner
import dev.ide.build.engine.ProgramIo
import dev.ide.build.engine.StreamingTextDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.nio.file.Path

/**
 * On-device [DexRunner]: runs a dexed console program in-process via [dalvik.system.DexClassLoader] — the
 * ART counterpart to forking `java` (impossible on a device). It stages `classes*.dex` into internal
 * storage (via [DexStaging], the writable-dex fix), resolves `static void main(String[])` (or a no-arg
 * `main()` — Kotlin's top-level `fun main()`), redirects `System.out`/`System.err`/`System.in` to the run's
 * [ProgramIo] for the duration of the run, and returns the exit code. The dex is built by `:build-engine`'s
 * `dexRun` task (D8 in-process), so this is the launch half of `JavaBuildSystem.createDexRunGraph`.
 *
 * This is the FALLBACK runner, used when no `dalvikvm` launcher is available to fork ([ForkedDalvikRunner]
 * is preferred). Its run dex is instrumented ([DexRunner.isolatedProcess] = false) because it shares the
 * host process:
 *
 *  - `System.exit`/`Runtime.exit`/`Runtime.halt` are rewritten at dex time (build-engine's `ExitGuard`)
 *    to throw [ControlledExit], which this runner catches to end the run with that code — so a program's
 *    exit can't terminate the IDE (a `SecurityManager` trap is unsupported on ART). A native exit (e.g. via
 *    JNI) is still uncatchable, but that's vanishingly rare for the programs this runs.
 *  - The program's `main` runs on a **dedicated thread** with a generous stack, so a deeply-recursive
 *    program earns a clean, catchable [StackOverflowError] (reported, not an IDE crash) rather than
 *    overflowing a shared coroutine dispatcher thread. [OutOfMemoryError] and any other throwable are
 *    likewise caught and surfaced as a failed run.
 *  - stdout/stderr/stdin are process-global, so they're restored in `finally`; runs are sequential, so two
 *    runs never race on them. Output from other threads during the brief run is also captured.
 *  - `System.exit`/`Runtime.exit`/`Runtime.halt` are rewritten at dex time (build-engine's `ExitGuard`)
 *    to throw [ControlledExit], which this runner catches to end the run with that code — so a program's
 *    exit can't terminate the IDE (a `SecurityManager` trap is unsupported on ART). A native exit (e.g. via
 *    JNI) is still uncatchable, but that's vanishingly rare for the programs this runs.
 *  - The program's `main` runs on a **dedicated thread** with a generous stack, so a deeply-recursive
 *    program earns a clean, catchable [StackOverflowError] (reported, not an IDE crash) rather than
 *    overflowing a shared coroutine dispatcher thread. [OutOfMemoryError] and any other throwable are
 *    likewise caught and surfaced as a failed run.
 *  - stdout/stderr/stdin are process-global, so they're restored in `finally`; runs are sequential, so two
 *    runs never race on them. Output from other threads during the brief run is also captured.
 */
class DexClassLoaderRunner(private val cacheDir: File) : DexRunner {

    override suspend fun run(
        dexDir: Path, mainClass: String, args: List<String>, io: ProgramIo
    ): Int = withContext(Dispatchers.IO) {
        val log: (String) -> Unit = { line -> io.stdout(line + "\n") }
        val sources = DexStaging.collectDexes(dexDir)

        if (sources.isEmpty()) {
            log("No dex to run."); return@withContext 1
        }

        // Stage the dex onto internal storage and make it read-only THERE — see [DexStaging]: the build
        // output lives on external (FUSE) storage where clearing the write bit is a no-op and ART rejects
        // the still-writable dex.
        val staged = DexStaging.stageReadOnly(File(cacheDir, "staged"), sources, log) ?: return@withContext 1

        val dexPath = staged.joinToString(File.pathSeparator) { it.absolutePath }
        val optimized = File(cacheDir, "dexrun-oat").apply { mkdirs() }
        val loader = try {
            dalvik.system.DexClassLoader(
                dexPath, optimized.absolutePath, null, javaClass.classLoader
            )
        } catch (t: Throwable) {
            log("Cannot load dex for $mainClass: ${t.message ?: t}")
            return@withContext 1
        }

        val clazz = try {
            loader.loadClass(mainClass)
        } catch (t: Throwable) {
            log("Cannot load class $mainClass: ${t.message ?: t}")
            return@withContext 1
        }
        // Prefer `main(String[])`; fall back to a no-arg `main()` (Kotlin top-level `fun main()`).
        val main =
            (runCatching { clazz.getDeclaredMethod("main", Array<String>::class.java) }.getOrNull()
                ?: runCatching { clazz.getDeclaredMethod("main") }.getOrNull())?.also {
                it.isAccessible = true
            } ?: run {
                log("Cannot find $mainClass.main(String[]) or $mainClass.main()")
                return@withContext 1
            }
        val noArg = main.parameterCount == 0
        // A static `main` is called with a null receiver; an INSTANCE `main` (a plain `class Test { fun main() }`)
        // is invoked on a fresh instance built from the class's no-arg constructor.
        val receiver: Any? = if (Modifier.isStatic(main.modifiers)) null else {
            runCatching {
                clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrElse { t ->
                log("Cannot instantiate $mainClass to run its instance main(): ${t.message ?: t}")
                return@withContext 1
            }
        }

        val sink = ChunkStream(io)
        val printer = PrintStream(sink, true, "UTF-8")
        val origOut = System.out
        val origErr = System.err
        val origIn = System.`in`
        var code = 0
        try {
            System.setOut(printer); System.setErr(printer); System.setIn(io.stdin)
            io.started()
            // Run `main` on its own thread so a stack overflow in user code hits a generous, dedicated stack
            // (deep-but-finite recursion works; runaway recursion overflows cleanly and is caught below)
            // instead of exhausting a shared IO-dispatcher thread. The invoked method's thrown Throwable is
            // captured; `join()` after it publishes it safely (happens-before).
            val holder = MainOutcome()
            val programThread = Thread(null, {
                try {
                    if (noArg) main.invoke(receiver) else main.invoke(receiver, args.toTypedArray())
                } catch (t: Throwable) {
                    holder.thrown = t
                }
            }, "user-main", PROGRAM_STACK_BYTES).apply { isDaemon = true }
            programThread.start()
            try {
                // Cancellation (Stop) interrupts this join; runInterruptible surfaces it as a
                // CancellationException, which the inner finally acts on and then lets propagate.
                runInterruptible { programThread.join() }
            } finally {
                // On Stop (or any early unwind), nudge a still-running program to end. A busy loop that
                // ignores interrupts is left as a leaked daemon thread — it cannot take the IDE down, and the
                // console is finalized by BuildService.stopBuild regardless.
                if (programThread.isAlive) {
                    programThread.interrupt()
                    runCatching { programThread.join(2_000) }
                }
            }
            code = exitCodeFor(holder.thrown, log)
        } finally {
            printer.flush()
            sink.flushTail()
            System.setOut(origOut)
            System.setErr(origErr)
            System.setIn(origIn)
        }
        io.exited(code)
        code
    }

    /** Where the program's captured throwable is published from its own thread to the run coroutine. Read
     *  only after [Thread.join], so the JMM's join happens-before makes the plain field visible. */
    private class MainOutcome {
        var thrown: Throwable? = null
    }

    /** Map the throwable a program's `main` ended with to an exit code, logging a friendly reason. `main`
     *  runs via reflection, so a program's own throwable arrives wrapped in [InvocationTargetException]. */
    private fun exitCodeFor(thrown: Throwable?, log: (String) -> Unit): Int {
        val cause = (thrown as? InvocationTargetException)?.targetException ?: thrown
        return when (cause) {
            null -> 0
            is ControlledExit -> cause.code // instrumented System.exit / Runtime.exit|halt
            is StackOverflowError -> {
                log("Stack overflow: your program recursed too deeply (likely unbounded recursion).")
                logStack(cause, log)
                1
            }
            is OutOfMemoryError -> {
                // Don't try to build a big trace under memory pressure — a short note is safer and enough.
                log("Out of memory: your program requested more memory than this run allows.")
                1
            }
            else -> {
                logThrowable(cause, log)
                1
            }
        }
    }

    private fun logThrowable(t: Throwable, log: (String) -> Unit) {
        log("Exception in thread \"main\" $t")
        logStack(t, log)
    }

    private fun logStack(t: Throwable, log: (String) -> Unit) {
        t.stackTrace.take(20).forEach { log("\tat $it") }
    }

    /** Forwards the program's raw stdout/stderr to [ProgramIo.stdout] as it arrives (partial lines and
     *  prompts without a trailing newline included), decoding bytes with a multi-byte-safe carry-over. */
    private class ChunkStream(private val io: ProgramIo) : OutputStream() {
        private val decoder = StreamingTextDecoder()
        private val one = ByteArray(1)

        @Synchronized
        override fun write(b: Int) {
            one[0] = b.toByte()
            val s = decoder.decode(one, 0, 1)
            if (s.isNotEmpty()) io.stdout(s)
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            val s = decoder.decode(b, off, len)
            if (s.isNotEmpty()) io.stdout(s)
        }

        @Synchronized
        fun flushTail() {
            val s = decoder.flush()
            if (s.isNotEmpty()) io.stdout(s)
        }
    }

    private companion object {
        /** Stack size (bytes) for the program's `main` thread — a hint to ART. Generous enough for genuine
         *  deep recursion, still bounded so runaway recursion overflows quickly and is caught. */
        private const val PROGRAM_STACK_BYTES = 16L * 1024 * 1024
    }
}
