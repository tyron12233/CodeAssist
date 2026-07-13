package dev.ide.lang.kotlin

import dev.ide.platform.log.Log
import dev.ide.platform.log.PerfTrace

/**
 * Lightweight, opt-in per-stage timing for the Kotlin editor hot paths (completion, analyze, highlight). A
 * single [trace] wraps a top-level call; [span]s inside it (here or down in the symbol service) attribute
 * time to named buckets. On [trace] exit one line is logged via the platform [Log] facade (so it shows in the
 * desktop console and the on-device log ring): `complete total=812ms parse=540ms infer=12ms members=240ms`.
 *
 * Disabled by default — zero cost (no `nanoTime`, no allocation) unless [enabled]. [enabled] is the shared
 * [PerfTrace] flag, so the "Log analysis timings" setting (or `-Dide.editor.perf=true`) turns this and the
 * backend's per-pass timing on together.
 *
 * The editor engine runs these calls on a single serialized dispatcher, but completion and a background
 * analyze can still interleave on different threads, so the active trace is held per-thread.
 */
object KotlinPerf {
    /** Backed by the shared [PerfTrace] flag so all editor timing toggles as one. */
    var enabled: Boolean
        get() = PerfTrace.enabled
        set(value) { PerfTrace.enabled = value }

    private val log = Log.logger("kotlin-perf")
    private val current = ThreadLocal<Trace?>()

    /** Global count of callee-resolution / applicability work units (bumped by the resolver's hottest path via
     *  [bump]). Surfaced as `resolveOps=N` in each [trace] summary so an exponential overload/inference blowup
     *  is a visible NUMBER (millions on one file), not just a slow wall time. Only bumped while [enabled]. */
    private val resolveOps = java.util.concurrent.atomic.AtomicLong(0)

    /** Bump the resolve-work counter (cheap; only when timing is on). Called from the hot resolver path. */
    fun bump() { if (PerfTrace.enabled) resolveOps.incrementAndGet() }

    /** Time [block] as a top-level path called [label]; logs the stage breakdown on exit (when [enabled]). */
    inline fun <T> trace(label: String, block: () -> T): T {
        if (!enabled) return block()
        val outer = begin(label)
        try {
            return block()
        } finally {
            end(outer) // restore the outer trace (supports nesting, though paths here don't nest today)
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

    // --- internals (public so the inline functions above can reach them; not block-taking, so legal) ---

    fun begin(label: String): Trace? {
        val outer = current.get()
        // START marker: during a hang the summary line never arrives, so this names the Kotlin pass that is
        // stuck at a given (GC) timestamp.
        log.info("$label STARTED")
        current.set(Trace(label))
        return outer
    }

    fun end(outer: Trace?) {
        val trace = current.get()
        current.set(outer)
        if (trace != null) log.info(trace.summary())
    }

    fun record(name: String, nanos: Long) {
        current.get()?.add(name, nanos)
    }

    class Trace(private val label: String) {
        private val start = System.nanoTime()
        private val opsStart = resolveOps.get()
        private val buckets = LinkedHashMap<String, Long>()
        fun add(name: String, nanos: Long) {
            buckets[name] = (buckets[name] ?: 0L) + nanos
        }
        fun summary(): String {
            val total = System.nanoTime() - start
            val sb = StringBuilder("$label total=${ms(total)}")
            for ((n, ns) in buckets) sb.append(' ').append(n).append('=').append(ms(ns))
            sb.append(" resolveOps=").append(resolveOps.get() - opsStart)
            return sb.toString()
        }
    }

    private fun ms(nanos: Long): String {
        val whole = nanos / 1_000_000
        val tenth = (nanos % 1_000_000) / 100_000
        return "$whole.${tenth}ms"
    }
}
