package dev.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PerfSampler] turns per-keystroke latency into one event per window, and each window carries the peak heap
 * it saw. These pin the aggregation (one emit per [windowSize] samples, correct percentiles, partial windows
 * drained by `flushAll`) and that the heap keys ride along, since the OOM investigation depends on ordinary
 * sessions reporting heap and not just the crashes.
 */
class PerfSamplerTest {

    @Test
    fun emitsOncePerFullWindowWithPercentiles() {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val sampler = PerfSampler(windowSize = 4) { name, props -> events += name to props }

        listOf(10L, 20L, 30L, 40L).forEach { sampler.record("completion_perf", it) }

        assertEquals(1, events.size, "a full window emits exactly one event")
        val (name, props) = events.single()
        assertEquals("completion_perf", name)
        assertEquals("4", props["count"])
        assertEquals("25", props["mean_ms"])
        assertEquals("20", props["p50_ms"])
        assertEquals("40", props["max_ms"])
    }

    @Test
    fun windowCarriesTheHeapItSaw() {
        val events = mutableListOf<Map<String, String>>()
        val sampler = PerfSampler(windowSize = 2) { _, props -> events += props }

        sampler.record("analysis_perf", 5L)
        sampler.record("analysis_perf", 6L)

        val props = events.single()
        val used = props["heap_used_mb"]?.toLongOrNull()
        val max = props["heap_max_mb"]?.toLongOrNull()
        val headroom = props["heap_headroom_mb"]?.toLongOrNull()
        assertTrue(used != null && used >= 0, "heap_used_mb must be reported, was ${props["heap_used_mb"]}")
        assertTrue(max != null && max >= used!!, "heap_max_mb must be at least the used heap")
        assertTrue(headroom != null && headroom >= 0, "heap_headroom_mb must be reported")
    }

    @Test
    fun separateMetricsKeepSeparateWindows() {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val sampler = PerfSampler(windowSize = 2) { name, props -> events += name to props }

        sampler.record("completion_perf", 1L)
        sampler.record("analysis_perf", 2L)
        assertTrue(events.isEmpty(), "neither metric has filled its own window yet")

        sampler.record("analysis_perf", 8L)
        assertEquals(listOf("analysis_perf"), events.map { it.first })
        assertEquals("2", events.single().second["count"])
    }

    @Test
    fun flushAllDrainsPartialWindowsOnce() {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val sampler = PerfSampler(windowSize = 50) { name, props -> events += name to props }

        sampler.record("completion_perf", 7L)
        sampler.flushAll()
        sampler.flushAll()

        assertEquals(1, events.size, "an already-drained window must not be emitted again")
        assertEquals("1", events.single().second["count"])
        assertTrue(events.single().second.containsKey("heap_used_mb"), "a flushed window still reports heap")
    }
}
