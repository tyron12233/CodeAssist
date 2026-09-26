// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessDeathTest {

    @Test
    fun `a signalled death with no tombstone and no fault signal is a system kill`() {
        // The shape 74% of 3.14.0's reported crashes had: REASON_SIGNALED, SIGKILL, no tombstone.
        assertTrue(ProcessDeath.isSystemKill(reason = 2, status = 9, hasTombstone = false))
    }

    @Test
    fun `a SIGKILL that did produce a tombstone is still reported`() {
        // A tombstone means the OS had something to say about the death; keep it rather than guess.
        assertFalse(ProcessDeath.isSystemKill(reason = 2, status = 9, hasTombstone = true))
    }

    @Test
    fun `a fault signal filed as SIGNALED stays reportable with or without a tombstone`() {
        // SIGSEGV (11) / SIGABRT (6) / SIGBUS (7) are real native crashes — the ROM just may not hand over
        // the trace, which is exactly the case that must not be filtered away.
        for (signal in listOf(11, 6, 7, 4, 8)) {
            assertFalse(ProcessDeath.isSystemKill(2, signal, hasTombstone = false), "signal $signal")
            assertFalse(ProcessDeath.isSystemKill(2, signal, hasTombstone = true), "signal $signal")
        }
    }

    @Test
    fun `a native crash record is never a system kill`() {
        // REASON_CRASH_NATIVE (5) records go through untouched whatever their status.
        assertFalse(ProcessDeath.isSystemKill(reason = 5, status = 9, hasTombstone = false))
        assertFalse(ProcessDeath.isSystemKill(reason = 5, status = 0, hasTombstone = false))
    }

    @Test
    fun `a build process death names the low-memory killer, a Java crash and a native crash apart`() {
        val lmk = ProcessDeath.describeBuildProcessExit(3, 0, "lmk", rssKb = 900 * 1024L, heapLimitMb = 512)
        assertTrue("low-memory killer" in lmk && "900MB" in lmk && "(lmk)" in lmk, lmk)

        val kill = ProcessDeath.describeBuildProcessExit(2, 9, null, rssKb = 0, heapLimitMb = 512)
        assertTrue("SIGKILL" in kill && "low-memory" in kill && "using" !in kill, kill)

        val java = ProcessDeath.describeBuildProcessExit(4, 0, "crash", rssKb = 600 * 1024L, heapLimitMb = 512)
        assertTrue("uncaught error" in java && "512MB" in java && "OutOfMemoryError" in java, java)

        val native = ProcessDeath.describeBuildProcessExit(5, 11, null, rssKb = 0, heapLimitMb = 0)
        assertTrue("native code (SIGSEGV)" in native, native)
        val abort = ProcessDeath.describeBuildProcessExit(2, 6, null, rssKb = 0, heapLimitMb = 0)
        assertTrue("native code (SIGABRT)" in abort, abort)

        for (line in listOf(lmk, kill, java, native, abort)) assertFalse('\u2014' in line, "em dash in: $line")
    }
}
