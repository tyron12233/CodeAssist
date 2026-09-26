// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * Reading the OS's process-death records (`ActivityManager.getHistoricalProcessExitReasons`) for crash
 * reporting: which of them describe a fault the app can act on, and which are the system doing its job.
 *
 * The records are not a crash log. They cover every way a process of this package ended, and the app forks
 * two of its own (`:build`, `:preview`) that it kills routinely. Reporting a record that is not a fault does
 * not just add noise, it moves the headline number: on 3.14.0 these deaths were 479 of 649 reported crash
 * rows (74%), across 63 of 89 crashing installs.
 */
object ProcessDeath {

    /** `ApplicationExitInfo.REASON_SIGNALED` — the process was ended by a signal. */
    const val REASON_SIGNALED: Int = 2

    /** SIGKILL. For [REASON_SIGNALED] the record's `status` carries the signal number. */
    const val SIGKILL: Int = 9

    /**
     * True when a death record describes the SYSTEM (or this app) ending a process rather than a fault in it.
     *
     * SIGKILL cannot be caught, handled or caused by the code running: it is the low-memory killer reclaiming
     * a background process, the user swiping the task away, or the IDE tearing down the `:build` / `:preview`
     * child it forked. None of those is a crash, and a process killed this way leaves no tombstone — the
     * kernel writes one for a FAULT (SIGSEGV/SIGABRT/SIGBUS), never for a kill. So [hasTombstone] is what
     * separates the two: a signalled death that DID produce a tombstone is kept and reported, at the cost of
     * nothing, while the ones that carry no diagnostic at all are dropped.
     *
     * Every other signal stays reportable: a SIGSEGV or SIGABRT filed under [REASON_SIGNALED] is a real
     * native crash whose tombstone may simply have been unavailable on that ROM.
     */
    fun isSystemKill(reason: Int, status: Int, hasTombstone: Boolean): Boolean =
        reason == REASON_SIGNALED && status == SIGKILL && !hasTombstone

    /** `ApplicationExitInfo.REASON_LOW_MEMORY`: the low-memory killer, on devices that attribute it. */
    const val REASON_LOW_MEMORY: Int = 3

    /** `ApplicationExitInfo.REASON_CRASH`: an uncaught Java exception. */
    const val REASON_CRASH: Int = 4

    /** `ApplicationExitInfo.REASON_CRASH_NATIVE`: a native crash; `status` is the signal. */
    const val REASON_CRASH_NATIVE: Int = 5

    /** `ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE`: the system judged the process too costly. */
    const val REASON_EXCESSIVE_RESOURCE_USAGE: Int = 9

    /**
     * One console line saying why the `:build` process ended, from its OS death record, so a build that stops
     * with "Build process stopped" also says whether the system reclaimed the memory, the build's Java heap ran
     * out, or native code crashed. Those three have different remedies, and the bare message cannot tell them
     * apart.
     *
     * [rssKb] is the process's resident size when it died (0 when unknown), and [heapLimitMb] the app's Java
     * heap limit, which the `:build` process shares with the IDE (0 when unknown).
     */
    fun describeBuildProcessExit(
        reason: Int,
        status: Int,
        description: String?,
        rssKb: Long,
        heapLimitMb: Long,
    ): String {
        val using = if (rssKb > 0) ", while using ${rssKb / 1024}MB" else ""
        val detail = description?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return when {
            reason == REASON_LOW_MEMORY ->
                "Cause: Android's low-memory killer stopped the build process$using$detail. Other apps and the " +
                    "device itself needed the memory; closing apps frees it."
            reason == REASON_SIGNALED && status == SIGKILL ->
                "Cause: the build process was killed (SIGKILL)$using$detail, usually by Android's low-memory " +
                    "killer. Closing other apps frees memory for the next build."
            reason == REASON_CRASH ->
                "Cause: the build process crashed with an uncaught error$detail" +
                    (if (heapLimitMb > 0) ". Its Java heap is limited to ${heapLimitMb}MB; an OutOfMemoryError there means the build needed more." else ".")
            reason == REASON_CRASH_NATIVE || reason == REASON_SIGNALED ->
                "Cause: the build process crashed in native code (${signalName(status)})$using$detail."
            reason == REASON_EXCESSIVE_RESOURCE_USAGE ->
                "Cause: Android stopped the build process for excessive resource use$using$detail."
            else -> "Cause: the build process ended (exit reason $reason, status $status)$using$detail."
        }
    }

    private fun signalName(signal: Int): String = when (signal) {
        4 -> "SIGILL"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"
        else -> "signal $signal"
    }
}
