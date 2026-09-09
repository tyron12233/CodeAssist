package dev.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase-0 build-process-isolation instrumentation: the heap sampler must report a self-consistent reading
 * and a [PeakHeap] must hold the worst (max used / min headroom) over its window, since that worst point is
 * the OOM-relevant one we compare between the build and the project-open storm.
 */
class MemProbeTest {
    @Test
    fun sampleIsSelfConsistent() {
        val s = MemSample.now()
        assertTrue(s.usedMb >= 0, "used must be non-negative")
        assertTrue(s.maxMb >= s.usedMb, "max must be >= used")
        // headroom = max - used (allowing 1MB integer-division slack)
        assertTrue(kotlin.math.abs((s.maxMb - s.usedMb) - s.headroomMb) <= 1, "headroom should equal max - used")
    }

    @Test
    fun propsCarryHeapNumbersOnly() {
        val s = MemSample(usedMb = 300, maxMb = 512, headroomMb = 212)
        assertEquals(
            mapOf("heap_used_mb" to "300", "heap_max_mb" to "512", "heap_headroom_mb" to "212"),
            s.props(),
        )
    }

    @Test
    fun peakHoldsTheWorstReadingAcrossTheWindow() {
        val peak = PeakHeap()
        // Several real readings; even with GC jitter the peak's used must be >= every individual reading's
        // used and its headroom <= every reading's headroom (it tracks the worst, not the latest).
        val readings = (1..5).map { MemSample.now().also { peak.record() } }
        val p = peak.peak()
        assertTrue(p.samples >= readings.size, "peak must count every sample")
        assertTrue(readings.all { p.usedMb >= it.usedMb }, "peak used must dominate every reading")
        assertTrue(readings.all { p.headroomMb <= it.headroomMb }, "peak headroom must be the tightest seen")
    }
}
