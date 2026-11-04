package dev.codeassist.dart

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The Dart SDK's `frontend_server` for one Flutter app: the incremental compiler behind `flutter run`. It
 * stays running for the length of a run, so a hot reload recompiles only the libraries that changed.
 *
 * Spoken to over stdin, one command at a time (pkg/frontend_server/lib/frontend_server.dart): `compile <entry>`
 * for the first, full compile; `recompile <entry> <key>` then the changed files' URIs and `<key>` for a delta;
 * `accept` once the VM took a delta, `reject` when it did not; `reset` so the next recompile writes a full
 * kernel, which is how a hot restart gets one (`recompile-restart` is the web compiler's, and on the VM it
 * writes a delta, whose program has no `main` of its own). Each compile answers with `result <key>`, the
 * compiler's messages, and `<key> <dill> <errors>`.
 */
class FrontendServer(
    private val sdk: DartSdk,
    private val flutter: FlutterSdk,
    private val pkg: Path,
    val outputDill: Path,
    /**
     * Kernels to start from when [outputDill] does not exist yet, best first: another compile of this package
     * (the Run's, the preview pane's), then a plugin-wide one. See [warm].
     */
    private val seeds: List<Path> = emptyList(),
) : AutoCloseable {
    /** A compile's outcome: the kernel file (null when it failed outright), its error count, what it said. */
    class Result(val dill: Path?, val errors: Int, val messages: List<String>) {
        val ok: Boolean get() = dill != null && errors == 0
    }

    private var process: Process? = null
    private var lines = LinkedBlockingQueue<String>()

    /**
     * Whether this compiler started from a kernel ([outputDill] from a previous run, or a seed) rather than from
     * sources alone. It matters for memory far more than for time: a warm compiler of a Flutter app settles
     * around 375 MB, a cold one around 590 MB, and it keeps that for as long as it runs. Starting warm is safe
     * with any kernel of the same Flutter: the compiler checks each library it reuses against its source and
     * compiles again the ones that changed.
     */
    var warm = false
        private set

    fun start() {
        val runtime = sdk.runtime ?: throw IOException(DartSdk.NO_SDK)
        val compiler = sdk.frontendServer ?: throw IOException(DartSdk.NO_SDK)
        Files.createDirectories(outputDill.parent)
        // The compiler initializes from its own output when there is one; failing that, from a seed.
        val seed = if (Files.isRegularFile(outputDill)) null else seeds.firstOrNull { Files.isRegularFile(it) }
        warm = Files.isRegularFile(outputDill) || seed != null
        lines = LinkedBlockingQueue()
        val p = ProcessBuilder(
            listOf(runtime.toString()) + DartSdk.VM_TUNING + listOfNotNull(
                compiler.toString(),
            "--sdk-root", flutter.platformDir.toString() + "/",
            "--target=flutter",
            "--incremental",
            "--track-widget-creation",
            "--enable-asserts",
            "--packages=${pkg.resolve(DartPackages.PACKAGE_CONFIG)}",
            "--output-dill=$outputDill",
            "-Ddart.vm.profile=false",
            "-Ddart.vm.product=false",
            "--no-print-incremental-dependencies",
                seed?.let { "--initialize-from-dill=$it" },
            ),
        ).directory(pkg.toFile()).redirectErrorStream(true)
            .apply { environment().putAll(sdk.environment()) }
            .start()
        process = p
        val into = lines
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { into.put(it) }
            } catch (_: IOException) {
                // Closed by close().
            }
            into.put(EOF)
        }, "flutter-frontend-server").apply { isDaemon = true }.start()
    }

    /** The first, full compile of [entry] (`package:app/main.dart`). */
    fun compile(entry: String): Result {
        send("compile $entry\n")
        return result()
    }

    /** Recompile [entry] with [changed] (file URIs) invalidated: a delta holding only what changed. */
    fun recompile(entry: String, changed: List<String>): Result {
        val key = UUID.randomUUID().toString()
        send("recompile $entry $key\n" + changed.joinToString("") { "$it\n" } + "$key\n")
        return result()
    }

    /** A full kernel of the program as it is now, for a hot restart: `reset`, then a recompile. */
    fun recompileFull(entry: String, changed: List<String>): Result {
        send("reset\n")
        return recompile(entry, changed)
    }

    /** The VM took the last delta: it becomes the base of the next. */
    fun accept() = send("accept\n")

    /** The VM refused the last delta: the next recompile includes its changes again. */
    fun reject() {
        send("reject\n")
        result(expectDill = false)
    }

    override fun close() {
        val p = process ?: return
        runCatching { send("quit\n") }
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        process = null
    }

    private fun send(text: String) {
        val p = process ?: throw IOException("the compiler is not running")
        p.outputStream.write(text.toByteArray())
        p.outputStream.flush()
    }

    /**
     * Read one answer: everything up to `result <key>` is noise, then the messages until the key, then (for a
     * compile) `<key> <dill> <errors>`.
     */
    private fun result(expectDill: Boolean = true, timeoutSeconds: Long = 600): Result {
        val messages = ArrayList<String>()
        var key: String? = null
        while (true) {
            val line = lines.poll(timeoutSeconds, TimeUnit.SECONDS) ?: throw IOException("the compiler stopped answering")
            if (line === EOF) throw IOException("the compiler exited: ${messages.joinToString("\n").take(2000)}")
            if (key == null) {
                val at = line.indexOf("result ")
                if (at >= 0) key = line.substring(at + "result ".length).trim() else messages += line
                continue
            }
            if (line == key) {
                if (!expectDill) return Result(null, 0, messages)
                continue
            }
            if (line.startsWith("$key ")) {
                val rest = line.substring(key.length + 1)
                val space = rest.lastIndexOf(' ')
                val errors = rest.substring(space + 1).trim().toIntOrNull() ?: 1
                val dill = rest.substring(0, space.coerceAtLeast(0)).trim().takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
                return Result(dill?.takeIf { Files.isRegularFile(it) }, errors, messages)
            }
            if (!line.startsWith("+") && !line.startsWith("-")) messages += line
        }
    }

    private companion object {
        val EOF = String(charArrayOf('\u0000'))
    }
}
