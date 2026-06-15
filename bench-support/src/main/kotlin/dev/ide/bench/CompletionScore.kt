package dev.ide.bench

/**
 * One corpus case after running completion: where the expected item landed in the ranked list.
 * [rank] is 0-based, or -1 if the expected item was absent entirely (a recall miss).
 */
data class QualityCaseResult(
    val name: String,
    val category: String,
    val rank: Int,
    val candidates: Int,
)

/**
 * Aggregate completion-quality metrics over a corpus. These are what the quality suite tracks against a
 * baseline; a drop in any of them is the clearest "completion got worse" signal — independent of timing.
 *
 *  - [recall] — fraction of cases where the expected item appeared at all (catches lost candidates).
 *  - [top1]   — fraction ranked first (catches ranking regressions that bury the right answer).
 *  - [top5]   — fraction ranked in the first five (the items actually visible without scrolling).
 *  - [mrr]    — mean reciprocal rank, `avg(1 / (rank + 1))`, 0 for misses (a smooth ranking score).
 */
data class QualityMetrics(
    val n: Int,
    val recall: Double,
    val top1: Double,
    val top5: Double,
    val mrr: Double,
)

object CompletionScore {

    /** 0-based rank of [expected] in [ranked], or -1 if absent. */
    fun rankOf(ranked: List<String>, expected: String): Int = ranked.indexOf(expected)

    fun metrics(results: List<QualityCaseResult>): QualityMetrics {
        if (results.isEmpty()) return QualityMetrics(0, 0.0, 0.0, 0.0, 0.0)
        val n = results.size
        val present = results.count { it.rank >= 0 }
        val t1 = results.count { it.rank == 0 }
        val t5 = results.count { it.rank in 0..4 }
        val mrr = results.sumOf { if (it.rank >= 0) 1.0 / (it.rank + 1) else 0.0 }
        return QualityMetrics(n, present.toDouble() / n, t1.toDouble() / n, t5.toDouble() / n, mrr / n)
    }
}
