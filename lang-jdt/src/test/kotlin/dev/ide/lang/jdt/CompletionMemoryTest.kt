package dev.ide.lang.jdt

import dev.ide.bench.Bench
import dev.ide.bench.MemoryProbe
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
 * Heap-**occupancy** regression suite (opt-in: `./gradlew :lang-jdt:regressionTest`). Where
 * [CompletionBenchmark] tracks per-keystroke allocation *churn* (bytes the GC immediately reclaims, the
 * transient pressure signal), this tracks memory that *stays resident* — what makes the IDE get killed on
 * a memory-constrained device. Two metrics, both measured after a forced GC settle so they reflect
 * occupancy and not GC timing, against `baselines/completion-memory.json`:
 *
 *  - **per-analyzer retained heap** — the marginal cost of opening another file/module: the used-heap delta
 *    from creating + warming a *second* analyzer on top of a first (the process-global jrt image is already
 *    built, so this isolates the per-module caches — jar handles, package sets, the source index).
 *  - **session growth (leak guard)** — used heap after a long simulated editing session minus a settled
 *    floor taken after warmup. A healthy engine reaches steady state; *unbounded growth here is a leak* (a
 *    per-keystroke cache that never evicts, accumulated jar handles, listeners never disposed — exactly the
 *    class of bug the env-cache work had to fix). This is the suite's load-bearing assertion.
 *
 * Peak heap during an un-GC'd burst is deliberately not gated: that measures how lazily the
 * collector ran this run, not memory the engine holds — the deterministic `alloc.*` metrics in
 * [CompletionBenchmark] are the churn signal. Heap numbers are noisier than allocation counts, so the
 * drift tolerances are generous; the leak guard adds an absolute ceiling so a real leak fails the build
 * regardless of where the baseline sits.
 */
@Tag("regression")
class CompletionMemoryTest {

    private fun unit(body: String): String = """
        package app;
        import java.util.List;
        import java.util.ArrayList;
        class Service {
            private final List<String> names = new ArrayList<>();
            private int total;
            String render(StringBuilder sb, String prefix) {
                $body
                return sb.toString();
            }
        }
    """.trimIndent()

    /** A completion request whose text (and version) varies with [i], so each call forces a fresh reparse. */
    private fun req(file: Path, i: Int): CompletionRequest {
        val text = unit("int v$i = $i; sb.app|CARET|")
        val off = text.indexOf("|CARET|")
        val clean = text.replace("|CARET|", "")
        return CompletionRequest(MemSnapshot(StubFile(file.toString(), clean), i.toLong() + 1, clean), off, CompletionTrigger.Explicit)
    }

    private fun newWarmAnalyzer(): Pair<JdtSourceAnalyzer, Path> {
        val dir = Files.createTempDirectory("completion-mem")
        val f = dir.resolve("app/Service.java")
        Files.createDirectories(f.parent)
        Files.writeString(f, unit("sb.append(prefix);"))
        val a = analyzer(listOf(dir))
        a.indexService = fakeIndex()
        runSync { a.completion.complete(req(f, 0)) } // build the env cache (+ process-global jrt image)
        return a to dir
    }

    @Test
    fun completionMemoryHoldsAgainstBaseline() {
        val suite = RegressionSuite("completion-memory")

        // --- A. marginal per-analyzer retained heap (second analyzer on top of the first) ---
        val (a1, d1) = newWarmAnalyzer()
        val s0 = MemoryProbe.settledUsedHeap()
        val (a2, d2) = newWarmAnalyzer()
        val s1 = MemoryProbe.settledUsedHeap()
        val perAnalyzer = (s1 - s0).coerceAtLeast(0)
        Bench.sink += a1.hashCode().toLong() + a2.hashCode().toLong() // keep both alive across the measure
        a1.dispose(); a2.dispose(); d1.toFile().deleteRecursively(); d2.toFile().deleteRecursively()

        // --- B. session growth (leak guard): steady state must hold over a long edit/complete session ---
        val (a4, d4) = newWarmAnalyzer()
        val f4 = d4.resolve("app/Service.java")
        repeat(50) { i -> Bench.sink += runSync { a4.completion.complete(req(f4, i)) }.items.size.toLong() } // warm
        val l0 = MemoryProbe.settledUsedHeap()
        repeat(300) { i -> Bench.sink += runSync { a4.completion.complete(req(f4, 50 + i)) }.items.size.toLong() }
        val l1 = MemoryProbe.settledUsedHeap()
        val sessionGrowth = (l1 - l0).coerceAtLeast(0)
        a4.dispose(); d4.toFile().deleteRecursively()

        println("\n=== completion memory occupancy ===\n" +
            "per-analyzer retained : ${Bench.bytes(perAnalyzer.toDouble())}\n" +
            "session growth        : ${Bench.bytes(sessionGrowth.toDouble())} (over 300 edits, post-warmup)\n")

        suite.heapBytes("cache.perAnalyzer.bytes", perAnalyzer, tolerance = 1.0, ceilingBytes = 96.0 * 1024 * 1024)
        // The leak guard: generous drift, but a hard 48 MB ceiling — a healthy session barely grows.
        suite.heapBytes("session.growth.bytes", sessionGrowth, tolerance = 1.0, ceilingBytes = 48.0 * 1024 * 1024)
        suite.finishAndAssert()

        assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
    }
}

private class MemSnapshot(
    override val file: VirtualFile,
    override val version: Long,
    override val text: CharSequence,
) : DocumentSnapshot {
    override fun length() = text.length
}
