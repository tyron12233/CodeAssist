package dev.ide.lang.kotlin

import dev.ide.bench.Bench
import dev.ide.bench.RegressionSuite
import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.parse.KotlinParserHost
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cold-start ("first load takes a while") benchmark for the Kotlin path (opt-in `regressionTest`). Two
 * one-time costs the warm micro-benchmarks never see:
 *
 *  - **env standup** — `KotlinParserHost.warmUp()` stands up the KotlinCoreEnvironment (PSI/parser
 *    infrastructure). It's a process singleton fired lazily on the first `.kt` touch; measured best-effort
 *    (printed, not gated — can't re-cold a singleton in one JVM).
 *  - **index build** — `IndexServiceImpl.ensureUpToDate` segments the library classpath; first completion /
 *    diagnostics on a `.kt` file waits on it. Measured over several real library jars with a FRESH cache each
 *    op (forces a full rebuild, not a cheap segment reopen). This is the gated, optimizable metric — the
 *    artifact loop is the thing to parallelize.
 *
 * Baselines: `baselines/kotlin-coldstart.json`.
 */
@Tag("regression")
class KotlinColdStartBenchmark {

    @Test
    fun coldStartCostsHoldAgainstBaseline() {
        // Best-effort env-standup timing (a no-op if another test already warmed the singleton this worker).
        val warmT0 = System.nanoTime()
        KotlinParserHost.warmUp()
        val warmMs = (System.nanoTime() - warmT0) / 1_000_000
        println("\n=== Kotlin cold start ===\nenv standup (best-effort, singleton): ${warmMs}ms")

        val jars = libraryJars()
        assumeTrue(jars.size >= 3, "need a few library jars on the test classpath to benchmark the index build")
        println("index build over ${jars.size} jars: ${jars.joinToString { it.fileName.toString() }}")

        val suite = RegressionSuite("kotlin-coldstart")

        // Full index build, fresh cache each op so every run is a real rebuild (not a segment reopen).
        val buildNs = Bench.nsPerOp(warmup = 1, runs = 3, ops = 1) {
            val cache = Files.createTempDirectory("coldstart-idx")
            try {
                IndexServiceImpl(listOf(KotlinTypeShapeIndex, KotlinCallableIndex), cacheRoot = cache).use { idx ->
                    runBlocking { idx.ensureUpToDate(IndexScope(libraryJars = jars)) }
                }
            } finally {
                cache.toFile().deleteRecursively()
            }
            jars.size.toLong()
        }
        println("index build (fresh cache, ${jars.size} jars): ${Bench.ns(buildNs)}\n")
        // Loose drift (build time is machine + jar-set dependent) + a generous absolute ceiling: this guards
        // against cold start getting dramatically worse, and measures the parallelization win.
        suite.latencyNs("index-build.ns", buildNs, tolerance = 1.5, ceilingNs = 60_000_000_000.0)

        suite.finishAndAssert()
        assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
    }

    /** Real library jars on the test classpath worth indexing: 200 KB..8 MB, Kotlin/Compose/AndroidX shaped. */
    private fun libraryJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .asSequence()
            .filter { it.endsWith(".jar") }
            .map { Path.of(it) }
            .filter { Files.isRegularFile(it) }
            .filter { val n = it.fileName.toString(); n.contains("kotlin") || n.contains("compose") || n.contains("coroutines") || n.contains("androidx") || n.contains("material") || n.contains("runtime") }
            .filter { val sz = runCatching { Files.size(it) }.getOrDefault(0L); sz in 200_000..8_000_000 }
            .distinctBy { it.fileName.toString() }
            .sortedByDescending { runCatching { Files.size(it) }.getOrDefault(0L) }
            .take(8)
            .toList()
}
