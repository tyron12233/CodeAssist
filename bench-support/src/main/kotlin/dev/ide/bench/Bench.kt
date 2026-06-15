package dev.ide.bench

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/**
 * Micro-benchmark harness shared by the opt-in regression suites. Two measurements, both the unit a real
 * editor pays per keystroke:
 *
 *  - [nsPerOp] — steady-state latency: the *minimum* over several timed batches (the minimum is the least
 *    noise-contaminated estimate of the true cost on a busy machine), after untimed warmup so the JIT has
 *    compiled and any per-process caches are warm.
 *  - [allocPerOp] — bytes allocated per call, read from the HotSpot per-thread allocation counter
 *    (`com.sun.management.ThreadMXBean`). Allocation is far less machine-dependent than wall-clock, so it
 *    is the more trustworthy regression signal on a shared CI box. Returns 0 if the counter is absent
 *    (a non-HotSpot VM); the suites treat 0 as "unmeasured" and skip the alloc gate.
 *
 * Both are `inline` so the harness adds no lambda-call overhead to the thing being measured.
 */
object Bench {

    /**
     * Dead-code-elimination sink. Benchmark bodies fold their result here (return a `Long` derived from the
     * work) so the JIT cannot prove the work unused and elide it. Read it once in a trivial assertion.
     */
    @JvmStatic
    @Volatile
    var sink: Long = 0

    /** The HotSpot per-thread allocation counter, or null on a VM that doesn't expose it. */
    @PublishedApi
    internal val allocBean: ThreadMXBean? =
        (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf { it.isThreadAllocatedMemorySupported }

    /** True when [allocPerOp] can actually measure (HotSpot/JBR); false elsewhere. */
    val allocMeasurable: Boolean get() = allocBean != null

    /** Min ns/op over [runs] batches of [ops] calls, after [warmup] untimed calls. Lower is better. */
    inline fun nsPerOp(warmup: Int = 3, runs: Int = 5, ops: Int = 8, op: () -> Long): Double {
        var s = 0L
        repeat(warmup) { s += op() }
        var best = Long.MAX_VALUE
        var r = 0
        while (r < runs) {
            val t0 = System.nanoTime()
            var i = 0
            while (i < ops) { s += op(); i++ }
            val dt = System.nanoTime() - t0
            if (dt < best) best = dt
            r++
        }
        sink += s
        return best.toDouble() / ops
    }

    /** Bytes allocated per op on the calling thread; 0 if the counter is unavailable. */
    inline fun allocPerOp(warmup: Int = 3, ops: Int = 8, op: () -> Long): Long {
        val bean = allocBean ?: run { var s = 0L; repeat(ops) { s += op() }; sink += s; return 0 }
        var s = 0L
        repeat(warmup) { s += op() }
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        var i = 0
        while (i < ops) { s += op(); i++ }
        val after = bean.getThreadAllocatedBytes(tid)
        sink += s
        return ((after - before) / ops).coerceAtLeast(0)
    }

    // ---- human-readable formatting (shared by every printed table) ----

    fun ns(v: Double): String = when {
        v >= 1_000_000 -> "%.2f ms".format(v / 1_000_000)
        v >= 1_000 -> "%.1f µs".format(v / 1_000)
        else -> "%.0f ns".format(v)
    }

    fun bytes(v: Double): String = when {
        v >= 1024 * 1024 -> "%.1f MB".format(v / (1024.0 * 1024))
        v >= 1024 -> "%.1f KB".format(v / 1024.0)
        else -> "%.0f B".format(v)
    }
}
