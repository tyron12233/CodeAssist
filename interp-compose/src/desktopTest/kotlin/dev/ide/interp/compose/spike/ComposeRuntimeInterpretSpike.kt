package dev.ide.interp.compose.spike

import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Feasibility spike: interpret the REAL `androidx.compose.runtime` snapshot state system (creating a state and
 * reading/writing it) with the bytecode VM, checking correctness against the same code run for real and
 * reporting the interpreted-op cost per state operation. Only `androidx.compose.runtime` and this package are
 * interpreted; the Kotlin/Java/atomic floor is bridged. The printed VM-BENCH-COMPOSE lines are the measurement.
 */
class ComposeRuntimeInterpretSpike {

    private val OWNER = "dev/ide/interp/compose/spike/RuntimeSpikeFixture"

    private fun newVm() = Vm(policy = InterpretPolicy { name ->
        name.startsWith("androidx/compose/runtime/") || name.startsWith("dev/ide/interp/compose/spike/")
    })

    @Test fun interpretsSnapshotIntState() {
        val vm = newVm()
        val small = vm.invokeStatic(OWNER, "roundTripIntState", "(I)J", listOf(5))
        assertEquals(RuntimeSpikeFixture.roundTripIntState(5), small, "interpreted int-state round trip")

        vm.invokeStatic(OWNER, "roundTripIntState", "(I)J", listOf(2_000)) // warm caches
        val n = 20_000
        val before = vm.steps
        val t0 = System.nanoTime()
        val result = vm.invokeStatic(OWNER, "roundTripIntState", "(I)J", listOf(n))
        val ns = System.nanoTime() - t0
        val steps = vm.steps - before
        assertEquals(RuntimeSpikeFixture.roundTripIntState(n), result)
        println(
            "VM-BENCH-COMPOSE intState: $n write+read cycles = $steps interpreted ops in ${ns / 1_000_000}ms " +
                "(~${steps * 1000 / maxOf(ns, 1)}M ops/sec, ${steps / n} ops per cycle, ${ns / n}ns per cycle)",
        )
    }

    @Test fun interpretsBoxedState() {
        val vm = newVm()
        val small = vm.invokeStatic(OWNER, "roundTripBoxedState", "(I)I", listOf(7))
        assertEquals(RuntimeSpikeFixture.roundTripBoxedState(7), small, "interpreted boxed-state round trip")

        vm.invokeStatic(OWNER, "roundTripBoxedState", "(I)I", listOf(1_000)) // warm
        val n = 10_000
        val before = vm.steps
        val t0 = System.nanoTime()
        vm.invokeStatic(OWNER, "roundTripBoxedState", "(I)I", listOf(n))
        val ns = System.nanoTime() - t0
        val steps = vm.steps - before
        println(
            "VM-BENCH-COMPOSE boxedState: $n writes = $steps interpreted ops in ${ns / 1_000_000}ms " +
                "(~${steps * 1000 / maxOf(ns, 1)}M ops/sec, ${steps / n} ops per write)",
        )
    }
}
