package dev.codeassist.ndk

import dev.ide.platform.log.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The resident clang (`libndkclangd.so`, tools/ndk-toolchain/daemon/ndkclangd.c), and how to talk to it.
 *
 * Launching clang per request paid a process start and a preamble load every time: ~17 of the ~22 ms of a
 * completion on the emulator, more on a phone. The daemon keeps each open file's translation unit and its
 * precompiled preamble in memory, so a request is a reparse of the user's own code against headers that are
 * already loaded. Its answers are in clang's command-line formats, so [ClangDiagnostics] and
 * [ClangCompletions] read them unchanged.
 *
 * **It is an optimisation, never a dependency.** Every method answers null when the daemon is not there
 * (a toolchain built before it existed), could not start, died, or answered an error, and the caller then
 * runs clang directly, exactly as before. After [MAX_DEATHS] deaths in one session it is not started again.
 *
 * One request at a time: the daemon is single-threaded and so is the pipe. A request whose coroutine was
 * cancelled while it waited for its turn is dropped before it is sent, which is what keeps a burst of
 * typing from queueing a check per keystroke. One already sent runs to the end -- killing the daemon to stop
 * it would throw away every file's preamble, which costs far more than letting it finish.
 *
 * It exits on its own when its stdin closes, and is stopped after [idleMillis] without a request so the
 * memory its translation units hold (~10 MB of preamble each) is not kept for a file nobody is editing.
 */
internal class ClangDaemon(
    private val executable: Path,
    private val libDir: Path,
    private val tmpDir: Path,
    private val log: Logger,
    private val idleMillis: Long = 10 * 60_000L,
) {
    /** What the daemon said: [ok] false when it answered `error`, with its reason in [payload]. */
    class Reply(val ok: Boolean, val payload: String)

    private var process: Process? = null
    private var input: InputStream? = null
    private var output: BufferedOutputStream? = null
    private var nextId = 0L
    private var deaths = 0
    private var idleStop: ScheduledFuture<*>? = null

    /**
     * Send one request and wait for its answer. Null when the caller should run clang itself instead: no
     * daemon, a dead one, a timeout, or a request cancelled before its turn ([cancelled] tells those apart).
     */
    fun request(
        command: String,
        path: String,
        text: String,
        args: List<String>,
        line: Int = 0,
        column: Int = 0,
        timeoutSeconds: Long = 20,
        cancellation: ToolCancellation? = null,
        /** Told how many milliseconds the request waited for the one before it to finish. */
        onTurn: (waitedMs: Long) -> Unit = {},
    ): Reply? {
        val queued = System.nanoTime()
        return synchronized(this) {
            onTurn((System.nanoTime() - queued) / 1_000_000)
            send(command, path, text, args, line, column, timeoutSeconds, cancellation)
        }
    }

    private fun send(
        command: String,
        path: String,
        text: String,
        args: List<String>,
        line: Int,
        column: Int,
        timeoutSeconds: Long,
        cancellation: ToolCancellation?,
    ): Reply? {
        if (deaths >= MAX_DEATHS) return null
        if (cancellation?.isCancelled == true) return null
        val process = ensureStarted() ?: return null
        val out = output!!
        val inp = input!!
        val id = (++nextId).toString()

        val timedOut = AtomicBoolean(false)
        val watchdog = WATCHDOG.schedule({
            timedOut.set(true)
            process.destroyForcibly()
        }, timeoutSeconds, TimeUnit.SECONDS)
        return try {
            val blobs = ArrayList<ByteArray>(2 + args.size)
            blobs += path.toByteArray()
            blobs += text.toByteArray()
            args.mapTo(blobs) { it.toByteArray() }
            out.write("$command $id $line $column ${blobs.size}\n".toByteArray())
            for (blob in blobs) {
                out.write("${blob.size}\n".toByteArray())
                out.write(blob)
            }
            out.flush()

            val header = readLine(inp) ?: throw IOException("the daemon closed its output")
            val parts = header.split(' ')
            if (parts.size != 3 || parts[0] != id) throw IOException("out of step with the daemon: '$header'")
            val length = parts[2].toIntOrNull() ?: throw IOException("bad length in '$header'")
            val payload = readExactly(inp, length)
            Reply(parts[1] == "ok", String(payload))
        } catch (e: IOException) {
            died(if (timedOut.get()) "timed out after ${timeoutSeconds}s" else e.message ?: e.toString())
            null
        } finally {
            watchdog.cancel(false)
            scheduleIdleStop()
        }
    }

    /** Stop the daemon now. The next request starts a fresh one. */
    @Synchronized
    fun stop() {
        process?.let { p ->
            runCatching { output?.close() } // EOF on its stdin: it disposes its units and exits
            if (!runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly()
        }
        process = null
        input = null
        output = null
    }

    private fun ensureStarted(): Process? {
        process?.let { if (it.isAlive) return it }
        if (!Files.isRegularFile(executable)) return null
        return try {
            Files.createDirectories(tmpDir)
            val p = ProcessBuilder(executable.toString())
                .apply {
                    environment()["LD_LIBRARY_PATH"] = libDir.toString()
                    // libclang may still want a scratch directory; the default /tmp does not exist here.
                    environment()["TMPDIR"] = tmpDir.toString()
                    // One line per request with the time it spent inside clang, which is what separates a slow
                    // clang from a slow trip to it when a popup is late on someone's phone.
                    environment()["NDKCLANGD_TIMING"] = "1"
                    // Its stderr is for its own complaints and timings, and must never fill a pipe nobody
                    // drains. Truncated at each start, so it holds one daemon's life and cannot grow forever.
                    redirectError(ProcessBuilder.Redirect.to(tmpDir.resolve("ndkclangd.log").toFile()))
                }
                .start()
            process = p
            input = BufferedInputStream(p.inputStream, 1 shl 16)
            output = BufferedOutputStream(p.outputStream, 1 shl 16)
            log.info("started the resident clang")
            p
        } catch (e: IOException) {
            died("could not start: ${e.message}")
            null
        }
    }

    private fun died(reason: String) {
        deaths++
        log.warn(
            "the resident clang stopped ($reason)" +
                if (deaths >= MAX_DEATHS) "; running clang per request for the rest of this session" else ""
        )
        process?.destroyForcibly()
        process = null
        input = null
        output = null
    }

    private fun scheduleIdleStop() {
        idleStop?.cancel(false)
        idleStop = WATCHDOG.schedule({ stop() }, idleMillis, TimeUnit.MILLISECONDS)
    }

    private fun readLine(inp: InputStream): String? {
        val bytes = ByteArrayOutputStream(32)
        while (true) {
            val b = inp.read()
            if (b < 0) return null
            if (b == '\n'.code) return bytes.toString()
            bytes.write(b)
        }
    }

    private fun readExactly(inp: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = inp.read(data, at, length - at)
            if (n < 0) throw IOException("the daemon closed its output mid-answer")
            at += n
        }
        return data
    }

    companion object {
        /** `libndkclangd.so`, packaged beside the driver. Absent from toolchains built before it existed. */
        const val LIB = "ndkclangd"

        /** Deaths in one session after which the daemon is left off: something about this device breaks it. */
        const val MAX_DEATHS = 3

        private val WATCHDOG: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ndk-daemon-watchdog").apply { isDaemon = true }
        }
    }
}
