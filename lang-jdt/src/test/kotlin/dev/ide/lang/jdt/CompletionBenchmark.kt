package dev.ide.lang.jdt

import dev.ide.bench.Bench
import dev.ide.bench.RegressionSuite
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.vfs.VirtualFile
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Latency + allocation regression suite for the editor-time hot paths the IDE runs on every keystroke
 * (opt-in: `./gradlew :lang-jdt:regressionTest`). It measures, against the **real** JDK jrt image (the
 * platform classes a real project resolves against):
 *
 *  1. **Code completion** — [JdtCompletionService.complete] at carets that exercise each completion kind:
 *     member access (`recv.|`, resolved bindings), name reference (in-scope names + the index for
 *     auto-import), type reference (`new Foo|`), and package reference (`import a.b.|`). This is the
 *     full pipeline: splice the marker → resolve the focal unit with ecj over [JdtNameEnvironment] →
 *     analyze the context → collect candidates → rank.
 *  2. **Analysis** — [JdtSourceAnalyzer.analyze], the error-tolerant DOM parse + diagnostics the editor
 *     debounces after each edit.
 *
 * Each scenario records steady-state **ns/op** and **bytes/op** against a committed baseline
 * (`baselines/completion-latency.json`). Latency is machine-dependent, so its gate is loose drift
 * (catches an order-of-magnitude regression — e.g. completion drifting into the hundreds-of-ms range that
 * makes typing feel laggy) plus an absolute interactive-budget ceiling. **Allocation gates tightly**: a
 * warm completion reuses the shared [JdtEnvironmentCache], so it allocates only for the ecj resolve + the
 * candidate list (single-digit MB). Before that cache, each resolve rebuilt the jrt index + package set —
 * 65–195 MB per keystroke (ruinous GC on ART). The alloc baseline + ceiling fail loudly if that
 * per-resolve rebuild is ever reintroduced.
 */
@Tag("regression")
class CompletionBenchmark {

    // ---- fixtures ----

    /** A realistic-sized focal unit: imports, fields, a few methods, locals, a fluent receiver. */
    private fun focalUnit(caretMarker: String, body: String): String = """
        package app;

        import java.util.List;
        import java.util.ArrayList;
        import java.util.Map;
        import java.util.HashMap;

        class Service {
            private final List<String> names = new ArrayList<>();
            private final Map<String, Integer> counts = new HashMap<>();
            private int total;

            int compute(int seed) {
                int acc = seed;
                for (int i = 0; i < names.size(); i++) {
                    acc += counts.getOrDefault(names.get(i), 0);
                }
                return acc;
            }

            String render(StringBuilder sb, String prefix) {
                $body
                return sb.toString();
            }

            void tick() { total++; }
        }
    """.trimIndent().replace("|CARET|", caretMarker)

    private data class Scenario(val label: String, val text: String, val offset: Int)

    private fun scenario(label: String, body: String): Scenario {
        val text = focalUnit("|CARET|", body)
        val offset = text.indexOf("|CARET|")
        require(offset >= 0) { "scenario '$label' has no |CARET|" }
        return Scenario(label, text.replace("|CARET|", ""), offset)
    }

    private fun scenarios(): List<Scenario> = listOf(
        scenario("member-access", "sb.app|CARET|"),          // resolved-binding member path, no index
        scenario("name-ref", "int n = to|CARET|"),            // in-scope names + index (unimported types)
        scenario("type-ref", "Array|CARET| list = null;"),    // index-backed type ref with auto-import
        scenario("package-ref", "sb.append(\"x\"); java.util.|CARET|"), // package children from the index
    )

    @Test
    fun completionAndAnalysisLatencyHoldAgainstBaseline() {
        val (analyzer, dir) = newAnalyzer()
        try {
            analyzer.indexService = fakeIndex()
            val file = dir.resolve("app/Service.java")
            val suite = RegressionSuite("completion-latency")

            val report = StringBuilder("\n=== Code completion: full complete() per keystroke (lower is better) ===\n")
            report.append("scenario        |        ns/op     alloc/op       items\n")
            for (s in scenarios()) {
                val req = request(file, s.text, s.offset)
                val items = runSync { analyzer.completion.complete(req) }.items.size
                val nsPerOp = Bench.nsPerOp(warmup = 3, runs = 5, ops = 6) {
                    runSync { analyzer.completion.complete(req) }.items.size.toLong()
                }
                val bytesPerOp = Bench.allocPerOp(warmup = 3, ops = 6) {
                    runSync { analyzer.completion.complete(req) }.items.size.toLong()
                }
                report.append("%-15s | %12s %12s %11d\n".format(s.label, Bench.ns(nsPerOp), Bench.bytes(bytesPerOp.toDouble()), items))
                // Loose drift + a 200 ms interactive backstop (warm is single-digit ms); 1 s would be unusable.
                suite.latencyNs("latency.${s.label}.ns", nsPerOp, tolerance = 1.5, ceilingNs = 200_000_000.0)
                // Tight: a warm completion is single-digit MB. 40 MB ⇒ the per-resolve env rebuild is back.
                suite.allocBytes("alloc.${s.label}.bytes", bytesPerOp, tolerance = 0.40, ceilingBytes = 40.0 * 1024 * 1024)
            }
            println(report)

            // analysis: the error-tolerant DOM parse + diagnostics the editor debounces after each edit
            val text = focalUnit("", "sb.append(prefix);")
            Files.writeString(file, text)
            val vf = StubFile(file.toString(), text)
            val aNs = Bench.nsPerOp(warmup = 2, runs = 4, ops = 5) { runSync { analyzer.analyze(vf) }.diagnostics.size.toLong() }
            val aBytes = Bench.allocPerOp(warmup = 2, ops = 5) { runSync { analyzer.analyze(vf) }.diagnostics.size.toLong() }
            println("\n=== Analysis: error-tolerant DOM parse + diagnostics ===\nper parse: ${Bench.ns(aNs)}, alloc/parse: ${Bench.bytes(aBytes.toDouble())}\n")
            suite.latencyNs("latency.analysis.ns", aNs, tolerance = 1.5, ceilingNs = 500_000_000.0)
            suite.allocBytes("alloc.analysis.bytes", aBytes, tolerance = 0.40)

            suite.finishAndAssert()
            assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- harness helpers ----

    private fun newAnalyzer(): Pair<JdtSourceAnalyzer, Path> {
        val dir = Files.createTempDirectory("completion-bench")
        val f = dir.resolve("app/Service.java")
        Files.createDirectories(f.parent)
        Files.writeString(f, focalUnit("", "sb.append(prefix);"))
        return analyzer(listOf(dir)) to dir
    }

    private fun request(file: Path, text: String, offset: Int): CompletionRequest =
        CompletionRequest(BenchSnapshot(StubFile(file.toString(), text), 1, text), offset, CompletionTrigger.Explicit)
}

private class BenchSnapshot(
    override val file: VirtualFile,
    override val version: Long,
    override val text: CharSequence,
) : DocumentSnapshot {
    override fun length() = text.length
}
