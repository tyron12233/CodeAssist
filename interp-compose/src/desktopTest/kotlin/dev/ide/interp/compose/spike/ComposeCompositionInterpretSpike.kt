package dev.ide.interp.compose.spike

import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composition-entry feasibility spike: the bytecode VM runs a FULL composition life cycle through the real
 * `androidx.compose.runtime` — Recomposer, ControlledComposition, the slot table (`remember`), a snapshot
 * state write, invalidation, and a controlled recomposition — checked against the same fixture run for real.
 * Complements ComposeRuntimeInterpretSpike, which covers the snapshot state system only. The printed
 * VM-BENCH-COMPOSE line is the measurement.
 */
class ComposeCompositionInterpretSpike {

    private val OWNER = "dev/ide/interp/compose/spike/CompositionSpikeFixture"

    @Test fun composesRemembersAndRecomposesInterpreted() {
        val vm = Vm(policy = InterpretPolicy { name ->
            name.startsWith("androidx/compose/runtime/") || name.startsWith("dev/ide/interp/compose/spike/")
        })
        val before = vm.steps
        val t0 = System.nanoTime()
        val out = vm.invokeStatic(OWNER, "composeAndRecompose", "()Ljava/lang/String;")
        val ns = System.nanoTime() - t0
        assertEquals("values=0;1; runs=2 invalidated=true rememberSurvived=true", out, "interpreted composition life cycle")
        assertEquals(CompositionSpikeFixture.composeAndRecompose(), out, "interpreted output matches the real runtime")
        println("VM-BENCH-COMPOSE composition: setContent+recompose = ${vm.steps - before} ops in ${ns / 1_000_000}ms")
    }
}
