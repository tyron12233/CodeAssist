package dev.ide.bench

import java.nio.file.Files
import java.nio.file.Path

/** Which direction is "good" for a metric — used to decide what counts as a regression. */
enum class Direction { LOWER_BETTER, HIGHER_BETTER }

/** How a metric prints in the comparison table. */
enum class MetricUnit { NS, BYTES, RATIO, COUNT }

/**
 * One suite's worth of metrics, compared against a committed baseline file and gated with per-metric
 * thresholds. The contract:
 *
 *  - Record metrics with [metric] (or the [latencyNs] / [allocBytes] / [heapBytes] / [quality] helpers).
 *  - Call [finishAndAssert] last. It prints a `current vs baseline vs Δ%` table, then:
 *      * a **FAIL** (regression past the metric's tolerance, or past an absolute ceiling/floor) throws —
 *        failing the test, which is how "completion degraded" surfaces;
 *      * a metric with **no baseline yet is seeded** (recorded as the new baseline) and passes — so the
 *        first run on a machine establishes the numbers to commit;
 *      * an improvement past 20% is flagged as a reminder to ratchet the baseline down.
 *
 * Baselines live at `<baselineDir>/<name>.json` (a flat metric→number map). [baselineDir] comes from the
 * `bench.baselineDir` system property (the `regressionTest` Gradle task points it at `<module>/baselines`),
 * falling back to `./baselines` under the working directory. The file is rewritten when a metric is newly
 * seeded, or for every metric when run with `-Dbench.updateBaselines=true` (lock in deliberate changes).
 *
 * Latency is machine-dependent, so its default tolerance is generous (it catches *order-of-magnitude*
 * regressions, e.g. the per-keystroke environment rebuild coming back); allocation and quality are far
 * more deterministic and gate tightly.
 */
class RegressionSuite internal constructor(
    private val name: String,
    private val baselineDir: Path,
    private val update: Boolean,
) {
    constructor(name: String) : this(name, defaultBaselineDir(), System.getProperty("bench.updateBaselines") == "true")

    private val file: Path = baselineDir.resolve("$name.json")
    private val baseline: Map<String, Double> =
        if (Files.exists(file)) FlatJson.read(Files.readString(file)) else emptyMap()
    private val entries = ArrayList<Entry>()

    private data class Entry(
        val key: String,
        val value: Double,
        val dir: Direction,
        val unit: MetricUnit,
        val tolerance: Double, // allowed relative drift before FAIL (0.5 = 50%)
        val bound: Double?,     // absolute backstop: a ceiling for LOWER_BETTER, a floor for HIGHER_BETTER
    )

    /** Record one metric. Prefer the typed helpers below; this is the general form. */
    fun metric(
        key: String,
        value: Double,
        dir: Direction,
        unit: MetricUnit,
        tolerance: Double,
        bound: Double? = null,
    ) {
        entries += Entry(key, value, dir, unit, tolerance, bound)
    }

    /** Per-keystroke latency (ns). Loose by default (machine noise); [ceilingNs] is an absolute backstop. */
    fun latencyNs(key: String, value: Double, tolerance: Double = 1.5, ceilingNs: Double? = null) =
        metric(key, value, Direction.LOWER_BETTER, MetricUnit.NS, tolerance, ceilingNs)

    /** Allocation per op (bytes). Deterministic, so it gates tightly. Skipped when unmeasurable (value 0). */
    fun allocBytes(key: String, value: Long, tolerance: Double = 0.35, ceilingBytes: Double? = null) {
        if (value <= 0L && !Bench.allocMeasurable) return // counter unavailable on this VM
        metric(key, value.toDouble(), Direction.LOWER_BETTER, MetricUnit.BYTES, tolerance, ceilingBytes)
    }

    /** Heap occupancy (bytes): retained footprint or session growth. Noisy, so loose by default. */
    fun heapBytes(key: String, value: Long, tolerance: Double = 0.75, ceilingBytes: Double? = null) =
        metric(key, value.toDouble(), Direction.LOWER_BETTER, MetricUnit.BYTES, tolerance, ceilingBytes)

    /** A quality fraction in 0..1 (recall / top-k / MRR). Higher is better; [floor] is an absolute backstop. */
    fun quality(key: String, value: Double, tolerance: Double = 0.0, floor: Double? = null) =
        metric(key, value, Direction.HIGHER_BETTER, MetricUnit.RATIO, tolerance, floor)

    /** A raw count (e.g. candidates returned). Recorded for the table; gate it with [dir]. */
    fun count(key: String, value: Int, dir: Direction = Direction.HIGHER_BETTER, tolerance: Double = 0.5) =
        metric(key, value.toDouble(), dir, MetricUnit.COUNT, tolerance)

    fun finishAndAssert() {
        val rows = entries.map { evaluate(it) }
        println(render(rows))

        val newSeeds = rows.filter { it.verdict == Verdict.NEW }
        if (update || newSeeds.isNotEmpty()) persist(rows)
        if (newSeeds.isNotEmpty() && !update) {
            println("  → seeded ${newSeeds.size} new baseline metric(s) into ${relativeFile()} — commit it.")
        }
        if (update) println("  → -Dbench.updateBaselines=true: rewrote ${relativeFile()} from this run.")

        val failures = rows.filter { it.verdict == Verdict.FAIL }
        if (failures.isNotEmpty()) {
            val detail = failures.joinToString("\n") { "  - ${it.entry.key}: ${it.reason}" }
            throw AssertionError("[$name] ${failures.size} completion-regression metric(s) failed:\n$detail")
        }
    }

    // ---- evaluation ----

    private enum class Verdict { OK, IMPROVED, FAIL, NEW }

    private data class Row(val entry: Entry, val base: Double?, val verdict: Verdict, val reason: String)

    private fun evaluate(e: Entry): Row {
        val base = baseline[e.key] ?: return Row(e, null, Verdict.NEW, "no baseline yet")
        val v = e.value
        // Absolute backstop first (machine-independent gross-regression guard).
        if (e.bound != null) {
            if (e.dir == Direction.LOWER_BETTER && v > e.bound)
                return Row(e, base, Verdict.FAIL, "${fmt(e, v)} over the absolute ceiling ${fmt(e, e.bound)}")
            if (e.dir == Direction.HIGHER_BETTER && v < e.bound)
                return Row(e, base, Verdict.FAIL, "${fmt(e, v)} under the absolute floor ${fmt(e, e.bound)}")
        }
        return when (e.dir) {
            Direction.LOWER_BETTER -> {
                val allowed = base * (1 + e.tolerance)
                when {
                    v > allowed -> Row(e, base, Verdict.FAIL,
                        "${fmt(e, v)} > baseline ${fmt(e, base)} +${pct(e.tolerance)} (allowed ${fmt(e, allowed)})")
                    v < base * 0.8 -> Row(e, base, Verdict.IMPROVED, "improved")
                    else -> Row(e, base, Verdict.OK, "ok")
                }
            }
            Direction.HIGHER_BETTER -> {
                val allowed = base * (1 - e.tolerance)
                when {
                    v < allowed -> Row(e, base, Verdict.FAIL,
                        "${fmt(e, v)} < baseline ${fmt(e, base)} -${pct(e.tolerance)} (allowed ${fmt(e, allowed)})")
                    v > base * 1.2 -> Row(e, base, Verdict.IMPROVED, "improved")
                    else -> Row(e, base, Verdict.OK, "ok")
                }
            }
        }
    }

    private fun persist(rows: List<Row>) {
        val merged = LinkedHashMap(baseline)
        for (r in rows) {
            // In update mode, move every baseline to the current run; otherwise only seed missing keys.
            if (update || r.verdict == Verdict.NEW) merged[r.entry.key] = r.entry.value
        }
        Files.createDirectories(baselineDir)
        Files.writeString(file, FlatJson.write(merged))
    }

    // ---- rendering ----

    private fun render(rows: List<Row>): String {
        val sb = StringBuilder("\n=== regression: $name (baseline: ${relativeFile()}) ===\n")
        sb.append("%-40s %14s %14s %9s  %s\n".format("metric", "current", "baseline", "Δ%", "verdict"))
        for (r in rows) {
            val cur = fmt(r.entry, r.entry.value)
            val base = r.base?.let { fmt(r.entry, it) } ?: "—"
            val delta = if (r.base != null && r.base != 0.0)
                "%+.1f%%".format((r.entry.value - r.base) / r.base * 100) else "—"
            sb.append("%-40s %14s %14s %9s  %s\n".format(r.entry.key, cur, base, delta, badge(r.verdict)))
        }
        return sb.toString()
    }

    private fun badge(v: Verdict) = when (v) {
        Verdict.OK -> "ok"
        Verdict.IMPROVED -> "↑ improved"
        Verdict.FAIL -> "✗ FAIL"
        Verdict.NEW -> "• new (seeded)"
    }

    private fun fmt(e: Entry, v: Double): String = when (e.unit) {
        MetricUnit.NS -> Bench.ns(v)
        MetricUnit.BYTES -> Bench.bytes(v)
        MetricUnit.RATIO -> "%.3f".format(v)
        MetricUnit.COUNT -> v.toLong().toString()
    }

    private fun pct(frac: Double) = "%.0f%%".format(frac * 100)

    private fun relativeFile(): String =
        runCatching { Path.of("").toAbsolutePath().relativize(file.toAbsolutePath()).toString() }.getOrDefault(file.toString())

    companion object {
        /** Where baselines live: the `bench.baselineDir` system property, else `./baselines`. */
        fun defaultBaselineDir(): Path =
            System.getProperty("bench.baselineDir")?.let { Path.of(it) } ?: Path.of("baselines")
    }
}
