package dev.ide.lang.jdt.compat

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The [CaffeineThreadProbe] shim stands in for caffeine `StripedBuffer`'s thread-probe (a copy of
 * `Striped64`'s). It must honor that probe's contract: a per-thread, always-nonzero `int` that `advanceProbe`
 * evolves by the same xorshift caffeine uses and stores back per thread.
 */
class CaffeineThreadProbeTest {

    @Test
    fun probeIsNonZeroAndStableWithinAThread() {
        val first = CaffeineThreadProbe.getProbe()
        assertNotEquals(0, first, "the probe must never be zero (caffeine skips its lazy-init branch)")
        assertEquals(first, CaffeineThreadProbe.getProbe(), "getProbe must be stable until advanced")
    }

    @Test
    fun advanceProbeXorshiftsAndStores() {
        var probe = CaffeineThreadProbe.getProbe()
        val expected = xorshift(probe)
        val returned = CaffeineThreadProbe.advanceProbe(probe)
        assertEquals(expected, returned, "advanceProbe must apply Striped64's xorshift")
        assertEquals(expected, CaffeineThreadProbe.getProbe(), "the advanced probe must be stored for the thread")
    }

    @Test
    fun eachThreadGetsItsOwnDistinctProbe() {
        val threads = 8
        val ready = CountDownLatch(threads)
        val probes = java.util.Collections.synchronizedList(ArrayList<Int>())
        val workers = (0 until threads).map {
            Thread {
                probes.add(CaffeineThreadProbe.getProbe())
                ready.countDown()
            }
        }
        workers.forEach { it.start() }
        ready.await()
        workers.forEach { it.join() }

        assertTrue(probes.none { it == 0 }, "no thread may see a zero probe")
        assertEquals(threads, probes.toSet().size, "each thread must seed a distinct probe: $probes")
    }

    private fun xorshift(seed: Int): Int {
        var p = seed
        p = p xor (p shl 13)
        p = p xor (p ushr 17)
        p = p xor (p shl 5)
        return p
    }
}
