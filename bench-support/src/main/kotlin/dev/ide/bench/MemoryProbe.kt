package dev.ide.bench

import java.lang.management.ManagementFactory

/**
 * Heap-usage probes for the memory regression suite. Where [Bench.allocPerOp] measures *churn* (bytes
 * allocated per call, which the GC reclaims), [settledUsedHeap] measures *occupancy* — used heap after
 * forcing GC, a stable floor for retained-memory deltas (the per-analyzer footprint, and growth across a
 * long editing session, i.e. a leak). Occupancy taken without a GC settle would reflect collector timing
 * rather than memory the engine holds, so always settle before reading.
 */
object MemoryProbe {

    private val heapBean = ManagementFactory.getMemoryMXBean()

    /** Current used heap in bytes. */
    fun usedHeap(): Long = heapBean.heapMemoryUsage.used

    /** Forces GC over several rounds with a short pause, so used heap settles. */
    fun gcSettle(rounds: Int = 6) {
        repeat(rounds) {
            System.gc()
            try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    /** Used heap after [gcSettle] — a repeatable floor against which retained-memory deltas are taken. */
    fun settledUsedHeap(): Long {
        gcSettle()
        return usedHeap()
    }
}
