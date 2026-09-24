package dev.ide.core.perf

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

    /**
     * One metric's open window: its latency samples plus the worst heap reading taken across them, the
     * dimensions that key it, the time each sample spent waiting for the engine (when the caller measured
     * it), and how many samples ran over the caller's threshold (when it gave one).
     */
    private class Window(capacity: Int, val name: String, val dims: Map<String, String>) {
        val samples = ArrayList<Long>(capacity)
        val queued = ArrayList<Long>(0)
        var over = 0
        var hasThreshold = false
        val heap = PeakHeap()
    }

    /**
     * Record one [ms] sample of [name]. [dims] split the metric into separately summarized windows (the
     * language, the editor pass), and are reported with it; they must come from closed sets, never user
     * content. [queuedMs] is the part of [ms] spent waiting for the engine before the work started.
     * [overMs] counts the samples above it (a frame budget).
     */
    fun record(
        name: String,
        ms: Long,
        dims: Map<String, String> = emptyMap(),
        queuedMs: Long? = null,
        overMs: Long? = null,
    ) {
        val key = if (dims.isEmpty()) name else name + dims.entries.sortedBy { it.key }.joinToString("") { "|${it.key}=${it.value}" }
        val full = synchronized(lock) {
            val window = buckets.getOrPut(key) { Window(windowSize, name, dims) }
            window.samples.add(ms)
            if (queuedMs != null) window.queued.add(queuedMs)
            if (overMs != null) {
                window.hasThreshold = true
                if (ms > overMs) window.over++
            }
            window.heap.record()
            if (window.samples.size >= windowSize) buckets.remove(key) else null
        }
        if (full != null) emit(full.name, summarize(full))
    }

    fun flushAll() {
        val pending = synchronized(lock) {
            val snap = buckets.values.filter { it.samples.isNotEmpty() }
            buckets.clear()
            snap
        }
        pending.forEach { window -> emit(window.name, summarize(window)) }
    }

    private fun summarize(window: Window): Map<String, String> {
        val xs = window.samples
        val sorted = xs.sorted()
        fun pct(values: List<Long>, p: Double) = values[((values.size - 1) * p).toInt().coerceIn(0, values.lastIndex)]
        val out = LinkedHashMap<String, String>()
        out += window.dims
        out["count"] = xs.size.toString()
        out["mean_ms"] = (xs.sum() / xs.size).toString()
        out["p50_ms"] = pct(sorted, 0.50).toString()
        out["p95_ms"] = pct(sorted, 0.95).toString()
        out["max_ms"] = sorted.last().toString()
        if (window.queued.isNotEmpty()) {
            val q = window.queued.sorted()
            out["queue_p50_ms"] = pct(q, 0.50).toString()
            out["queue_p95_ms"] = pct(q, 0.95).toString()
            out["queue_max_ms"] = q.last().toString()
        }
        if (window.hasThreshold) out["over_count"] = window.over.toString()
        return out + window.heap.peak().props()
    }
}
