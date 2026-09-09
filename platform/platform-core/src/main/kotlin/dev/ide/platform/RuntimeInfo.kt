// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * Process-level runtime facts the engine adapts to, chiefly the CPU-word size of the RUNNING PROCESS.
 *
 * ## Why the process is 32-bit-aware
 * A class of hard native SIGSEGV (`SEGV_MAPERR`, tiny fault addr) shows up ONLY on 32-bit ARM ART budget
 * devices (issues #1396/#1332 and later reports on Itel/Unisoc/MediaTek, `ABI: 'arm'`), on the editor's
 * `ide-engine` thread while typing. The concurrent-`buildTree` provocation is already serialized away by the
 * one global PSI parse lock, yet the crash persists — pointing at a broader weak-memory / concurrent-GC
 * torn-reference fault BELOW our code (the same class the dedicated `ide-engine` thread was first introduced
 * for). We can't fix an ART runtime bug; we can only stop provoking it, and the lever we control is how much
 * heap-churning work runs on OTHER threads CONCURRENTLY with the editor. So on a detected 32-bit process the
 * engine collapses background index concurrency (see [dev.ide.index] / the index build), trading indexing
 * speed on those devices for stability. 64-bit devices and the desktop JVM are unaffected.
 *
 * ## How the bit-ness is known
 * The authoritative source is the launcher: on Android it calls `android.os.Process.is64Bit()` (which reports
 * the CURRENT process, not just device capability) and pins the answer via [set32Bit] at bootstrap, before any
 * engine is created. When nothing has pinned it (tests, the desktop launcher, a headless build), it is inferred
 * from `os.arch`; an unknown arch defaults to NOT 32-bit, so the mitigation is never imposed on an
 * unrecognized platform.
 */
object RuntimeInfo {

    // Null until a launcher pins it; then it wins over the os.arch inference. @Volatile so the value set on the
    // launcher's bootstrap thread is visible to the engine threads that read it.
    @Volatile
    private var pinned32Bit: Boolean? = null

    /**
     * Pin whether this process is 32-bit, from the platform's authoritative signal (Android
     * `android.os.Process.is64Bit()`). Call once at launcher bootstrap, before the engine starts. Idempotent.
     */
    fun set32Bit(value: Boolean) {
        pinned32Bit = value
    }

    /** True when this process runs on a 32-bit CPU model (armeabi-v7a / x86). Pinned value if set, else the
     *  `os.arch` inference. Read this to gate concurrency/memory trade-offs for the fragile 32-bit ARM ARTs. */
    val is32Bit: Boolean get() = pinned32Bit ?: inferredIs32Bit

    /** Lazily computed once: `os.arch` never changes for the life of a process. */
    private val inferredIs32Bit: Boolean by lazy { infer32BitFromArch(System.getProperty("os.arch")) }

    /**
     * Infer 32-bit-ness from an `os.arch` string. Any arch carrying "64" (aarch64, arm64, x86_64, amd64, …) is
     * 64-bit; a 32-bit ARM (arm, armv7l, armv8l = 32-bit userspace on a 64-bit core) or x86-32 (x86, i686, …)
     * is 32-bit; anything unrecognized defaults to false so the 32-bit mitigation is never imposed by mistake.
     * Package-visible for [RuntimeInfoTest].
     */
    internal fun infer32BitFromArch(arch: String?): Boolean {
        val a = arch.orEmpty().lowercase()
        if (a.isEmpty()) return false
        if (a.contains("64")) return false
        return a.startsWith("arm") || a == "x86" || a.startsWith("i386") || a.startsWith("i486") ||
            a.startsWith("i586") || a.startsWith("i686")
    }
}
