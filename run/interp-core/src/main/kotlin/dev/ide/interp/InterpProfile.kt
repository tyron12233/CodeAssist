package dev.ide.interp

import dev.ide.platform.log.Log
import dev.ide.platform.log.PerfTrace

/**
 * Opt-in per-composition-pass profiler for the interpreter RUNTIME — the tree-walk, reflective dispatch, and
 * Compose ABI that run when a `@Preview` renders or recomposes. It answers, ON DEVICE, where the edit→render
 * loop actually spends its time (desktop numbers say lowering dominates, but ART cost and GC pressure differ
 * and were never measured). It is the counterpart to [dev.ide.lang.kotlin.KotlinPerf] (which times the
 * *lowering* stage) — one [trace] wraps a whole composition pass, [span]s attribute wall-clock to coarse
 * buckets (reflect-dispatch / compose-ABI), and [count]ers tally cheap per-pass events (dispatch calls, cache
 * hits/misses, composables run vs skipped, `Env`/closure allocations).
 *
 * **Gated on the shared [PerfTrace] flag** — the one on-device "Log analysis timings" setting (or
 * `-Dide.editor.perf=true` on desktop) turns this on together with the editor timings. **Zero cost when off:
 * one volatile read.** When on, one summary line is logged per pass via the platform [Log] facade (tag
 * `interp-perf`), so it lands in both logcat and the in-app Logs viewer (Settings → Privacy → View logs) — no
 * `adb` needed. This is distinct from [InterpTrace], the verbose per-read/write *correctness* trace (its own
 * flag); this object is for perf only.
 *
 * The active trace is held per-thread: the light/dark preview frames run on separate threads/interpreters, and
 * a state-driven recomposition of a single scope runs on the recompose thread — each gets its own trace.
 */
object InterpProfile {
    /** Backed by the shared [PerfTrace] flag so all timing toggles as one (see the class docs). */
    val enabled: Boolean get() = PerfTrace.enabled

    private val log = Log.logger("interp-perf")
    private val current = ThreadLocal<Trace?>()

    /**
     * A structured sink for each completed trace's [Summary] — set by the on-device benchmark so it can read
     * per-phase timing/counters without scraping the log. Null (the default) in normal operation; the live log
     * line is always emitted regardless.
     */
    @Volatile
    var onTrace: ((Summary) -> Unit)? = null

    /** Time [block] as one composition pass called [label] in [phase] (first / recompose / liveEdit); logs the
     *  bucket + counter breakdown on exit (only when [enabled]). Nesting is supported (the outer trace is
     *  restored), though the recompose paths here don't nest. */
    inline fun <T> trace(label: String, phase: String, block: () -> T): T {
        if (!enabled) return block()
        val outer = begin(label, phase)
        try {
            return block()
        } finally {
            end(outer)
        }
    }

    /** Attribute [block]'s wall time to the bucket [name] within the current [trace] (no-op outside one). */
    inline fun <T> span(name: String, block: () -> T): T {
        if (!enabled) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - t0)
        }
    }

    /** Tally [n] against the counter [name] on the current pass (no-op when disabled or outside a [trace]). */
    fun count(name: String, n: Long = 1L) {
        if (enabled) current.get()?.count(name, n)
    }

    // --- internals (public so the inline functions above can reach them; not block-taking, so legal) ---

    fun begin(label: String, phase: String): Trace? {
        val outer = current.get()
        current.set(Trace(label, phase))
        return outer
    }

    fun end(outer: Trace?) {
        val trace = current.get()
        current.set(outer)
        if (trace != null) {
            val summary = trace.summarize()
            log.info(summary.line())
            onTrace?.invoke(summary)
        }
    }

    fun record(name: String, nanos: Long) {
        current.get()?.add(name, nanos)
    }

    class Trace(private val label: String, private val phase: String) {
        private val start = System.nanoTime()
        private val buckets = LinkedHashMap<String, Long>()
        private val counters = LinkedHashMap<String, Long>()
        fun add(name: String, nanos: Long) { buckets[name] = (buckets[name] ?: 0L) + nanos }
        fun count(name: String, n: Long) { counters[name] = (counters[name] ?: 0L) + n }
        fun summarize(): Summary =
            Summary(label, phase, System.nanoTime() - start, LinkedHashMap(buckets), LinkedHashMap(counters))
    }

    /** An immutable snapshot of one completed pass — total wall time, the timed [buckets], and the [counters]. */
    class Summary(
        val label: String,
        val phase: String,
        val totalNanos: Long,
        val buckets: Map<String, Long>,
        val counters: Map<String, Long>,
    ) {
        /** The single log line, e.g. `interp.render phase=recompose total=42.3ms | reflectDispatch=25.4
         *  composeABI=6.1 | calls=340 composablesRun=12 composablesSkip=33 cacheMiss=30 env=210`. */
        fun line(): String {
            val sb = StringBuilder("$label phase=$phase total=${ms(totalNanos)}")
            if (buckets.isNotEmpty()) {
                sb.append(" |")
                for ((n, v) in buckets) sb.append(' ').append(n).append('=').append(ms(v))
            }
            if (counters.isNotEmpty()) {
                sb.append(" |")
                for ((n, v) in counters) sb.append(' ').append(n).append('=').append(v)
            }
            return sb.toString()
        }
    }

    private fun ms(nanos: Long): String {
        val whole = nanos / 1_000_000
        val tenth = (nanos % 1_000_000) / 100_000
        return "$whole.${tenth}ms"
    }
}
