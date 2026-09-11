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
}
