package dev.codeassist.ndk

import dev.ide.platform.log.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.io.path.writeText

/**
 * Precompiled preambles, one per distinct header set, built in the background and reused by every
 * completion and error check that shares it.
 *
 * **Never in the way.** A request whose preamble is not built yet runs the old way (a full parse, the same
 * cost as before this existed) and queues the build, so the first popup in a file is no slower than it was
 * and every one after it is ~10x faster. Builds run one at a time on their own thread, and the newest
 * request replaces any that is still waiting: typing a new `#include` changes the preamble on every
 * keystroke, and only the last of those is worth compiling.
 *
 * **Keyed on everything that changes what the preamble means**: its text, the language, the flags, the
 * directory quote-includes resolve from, and the toolchain build. clang additionally checks that no header
 * the preamble opened has changed since; when one has, it refuses the file ([isStale]), and the caller drops
 * it, re-runs without it and queues a rebuild.
 */
internal class PreambleCache(
    private val dir: Path,
    private val log: Logger,
    /** Compile [Request.text] (already written to `header`) into `pch`. */
    private val build: (request: Request, header: Path, pch: Path) -> NdkToolchain.ToolResult,
    private val maxEntries: Int = 4,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ndk-preamble").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    },
) {

    /** What to precompile: the preamble's text and everything that changes how it parses. */
    data class Request(
        val text: String,
        val cpp: Boolean,
        val flags: List<String>,
        /** The source file's directory: quote-includes and relative `-I` flags resolve from it. */
        val sourceDir: Path,
        /** Identifies the toolchain build, so an updated compiler never loads an older one's output. */
        val toolchainStamp: String,
    ) {
        val key: String by lazy {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in listOf(FORMAT, toolchainStamp, if (cpp) "c++" else "c", sourceDir.toString()) + flags + text) {
                digest.update(part.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            digest.digest().take(12).joinToString("") { "%02x".format(it) }
        }
    }

    /** A built preamble, ready to hand clang with `-include-pch` and `-preamble-bytes`. */
    data class Ready(val pch: Path, val bytes: Int)

    private val lock = Any()
    private var pending: Request? = null
    private var building: String? = null
    private var draining = false

    /** Keys whose build failed. Not retried until the preamble changes, which changes the key. */
    private val failed = LinkedHashSet<String>()

    init {
        // A build the process died in the middle of leaves a temporary behind, never a half-written .pch.
        runCatching {
            Files.createDirectories(dir)
            Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(TMP) }.forEach { Files.deleteIfExists(it) } }
        }
    }

    /**
     * The built preamble for [request], or null when there is none yet, in which case one is queued.
     * [bytes] is the preamble's length in UTF-8 bytes, which the caller already knows.
     */
    fun readyOrQueue(request: Request, bytes: Int): Ready? {
        val pch = pchFor(request.key)
        if (Files.isRegularFile(pch)) {
            // The newest-used entry is the last to be evicted.
            runCatching { Files.setLastModifiedTime(pch, FileTime.fromMillis(System.currentTimeMillis())) }
            return Ready(pch, bytes)
        }
        queue(request)
        return null
    }

    /** clang refused [request]'s preamble (a header it opened has changed): drop it and build it again. */
    fun rebuild(request: Request) {
        delete(request.key)
        synchronized(lock) { failed.remove(request.key) }
        queue(request)
    }

    private fun queue(request: Request) {
        synchronized(lock) {
            if (request.key in failed || request.key == building) return
            pending = request
            if (draining) return
            draining = true
        }
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) { draining = false; building = null; return }
                building = next.key
                next
            }
            runCatching { buildOne(request) }.onFailure { log.warn("could not build a preamble", it) }
        }
    }

    private fun buildOne(request: Request) {
        val key = request.key
        val pch = pchFor(key)
        if (Files.isRegularFile(pch)) return

        // The header stays beside its .pch for as long as the .pch exists: clang records it as an input of
        // the precompiled header and refuses the .pch if it is missing.
        val header = dir.resolve("$key.h")
        header.writeText(request.text)
        val tmp = dir.resolve("$key.pch$TMP")

        val started = System.nanoTime()
        val result = build(request, header, tmp)
        val ms = (System.nanoTime() - started) / 1_000_000

        if (!result.ok || !Files.isRegularFile(tmp)) {
            // Usually an error in the preamble itself (a header that does not exist). The full parse the
            // caller falls back to reports it on the line where it is; nothing to say here beyond the log.
            synchronized(lock) {
                failed.add(key)
                if (failed.size > MAX_FAILED) failed.remove(failed.first())
            }
            Files.deleteIfExists(tmp)
            Files.deleteIfExists(header)
            log.info("preamble not precompiled (${ms} ms): ${result.output.lineSequence().firstOrNull().orEmpty().take(200)}")
            return
        }
        Files.move(tmp, pch, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info("precompiled a preamble in $ms ms (${Files.size(pch) / 1024} KB)")
        evict()
    }

    /** Keep the [maxEntries] most recently used: each one is ~10 MB for a file including a few std headers. */
    private fun evict() {
        val entries = runCatching {
            Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".pch") }.toArray().map { it as Path } }
        }.getOrDefault(emptyList())
        if (entries.size <= maxEntries) return
        entries.sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            .drop(maxEntries)
            .forEach { delete(it.fileName.toString().removeSuffix(".pch")) }
    }

    private fun delete(key: String) {
        runCatching { Files.deleteIfExists(pchFor(key)) }
        runCatching { Files.deleteIfExists(dir.resolve("$key.h")) }
    }

    private fun pchFor(key: String): Path = dir.resolve("$key.pch")

    companion object {
        private const val TMP = ".tmp"

        /**
         * Bumped when the way a .pch is BUILT changes in a way its key does not otherwise see (2: built with
         * -fpch-instantiate-templates), so a cache from an older plugin is rebuilt rather than reused.
         */
        private const val FORMAT = "2"
        private const val MAX_FAILED = 32

        /**
         * True when clang refused a precompiled preamble rather than the user's code: a header it opened has
         * changed since, its source was deleted, or the flags no longer match. Every such message names the
         * file kind, and no diagnostic about ordinary code does.
         */
        fun isStale(output: String): Boolean =
            output.contains("AST file") || output.contains("PCH file") || output.contains("precompiled header")
    }
}
