package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkedToolVmTest {

    private val ladder = listOf(768, 1024, 1536, 2048, 3072, 4096)

    @Test
    fun `a heap is affordable only when twice it fits the RAM budget`() {
        // 8GB device, half the RAM budgeted for the reservation → 4GB of reservation → 2048MB of heap.
        assertEquals(listOf(768, 1024, 1536, 2048), ForkedToolVm.affordableHeaps(ladder, 8192))
        // 4GB device → 2GB of reservation → 1024MB of heap. This is the rung the 3.9.x telemetry aborted at.
        assertEquals(listOf(768, 1024), ForkedToolVm.affordableHeaps(ladder, 4096))
        // 16GB device: the whole ladder is reachable, so the walk ends without a single abort.
        assertEquals(ladder, ForkedToolVm.affordableHeaps(ladder, 16384))
    }

    @Test
    fun `a device too small for the lowest rung affords nothing`() {
        assertEquals(emptyList<Int>(), ForkedToolVm.affordableHeaps(ladder, 2048))
    }

    @Test
    fun `unknown RAM keeps the whole ladder so the device stays the authority`() {
        assertEquals(ladder, ForkedToolVm.affordableHeaps(ladder, 0))
        assertEquals(ladder, ForkedToolVm.affordableHeaps(ladder, -1))
    }

    @Test
    fun `the budget fraction moves the bound`() {
        assertEquals(listOf(768, 1024), ForkedToolVm.affordableHeaps(ladder, 8192, budgetFraction = 0.25))
        assertEquals(ladder, ForkedToolVm.affordableHeaps(ladder, 8192, budgetFraction = 1.0))
    }

    @Test
    fun `order is preserved and nothing is invented`() {
        assertEquals(listOf(2048, 768), ForkedToolVm.affordableHeaps(listOf(2048, 768), 8192))
        assertEquals(emptyList<Int>(), ForkedToolVm.affordableHeaps(emptyList(), 8192))
    }

    @Test
    fun `a fork of a command-line VM is not an app crash`() {
        for (name in listOf("dalvikvm64", "dalvikvm32", "dalvikvm", "DalvikVM64", " dalvikvm64 "))
            assertTrue(ForkedToolVm.isToolVmCrash(tombstone(name)), "expected $name to read as a tool VM")
    }

    @Test
    fun `an IDE thread is still reported`() {
        // The engine thread and the app main thread are the ones a real crash names; neither may be filtered.
        for (name in listOf("ide-engine", "main", "dev.ide.codeassist", "RenderThread", "dalvik", ""))
            assertFalse(ForkedToolVm.isToolVmCrash(tombstone(name)), "expected $name to stay reportable")
    }

    @Test
    fun `no tombstone or no faulting thread stays reportable`() {
        assertFalse(ForkedToolVm.isToolVmCrash(null))
        assertFalse(ForkedToolVm.isToolVmCrash(NativeTombstone()))
    }

    /** The abort the ceiling probe actually produced on 3.9.6, reduced to the fields the filter reads. */
    @Test
    fun `the real ceiling-probe abort is filtered`() {
        val probe = NativeTombstone(
            arch = "arm64",
            signal = "SIGABRT",
            signalCode = "SI_QUEUE",
            abortMessage = "Check failed: main_mem_map_1.IsValid() Failed anonymous mmap(0x0, 4294967296, " +
                "0x3, 0x22, -1, 0): Success. See process maps in the log.",
            faultingThread = "dalvikvm64",
            frames = listOf("libc.so!abort", "libart.so!art::Runtime::Abort(char const*)"),
            uptimeSeconds = 1,
        )
        assertTrue(ForkedToolVm.isToolVmCrash(probe))
    }

    private fun tombstone(thread: String) = NativeTombstone(faultingThread = thread)
}
