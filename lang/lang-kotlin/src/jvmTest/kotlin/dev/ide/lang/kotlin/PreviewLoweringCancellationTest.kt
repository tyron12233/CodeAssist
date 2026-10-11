package dev.ide.lang.kotlin

import dev.ide.platform.CancelToken
import dev.ide.platform.EngineCancellation
import dev.ide.platform.EngineCanceledException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Preview lowering gives the engine worker back between declarations: a preempted lowering stops with
 * [EngineCanceledException] (so the preview lane retries it) instead of holding the worker to the end, and the
 * stop is never mistaken for a lowering failure and cached as one.
 */
class PreviewLoweringCancellationTest {

    private fun source(): String = buildString {
        append("package app\n")
        append("data class Model(val a: Int, val b: String)\n")
        repeat(12) { i -> append("fun helper$i(p: Int): Int { val xs = listOf(p, $i).map { it * 2 }; return xs.sum() }\n") }
        append("fun Entry() { val m = Model(1, \"x\"); println(helper3(m.a) + helper7(m.a)) }\n")
    }

    @Test
    fun aPreemptedLoweringStopsAndTheRetryIsComplete() {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val vf = DiskFile(srcDir.resolve("Preview.kt"))
        analyzer.incrementalParser.parseFull(SnippetDoc(source(), vf))

        val preempted = CancelToken().apply { cancel() }
        assertFailsWith<EngineCanceledException> {
            runBlocking { EngineCancellation.withToken(preempted) { analyzer.lowerFile(vf) } }
        }

        val program = analyzer.lowerFile(vf)
        assertEquals(13, program.size, "every declaration lowers on the retry: ${program.keys}")
        val failed = program.values.flatMap { it.diagnostics }.filter { "lowering failed" in it.reason }
        assertTrue(failed.isEmpty(), "a preemption was cached as a lowering failure: $failed")
    }

    /**
     * How long a RUNNING lowering takes to hand the worker back once preempted: about one declaration, not the
     * rest of the file. Measured on a cold lowering of a big file (the case that held completion for its whole
     * length), against the same lowering run to the end.
     */
    @Test
    fun aRunningLoweringYieldsWithinADeclaration() {
        val text = buildString {
            append("package app\n")
            repeat(800) { i ->
                append("fun f$i(xs: List<Int>): Int { val d = xs.map { it * 2 }.filter { it > $i }; ")
                append("val s = d.map { n -> \"v\$n\" }.joinToString(\", \"); return s.length + d.fold(0) { a, v -> a + v } }\n")
            }
        }
        fun freshFile(name: String): Pair<KotlinSourceAnalyzer, DiskFile> {
            val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
            val vf = DiskFile(srcDir.resolve(name))
            analyzer.incrementalParser.parseFull(SnippetDoc(text, vf))
            return analyzer to vf
        }
        // Warm the JIT + classpath on one analyzer, then time a cold lowering to the end on another.
        freshFile("Warm.kt").let { (a, vf) -> a.lowerFile(vf) }
        val fullMs = freshFile("Full.kt").let { (a, vf) ->
            val t0 = System.nanoTime(); a.lowerFile(vf); (System.nanoTime() - t0) / 1_000_000
        }

        val (analyzer, vf) = freshFile("Yield.kt")
        val token = CancelToken()
        val canceledAt = AtomicLong()
        val canceller = Thread {
            Thread.sleep(fullMs / 4)
            canceledAt.set(System.nanoTime())
            token.cancel()
        }.apply { start() }
        assertFailsWith<EngineCanceledException> {
            runBlocking { EngineCancellation.withToken(token) { analyzer.lowerFile(vf) } }
        }
        val exitedAt = System.nanoTime()
        canceller.join()
        val yieldMs = (exitedAt - canceledAt.get()) / 1_000_000
        println("\n=== Preview lowering preemption ===\nfull lowering: ${fullMs}ms  yield after preemption: ${yieldMs}ms\n")
        assertTrue(yieldMs < fullMs / 2, "a preempted lowering kept the worker ${yieldMs}ms of a ${fullMs}ms lowering")
    }

    companion object {
        val srcDir = tempProject(mapOf("placeholder.kt" to "package app\n"))
    }
}
