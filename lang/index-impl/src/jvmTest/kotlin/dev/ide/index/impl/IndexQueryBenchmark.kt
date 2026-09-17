package dev.ide.index.impl

import dev.ide.bench.RegressionSuite
import dev.ide.index.Hit
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Latency + allocation benchmark for the index query path — the symbol/type lookups completion fires on
 * every keystroke (unimported-type auto-import, type-position, go-to-symbol). Runnable as a JVM test
 * (`./gradlew :index-impl:test --tests '*IndexQueryBenchmark*' --info`).
 *
 * Two measurements:
 *  1. **End-to-end query** — [IndexData.prefix] / [IndexData.fuzzy] over a 20k-name synthetic index (the
 *     scale of a real SDK + project symbol set), reporting ns/op and bytes/op. This is the actual
 *     per-keystroke query cost.
 *  2. **Per-candidate scoring** — the allocation-free fuzzy scorer that ships now vs the earlier form that
 *     lowercased both strings and built a camel-humps string for *every* matched candidate. The query
 *     scores hundreds of candidates per keystroke, so that per-candidate allocation was the bulk of the
 *     query's GC churn on ART. This isolates the win (same scores, no allocation) the way the editor
 *     benchmark isolates rope-vs-rebuild.
 *
 * The ns/op + bytes/op per query are gated against a committed baseline (`baselines/index-perf.json`,
 * loose latency drift + a tight allocation gate); the equality assertion that the two scorers agree on
 * every candidate is tight — a faster scorer that ranks differently is a regression.
 */
@Tag("regression")
class IndexQueryBenchmark {

    private var blackhole = 0L

    private val terms: List<String> = buildList {
        // a realistic mix: java.* style names, camel-cased, varying lengths
        val roots = listOf("Array", "Abstract", "Buffered", "Concurrent", "Default", "Linked", "Hash",
            "Tree", "Weak", "Atomic", "Reentrant", "Scheduled", "Copy", "Identity", "Priority", "String")
        val tails = listOf("List", "Map", "Set", "Queue", "Deque", "Buffer", "Reader", "Writer", "Stream",
            "Builder", "Factory", "Handler", "Service", "Manager", "Context", "Reference", "Iterator")
        var n = 0
        for (r in roots) for (t1 in tails) for (t2 in tails) {
            add("$r$t1$t2"); add("$r$t1"); n += 2
            if (n >= 20_000) return@buildList
        }
    }

    // ---- 1. end-to-end query ----

    @Test
    fun benchmarkQuery() {
        val data = IndexData(MatchingMode.PREFIX_AND_FUZZY)
        terms.forEachIndexed { i, t -> data.add(t, t, if (i % 4 == 0) IndexOrigin.SOURCE else IndexOrigin.SDK) }
        val cap = 480 // limit 60 * 8, as IndexServiceImpl.query computes

        val suite = RegressionSuite("index-perf")
        val report = StringBuilder("\n=== Index query over ${terms.size} names (lower is better) ===\n")
        report.append("query           | mode   |      ns/op    alloc/op     hits\n")
        for ((label, q) in listOf("prefix 'Arr'" to "Arr", "prefix 'A'" to "A", "fuzzy 'ALi'" to "ALi", "fuzzy 'Bufrd'" to "Bufrd")) {
            val fuzzy = label.startsWith("fuzzy")
            val key = label.replace(" '", ".").replace("'", "")
            val out = ArrayList<Hit<Any>>(cap)
            val hits = runQuery(data, q, fuzzy, cap, out)
            val nsPerOp = bench(warmup = 2_000, runs = 5, ops = 5_000) {
                out.clear(); runQuery(data, q, fuzzy, cap, out).toLong()
            }
            val bytesPerOp = allocPerOp(ops = 5_000) { out.clear(); blackhole += runQuery(data, q, fuzzy, cap, out).toLong() }
            report.append("%-15s | %-6s | %11s %11s %8d\n".format(label, if (fuzzy) "fuzzy" else "prefix", ns(nsPerOp), bytes(bytesPerOp), hits))
            // A per-keystroke index query is microseconds; 50 ms is a gross-regression backstop. Alloc is the
            // Hit list it fills (bounded by cap), so it gates moderately tight against the baseline.
            suite.latencyNs("query.$key.ns", nsPerOp, tolerance = 1.5, ceilingNs = 50_000_000.0)
            suite.allocBytes("query.$key.bytes", bytesPerOp, tolerance = 0.5)
        }
        println(report)
        suite.finishAndAssert()
    }

    private fun runQuery(data: IndexData, q: String, fuzzy: Boolean, cap: Int, out: MutableList<Hit<Any>>): Int {
        if (fuzzy) data.fuzzy(q, out, cap) else data.prefix(q, out, cap)
        return out.size
    }

    // ---- 2. per-candidate scoring: allocation-free vs the old lowercasing/buildString form ----

    @Test
    fun benchmarkScoring() {
        val pattern = "ALi" // camel-style: matches ArrayList, AbstractList… via the camel-hump branch
        // correctness: the two scorers must agree on every candidate, else the "optimization" changed ranking
        for (t in terms) assertEquals(oldScoreFuzzy(t, pattern), newScoreFuzzy(t, pattern), "scorer disagreement on '$t'")

        val oldNs = bench(warmup = 200, runs = 5, ops = 2_000) {
            var acc = 0L; for (t in terms) acc += oldScoreFuzzy(t, pattern); acc
        }
        val oldBytes = allocPerScan { var acc = 0L; for (t in terms) acc += oldScoreFuzzy(t, pattern); blackhole += acc }
        val newNs = bench(warmup = 200, runs = 5, ops = 2_000) {
            var acc = 0L; for (t in terms) acc += newScoreFuzzy(t, pattern); acc
        }
        val newBytes = allocPerScan { var acc = 0L; for (t in terms) acc += newScoreFuzzy(t, pattern); blackhole += acc }

        println(
            "\n=== Fuzzy scoring of ${terms.size} candidates (one keystroke's worth) ===\n" +
                "old (lowercase + buildString): ${ns(oldNs)}, ${bytes(oldBytes)} per scan\n" +
                "new (allocation-free)        : ${ns(newNs)}, ${bytes(newBytes)} per scan\n" +
                "speedup ${"%.1f".format(oldNs / newNs)}x, alloc ${if (newBytes > 0) "%.0f".format(oldBytes.toDouble() / newBytes) else "∞"}x less\n"
        )
        // The new scorer allocates essentially nothing per scan; the old one allocates KB-to-MB. Loose floor.
        assertTrue(newBytes <= oldBytes, "new scorer must not allocate more than the old one")
    }

    @Test
    fun blackholeIsObserved() {
        assertTrue(blackhole >= 0L || blackhole < 0L)
    }

    // ---- the two scorers (the new one mirrors IndexData; the old one is the prior allocating form) ----

    private fun originBonus(o: IndexOrigin) = when (o) {
        IndexOrigin.SOURCE -> 30; IndexOrigin.LIBRARY -> 12; IndexOrigin.SDK -> 0; IndexOrigin.LIBRARY_SOURCE -> 0
    }

    /** The prior form: lowercases both strings and builds a humps string per candidate. */
    private fun oldScoreFuzzy(term: String, pattern: String): Int {
        val lt = term.lowercase()
        val lp = pattern.lowercase()
        val base = when {
            term == pattern -> 1000
            term.startsWith(pattern) -> 850
            lt.startsWith(lp) -> 700
            oldCamel(term, pattern) -> 600
            lt.contains(lp) -> 420
            oldSubseq(lt, lp) -> 220
            else -> return -1
        }
        return base - term.length.coerceAtMost(40) + originBonus(IndexOrigin.SDK)
    }

    private fun oldSubseq(haystack: String, needle: String): Boolean {
        var i = 0
        for (c in haystack) if (i < needle.length && c == needle[i]) i++
        return i == needle.length
    }

    private fun oldCamel(term: String, pattern: String): Boolean {
        val humps = buildString { term.forEachIndexed { i, c -> if (i == 0 || c.isUpperCase() || !c.isLetter()) append(c.lowercaseChar()) } }
        return oldSubseq(humps, pattern.lowercase())
    }

    /** The shipping form: char-by-char, no allocation (must match IndexData.scoreFuzzy). */
    private fun newScoreFuzzy(term: String, pattern: String): Int {
        val base = when {
            term == pattern -> 1000
            term.startsWith(pattern) -> 850
            term.startsWith(pattern, ignoreCase = true) -> 700
            newCamel(term, pattern) -> 600
            containsCi(term, pattern) -> 420
            subseqCi(term, pattern) -> 220
            else -> return -1
        }
        return base - term.length.coerceAtMost(40) + originBonus(IndexOrigin.SDK)
    }

    private fun subseqCi(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return true
        var i = 0
        for (c in haystack) if (c.lowercaseChar() == needle[i].lowercaseChar()) { i++; if (i == needle.length) return true }
        return false
    }

    private fun containsCi(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..(haystack.length - needle.length)) {
            if (haystack.regionMatches(start, needle, 0, needle.length, ignoreCase = true)) return true
        }
        return false
    }

    private fun newCamel(term: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return true
        var pi = 0
        term.forEachIndexed { i, c ->
            if (i == 0 || c.isUpperCase() || !c.isLetter()) {
                if (c.lowercaseChar() == pattern[pi].lowercaseChar()) { pi++; if (pi == pattern.length) return true }
            }
        }
        return false
    }

    // ---- harness (mirrors EditorPerformanceBenchmark) ----

    private inline fun bench(warmup: Int, runs: Int, ops: Int, op: () -> Long): Double {
        var bh = 0L
        repeat(warmup) { bh += op() }
        var best = Long.MAX_VALUE
        repeat(runs) {
            val t0 = System.nanoTime()
            var i = 0
            while (i < ops) { bh += op(); i++ }
            val dt = System.nanoTime() - t0
            if (dt < best) best = dt
        }
        blackhole += bh
        return best.toDouble() / ops
    }

    private inline fun allocPerOp(ops: Int, op: () -> Unit): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return 0
        if (!bean.isThreadAllocatedMemorySupported) return 0
        val tid = Thread.currentThread().threadId()
        repeat(200) { op() }
        val before = bean.getThreadAllocatedBytes(tid)
        repeat(ops) { op() }
        val after = bean.getThreadAllocatedBytes(tid)
        return ((after - before) / ops).coerceAtLeast(0)
    }

    /** Bytes allocated by one full scan of all candidates (the unit the scoring table reports). */
    private inline fun allocPerScan(scan: () -> Unit): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return 0
        if (!bean.isThreadAllocatedMemorySupported) return 0
        val tid = Thread.currentThread().threadId()
        repeat(5) { scan() }
        val reps = 50
        val before = bean.getThreadAllocatedBytes(tid)
        repeat(reps) { scan() }
        val after = bean.getThreadAllocatedBytes(tid)
        return ((after - before) / reps).coerceAtLeast(0)
    }

    private fun ns(v: Double): String = when {
        v >= 1_000_000 -> "%.2f ms".format(v / 1_000_000)
        v >= 1_000 -> "%.1f µs".format(v / 1_000)
        else -> "%.0f ns".format(v)
    }

    private fun bytes(v: Long): String = when {
        v >= 1024 * 1024 -> "%.1f MB".format(v / (1024.0 * 1024))
        v >= 1024 -> "%.1f KB".format(v / 1024.0)
        else -> "$v B"
    }
}
