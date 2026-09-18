package dev.ide.lang.kotlin

import dev.ide.bench.Bench
import dev.ide.bench.RegressionSuite
import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.complete
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compose-scale completion benchmark — the case the stdlib-only [KotlinCompletionBenchmark] does NOT
 * reproduce. The known Kotlin completion pain is classpath-scale-dependent: a large library (Compose) blows
 * up the `kotlin.Any` extension bucket and the per-receiver supertype walk. This wires the REAL persistent
 * index (the production path) over the kotlin-stdlib + Compose runtime jars and measures completion through
 * it, with the built-in [KotlinPerf] stage breakdown so the dominant bucket is visible.
 *
 * Self-gating: skips (assumeTrue) when the Compose runtime jar isn't on the test classpath, so CI without the
 * Compose repo just doesn't run it — same contract as the other Compose tests here.
 *
 * Baselines: `baselines/kotlin-compose-completion-latency.json` (seeded on first run — commit it).
 */
@Tag("regression")
class KotlinComposeCompletionBenchmark {

    @Test
    fun composeScaleCompletionHoldsAgainstBaseline() {
        val compose = composeRuntimeJarOrNull()
        assumeTrue(compose != null, "Compose runtime jar not on the test classpath — skipping Compose-scale benchmark")
        val stdlib = stdlibJarPath()

        // Build the real persistent index over stdlib + Compose (the production index-backed path), then inject
        // it into the analyzer exactly as IdeServices.buildAnalyzer does.
        val index = IndexServiceImpl(
            listOf(KotlinTypeShapeIndex, KotlinCallableIndex),
            cacheRoot = Files.createTempDirectory("compose-bench-idx"),
        )
        runBlocking { index.ensureUpToDate(IndexScope(libraryJars = listOf(stdlib, compose!!))) }

        val srcDir = tempProject(mapOf("app/Use.kt" to "package app\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlib, compose!!))).apply {
            indexService = index
            extensionCacheDir = Files.createTempDirectory("compose-bench-ext")
            liveOverlayProvider = { emptyMap() }
        }

        val suite = RegressionSuite("kotlin-compose-completion-latency")
        val report = StringBuilder("\n=== Kotlin Compose-scale completion (index-backed; lower is better) ===\n")
        report.append("scenario          |        ns/op     alloc/op       items\n")

        for (s in scenarios()) {
            val req = CompletionRequest(
                SnippetDoc(s.text, DiskFile(srcDir.resolve("app/Use.kt"))), s.offset, CompletionTrigger.TypedChar('.'),
            )
            val items = runBlocking { analyzer.complete(req) }.items.size
            val nsPerOp = Bench.nsPerOp(warmup = 3, runs = 5, ops = 6) {
                runBlocking { analyzer.complete(req) }.items.size.toLong()
            }
            val bytesPerOp = Bench.allocPerOp(warmup = 3, ops = 6) {
                runBlocking { analyzer.complete(req) }.items.size.toLong()
            }
            report.append("%-17s | %12s %12s %11d\n".format(s.label, Bench.ns(nsPerOp), Bench.bytes(bytesPerOp.toDouble()), items))
            // Latency runs under gradle load here, so it's noisy run-to-run; gate it loosely (catch only an
            // order-of-magnitude drift) plus the absolute ceiling. Allocation is deterministic — it's the real
            // regression signal and gates tightly (it's what the ext-query memo cut by ~70% on member-bare).
            suite.latencyNs("latency.${s.label}.ns", nsPerOp, tolerance = 2.0, ceilingNs = 500_000_000.0)
            suite.allocBytes("alloc.${s.label}.bytes", bytesPerOp, tolerance = 0.40, ceilingBytes = 128.0 * 1024 * 1024)
        }
        println(report)

        // Stage breakdown for the heaviest scenario (bare-dot member access: full member + extension set).
        run {
            val lines = ArrayList<String>()
            val sink = LogSink { r -> if (r.tag == "kotlin-perf") lines += r.message }
            Log.addSink(sink)
            KotlinPerf.enabled = true
            try {
                val s = scenarios().first { it.label == "member-bare" }
                val req = CompletionRequest(SnippetDoc(s.text, DiskFile(srcDir.resolve("app/Use.kt"))), s.offset, CompletionTrigger.TypedChar('.'))
                repeat(8) { runBlocking { analyzer.complete(req) } }
            } finally {
                KotlinPerf.enabled = false
                Log.removeSink(sink)
            }
            println("=== Compose-scale member-bare stage breakdown (warm; last 3 of ${lines.size}) ===")
            lines.takeLast(3).forEach { println("  $it") }
            println()
        }

        suite.finishAndAssert()
        assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
    }

    private data class Scenario(val label: String, val text: String, val offset: Int)

    private fun scenario(label: String, code: String): Scenario {
        val caret = code.indexOf('|')
        require(caret >= 0) { "scenario '$label' has no caret '|'" }
        return Scenario(label, code.removeRange(caret, caret + 1), caret)
    }

    private fun scenarios(): List<Scenario> = listOf(
        // bare-dot member access: forces materializing the FULL member + extension set incl. the kotlin.Any
        // bucket, which Compose grows — the extension-materialization worst case.
        scenario("member-bare", "package app\nfun f() { \"\".| }"),
        // narrowed member access (the common keystroke): prefix should be pushed into the index query.
        scenario("member-prefix", "package app\nfun f() { \"\".upper| }"),
        // bare-name scope completion: top-level callables over the (now Compose-scale) index.
        scenario("scope", "package app\nfun f() { mutableStateO| }"),
    )

    companion object {
        /** A jar on the test classpath carrying `androidx.compose.runtime.*`, or null if Compose isn't present. */
        fun composeRuntimeJarOrNull(): Path? {
            val cp = System.getProperty("java.class.path").split(File.pathSeparator)
            val entry = cp.firstOrNull { e ->
                e.endsWith(".jar") && runCatching {
                    ZipFile(e).use { it.getEntry("androidx/compose/runtime/Composer.class") != null }
                }.getOrDefault(false)
            }
            return entry?.let { Path.of(it) }
        }
    }
}
