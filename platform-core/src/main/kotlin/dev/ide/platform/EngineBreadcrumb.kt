package dev.ide.platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * A crash breadcrumb for the editor engine: the single op the `ide-engine` worker was running, persisted to a
 * tiny file so it survives an UNCATCHABLE native SIGSEGV (the 32-bit-ARM-ART crash; see [RuntimeInfo]) and can
 * be read by the NEXT process on relaunch.
 *
 * ## Why this exists
 * The crash is a native fault (`SEGV_MAPERR`) below the JVM — no Java exception is thrown, so the in-process
 * error dialog / uncaught-handler never sees it, and the process is gone before anything can log. The only way
 * to learn what the engine was doing is to write it down BEFORE the op runs and read it back after the restart.
 * Correlated with the OS's own "why did the last process die" (`ActivityManager.getHistoricalProcessExitReasons`
 * → `REASON_CRASH_NATIVE`), a leftover breadcrumb pins the crash to a specific engine activity (completion vs.
 * analysis vs. preview), which is exactly the signal weeks of blind fixing has lacked.
 *
 * ## What it records — and deliberately does NOT
 * Only a COARSE op label (the engine lane: `interactive`/`background`/`preview`), the thread name, and a
 * timestamp. NO file names, paths, or source content — the label is safe to ship to opt-in analytics, which is
 * PERFORMANCE/CRASH-only and must never carry user content. The lane already answers the diagnostic question
 * (which editor activity was in flight); precise file/offset would only add user content for little extra signal.
 *
 * ## Durability & cost
 * A plain [Files.write] puts the bytes in the OS page cache immediately; a process SIGSEGV does NOT lose them
 * (the kernel flushes), so no `fsync` is needed — only a kernel panic / power loss would. The write happens on
 * the engine thread at op start (before the op body that may crash), so the file always holds the crashing op,
 * fully written. A single ~50-byte overwrite is sub-millisecond against an op that already costs ms; no throttle.
 *
 * Best-effort throughout: every path swallows I/O errors, and [record] is a no-op until [init] is called (so the
 * desktop launcher and tests, which never arm it, pay nothing).
 */
object EngineBreadcrumb {

    // The file to write; null until a launcher arms it via [init]. @Volatile: armed on the bootstrap thread,
    // read/written on the engine thread.
    @Volatile
    private var file: Path? = null

    /** Arm the breadcrumb at [path] (an app-global location the launcher owns). Call once at bootstrap, BEFORE
     *  any engine op runs; reading the previous session's crumb ([readLast]) must happen before the first
     *  [record] overwrites it. Idempotent. */
    fun init(path: Path) {
        file = path
    }

    /**
     * Record [op] as the engine op now running. Called from the engine worker at op start. Overwrites the file
     * with `epochMillis \t threadName \t op`. No-op if not [init]-ed; never throws.
     */
    fun record(op: String) {
        val f = file ?: return
        val line = "${System.currentTimeMillis()}\t${Thread.currentThread().name}\t$op"
        runCatching { Files.write(f, line.toByteArray(Charsets.UTF_8)) }
    }

    /** Clear the breadcrumb on a CLEAN engine shutdown, so a file that survives to the next launch means the
     *  process did NOT exit cleanly (it crashed or was killed). Best-effort. */
    fun clear() {
        file?.let { runCatching { Files.deleteIfExists(it) } }
    }

    /** The last recorded op (the previous session's, when read at launch before the first [record]), or null if
     *  unarmed / absent / malformed. */
    fun readLast(): Crumb? {
        val f = file ?: return null
        return runCatching {
            if (!Files.exists(f)) return null
            val parts = Files.readAllBytes(f).toString(Charsets.UTF_8).trim().split('\t', limit = 3)
            if (parts.size < 3) return null
            val ts = parts[0].toLongOrNull() ?: return null
            Crumb(ts, parts[1], parts[2])
        }.getOrNull()
    }

    /** A recorded breadcrumb: when it ran ([epochMillis]), on which [thread], and the coarse [op] label. */
    data class Crumb(val epochMillis: Long, val thread: String, val op: String)
}
