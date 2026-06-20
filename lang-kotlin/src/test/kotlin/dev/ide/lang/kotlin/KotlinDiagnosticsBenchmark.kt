package dev.ide.lang.kotlin

import dev.ide.bench.Bench
import dev.ide.bench.RegressionSuite
import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
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
 * Diagnostics-pass benchmark (opt-in `regressionTest`). `analyze()` runs a full-file semantic walk on every
 * debounced edit (unresolved member/call, type mismatch, nullable, unused, …). The per-node checks
 * (`unresolvedMember` especially) re-resolve receiver members against the classpath repeatedly. This measures
 * a full analyze pass on a member-access-heavy file at Compose scale (real index over stdlib + compose-runtime)
 * and prints the KotlinPerf `sem.*` breakdown so the dominant check is visible.
 *
 * Self-gating: skips when the Compose runtime jar isn't on the test classpath.
 * Baseline: `baselines/kotlin-diagnostics-latency.json`.
 */
@Tag("regression")
class KotlinDiagnosticsBenchmark {

    @Test
    fun diagnosticsPassHoldsAgainstBaseline() {
        val compose = composeRuntimeJarOrNull()
        assumeTrue(compose != null, "Compose runtime jar not on the test classpath — skipping")
        val stdlib = stdlibJarPath()

        val index = IndexServiceImpl(listOf(KotlinTypeShapeIndex, KotlinCallableIndex), cacheRoot = Files.createTempDirectory("diag-bench-idx"))
        runBlocking { index.ensureUpToDate(IndexScope(libraryJars = listOf(stdlib, compose!!))) }

        val srcDir = tempProject(mapOf("app/Screen.kt" to "package app\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlib, compose!!))).apply {
            indexService = index
            extensionCacheDir = Files.createTempDirectory("diag-bench-ext")
        }
        val vf = DiskFile(srcDir.resolve("app/Screen.kt"))

        // A member-access-heavy function: many `recv.member` / `recv.call()` across a few receiver types, the
        // shape that makes unresolvedMember fire repeatedly per pass. `edit` is injected into one expression so
        // each op is a REAL keystroke (changed text → a full re-walk of the function), not a cache hit.
        fun code(edit: Int) = buildString {
            append("package app\n")
            append("fun screen(s: String, xs: List<Int>, m: Map<String, Int>) {\n")
            append("  val seed = $edit\n")
            repeat(40) { i ->
                append("  val a$i = s.uppercase().trim().length + ${i} + seed\n")
                append("  val b$i = xs.map { it + $i }.filter { it > $i }.sum()\n")
                append("  val c$i = m.getOrDefault(\"k$i\", 0) + xs.size\n")
            }
            append("}\n")
        }
        var edit = 0
        fun analyzeOnce(): Long {
            analyzer.incrementalParser.parseFull(SnippetDoc(code(++edit), vf))
            return runBlocking { analyzer.analyze(vf) }.diagnostics.size.toLong()
        }

        repeat(3) { analyzeOnce() }
        val suite = RegressionSuite("kotlin-diagnostics-latency")

        val ns = Bench.nsPerOp(warmup = 2, runs = 5, ops = 3) { analyzeOnce() }
        val bytes = Bench.allocPerOp(warmup = 2, ops = 3) { analyzeOnce() }
        println("\n=== Kotlin diagnostics: full analyze pass (member-heavy, Compose-scale) ===\nper pass: ${Bench.ns(ns)}, alloc: ${Bench.bytes(bytes.toDouble())}\n")
        suite.latencyNs("analyze.member-heavy.ns", ns, tolerance = 1.5, ceilingNs = 500_000_000.0)
        suite.allocBytes("analyze.member-heavy.bytes", bytes, tolerance = 0.40, ceilingBytes = 256.0 * 1024 * 1024)

        // Stage breakdown.
        run {
            val lines = ArrayList<String>()
            val sink = LogSink { r -> if (r.tag == "kotlin-perf") lines += r.message }
            Log.addSink(sink); KotlinPerf.enabled = true
            try { repeat(6) { analyzeOnce() } } finally { KotlinPerf.enabled = false; Log.removeSink(sink) }
            println("=== diagnostics stage breakdown (last 3 of ${lines.size}) ===")
            lines.takeLast(3).forEach { println("  $it") }
            println()
        }

        suite.finishAndAssert()
        assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
    }

    companion object {
        fun composeRuntimeJarOrNull(): Path? =
            System.getProperty("java.class.path").split(File.pathSeparator)
                .firstOrNull { it.endsWith(".jar") && runCatching { ZipFile(it).use { z -> z.getEntry("androidx/compose/runtime/Composer.class") != null } }.getOrDefault(false) }
                ?.let { Path.of(it) }
    }
}
