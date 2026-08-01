package dev.ide.interp.compose.spike

import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Milestone-A phase 1 (desktop, fast iteration): prove the bytecode VM can drive an interpreted `Composer` through
 * a real COMPOSITION — `setContent` + `remember` — not just snapshot state (see [ComposeRuntimeInterpretSpike]).
 * This is the core the bridged-runtime "material3 flip" hits a version ceiling on: interpreted composables driving
 * the REAL host composer diverge in positional memoization across ~4 Compose versions. If the composer itself
 * interprets correctly here, the fix is interpreting the runtime at the PROJECT's version, and this is step 1.
 *
 * Only `androidx.compose.runtime` + this spike package are interpreted; the Kotlin/coroutine floor is bridged.
 */
class InterpretedComposerSpike {

    private val OWNER = "dev/ide/interp/compose/spike/ComposerSpikeFixture"

    private fun newVm() = Vm(policy = InterpretPolicy { name ->
        name.startsWith("androidx/compose/runtime/") || name.startsWith("dev/ide/interp/compose/spike/")
    })

    @Test fun interpretsSetContentAndRemember() {
        val expected = ComposerSpikeFixture.rememberRunsOnce() // run for real: 1
        val interpreted = newVm().invokeStatic(OWNER, "rememberRunsOnce", "()I", emptyList())
        assertEquals(expected, interpreted, "remember calc runs exactly once through an interpreted composition")
        assertEquals(1, expected, "sanity: a single composeInitial runs the remember calculation once")
    }

    @Test fun interpretsMultiSlotGroupPositionalMemoization() {
        val expected = ComposerSpikeFixture.multiSlotGroups() // run for real
        val interpreted = newVm().invokeStatic(OWNER, "multiSlotGroups", "()Ljava/lang/String;", emptyList())
        assertEquals("start|a|b|k0|k1|k2|end", expected, "sanity: real composer lays out the slots/groups in order")
        assertEquals(expected, interpreted, "interpreted composer keeps distinct slots across sequential + keyed groups")
    }

    @Test fun interpretsRecompositionOnStateChange() {
        val expected = ComposerSpikeFixture.recomposesOnStateChange() // run for real
        assertEquals(2, expected, "sanity: real composer recomposes the state-reading scope once (2 body runs)")
        val interpreted = newVm().invokeStatic(OWNER, "recomposesOnStateChange", "()I", emptyList())
        assertEquals(expected, interpreted, "interpreted composer drives Recomposer-loop recomposition on a state write")
    }
}
