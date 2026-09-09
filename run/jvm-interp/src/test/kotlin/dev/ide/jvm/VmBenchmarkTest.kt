package dev.ide.jvm

import dev.ide.jvm.fixtures.Bench
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rough throughput measurement, not a precise benchmark: it prints per-instruction and per-call rates (which
 * appear in the test report's captured output) and asserts only a low floor, so it catches a catastrophic
 * regression without being timing-flaky. The per-call figure reflects the recomposition pattern (a small method
 * re-invoked many times), which is what the decoded-method cache targets.
 */
class VmBenchmarkTest {

    private val vm = Vm()
    private val B = "dev/ide/jvm/fixtures/Bench"

    @Test fun throughput() {
        assertEquals(Bench.sumTo(1000), vm.invokeStatic(B, "sumTo", "(I)J", listOf(1000)))
        assertEquals(Bench.fib(20), vm.invokeStatic(B, "fib", "(I)I", listOf(20)))

        // Per-instruction: one long loop. sumTo's body is ~8 bytecode ops per iteration.
        repeat(20) { vm.invokeStatic(B, "sumTo", "(I)J", listOf(200_000)) }
        val iters = 3_000_000
        val loopStart = System.nanoTime()
        vm.invokeStatic(B, "sumTo", "(I)J", listOf(iters))
        val loopNs = System.nanoTime() - loopStart
        val opsPerSec = iters * 8L * 1_000_000_000L / loopNs
        println("VM-BENCH loop: $iters iters in ${loopNs / 1_000_000}ms  (~${opsPerSec / 1_000_000}M bytecode ops/sec)")

        // Per-call: many invocations of a small method (the recomposition shape).
        repeat(50) { vm.invokeStatic(B, "fib", "(I)I", listOf(20)) }
        val calls = 500_000
        val callStart = System.nanoTime()
        repeat(calls) { vm.invokeStatic(B, "fib", "(I)I", listOf(20)) }
        val callNs = System.nanoTime() - callStart
        println("VM-BENCH calls: $calls fib(20) calls in ${callNs / 1_000_000}ms  (${callNs / calls}ns/call, ~${calls * 1_000_000_000L / callNs} calls/sec)")

        assertTrue(opsPerSec > 1_000_000, "interpreter throughput regressed badly: $opsPerSec ops/sec")
    }
}
