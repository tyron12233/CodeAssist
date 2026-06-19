package dev.ide.core

/**
 * Aggregates high-frequency latency samples (completion, analysis) into periodic summary events, so a
 * per-keystroke metric becomes ONE analytics event per window instead of thousands. Records are bucketed
 * by metric name; when a bucket reaches [windowSize] it's summarised (count + mean + p50/p95/max, all ms)
 * and emitted via [emit], then reset. [flushAll] drains partial buckets (e.g. on shutdown).
 *
 * Thread-safe (the editor's completion/analysis calls land on a background engine thread). [emit] runs
 * off the lock so it can't deadlock against [track]. Holds at most [windowSize] longs per metric — flat.
 */
internal class PerfSampler(
    private val windowSize: Int = 50,
    private val emit: (name: String, props: Map<String, String>) -> Unit,
) {
    private val lock = Any()
    private val buckets = HashMap<String, MutableList<Long>>()

    fun record(name: String, ms: Long) {
        val full = synchronized(lock) {
            val list = buckets.getOrPut(name) { ArrayList(windowSize) }
            list.add(ms)
            if (list.size >= windowSize) buckets.remove(name) else null
        }
        if (full != null) emit(name, summarize(full))
    }

    fun flushAll() {
        val pending = synchronized(lock) {
            val snap = buckets.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }
            buckets.clear()
            snap
        }
        pending.forEach { (name, xs) -> emit(name, summarize(xs)) }
    }

    private fun summarize(xs: List<Long>): Map<String, String> {
        val sorted = xs.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
        return mapOf(
            "count" to xs.size.toString(),
            "mean_ms" to (xs.sum() / xs.size).toString(),
            "p50_ms" to pct(0.50).toString(),
            "p95_ms" to pct(0.95).toString(),
            "max_ms" to sorted.last().toString(),
        )
    }
}
