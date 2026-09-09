package dev.ide.lang.jdt

import dev.ide.bench.Bench
import dev.ide.bench.CompletionScore
import dev.ide.bench.Direction
import dev.ide.bench.QualityCaseResult
import dev.ide.bench.RegressionSuite
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Completion **quality** regression suite (opt-in: `./gradlew :lang-jdt:regressionTest`). The latency and
 * memory suites answer "is completion still fast / cheap"; this one answers the question that actually
 * makes the feature useful — "does it still offer the *right* item, ranked where the user will see it".
 *
 * It runs a fixed golden corpus of `(code, caret, expected-item)` cases spanning every completion context
 * (member access on a resolved binding, static vs instance members, name references in scope, type
 * references with auto-import, package members, broken-code recovery, and a smart-ranking case), then
 * tracks four aggregate metrics against a committed baseline:
 *
 *   recall — expected item present at all; top1 — ranked first; top5 — in the visible window; mrr.
 *
 * Per the [RegressionSuite] contract the first run seeds `baselines/completion-quality.json` (commit it);
 * later runs fail if any metric drops past its tolerance, or below the absolute floors. Results are
 * deterministic: the platform is the real JDK jrt and the index is the fixed [fakeIndex], so the same code
 * yields the same ranks every run. A regression here means a real-world completion got worse — a lost
 * candidate (recall), or the right answer pushed down the list (top1/top5/mrr).
 */
@Tag("regression")
class CompletionQualityTest {

    private data class QCase(
        val name: String,
        val category: String,
        val code: String,            // a full compilation unit with a single |CARET|
        val expected: String,        // the item that should be offered (bare identifier, no `()`)
        val deps: List<Pair<String, String>> = emptyList(),
    )

    private val helper =
        "package lib; public class Helper { public static String stat() { return null; } public String inst() { return null; } public static int SFIELD = 0; public int field = 0; }"
    private val holder =
        "package lib; public class Holder { public interface Open {} private interface Hidden {} public static int field = 0; }"

    private fun corpus(): List<QCase> = listOf(
        // ---- member access: instance members on a resolved binding ----
        QCase("string-length", "member-instance",
            "package app; class T { void m() { String s = \"\"; s.len|CARET| } }", "length"),
        QCase("list-add", "member-instance",
            "package app; import java.util.List; class T { void m(List<String> xs) { xs.ad|CARET| } }", "add"),
        QCase("dep-instance", "member-instance",
            "package app; import lib.Helper; class T { void m() { Helper h = new Helper(); h.in|CARET| } }", "inst",
            deps = listOf("lib/Helper.java" to helper)),
        QCase("builder-empty-prefix", "member-instance",
            "package app; class T { void m(StringBuilder sb) { sb.|CARET| } }", "append"),

        // ---- member access: static members / nested types ----
        QCase("dep-static", "member-static",
            "package app; import lib.Helper; class T { void m() { Helper.st|CARET| } }", "stat",
            deps = listOf("lib/Helper.java" to helper)),
        QCase("fq-static-of", "member-static",
            "package app; class T { void m() { java.util.List.o|CARET| } }", "of"),
        QCase("nested-type", "member-static",
            "package app; import lib.Holder; class T { void m() { Holder.O|CARET| } }", "Open",
            deps = listOf("lib/Holder.java" to holder)),

        // ---- name references: locals / params / fields in scope ----
        QCase("param-ref", "name-ref",
            "package app; class T { void m(String prefix) { String z = pre|CARET| ; } }", "prefix"),
        QCase("field-ref", "name-ref",
            "package app; class T { int total; void m() { int z = tot|CARET| ; } }", "total"),
        QCase("local-ref", "name-ref",
            "package app; class T { void m() { int counter = 0; int z = coun|CARET| ; } }", "counter"),

        // ---- type references with auto-import (index-backed) ----
        QCase("type-field", "type-ref",
            "package app; class T { Array|CARET| f; }", "ArrayList"),
        QCase("type-new", "type-ref",
            "package app; class T { void m() { Object o = new Linked|CARET| ; } }", "LinkedList"),
        QCase("type-hashmap", "type-ref",
            "package app; class T { HashM|CARET| f; }", "HashMap"),

        // ---- package members ----
        QCase("package-member", "package-ref",
            "package app; class T { void m(StringBuilder sb) { sb.append(\"x\"); java.util.Array|CARET| } }", "ArrayList"),

        // ---- smart ranking: expected type lifts the type-matching member ----
        QCase("smart-int-length", "smart-rank",
            "package app; class T { void m() { int x = \"\".|CARET| } }", "length"),

        // ---- error tolerance: complete through broken code earlier in the method ----
        QCase("broken-code", "broken-code",
            "package app; class T { void m() { System.out.println( ; String s = \"\"; s.len|CARET| } }", "length"),
    )

    @Test
    fun completionQualityHoldsAgainstBaseline() {
        val index = fakeIndex()
        val results = ArrayList<QualityCaseResult>()
        val table = StringBuilder("\n=== completion quality: per-case rank of the expected item ===\n")
        table.append("%-22s %-15s %-12s %6s %7s\n".format("case", "category", "expected", "rank", "items"))

        for (c in corpus()) {
            val (analyzer, dir) = workspaceWith(*c.deps.toTypedArray())
            try {
                analyzer.indexService = index
                val labels = completeLabels(analyzer, dir.resolve("app/T.java"), c.code)
                val rank = CompletionScore.rankOf(labels, c.expected)
                results += QualityCaseResult(c.name, c.category, rank, labels.size)
                table.append("%-22s %-15s %-12s %6s %7d\n".format(
                    c.name, c.category, c.expected, if (rank < 0) "MISS" else rank.toString(), labels.size))
                Bench.sink += labels.size.toLong()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
        println(table)

        val suite = RegressionSuite("completion-quality")
        val all = CompletionScore.metrics(results)
        // Absolute floors are a backstop against a broken engine seeding garbage; the day-to-day gate is
        // the small drift tolerance against the committed baseline.
        suite.quality("overall.recall", all.recall, tolerance = 0.05, floor = 0.85)
        suite.quality("overall.top1", all.top1, tolerance = 0.10)
        suite.quality("overall.top5", all.top5, tolerance = 0.07, floor = 0.60)
        suite.quality("overall.mrr", all.mrr, tolerance = 0.07, floor = 0.45)
        // Per-category recall + mrr: small n, so looser tolerance (one case flipping is a big fraction).
        results.groupBy { it.category }.toSortedMap().forEach { (cat, rs) ->
            val m = CompletionScore.metrics(rs)
            suite.quality("category.$cat.recall", m.recall, tolerance = 0.20)
            suite.quality("category.$cat.mrr", m.mrr, tolerance = 0.25)
        }
        // The corpus must not silently shrink (a deleted case can only flatter the aggregate metrics).
        suite.count("corpus.size", results.size, dir = Direction.HIGHER_BETTER, tolerance = 0.0)
        suite.finishAndAssert()

        assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
    }
}
