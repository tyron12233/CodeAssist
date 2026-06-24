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
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * On-device [DexRunner]: runs a dexed console program in-process via [dalvik.system.DexClassLoader] — the
 * ART counterpart to forking `java` (impossible on a device). It loads `classes*.dex` from the dex dir,
 * resolves `static void main(String[])` (or a no-arg `main()` — Kotlin's top-level `fun main()`), redirects
 * `System.out`/`System.err`/`System.in` to the run's [ProgramIo] for the duration of the run, and returns
 * the exit code. The dex is built by `:build-engine`'s `dexRun` task (D8 in-process), so this is the launch
 * half of `JavaBuildSystem.createDexRunGraph`.
 *
 * In-process execution shares the IDE process — the cost of the [DexRunner] approach:
 *  - `System.exit`/`Runtime.exit`/`Runtime.halt` are rewritten at dex time (build-engine's `ExitGuard`)
 *    to throw [ControlledExit], which this runner catches to end the run with that code — so a program's
 *    exit can't terminate the IDE (a `SecurityManager` trap is unsupported on ART). A native exit (e.g. via
 *    JNI) is still uncatchable, but that's vanishingly rare for the programs this runs.
 *  - stdout/stderr/stdin are process-global, so they're restored in `finally`; runs are sequential, so two
 *    runs never race on them. Output from other threads during the brief run is also captured.
 */
class DexClassLoaderRunner(private val cacheDir: File) : DexRunner {

    override suspend fun run(
        dexDir: Path, mainClass: String, args: List<String>, io: ProgramIo
    ): Int = withContext(Dispatchers.IO) {
        val log: (String) -> Unit = { line -> io.stdout(line + "\n") }
        val dexes = if (Files.isDirectory(dexDir)) Files.walk(dexDir).use { s ->
            s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList())
        }
        else emptyList()

        if (dexes.isEmpty()) {
            log("No dex to run."); return@withContext 1
        }

        val madeReadOnlyDexes = dexes.map { path ->
            val file = path.toFile()

            val madeReadOnly = runCatching {
                file.setWritable(false, false)
            }.getOrDefault(false)

            return@map file to madeReadOnly
        }

        val hasErrors = madeReadOnlyDexes.any { !it.second }
        if (hasErrors) {
            val files = madeReadOnlyDexes.filter { !it.second }
                .joinToString(",") { it.first.path }
            log("Fatal: Cannot make dex file(s) read-only $files")
            return@withContext 1
        }

        val dexPath = dexes.joinToString(File.pathSeparator) { it.toString() }
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
        val main = (runCatching { clazz.getDeclaredMethod("main", Array<String>::class.java) }.getOrNull()
            ?: runCatching { clazz.getDeclaredMethod("main") }.getOrNull())
            ?.also { it.isAccessible = true }
            ?: run {
                log("Cannot find $mainClass.main(String[]) or $mainClass.main()")
                return@withContext 1
            }
        val noArg = main.parameterCount == 0

        val sink = ChunkStream(io)
        val printer = PrintStream(sink, true, "UTF-8")
        val origOut = System.out
        val origErr = System.err
        val origIn = System.`in`
        var code = 0
        try {
            System.setOut(printer); System.setErr(printer); System.setIn(io.stdin)
            io.started()
            runInterruptible { if (noArg) main.invoke(null) else main.invoke(null, args.toTypedArray()) }
        } catch (e: InvocationTargetException) {
            when (val cause = e.targetException) {
                is ControlledExit -> code =
                    cause.code   // instrumented System.exit / Runtime.exit|halt
                null -> code = 1
                else -> {
                    code = 1; logThrowable(cause, log)
                }
            }
        } catch (e: ControlledExit) {
            code = e.code
        } catch (e: InterruptedException) {
            code = 130; log("Run interrupted.")
        } catch (t: Throwable) {
            code = 1; logThrowable(t, log)
        } finally {
            printer.flush(); sink.flushTail()
            System.setOut(origOut); System.setErr(origErr); System.setIn(origIn)
        }
        io.exited(code)
        code
    }

    private fun logThrowable(t: Throwable, log: (String) -> Unit) {
        log("Exception in thread \"main\" $t")
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
}
