package dev.ide.lang.kotlin

import dev.ide.bench.Bench
import dev.ide.bench.RegressionSuite
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Preview-lowering benchmark (opt-in `regressionTest`). The Compose preview re-lowers the edited file's
 * PSI → ResolvedTree on every keystroke (the dominant interpreter-side cost). The cache is per-FUNCTION:
 * editing one function's body re-lowers only that function and reuses every sibling + the file's classes;
 * only a signature/import/class edit forces a full re-lower. This measures both:
 *
 *  - **body-edit** — change one function's body each op (the hot case: typing inside a @Composable).
 *  - **sig-edit** — change a top-level token each op (forces a full re-lower; the prior per-file behavior).
 *
 * The win is body-edit ≪ sig-edit on a multi-function file. Baseline: `baselines/kotlin-preview-lowering.json`.
 */
@Tag("regression")
class KotlinPreviewLoweringBenchmark {

    // A file with a preview-style entry function + many siblings + classes, so reusing siblings matters.
    private fun source(body: String, sig: String): String = buildString {
        append("package app\n")
        append("// v$sig\n")
        append("data class Model(val a: Int, val b: String, val c: Float)\n")
        append("data class State(val items: List<Int>, val label: String)\n")
        repeat(24) { i ->
            append(
                "fun helper$i(p: Int): Int { " +
                    "val a = p + $i; val b = listOf(a, $i, p).map { it * 2 }.filter { it > $i }; " +
                    "val c = b.sum() + a; val d = (\"x\" + c).length; val e = maxOf(c, d, a); " +
                    "return e + b.size + c + d }\n",
            )
        }
        append("fun Entry() { val m = Model($body, \"x\", 1.0f); val s = State(listOf(m.a), m.b); val r = helper3(m.a) + helper7(s.items.size); println(r) }\n")
    }

    @Test
    fun previewLoweringHoldsAgainstBaseline() {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val vf = DiskFile(srcDir.resolve("Preview.kt"))
        fun lower(body: String, sig: String): Long {
            analyzer.incrementalParser.parseFull(SnippetDoc(source(body, sig), vf))
            return analyzer.lowerFile(vf).size.toLong() + analyzer.lowerFileClasses(vf).size
        }

        // warm + sanity: program has the 10 helpers + Entry; classes has Model + State.
        assertTrue(lower("0", "0") >= 11, "expected the full program to lower")

        val suite = RegressionSuite("kotlin-preview-lowering")

        // body-edit: only Entry's body changes each op → re-lower Entry, reuse 10 helpers + 2 classes.
        var b = 0
        val bodyNs = Bench.nsPerOp(warmup = 3, runs = 5, ops = 4) { lower("${++b}", "0") }
        // sig-edit: a top-level token changes each op → fileSigHash moves → full re-lower (prior behavior).
        var s = 0
        val sigNs = Bench.nsPerOp(warmup = 3, runs = 5, ops = 4) { lower("0", "${++s}") }

        val ratio = if (bodyNs > 0) sigNs / bodyNs else 1.0
        println("\n=== Preview lowering (per-function cache) ===\nbody-edit (re-lower 1): ${Bench.ns(bodyNs)}\nsig-edit  (re-lower all): ${Bench.ns(sigNs)}\nfull/incremental: ${"%.2f".format(ratio)}x\n")

        suite.latencyNs("lowering.body-edit.ns", bodyNs, tolerance = 1.5, ceilingNs = 500_000_000.0)
        suite.latencyNs("lowering.sig-edit.ns", sigNs, tolerance = 1.5, ceilingNs = 1_000_000_000.0)
        suite.finishAndAssert()
        assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
    }

    companion object {
        val srcDir = tempProject(mapOf("placeholder.kt" to "package app\n"))
    }
}
