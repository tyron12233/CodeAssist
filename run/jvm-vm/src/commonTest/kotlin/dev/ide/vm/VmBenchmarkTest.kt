package dev.ide.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * What interpreting costs on the platform the test runs on, printed as `VM-BENCH` lines. Asserts only the
 * results, never a time: timings are for reading, and a threshold would flake.
 *
 * A simulator runs arm64 natively on the Mac's CPU, so its numbers are Mac numbers; a phone is slower.
 */
class VmBenchmarkTest {
    private val clock = TimeSource.Monotonic

    private inline fun <T> timed(vm: Vm, label: String, block: () -> T): T {
        val steps0 = vm.steps
        val start = clock.markNow()
        val result = block()
        val micros = start.elapsedNow().inWholeMicroseconds.coerceAtLeast(1)
        val steps = vm.steps - steps0
        println("VM-BENCH $label: ${micros / 1000.0} ms, $steps steps, ${steps * 1_000_000 / micros / 1000}k steps/s, ${vm.loadedClassCount} classes")
        return result
    }

    @Test
    fun throughput() {
        val vm = newTestVm()
        val owner = "dev/ide/vm/fixtures/Basics"
        timed(vm, "loop cold (2M)") { vm.invokeStatic(owner, "loop", "(I)J", 2_000_000) }
        timed(vm, "loop warm (2M)") { vm.invokeStatic(owner, "loop", "(I)J", 2_000_000) }
        val fib = timed(vm, "fib(24)") { vm.invokeStatic(owner, "fib", "(I)I", 24) }
        assertEquals(46368, fib)
    }

    @Test
    fun composeRuntime() {
        val owner = "dev/ide/vm/fixtures/ComposeRuntime"
        val vm = newTestVm()
        val cold = timed(vm, "compose setContent+recompose cold") { vm.invokeStatic(owner, "composeAndRecompose", "()Ljava/lang/String;") }
        val warm = timed(vm, "compose setContent+recompose warm") { vm.invokeStatic(owner, "composeAndRecompose", "()Ljava/lang/String;") }
        assertEquals(cold, warm)
        timed(vm, "intState 10k writes+reads") { vm.invokeStatic(owner, "roundTripIntState", "(I)J", 10_000) }
    }
}
