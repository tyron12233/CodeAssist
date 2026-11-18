package dev.codeassist.csharp.sdk


import android.util.Log
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Keeps a .NET program running between requests ([CsServer] is the one there is).
 *
 * A fresh `csc` process takes seconds to start, nearly all of it the runtime preparing Roslyn's code;
 * the same work asked again of a running process costs only the work.
 *
 * Roslyn's own `VBCSCompiler` server is not used because its named pipe
 * fails to bind on a device with "Permission denied". A plain program reading stdin does not need one.
 *
 * Communication uses stdin and stdout:
 * - The request is the path to a response file containing one argument per line.
 * - The response contains the program's output followed by `@@CS-DONE@@ <exit code>`.
 *
 * If the program cannot be built or started, the caller falls back (to a regular `csc` process for a
 * compile). A failure here therefore affects performance, not correctness.
 * Once marked unavailable, the program is not retried during the current process.
 *
 * Cancelling a request leaves the program running while its response is pending.
 * If the program itself is terminated, it starts again on the next request. It is also stopped after
 * [IDLE_MS] without a request, so its memory is not held while the user does something else.
 */
internal open class ResidentProgram(
    /** Name used in logs, thread names and request filenames. */
    private val label: String,

    /** Program name used for its source, DLL and runtime configuration files. */
    private val fileName: String,

    /** Directory under the plugin's home where the program is built. */
    private val folder: String,

    /** C# source. Any change, including whitespace, triggers a rebuild. */
    private val source: String,

    /** Additional assemblies required when compiling the program. */
    private val extraReferences: (DotnetSdk) -> List<Path> = { emptyList() },
) {

    /** A running process, its stdin writer and the lines received from stdout. */
    private class Running(
        val process: Process,
        val input: BufferedWriter,
        val output: LinkedBlockingQueue<String>,
    )

    @Volatile
    private var running: Running? = null

    /** Process currently being started, so shutdown can terminate it. */
    @Volatile
    private var starting: Process? = null

    /** Set permanently by shutdown(). */
    @Volatile
    private var closed = false

    /** Number of replies still expected from cancelled requests. */
    private var owed = 0

    /** Prevents further attempts after a permanent startup or build failure. */
    private var unavailable = false

    /** Unique request number used in response filenames. */
    private var sequence = 0

    /** When the last request finished, or the program started, in `System.nanoTime` terms. */
    private var lastUsed = 0L

    /**
     * Sends a request to the program, starting it if necessary.
     *
     * [arguments] become the lines of the response file: what to do, then `csc`'s arguments.
     * [onLine] receives each line the program prints.
     *
     * Returns the exit code it reports, or null if the program could not be used.
     * Only one request can run at a time.
     */
    @Synchronized
    fun compile(
        sdk: DotnetSdk,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int? {
        if (unavailable || closed) return null

        running?.let {
            if (!it.process.isAlive) {
                Log.d(TAG, "$label: found dead (exit ${it.process.exitValue()}), starting another")
            }
        }

        val host = running?.takeIf { it.process.isAlive }
            ?: start(sdk, cancellation)
            ?: return null

        // Discard replies from previously cancelled requests before sending
        // another request. Otherwise, an old reply could be mistaken for ours.
        if (!drainOwed(host)) return null

        val response = sdk.home.resolve("tmp").resolve("$label-${++sequence}.rsp")

        try {
            Files.write(
                response,
                arguments.joinToString("\n").toByteArray(Charsets.UTF_8),
            )

            host.input.write(response.toString())
            host.input.newLine()
            host.input.flush()
            owed++
        } catch (e: Exception) {
            Log.d(TAG, "$label: could not send the request: $e")
            stop("send failed")
            return null
        }

        val deadline = System.nanoTime() + COMPILE_MS * 1_000_000

        try {
            while (true) {
                val line = host.output.poll(200, TimeUnit.MILLISECONDS)

                when {
                    line == null -> {
                        // Keep the host alive after cancellation. Its reply
                        // will be discarded before the next request.
                        if (cancellation.isCancelled) {
                            Log.d(TAG, "$label: request cancelled, host kept running (owed=$owed)")
                            return null
                        }

                        if (!host.process.isAlive || System.nanoTime() > deadline) {
                            stop("no reply (alive=${host.process.isAlive})")
                            return null
                        }
                    }

                    line == ENDED -> {
                        if (closed) return null

                        stop("ended while compiling")
                        return null
                    }

                    line.startsWith(DONE) -> {
                        owed--
                        return line.removePrefix(DONE).trim().toIntOrNull()
                    }

                    else -> onLine(line)
                }
            }
        } finally {
            lastUsed = System.nanoTime()
            runCatching { Files.deleteIfExists(response) }
        }
    }

    /**
     * Stops [host] if it is still the running program and has had no request for [IDLE_MS]. Returns true
     * when there is nothing left to watch. Holds the request lock, so it never stops a request in progress.
     */
    @Synchronized
    private fun stopIfIdle(host: Running): Boolean {
        if (running !== host) return true
        if (owed > 0 || System.nanoTime() - lastUsed < IDLE_MS * 1_000_000) return false

        stop("idle for ${IDLE_MS / 60_000} min")
        return true
    }

    /**
     * Builds and starts the program in advance.
     *
     * This avoids making the first real request wait for compilation and
     * startup. The method blocks until the host is ready, so call it from
     * a background thread.
     *
     * Does nothing if the host is already running or unavailable.
     */
    @Synchronized
    fun warm(sdk: DotnetSdk) {
        if (unavailable || closed || running?.process?.isAlive == true) return

        start(sdk, ToolCancellation())
    }

    /**
     * Permanently shuts down this program.
     *
     * This method does not acquire the compile lock because compile() may
     * hold it while waiting for a response.
     *
     * A request in progress will detect that its process has ended.
     * A process being started is also terminated, even if start() has not
     * finished assigning it to [running].
     */
    fun shutdown() {
        if (closed) return

        closed = true

        // start() checks closed both before and after assigning running.
        // This ensures that either shutdown() or start() terminates the process.
        val host = running
        val pending = starting

        Log.d(
            TAG,
            "$label: shutting down (running=${host != null}, starting=${pending != null})",
        )

        host?.process?.destroyForcibly()
        pending?.destroyForcibly()
    }

    /** Discards responses left over from cancelled requests. */
    private fun drainOwed(host: Running): Boolean {
        if (owed == 0) return true

        Log.d(TAG, "$label: waiting for $owed reply(ies) to cancelled requests")

        val deadline = System.nanoTime() + COMPILE_MS * 1_000_000

        while (owed > 0) {
            val line = host.output.poll(200, TimeUnit.MILLISECONDS)

            when {
                line == null -> {
                    if (!host.process.isAlive || System.nanoTime() > deadline) {
                        stop("drain: no reply")
                        return false
                    }
                }

                line == ENDED -> {
                    stop("drain: ended")
                    return false
                }

                line.startsWith(DONE) -> {
                    owed--
                    Log.d(TAG, "$label: dropped the reply to a cancelled request")
                }
            }
        }

        return true
    }

    /** Terminates the current host and clears its pending request count. */
    private fun stop(reason: String) {
        Log.d(TAG, "$label: stopped ($reason)")

        running?.process?.destroyForcibly()
        running = null
        owed = 0
    }

    /**
     * Starts a daemon thread for process output or exit monitoring.
     */
    private fun startDaemon(name: String, body: () -> Unit) {
        Thread({ body() }, name).apply {
            isDaemon = true
        }.start()
    }

    /**
     * Builds the program if necessary, starts its process and waits for
     * the ready signal.
     *
     * Returns null if the build or startup fails.
     */
    private fun start(
        sdk: DotnetSdk,
        cancellation: ToolCancellation,
    ): Running? {
        val launcher = sdk.launcher ?: return null

        // A cancelled build is not a permanent failure.
        val dll = build(sdk, cancellation) ?: run {
            if (!cancellation.isCancelled) unavailable = true
            return null
        }

        if (closed) return null

        val startedAt = System.nanoTime()

        val process = try {
            ProcessBuilder(
                listOf(
                    launcher.toString(),
                    dll.toString(),
                    sdk.csc.parent.toString(),
                ),
            )
                .directory(sdk.home.toFile())
                .apply { environment().putAll(sdk.environment()) }
                .start()
        } catch (e: Exception) {
            Log.d(TAG, "$label: did not start: $e")
            unavailable = true
            return null
        }

        starting = process

        if (closed) {
            // shutdown() may have run before starting was assigned.
            process.destroyForcibly()
            starting = null
            return null
        }

        owed = 0

        // Log the exit code if the process terminates unexpectedly.
        startDaemon("cs-$label-exit") {
            val exitCode = runCatching {
                process.waitFor()
            }.getOrDefault(-1)

            Log.d(TAG, "$label: process exited with $exitCode")
        }

        val output = LinkedBlockingQueue<String>()

        // stdout carries the communication protocol.
        startDaemon("cs-$label-out") {
            try {
                process.inputStream.bufferedReader().forEachLine {
                    output.put(it)
                }
            } catch (_: Exception) {
                // The process has exited or its stream is closed.
            }

            output.put(ENDED)
        }

        // stderr contains runtime messages, not protocol responses.
        startDaemon("cs-$label-err") {
            try {
                process.errorStream.bufferedReader().forEachLine {
                    Log.d(TAG, "$label!> $it")
                }
            } catch (_: Exception) {
                // The process has exited or its stream is closed.
            }
        }

        val deadline = System.nanoTime() + START_MS * 1_000_000

        while (true) {
            val line = output.poll(200, TimeUnit.MILLISECONDS)

            if (line == READY) break

            if (
                line == ENDED ||
                (line == null && (cancellation.isCancelled || System.nanoTime() > deadline))
            ) {
                starting = null

                Log.d(
                    TAG,
                    "$label: never became ready${if (closed) " (shut down)" else ""}",
                )

                process.destroyForcibly()

                if (!cancellation.isCancelled && !closed) {
                    unavailable = true
                }

                return null
            }

            if (line != null) {
                Log.d(TAG, "$label: $line")
            }
        }

        Log.d(
            TAG,
            "$label: ready in ${(System.nanoTime() - startedAt) / 1_000_000} ms",
        )

        val input = BufferedWriter(
            OutputStreamWriter(process.outputStream, Charsets.UTF_8),
        )

        val host = Running(process, input, output)

        running = host
        starting = null
        lastUsed = System.nanoTime()

        startDaemon("cs-$label-idle") {
            while (true) {
                try {
                    Thread.sleep(IDLE_CHECK_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (stopIfIdle(host)) break
            }
        }

        if (closed) {
            // shutdown() may have read running before it was assigned.
            process.destroyForcibly()
            running = null
            return null
        }

        return host
    }

    /**
     * Compiles the host into <home>/<folder>/<fileName>.dll.
     *
     * Reuses the existing DLL when the source has not changed.
     * Its runtime configuration is refreshed independently so the program
     * can run with a newer bundled .NET runtime.
     *
     * The DLL lives beside its runtimeconfig.json rather than in Roslyn's
     * directory. The host resolves Roslyn's assemblies through the default
     * load context.
     */
    private fun build(
        sdk: DotnetSdk,
        cancellation: ToolCancellation,
    ): Path? {
        val launcher = sdk.launcher ?: return null

        val dir = sdk.home.resolve(folder)
        val sourceFile = dir.resolve("$fileName.cs")
        val dll = dir.resolve("$fileName.dll")

        val current = runCatching {
            String(Files.readAllBytes(sourceFile), Charsets.UTF_8)
        }.getOrNull()

        if (current == source && Files.isRegularFile(dll)) {
            // An unchanged source does not need recompilation.
            // Only refresh the runtime configuration if necessary.
            val refreshed = ensureRuntimeConfig(
                dir.resolve("$fileName.runtimeconfig.json"),
            )

            if (refreshed) {
                Log.d(TAG, "$label: runtimeconfig refreshed")
            }

            return dll
        }

        Files.createDirectories(dir)
        Files.write(sourceFile, source.toByteArray(Charsets.UTF_8))

        ensureRuntimeConfig(dir.resolve("$fileName.runtimeconfig.json"))
        Files.deleteIfExists(dll)

        val command = buildList {
            add(launcher.toString())
            add(sdk.csc.toString())
            add("-nologo")
            add("-noconfig")
            add("-target:exe")
            add("-out:$dll")

            sdk.references().forEach {
                add("-r:$it")
            }

            extraReferences(sdk).forEach {
                add("-r:$it")
            }

            add(sourceFile.toString())
        }

        val startedAt = System.nanoTime()

        return try {
            val process = ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(sdk.environment()) }
                .start()

            cancellation.attach(process)

            runCatching {
                process.outputStream.close()
            }

            val text = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Log.d(
                TAG,
                "$label: built in ${(System.nanoTime() - startedAt) / 1_000_000} ms, exit $exitCode",
            )

            if (exitCode != 0) {
                text.lines().forEach {
                    Log.d(TAG, "$label build> $it")
                }
            }

            if (exitCode == 0 && Files.isRegularFile(dll)) {
                dll
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "$label: could not build: $e")
            null
        }
    }


    // Protocol and timeouts

    private companion object {
        const val READY = "@@CS-READY@@"
        const val DONE = "@@CS-DONE@@ "
        const val ENDED = "@@CS-ENDED@@"

        const val START_MS = 30_000L
        const val COMPILE_MS = 180_000L

        /** How long the program may sit unused before it is stopped. */
        const val IDLE_MS = 5 * 60_000L
        const val IDLE_CHECK_MS = 30_000L
    }
}
