package dev.ide.core

/**
 * Aggregates high-frequency latency samples (completion, analysis) into periodic summary events, so a
 * per-keystroke metric becomes ONE analytics event per window instead of thousands. Records are bucketed
 * by metric name; when a bucket reaches [windowSize] it's summarised (count + mean + p50/p95/max, all ms)
 * and emitted via [emit], then reset. [flushAll] drains partial buckets (e.g. on shutdown).
 *
 * Each window also carries the peak Java heap it saw ([PeakHeap], sampled once per latency sample), which
 * makes these events a heap time-series across an editing session at no extra event cost. Crashes report
 * only the heap at the moment they died, always at the ceiling, which cannot distinguish a slow climb from
 * a single large allocation; a per-window peak from ordinary sessions can. The keys match the crash event's
 * (`heap_used_mb`/`heap_max_mb`/`heap_headroom_mb`), with `heap_used_mb` being the window's worst reading.
 * Reading the heap is two counter loads (no GC, no allocation), so it is affordable per sample.
 *
 * Thread-safe (the editor's completion/analysis calls land on a background engine thread). [emit] runs
 * off the lock so it can't deadlock against [track]. Holds at most [windowSize] longs per metric, flat.
 */
internal class PerfSampler(
    private val windowSize: Int = 50,
    private val emit: (name: String, props: Map<String, String>) -> Unit,
) {
    private val lock = Any()
    private val buckets = HashMap<String, Window>()

    /** One metric's open window: its latency samples plus the worst heap reading taken across them. */
    private class Window(capacity: Int) {
        val samples = ArrayList<Long>(capacity)
        val heap = PeakHeap()
    }

    fun record(name: String, ms: Long) {
        val full = synchronized(lock) {
            val window = buckets.getOrPut(name) { Window(windowSize) }
            window.samples.add(ms)
            window.heap.record()
            if (window.samples.size >= windowSize) buckets.remove(name) else null
        }
        if (full != null) emit(name, summarize(full))
    }

    fun flushAll() {
        val pending = synchronized(lock) {
            val snap = buckets.filterValues { it.samples.isNotEmpty() }.toMap()
            buckets.clear()
            snap
        }
        pending.forEach { (name, window) -> emit(name, summarize(window)) }
    }

    private fun summarize(window: Window): Map<String, String> {
        val xs = window.samples
        val sorted = xs.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
        return mapOf(
            "count" to xs.size.toString(),
            "mean_ms" to (xs.sum() / xs.size).toString(),
            "p50_ms" to pct(0.50).toString(),
            "p95_ms" to pct(0.95).toString(),
            "max_ms" to sorted.last().toString(),
        ) + window.heap.peak().props()
    }
}
