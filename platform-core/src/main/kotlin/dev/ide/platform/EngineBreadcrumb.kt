// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * A crash breadcrumb for the editor engine: the single op the `ide-engine` worker was running, persisted to a
 * tiny file so it survives an UNCATCHABLE native SIGSEGV and can be read by the NEXT process on relaunch.
 *
 * ## Why this exists
 * The crash is a native fault (`SEGV_MAPERR`) below the JVM — no Java exception is thrown, so the in-process
 * error dialog / uncaught-handler never sees it, and the process is gone before anything can log. The only way
 * to learn what the engine was doing is to write it down BEFORE the op runs and read it back after the restart.
 * Correlated with the OS's own "why did the last process die" (`ActivityManager.getHistoricalProcessExitReasons`
 * → `REASON_CRASH_NATIVE`/`REASON_SIGNALED`), a leftover breadcrumb pins the crash to a specific engine activity.
 * First built for the 32-bit-ARM-ART fault (see [RuntimeInfo]), but the fleet shows the same native death on
 * 64-bit `arm64-v8a` devices too, where the 32-bit concurrency-collapse mitigation never runs — so this is NOT
 * a 32-bit-only signal and the recorded op must be fine-grained enough to localise it on either ABI.
 *
 * ## What it records — and deliberately does NOT
 * Two independent signals, each analytics-safe (no file names, paths, or source content):
 *  - the [record]ed engine op — a fine op label (`analysis`/`semantic`/`folding`/`signature`/`completion`/…),
 *    the recording thread name, and a timestamp; and
 *  - whether an index build was in flight ([noteIndexBuilding]) — the leading hypothesis for the residual
 *    native death is heavy background heap churn (the index build) running CONCURRENTLY with the editor thread,
 *    so knowing a build was live when the process died is the signal that confirms or refutes it.
 *
 * The op label answers "which editor activity", the index flag answers "was the index churning at the same
 * time" — a precise file/offset would only add user content for little extra signal.
 *
 * NOTE the recorded thread is the one that WROTE the crumb (always `ide-engine`, since [record] is driven from
 * the engine worker), not necessarily the thread that faulted — a native crash carries no Java thread. Read it
 * as "what the engine was doing", not "where the fault was".
 *
 * ## Durability & cost
 * A plain [Files.write] puts the bytes in the OS page cache immediately; a process SIGSEGV does NOT lose them
 * (the kernel flushes), so no `fsync` is needed — only a kernel panic / power loss would. The op write happens
 * on the engine thread at op start (before the op body that may crash), so the file always holds the crashing
 * op, fully written. A single ~50-byte overwrite is sub-millisecond against an op that already costs ms. The
 * index flag is touched only on a building↔idle transition (once per build, NOT per keystroke), so it is not a
 * hot path. The two signals live in SEPARATE files, each written by a single thread, so they never race.
 *
 * Best-effort throughout: every path swallows I/O errors, and [record]/[noteIndexBuilding] are no-ops until
 * [init] is called (so the desktop launcher and tests, which never arm it, pay nothing).
 */
object EngineBreadcrumb {

    // The engine-op file; null until a launcher arms it via [init]. @Volatile: armed on the bootstrap thread,
    // read/written on the engine thread.
    @Volatile
    private var file: Path? = null

    // The "an index build is in flight" flag file (a sibling of [file]); present ⇒ a build was live. Written
    // only by the index-status observer, on a transition — never concurrently with [record]'s writes to [file].
    @Volatile
    private var indexFlag: Path? = null

    /** Arm the breadcrumb at [path] (an app-global location the launcher owns). Call once at bootstrap, BEFORE
     *  any engine op runs; reading the previous session's crumb ([readLast]) must happen before the first
     *  [record] overwrites it. Idempotent. */
    fun init(path: Path) {
        file = path
        indexFlag = path.resolveSibling("index-build.active")
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

    /**
     * Mark whether an index build is currently in flight. Called on a building↔idle transition (the index
     * status observer), so a crumb surviving to the next launch with the flag present means the process died
     * while the index was churning — the signal for the concurrent-heap-churn hypothesis. No-op if not
     * [init]-ed; never throws.
     */
    fun noteIndexBuilding(active: Boolean) {
        val f = indexFlag ?: return
        runCatching { if (active) Files.write(f, ByteArray(0)) else Files.deleteIfExists(f) }
    }

    /** Clear both breadcrumb files on a CLEAN engine shutdown, so files that survive to the next launch mean
     *  the process did NOT exit cleanly (it crashed or was killed). Best-effort. */
    fun clear() {
        file?.let { runCatching { Files.deleteIfExists(it) } }
        indexFlag?.let { runCatching { Files.deleteIfExists(it) } }
    }

    /** The last recorded op (the previous session's, when read at launch before the first [record]), or null if
     *  unarmed / absent / malformed. When only the index flag survived (a native death during a startup/
     *  background index build, before any editor op ran) a synthetic crumb is returned so it is still reported,
     *  timestamped from the flag file and with [Crumb.indexBuilding] set. */
    fun readLast(): Crumb? {
        val f = file ?: return null
        val idxBuilding = indexFlag?.let { runCatching { Files.exists(it) }.getOrDefault(false) } ?: false
        val crumb = runCatching {
            if (!Files.exists(f)) return@runCatching null
            val parts = Files.readAllBytes(f).toString(Charsets.UTF_8).trim().split('\t', limit = 3)
            if (parts.size < 3) return@runCatching null
            val ts = parts[0].toLongOrNull() ?: return@runCatching null
            Crumb(ts, parts[1], parts[2], idxBuilding)
        }.getOrNull()
        if (crumb != null) return crumb
        if (idxBuilding) {
            val ts = indexFlag?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() } ?: return null
            return Crumb(ts, "?", "(index-only)", indexBuilding = true)
        }
        return null
    }

    /** A recorded breadcrumb: when it ran ([epochMillis]), on which [thread], the [op] label, and whether an
     *  index build was in flight ([indexBuilding]) when the process died. */
    data class Crumb(val epochMillis: Long, val thread: String, val op: String, val indexBuilding: Boolean = false)
}
