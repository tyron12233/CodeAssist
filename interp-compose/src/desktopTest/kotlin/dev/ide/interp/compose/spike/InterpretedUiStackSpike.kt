package dev.ide.interp.compose.spike

import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Milestone-A phase B (desktop, fast iteration): the interpreted composer now drives the REAL UI stack. The
 * bytecode VM interprets `androidx.compose.ui` + `androidx.compose.foundation` alongside the runtime and
 * composes a real `Column { Box(); Box() }`, materializing the LayoutNode tree it emits — the render-tree step
 * beyond phase A's slot/remember/recomposition proofs and phase B's synthetic-node emission
 * ([InterpretedComposerSpike.interpretsNodeEmissionThroughApplier]).
 *
 * Only `androidx.compose.{runtime,ui,foundation}` + this spike package are interpreted; the Kotlin/coroutine
 * floor is bridged. The check is the same shape as the rest: run the fixture for real, then interpreted, and
 * assert the emitted tree matches.
 */
class InterpretedUiStackSpike {

    private val OWNER = "dev/ide/interp/compose/spike/UiStackSpikeFixture"

    private fun newVm() = Vm(policy = InterpretPolicy { name ->
        name.startsWith("androidx/compose/runtime/") ||
            name.startsWith("androidx/compose/ui/") ||
            name.startsWith("androidx/compose/foundation/") ||
            name.startsWith("dev/ide/interp/compose/spike/")
    })

    @Test fun interpretsRealUiStackNodeTree() {
        val expected = UiStackSpikeFixture.composeColumnOfBoxes() // run for real
        assertEquals("(((),()))", expected, "sanity: real ui stack emits Column with two Box leaf children")
        val interpreted = newVm().invokeStatic(OWNER, "composeColumnOfBoxes", "()Ljava/lang/String;", emptyList())
        assertEquals(expected, interpreted, "interpreted ui/foundation stack composes the same LayoutNode tree")
    }

    @Test fun interpretsRealUiStackWithTextAndNesting() {
        val expected = UiStackSpikeFixture.composeTextTree() // run for real
        assertEquals(
            "(((),((),())))", expected,
            "sanity: real ui stack emits Column[ BasicText, Row[ BasicText, Box ] ]",
        )
        val interpreted = newVm().invokeStatic(OWNER, "composeTextTree", "()Ljava/lang/String;", emptyList())
        assertEquals(expected, interpreted, "interpreted stack composes BasicText + nested Row/Box to the same tree")
    }
}
