// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * A scrubbed summary of the thread dump Android takes when an app stops responding (an ANR).
 *
 * ## Why this exists
 * An ANR is not a crash: nothing throws, so the in-process crash reporter never runs, and when the system
 * then kills the app the only record is the OS's process-death history. For a process that ended with
 * `REASON_ANR`, `ApplicationExitInfo.getTraceInputStream()` (API 30+) hands the app the dump ART wrote when the
 * system asked it to, in the same text format as `/data/anr/traces.txt`: every thread's name, state and Java
 * stack. What the main thread was doing, and which thread holds the lock it waits for, is what localises an
 * ANR, and Play counts ANRs against the app separately from crashes.
 *
 * This keeps those two threads and drops the rest. Java frames are kept only for code that holds no user data
 * ([SAFE_PREFIXES]: this app, the platform and the libraries it bundles; the user's own program runs on the
 * interpreter VM, never as host frames), and a run of any other frames collapses to `… (n frames)`, the same
 * rule `CrashScrub` applies to exceptions. Native frames are reduced to `libname.so!symbol`, so no install
 * path is reported. Lock addresses, register state, thread ids and CPU accounting are never carried over.
 *
 * ## Parsing
 * Line-oriented and tolerant: an unrecognised line is skipped, and a dump cut short by [MAX_BYTES] reports the
 * threads read before the cut. The format is the one `art/runtime/thread.cc` (`Thread::Dump`) and
 * `art/runtime/monitor.cc` write, pinned by `AnrTraceTest`.
 */
data class AnrTrace(
    /** The main thread, or null when the dump has none (a truncated or foreign file). */
    val main: ThreadDump?,
    /** The thread holding the monitor the main thread is blocked on, when it is blocked on one. */
    val lockHolder: ThreadDump?,
    /** How many threads the dump listed. A very large number is itself a finding (a thread leak). */
    val threadCount: Int,
) {

    /** One thread of the dump, scrubbed. */
    data class ThreadDump(
        /** The thread's name, for example `main`, `ide-engine`, `DefaultDispatcher-worker-3`. */
        val name: String,
        /** ART's thread state: `Runnable`, `Blocked`, `Waiting`, `TimedWaiting`, `Sleeping`, `Native`, ... */
        val state: String,
        /** Java frames, innermost first, as `pkg.Class.method:line` (`-2` for a native method, `-1` unknown),
         *  with runs of frames outside [SAFE_PREFIXES] collapsed to `… (n frames)`. */
        val frames: List<String>,
        /** Native frames, innermost first, as `libname.so!symbol`. */
        val nativeFrames: List<String>,
        /** The innermost frame of this app's own code anywhere on the stack, even past the [frames] cap. */
        val firstOwnFrame: String?,
        /** The id (within the dump) of the thread holding the monitor this one waits to lock. Not reported. */
        internal val waitingOnTid: Int?,
    )

    companion object {
        /** How much of a dump to read. A dump lists every thread, so a busy IDE's can run to megabytes; the main
         *  thread comes first and a lock holder is rarely far behind. */
        const val MAX_BYTES: Int = 2 * 1024 * 1024

        /** Java frames kept per thread (after collapsing), and native frames. */
        const val MAX_FRAMES: Int = 14
        const val MAX_NATIVE_FRAMES: Int = 4

        /** Package prefixes whose frames are safe to report: this app, the platform, and bundled libraries. */
        val SAFE_PREFIXES: List<String> = listOf(
            "dev.ide.",
            "androidx.", "android.", "java.", "javax.", "kotlin.", "kotlinx.", "org.jetbrains.", "com.google.",
            "com.android.", "dalvik.", "sun.", "libcore.", "jdk.internal.",
            // Bundled engines: the IntelliJ PSI, ECJ, fastutil, JGit.
            "com.intellij.", "org.eclipse.", "it.unimi.dsi.", "org.objectweb.asm.",
        )

        private const val OWN_PREFIX = "dev.ide."
        private const val MAX_NAME = 64

        private val THREAD_HEADER = Regex("""^"(.*)"(?:\s.*)?\stid=(\d+)\s+(\S+)""")
        private val JAVA_FRAME = Regex("""^\s+at\s+([^\s(]+)\((.*)\)\s*$""")
        private val WAITING_TO_LOCK = Regex("""^\s+- waiting to lock .*held by thread (\d+)""")
        private val NATIVE_FRAME = Regex("""^\s+native:\s+#\d+\s+pc\s+[0-9a-fA-F]+\s+(\S+)(.*)$""")
        private val BUILD_ID = Regex("""\s*\(BuildId:[^)]*\)\s*$""")
        private val OFFSET = Regex("""\+\d+$""")

        /** Parse a dump. Returns null when nothing in it looks like a thread. Never throws. */
        fun parse(input: InputStream, limit: Int = MAX_BYTES): AnrTrace? =
            runCatching { decode(String(readUpTo(input, limit), Charsets.UTF_8)) }.getOrNull()

        /** Parse a dump already in memory. */
        fun parse(text: String): AnrTrace? = runCatching { decode(text) }.getOrNull()

        /**
         * A coarse kind for the ANR from the OS's description of it, so a report says what timed out without
         * carrying the description itself (which names windows and components).
         */
        fun kindOf(description: String?): String {
            val d = description?.lowercase() ?: return "unknown"
            return when {
                "input dispatching timed out" in d || "no focused window" in d -> "input"
                "broadcast of intent" in d -> "broadcast"
                "startforegroundservice" in d -> "foreground_service"
                "executing service" in d || ("service" in d && "timed out" in d) -> "service"
                "contentprovider" in d || "content provider" in d -> "provider"
                "job service" in d || "jobscheduler" in d -> "job"
                else -> "other"
            }
        }

        /** True when the OS marked the ANR as happening in the background, which Play does not count as
         *  user-perceived. The description starts `bg anr:` for those. */
        fun isBackground(description: String?): Boolean =
            description?.trimStart()?.lowercase()?.startsWith("bg anr") == true

        private fun readUpTo(input: InputStream, limit: Int): ByteArray {
            val raw = input.readNBytesCompat(limit)
            // The system stores these compressed; the stream normally inflates them, but not on every build.
            if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                return runCatching {
                    GZIPInputStream(ByteArrayInputStream(raw)).use { it.readNBytesCompat(limit) }
                }.getOrDefault(raw)
            }
            return raw
        }

        /** `InputStream.readNBytes` is API 33 on Android; this is the same thing on every level. */
        private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val n = read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        private class Raw(val name: String, val tid: Int, val state: String) {
            val java = ArrayList<String>()
            val native = ArrayList<String>()
            var firstOwn: String? = null
            var waitingOn: Int? = null
            var collapsed = 0
        }

        private fun decode(text: String): AnrTrace? {
            val threads = ArrayList<Raw>()
            var cur: Raw? = null
            var processes = 0
            for (line in text.lineSequence()) {
                if (line.startsWith("----- pid ")) {
                    // A second process's dump (a combined traces file): only the first is this app's.
                    if (++processes > 1) break
                    continue
                }
                if (line.startsWith("----- end ")) break
                if (line.startsWith("\"")) {
                    cur?.let { finish(it); threads += it }
                    // An unattached native thread (`"x" prio=5 (not attached)`) has no tid or Java state.
                    cur = THREAD_HEADER.find(line)?.let { m ->
                        Raw(m.groupValues[1].take(MAX_NAME), m.groupValues[2].toInt(), m.groupValues[3])
                    }
                    continue
                }
                val t = cur ?: continue
                val java = JAVA_FRAME.find(line)
                if (java != null) {
                    addJavaFrame(t, java.groupValues[1], java.groupValues[2])
                    continue
                }
                val lock = WAITING_TO_LOCK.find(line)
                if (lock != null) {
                    if (t.waitingOn == null) t.waitingOn = lock.groupValues[1].toIntOrNull()
                    continue
                }
                val native = NATIVE_FRAME.find(line)
                if (native != null && t.native.size < MAX_NATIVE_FRAMES) {
                    t.native += nativeFrame(native.groupValues[1], native.groupValues[2])
                }
            }
            cur?.let { finish(it); threads += it }
            if (threads.isEmpty()) return null

            val main = threads.firstOrNull { it.tid == 1 } ?: threads.firstOrNull { it.name == "main" }
            val holder = main?.waitingOn?.let { tid -> threads.firstOrNull { it.tid == tid && it !== main } }
            return AnrTrace(main?.toDump(), holder?.toDump(), threads.size)
        }

        private fun addJavaFrame(t: Raw, method: String, source: String) {
            val safe = SAFE_PREFIXES.any { method.startsWith(it) }
            if (t.firstOwn == null && method.startsWith(OWN_PREFIX)) t.firstOwn = "$method:${lineOf(source)}"
            if (t.java.size >= MAX_FRAMES) return
            if (!safe) {
                t.collapsed++
                return
            }
            if (t.collapsed > 0) {
                t.java += "… (${t.collapsed} frames)"
                t.collapsed = 0
                if (t.java.size >= MAX_FRAMES) return
            }
            t.java += "$method:${lineOf(source)}"
        }

        private fun finish(t: Raw) {
            if (t.collapsed > 0 && t.java.size < MAX_FRAMES) t.java += "… (${t.collapsed} frames)"
            t.collapsed = 0
        }

        /** `Foo.kt:12` -> 12, `Native method` -> -2, anything else -> -1 (the `StackTraceElement` convention). */
        private fun lineOf(source: String): Int = when {
            source.equals("Native method", ignoreCase = true) -> -2
            else -> source.substringAfterLast(':', "").toIntOrNull() ?: -1
        }

        /** `/apex/.../libc.so (__ioctl+8) (BuildId: x)` -> `libc.so!__ioctl`. */
        private fun nativeFrame(path: String, rest: String): String {
            val lib = path.substringAfterLast('/')
            var sym = rest.replace(BUILD_ID, "").trim()
            if (sym.startsWith("(") && sym.endsWith(")")) sym = sym.substring(1, sym.length - 1)
            sym = sym.replace(OFFSET, "").take(160)
            return if (sym.isEmpty()) lib else "$lib!$sym"
        }

        private fun Raw.toDump() = ThreadDump(name, state, java.toList(), native.toList(), firstOwn, waitingOn)
    }
}
