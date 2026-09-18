// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * Facts about the command-line ART VM this app forks to run a build tool (R8/D8) with a heap above the app's
 * `largeHeap` cap — see `dev.ide.android.R8ForkSupport`.
 *
 * Both helpers here exist because a forked VM that cannot get its heap does not fail politely. ART aborts
 * inside `Runtime::Init` (`Check failed: main_mem_map_1.IsValid() Failed anonymous mmap(…)`, or
 * `region_space_mem_map.IsValid() No region space mem map`), which is a SIGABRT: the OS writes a tombstone and
 * files the death in this package's `ActivityManager.getHistoricalProcessExitReasons` history. So the app has
 * to both stop asking for heaps the device visibly cannot back, and recognise the resulting death as a tool
 * VM's rather than its own.
 */
object ForkedToolVm {

    /**
     * ART's concurrent-copying collector reserves TWICE `-Xmx` of address space for the region space when the
     * runtime starts, so a 2048MB `-Xmx` asks the kernel for one 4GB anonymous mapping. That reservation, not
     * `-Xmx` itself, is what fails on a phone.
     */
    const val HEAP_RESERVATION_FACTOR: Int = 2

    /** Share of device RAM the region-space reservation may claim. Half leaves the running IDE, the tool's own
     *  working set and the rest of the system the other half; above it the mapping is refused or the fork is
     *  killed shortly after, so probing it costs a VM launch and buys nothing. */
    const val RESERVATION_BUDGET_FRACTION: Double = 0.5

    /**
     * The `-Xmx` values (MB) from [candidates] whose region-space reservation this device can back, in the
     * order given. A device with unknown RAM (`totalMemMb <= 0`) keeps the whole list: with nothing to reason
     * from, the device itself is still the authority.
     *
     * An empty result means forking is not usable here at any offered heap — the caller should stay
     * in-process rather than launch a VM that will abort.
     */
    fun affordableHeaps(
        candidates: List<Int>,
        totalMemMb: Long,
        budgetFraction: Double = RESERVATION_BUDGET_FRACTION,
    ): List<Int> {
        if (totalMemMb <= 0L) return candidates
        val budgetMb = totalMemMb * budgetFraction
        return candidates.filter { it.toLong() * HEAP_RESERVATION_FACTOR <= budgetMb }
    }

    /**
     * Names ART gives the main thread of a command-line VM (the basename of `argv[0]`). Keep in sync with the
     * launcher paths in `R8ForkSupport.LAUNCHERS`; they are the only VMs this app forks.
     */
    val LAUNCHER_THREAD_NAMES: Set<String> = setOf("dalvikvm", "dalvikvm32", "dalvikvm64")

    /**
     * True when [tombstone] describes a VM this app forked to run a build tool, not one of its own processes.
     *
     * Such a death is not an app crash: the fork is a capability probe or a tool run that self-falls back
     * in-process, and reporting it as a crash makes a working device look broken (it dominated the 3.9.5/3.9.6
     * crash counts). The faulting thread is the only unambiguous signal — no thread in an IDE process is named
     * after a VM launcher — so a tombstone that names no thread is treated as the app's own, which keeps a real
     * crash reportable at the cost of leaving a probe abort in the numbers on ROMs that hand out no tombstone.
     */
    fun isToolVmCrash(tombstone: NativeTombstone?): Boolean {
        val thread = tombstone?.faultingThread?.trim()?.lowercase() ?: return false
        return thread in LAUNCHER_THREAD_NAMES
    }
}
