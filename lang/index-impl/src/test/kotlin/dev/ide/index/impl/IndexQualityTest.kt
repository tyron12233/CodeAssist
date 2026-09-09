package dev.ide.index.impl

import dev.ide.bench.CompletionScore
import dev.ide.bench.Direction
import dev.ide.bench.QualityCaseResult
import dev.ide.bench.RegressionSuite
import dev.ide.index.Hit
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Quality regression suite for the index query ranking (opt-in: `./gradlew :index-impl:regressionTest`).
 * The index backs auto-import, type-position completion, and go-to-symbol; its *ranking* decides whether
 * the symbol the user wants is the first hit or buried under near-matches. This suite freezes a corpus of
 * `(query, expected-symbol)` cases and tracks recall / top-1 / top-5 / MRR against a committed baseline —
 * a drop means the scorer started ranking the right symbol lower (e.g. a tweak to the prefix/camel/fuzzy
 * weights, or the origin bonus, that helped one case at another's expense).
 *
 * It mirrors production ranking exactly: collect hits from [IndexData], then
 * `sortedByDescending { score }.distinctBy { value }.take(limit)` — the same three lines `IndexServiceImpl`
 * runs — so the metrics reflect what the editor's popup actually shows.
 */
@Tag("regression")
class IndexQualityTest {

    private data class QCase(val query: String, val fuzzy: Boolean, val category: String, val expected: String)

    /** A fixed symbol set: JDK collection/util simple names (SDK) plus a few project symbols (SOURCE). */
    private fun corpusData(): IndexData {
        val data = IndexData(MatchingMode.PREFIX_AND_FUZZY)
        val sdk = listOf(
            "ArrayList", "ArrayDeque", "Arrays", "HashMap", "HashSet", "Hashtable", "LinkedList",
            "LinkedHashMap", "LinkedHashSet", "TreeMap", "TreeSet", "List", "Map", "Set", "Collection",
            "Collections", "Optional", "Objects", "Comparator", "Iterator", "PriorityQueue", "Vector",
            "Stack", "Random", "Scanner", "StringBuilder", "StringJoiner",
        )
        for (n in sdk) data.add(n, n, IndexOrigin.SDK)
        // project symbols — exercise the SOURCE origin bonus (a local symbol should beat an SDK near-match)
        for (n in listOf("AppList", "AppService", "AppController", "AppModule")) data.add(n, n, IndexOrigin.SOURCE)
        return data
    }

    private val cases = listOf(
        QCase("ArrayL", false, "prefix", "ArrayList"),
        QCase("Hash", false, "prefix", "HashMap"),
        QCase("Tree", false, "prefix", "TreeMap"),
        QCase("Linked", false, "prefix", "LinkedList"),
        QCase("App", false, "origin", "AppList"),           // SOURCE bonus over any SDK near-match
        QCase("AList", true, "fuzzy", "ArrayList"),          // subsequence
        QCase("HMap", true, "fuzzy", "HashMap"),             // subsequence
        QCase("LHMap", true, "fuzzy", "LinkedHashMap"),      // longer subsequence
        QCase("Iter", true, "fuzzy", "Iterator"),            // prefix-as-fuzzy
        QCase("Comp", true, "fuzzy", "Comparator"),          // trigram + prefix-ish match
    )

    /** Exactly the ranking IndexServiceImpl.query applies, over the same IndexData hits. */
    private fun ranked(data: IndexData, q: String, fuzzy: Boolean, limit: Int = 20): List<String> {
        val out = ArrayList<Hit<Any>>()
        val cap = (limit * 8).coerceAtLeast(64)
        if (fuzzy) data.fuzzy(q, out, cap) else data.prefix(q, out, cap)
        return out.asSequence()
            .sortedByDescending { it.score }
            .distinctBy { it.value }
            .take(limit)
            .map { it.key }
            .toList()
    }

    @Test
    fun indexRankingQualityHoldsAgainstBaseline() {
        val data = corpusData()
        val results = ArrayList<QualityCaseResult>()
        val table = StringBuilder("\n=== index ranking quality: rank of the expected symbol ===\n")
        table.append("%-10s %-8s %-15s %6s %7s\n".format("query", "mode", "expected", "rank", "hits"))
        for (c in cases) {
            val r = ranked(data, c.query, c.fuzzy)
            val rank = CompletionScore.rankOf(r, c.expected)
            results += QualityCaseResult(c.query, c.category, rank, r.size)
            table.append("%-10s %-8s %-15s %6s %7d\n".format(
                c.query, if (c.fuzzy) "fuzzy" else "prefix", c.expected, if (rank < 0) "MISS" else rank.toString(), r.size))
        }
        println(table)

        val suite = RegressionSuite("index-quality")
        val all = CompletionScore.metrics(results)
        suite.quality("overall.recall", all.recall, tolerance = 0.05, floor = 0.85)
        suite.quality("overall.top1", all.top1, tolerance = 0.10)
        suite.quality("overall.top5", all.top5, tolerance = 0.07, floor = 0.60)
        suite.quality("overall.mrr", all.mrr, tolerance = 0.07, floor = 0.45)
        results.groupBy { it.category }.toSortedMap().forEach { (cat, rs) ->
            val m = CompletionScore.metrics(rs)
            suite.quality("category.$cat.recall", m.recall, tolerance = 0.20)
            suite.quality("category.$cat.mrr", m.mrr, tolerance = 0.25)
        }
        suite.count("corpus.size", results.size, dir = Direction.HIGHER_BETTER, tolerance = 0.0)
        suite.finishAndAssert()
    }
}
