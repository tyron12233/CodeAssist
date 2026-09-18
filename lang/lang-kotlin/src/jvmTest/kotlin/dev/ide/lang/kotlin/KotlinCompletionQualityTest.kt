package dev.ide.lang.kotlin

import dev.ide.bench.CompletionScore
import dev.ide.bench.Direction
import dev.ide.bench.QualityCaseResult
import dev.ide.bench.RegressionSuite
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Kotlin completion **quality** regression suite (opt-in: `./gradlew :lang-kotlin:regressionTest`) — the
 * Kotlin counterpart of lang-jdt's `CompletionQualityTest`, and the objective gate the completion-parity
 * plan phases measure against (recall / top-1 / top-5 / MRR over a fixed golden corpus).
 *
 * Runs `(code, caret, expected-item)` cases through the hand-built backend spanning every completion
 * context: member access (source members, stdlib members, extensions, companion statics, enum constants),
 * name references (locals, parameters, source and stdlib top-level callables), type references, expected-
 * type ranking, named arguments, camel-hump matching, and broken-code recovery. First run seeds
 * `baselines/kotlin-completion-quality.json` (commit it); later runs fail when a metric drops past its
 * tolerance. Deterministic: fixed sources + the bundled stdlib jar, no index (the in-memory reader path).
 */
@Tag("regression")
class KotlinCompletionQualityTest {

    private data class QCase(
        val name: String,
        val category: String,
        val file: String,
        val code: String,      // a full file with a single | caret marker
        val expected: String,  // bare identifier expected in the ranked list
    )

    private fun corpus(): List<QCase> = listOf(
        // ---- member access: source + stdlib members ----
        QCase("source-member", "member-instance", "U.kt",
            "package demo\nfun f(b: Box) { b.mem| }", "member1"),
        QCase("string-length", "member-instance", "U.kt",
            "package demo\nfun f(s: String) { s.len| }", "length"),
        QCase("empty-prefix-member", "member-instance", "U.kt",
            "package demo\nfun f(b: Box) { b.| }", "member1"),

        // ---- member access: extensions ----
        QCase("stdlib-extension", "extension", "U.kt",
            "package demo\nfun f(s: String) { s.upperc| }", "uppercase"),
        QCase("source-extension", "extension", "U.kt",
            "package demo\nfun f(b: Box) { b.ext| }", "extOne"),
        QCase("list-map", "extension", "U.kt",
            "package demo\nfun f(xs: List<Int>) { xs.ma| }", "map"),

        // ---- member access: statics / companions / enums ----
        QCase("int-max", "member-static", "U.kt",
            "package demo\nfun f() { Int.MAX| }", "MAX_VALUE"),
        QCase("enum-constant", "member-static", "U.kt",
            "package demo\nfun f() { Color.R| }", "RED"),

        // ---- name references ----
        QCase("local-ref", "name-ref", "U.kt",
            "package demo\nfun f() { val counter = 0\n val z = coun| }", "counter"),
        QCase("param-ref", "name-ref", "U.kt",
            "package demo\nfun f(prefix: String) { val z = pre| }", "prefix"),
        QCase("source-top-level", "name-ref", "U.kt",
            "package demo\nfun f() { greet| }", "greetTop"),
        QCase("stdlib-top-level", "name-ref", "U.kt",
            "package demo\nfun f() { printl| }", "println"),
        QCase("stdlib-listof", "name-ref", "U.kt",
            "package demo\nfun f() { val xs = list| }", "listOf"),

        // ---- type references ----
        QCase("source-type", "type-ref", "U.kt",
            "package demo\nfun f() { val b: Bo| }", "Box"),
        QCase("builtin-type", "type-ref", "U.kt",
            "package demo\nfun f() { val s: Stri| }", "String"),

        // ---- expected-type ranking ----
        QCase("boolean-literal", "expected-type", "U.kt",
            "package demo\nfun f() { val b: Boolean = tr| }", "true"),

        // ---- named arguments ----
        QCase("named-arg", "named-arg", "U.kt",
            "package demo\nfun draw(width: Int = 0, height: Int = 0) {}\nfun f() { draw(wid|) }", "width"),

        // ---- camel-hump matching ----
        QCase("hump-local", "camel-hump", "U.kt",
            "package demo\nfun f() { val myDynamicList = listOf(1)\n mDL| }", "myDynamicList"),
        QCase("hump-type", "camel-hump", "U.kt",
            "package demo\nfun f() { val x: MDL| }", "MyDynamicList"),
        QCase("hump-extension", "camel-hump", "U.kt",
            "package demo\nfun f(s: String) { s.fInd| }", "filterIndexed"),

        // ---- error tolerance ----
        QCase("broken-code", "broken-code", "U.kt",
            "package demo\nfun f(s: String) { println( \n s.len| }", "length"),
    )

    /** Ranked leading identifiers of the popup labels (`println(message: Any?)` → `println`). */
    private fun rankedNames(c: QCase): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, c.file, c.code) }.items
            .map { it.label.takeWhile { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '$' } }
            .distinct()

    @Test
    fun completionQualityHoldsAgainstBaseline() {
        val results = ArrayList<QualityCaseResult>()
        val table = StringBuilder("\n=== kotlin completion quality: per-case rank of the expected item ===\n")
        table.append("%-22s %-15s %-16s %6s %7s\n".format("case", "category", "expected", "rank", "items"))
        for (c in corpus()) {
            val names = rankedNames(c)
            val rank = CompletionScore.rankOf(names, c.expected)
            results += QualityCaseResult(c.name, c.category, rank, names.size)
            table.append("%-22s %-15s %-16s %6s %7d\n".format(
                c.name, c.category, c.expected, if (rank < 0) "MISS" else rank.toString(), names.size))
        }
        println(table)

        val suite = RegressionSuite("kotlin-completion-quality")
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

    companion object {
        val srcDir = tempProject(
            mapOf(
                "Box.kt" to "package demo\nclass Box {\n  fun member1() {}\n  fun member2() {}\n  val prop1 = 1\n}\nfun demo.Box.extOne() {}\n",
                "Top.kt" to "package demo\nfun greetTop() {}\nenum class Color { RED, GREEN }\nclass MyDynamicList\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
