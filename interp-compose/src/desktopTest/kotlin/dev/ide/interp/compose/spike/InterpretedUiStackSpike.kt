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

    /** Broadens [newVm] to also interpret `androidx.compose.material3` (and its `material`/`animation` deps), for
     *  the spike that composes a material3 composable on the fully-interpreted stack. */
    private fun newVmWithMaterial3() = Vm(policy = InterpretPolicy { name ->
        name.startsWith("androidx/compose/runtime/") ||
            name.startsWith("androidx/compose/ui/") ||
            name.startsWith("androidx/compose/foundation/") ||
            name.startsWith("androidx/compose/material3/") ||
            name.startsWith("androidx/compose/material/") ||
            name.startsWith("androidx/compose/animation/") ||
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

    @Test fun interpretsMaterial3Text() {
        val expected = UiStackSpikeFixture.composeMaterialText() // run for real
        assertEquals("(((),()))", expected, "sanity: real material3 Text emits a Column with two text leaves")
        val interpreted = newVmWithMaterial3().invokeStatic(OWNER, "composeMaterialText", "()Ljava/lang/String;", emptyList())
        assertEquals(expected, interpreted, "interpreted material3 Text composes the same LayoutNode tree")
    }

    @Test fun interpretsMaterial3Button() {
        val expected = UiStackSpikeFixture.composeButton() // run for real
        val interpreted = newVmWithMaterial3().invokeStatic(OWNER, "composeButton", "()Ljava/lang/String;", emptyList())
        assertEquals(expected, interpreted, "interpreted material3 Button composes the same LayoutNode tree")
        assertEquals("(((())))", expected, "sanity: real material3 Button emits the Surface->Row->Text node chain")
    }
}
