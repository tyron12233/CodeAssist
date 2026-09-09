package dev.ide.interp.compose.spike

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableIntStateOf
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import dev.ide.jvm.VmMethodView
import dev.ide.jvm.interpretedMethods
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The composer-threading spike: a Compose-plugin-COMPILED composable (transformed bytecode — Composer
 * parameter, `$changed`, restart group, inlined `remember` slot calls) runs INTERPRETED in the bytecode VM,
 * with the real runtime bridged: the live Composer crosses in as an argument, slot-table calls dispatch on
 * the real ComposerImpl, the interpreted restart lambda crosses out as a proxy the real Recomposer re-invokes
 * on invalidation. This is the execution model for a library composable that exists only in a project jar.
 */
class ComposerThreadingInterpretSpike {

    private val facade = "dev.ide.interp.compose.composerfixture.ComposerFixtureKt"

    private fun vm() = Vm(policy = InterpretPolicy { it.startsWith("dev/ide/interp/compose/composerfixture/") })

    private fun composerView(vm: Vm, name: String): VmMethodView =
        vm.interpretedMethods(facade).first { v ->
            v.name == name && v.paramDescriptors.contains("Landroidx/compose/runtime/Composer;")
        }

    @OptIn(InternalComposeApi::class)
    @Test fun interpretedComposableComposesAndRecomposesThroughTheRealRuntime() {
        val vm = vm()
        val view = composerView(vm, "CountingLabel")
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = ControlledComposition(UnitApplier, recomposer)
        val state = mutableIntStateOf(0)
        val log = mutableListOf<String>()
        composition.setContent {
            view.invoke(null, listOf(state, log, currentComposer, 0))
        }
        assertEquals(listOf("run:0:0"), log, "initial composition ran the interpreted body")

        state.intValue = 5
        composition.recordModificationsOf(setOf(state))
        assertTrue(composition.recompose(), "the interpreted body's state read registered the snapshot dependency")
        composition.applyChanges()
        assertEquals(listOf("run:0:0", "run:5:10"), log, "recomposition re-entered the VM through the restart-lambda proxy")

        composition.dispose()
        recomposer.cancel()
    }

    @Test fun realContentLambdaCrossesIntoTheInterpretedWrapper() {
        val vm = vm()
        val view = composerView(vm, "Wrapper")
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(UnitApplier, recomposer)
        val log = mutableListOf<String>()
        val content: @Composable () -> Unit = { log.add("inner") }
        composition.setContent {
            view.invoke(null, listOf(log, content, currentComposer, 0))
        }
        assertEquals(listOf("wrap-start", "inner", "wrap-end"), log, "the real content lambda composed inside the interpreted wrapper")
        composition.dispose()
        recomposer.cancel()
    }

    private object UnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }
}
