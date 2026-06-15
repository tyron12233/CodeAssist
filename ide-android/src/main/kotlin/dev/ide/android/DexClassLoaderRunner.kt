package dev.ide.android

import dev.ide.build.engine.ControlledExit
import dev.ide.build.engine.DexRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-device [DexRunner]: runs a dexed console program in-process via [dalvik.system.DexClassLoader] — the
 * ART counterpart to forking `java` (impossible on a device). It loads `classes*.dex` from the dex dir,
 * resolves `static void main(String[])`, redirects `System.out`/`System.err` to the build console for the
 * duration of the run, and returns the exit code. The dex is built by `:build-engine`'s `dexRun` task
 * (D8 in-process), so this is the launch half of `JavaBuildSystem.createDexRunGraph`.
 *
 * In-process execution shares the IDE process — the cost of the [DexRunner] approach:
 *  - `System.exit`/`Runtime.exit`/`Runtime.halt` are rewritten at dex time (build-engine's `ExitGuard`)
 *    to throw [ControlledExit], which this runner catches to end the run with that code — so a program's
 *    exit can't terminate the IDE (a `SecurityManager` trap is unsupported on ART). A native exit (e.g. via
 *    JNI) is still uncatchable, but that's vanishingly rare for the programs this runs.
 *  - stdout/stderr are process-global, so other threads' output during the brief run is also captured.
 */
class DexClassLoaderRunner(private val cacheDir: File) : DexRunner {

    override suspend fun run(dexDir: Path, mainClass: String, args: List<String>, log: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val dexes = if (Files.isDirectory(dexDir))
                Files.walk(dexDir).use { s -> s.filter { it.toString().endsWith(".dex") }.sorted().toList() }
            else emptyList()
            if (dexes.isEmpty()) { log("No dex to run."); return@withContext 1 }

            val dexPath = dexes.joinToString(File.pathSeparator) { it.toString() }
            val optimized = File(cacheDir, "dexrun-oat").apply { mkdirs() }
            val loader = dalvik.system.DexClassLoader(dexPath, optimized.absolutePath, null, javaClass.classLoader)

            val main = try {
                loader.loadClass(mainClass).getDeclaredMethod("main", Array<String>::class.java).also { it.isAccessible = true }
            } catch (t: Throwable) {
                log("Cannot find $mainClass.main(String[]): ${t.message}")
                return@withContext 1
            }

            val sink = LineStream(log)
            val printer = PrintStream(sink, true, "UTF-8")
            val origOut = System.out
            val origErr = System.err
            var code = 0
            try {
                System.setOut(printer); System.setErr(printer)
                runInterruptible { main.invoke(null, args.toTypedArray()) }
            } catch (e: InvocationTargetException) {
                when (val cause = e.targetException) {
                    is ControlledExit -> code = cause.code   // instrumented System.exit / Runtime.exit|halt
                    null -> code = 1
                    else -> { code = 1; logThrowable(cause, log) }
                }
            } catch (e: ControlledExit) {
                code = e.code
            } catch (e: InterruptedException) {
                code = 130; log("Run interrupted.")
            } catch (t: Throwable) {
                code = 1; logThrowable(t, log)
            } finally {
                printer.flush(); sink.flushPartial()
                System.setOut(origOut); System.setErr(origErr)
            }
            code
        }

    private fun logThrowable(t: Throwable, log: (String) -> Unit) {
        log("Exception in thread \"main\" $t")
        t.stackTrace.take(20).forEach { log("\tat $it") }
    }

    /** Buffers bytes and emits one [log] line per '\n' (UTF-8, '\r' trimmed); [flushPartial] flushes a tail. */
    private class LineStream(private val log: (String) -> Unit) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        @Synchronized override fun write(b: Int) {
            if (b == '\n'.code) emit() else buf.write(b)
        }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            var start = off
            val end = off + len
            for (i in off until end) {
                if (b[i] == '\n'.code.toByte()) { buf.write(b, start, i - start); emit(); start = i + 1 }
            }
            if (start < end) buf.write(b, start, end - start)
        }
        @Synchronized fun flushPartial() { if (buf.size() > 0) emit() }
        private fun emit() { log(buf.toString("UTF-8").trimEnd('\r')); buf.reset() }
    }
}
