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
}
