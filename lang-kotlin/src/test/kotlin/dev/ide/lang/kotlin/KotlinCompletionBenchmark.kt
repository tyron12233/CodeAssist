package dev.ide.lang.kotlin

import dev.ide.bench.Bench
import dev.ide.bench.RegressionSuite
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Latency + allocation regression suite for the Kotlin editor hot paths (opt-in:
 * `./gradlew :lang-kotlin:regressionTest`). The Java/JDT side has had a benchmark for a while; the Kotlin
 * backend had none, so every Kotlin perf change was unmeasured and unguarded. This closes that gap.
 *
 * Measured against a real kotlin-stdlib jar (the extension-heavy classpath a real Kotlin file resolves
 * against), the full per-keystroke pipeline for each completion kind:
 *
 *  - **parse** — [KotlinIncrementalParser.parseFull], the PSI reparse the backend does on EVERY edit
 *    (currently a full reparse; this metric is what an incremental reparse would move).
 *  - **member-access** — `recv.|`, members + the stdlib extension set spliced in.
 *  - **member-prefix** — `recv.upper|`, the narrowed (realistic) member case.
 *  - **chain** — `a.first().|`, the inference chain (generic call return → member).
 *  - **scope** — a bare prefix: locals + top-level callables + the index for auto-import.
 *  - **type-ref** — a type position (`val s: Stri|`).
 *
 * Each records steady-state ns/op + bytes/op against `baselines/kotlin-completion-latency.json`. Latency is
 * machine-dependent (loose drift + an absolute interactive-budget ceiling); allocation is deterministic and
 * gates tightly. First run on a machine seeds the baseline — commit it.
 */
@Tag("regression")
class KotlinCompletionBenchmark {

    /** A realistic-sized focal unit: package, imports, a class, fields, a few methods, locals, a receiver. */
    private fun focalUnit(body: String): String = """
        package app

        import kotlin.math.max
        import kotlin.math.min

        class Service(private val names: MutableList<String>) {
            private val counts: MutableMap<String, Int> = HashMap()
            private var total: Int = 0

            fun compute(seed: Int): Int {
                var acc = seed
                for (i in names.indices) {
                    acc += counts.getOrDefault(names[i], 0)
                }
                return max(acc, min(seed, total))
            }

            fun render(sb: StringBuilder, prefix: String): String {
                $body
                return sb.toString()
            }

            fun tick() { total++ }
        }
    """.trimIndent()

    private data class Scenario(val label: String, val text: String, val offset: Int)

    private fun scenario(label: String, body: String): Scenario {
        val text = focalUnit(body)
        val caret = text.indexOf('|')
        require(caret >= 0) { "scenario '$label' has no caret '|'" }
        return Scenario(label, text.removeRange(caret, caret + 1), caret)
    }

    private fun scenarios(): List<Scenario> = listOf(
        scenario("member-access", "sb.|"),               // members + the (large) extension set
        scenario("member-prefix", "sb.appe|"),           // narrowed member case (the common one)
        scenario("chain", "names.first().|"),            // inference chain: List<String>.first() : String
        scenario("scope", "val n = ma|"),                // locals + top-level (max/min) + index auto-import
        scenario("type-ref", "val s: Stri|"),            // type position
    )

    @Test
    fun kotlinCompletionAndParseLatencyHoldAgainstBaseline() {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val parser = analyzer.incrementalParser
        val suite = RegressionSuite("kotlin-completion-latency")

        // --- full PSI reparse: the cost paid on every keystroke ---
        // PSI parsing is lazy: createFileFromText builds a stub and the AST is only realized when the tree is
        // walked. So fully flatten the tree each op to force the real parse cost (what an incremental reparse
        // would move) — counting `.children.size` alone would only measure the lazy stub.
        val parseText = focalUnit("sb.append(prefix)")
        var v = 1L
        val parseNs = Bench.nsPerOp(warmup = 3, runs = 5, ops = 6) {
            parser.parseFull(SnippetDoc(parseText, DiskFile(srcDir.resolve("app/Service.kt")), ++v)).flatten().size.toLong()
        }
        val parseBytes = Bench.allocPerOp(warmup = 3, ops = 6) {
            parser.parseFull(SnippetDoc(parseText, DiskFile(srcDir.resolve("app/Service.kt")), ++v)).flatten().size.toLong()
        }
        println("\n=== Kotlin parse: full PSI reparse per keystroke ===\nper parse: ${Bench.ns(parseNs)}, alloc/parse: ${Bench.bytes(parseBytes.toDouble())}\n")
        suite.latencyNs("latency.parse.ns", parseNs, tolerance = 1.5, ceilingNs = 500_000_000.0)
        suite.allocBytes("alloc.parse.bytes", parseBytes, tolerance = 0.40)

        // --- completion per kind ---
        val report = StringBuilder("\n=== Kotlin completion: full complete() per keystroke (lower is better) ===\n")
        report.append("scenario        |        ns/op     alloc/op       items\n")
        for (s in scenarios()) {
            val req = CompletionRequest(SnippetDoc(s.text, DiskFile(srcDir.resolve("app/Service.kt"))), s.offset, CompletionTrigger.TypedChar('.'))
            val items = runBlocking { analyzer.completion!!.complete(req) }.items.size
            val nsPerOp = Bench.nsPerOp(warmup = 3, runs = 5, ops = 6) {
                runBlocking { analyzer.completion!!.complete(req) }.items.size.toLong()
            }
            val bytesPerOp = Bench.allocPerOp(warmup = 3, ops = 6) {
                runBlocking { analyzer.completion!!.complete(req) }.items.size.toLong()
            }
            report.append("%-15s | %12s %12s %11d\n".format(s.label, Bench.ns(nsPerOp), Bench.bytes(bytesPerOp.toDouble()), items))
            // Loose drift + a 300 ms interactive backstop (Kotlin completion does more inference than JDT).
            suite.latencyNs("latency.${s.label}.ns", nsPerOp, tolerance = 1.5, ceilingNs = 300_000_000.0)
            // Looser than JDT's 40 MB: the marker-splice reparse + symbol materialization run hotter here.
            suite.allocBytes("alloc.${s.label}.bytes", bytesPerOp, tolerance = 0.40, ceilingBytes = 64.0 * 1024 * 1024)
        }
        println(report)

        // --- the REAL hot path: member-access while EDITING the current file ---
        // Every keystroke in the open file changes its live-overlay text, so refreshOverlay()→setOverlay() drops
        // the cached source model + the source-supertype memo and the next completion rebuilds. The scenarios
        // above measure the model-WARM case (empty overlay); this measures what you actually pay while typing.
        run {
            val s = scenario("member-access", "sb.appe|")
            val servicePath = srcDir.resolve("app/Service.kt").toString()
            var edit = 0
            analyzer.liveOverlayProvider = { mapOf(servicePath to s.text + "\n// e$edit") }
            val req = CompletionRequest(SnippetDoc(s.text, DiskFile(srcDir.resolve("app/Service.kt"))), s.offset, CompletionTrigger.TypedChar('.'))
            val editNs = Bench.nsPerOp(warmup = 3, runs = 5, ops = 6) {
                edit++; runBlocking { analyzer.completion!!.complete(req) }.items.size.toLong()
            }
            val editBytes = Bench.allocPerOp(warmup = 3, ops = 6) {
                edit++; runBlocking { analyzer.completion!!.complete(req) }.items.size.toLong()
            }
            analyzer.liveOverlayProvider = { emptyMap() }
            println("\n=== Kotlin completion while editing (model rebuild per keystroke) ===\nmember-access-editing: ${Bench.ns(editNs)}, alloc: ${Bench.bytes(editBytes.toDouble())}\n")
            suite.latencyNs("latency.member-access-editing.ns", editNs, tolerance = 1.5, ceilingNs = 500_000_000.0)
            suite.allocBytes("alloc.member-access-editing.bytes", editBytes, tolerance = 0.40, ceilingBytes = 96.0 * 1024 * 1024)
        }

        // --- per-stage breakdown (informational, not gated): where does a member-access completion spend its
        // time? Enables the built-in KotlinPerf spans and captures the summary line via a log sink. ---
        run {
            val lines = ArrayList<String>()
            val sink = LogSink { r -> if (r.tag == "kotlin-perf") lines += r.message }
            Log.addSink(sink)
            dev.ide.lang.kotlin.KotlinPerf.enabled = true
            try {
                val s = scenario("member-access", "sb.|")
                val req = CompletionRequest(SnippetDoc(s.text, DiskFile(srcDir.resolve("app/Service.kt"))), s.offset, CompletionTrigger.TypedChar('.'))
                repeat(8) { runBlocking { analyzer.completion!!.complete(req) } } // warm + collect
            } finally {
                dev.ide.lang.kotlin.KotlinPerf.enabled = false
                Log.removeSink(sink)
            }
            println("\n=== Kotlin member-access stage breakdown (warm; last 3 of ${lines.size} traces) ===")
            lines.takeLast(3).forEach { println("  $it") }
            println()
        }

        suite.finishAndAssert()
        assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
    }

    companion object {
        // A small project on disk + the real stdlib jar; one analyzer warms the classpath extension scan once.
        val srcDir = tempProject(
            mapOf(
                "app/Service.kt" to "package app\nclass Service\n",
                "app/Util.kt" to "package app\nfun helper(): Int = 0\n",
            ),
        )
    }
}
