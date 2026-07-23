package dev.ide.interp.compose.spike

import androidx.compose.runtime.Applier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlin.coroutines.EmptyCoroutineContext

/** An applier that builds no node tree: the composition spike exercises the slot table and invalidation only. */
private class UnitApplier : Applier<Unit> {
    override val current: Unit get() = Unit
    override fun down(node: Unit) {}
    override fun up() {}
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun clear() {}
}

/**
 * Drives a full composition life cycle for the interpret spike: an initial composition with `remember` and a
 * state read, then a state write, explicit invalidation, and a controlled recomposition (the Recomposer's
 * scheduler loop is not run; `recompose`/`applyChanges` are driven directly, the way the runtime's own tests
 * do). The returned string captures what the spike asserts: the composed values, the run count, whether the
 * write invalidated the scope, and whether the remembered instance survived recomposition.
 */
object CompositionSpikeFixture {

    @OptIn(InternalComposeApi::class)
    @JvmStatic
    fun composeAndRecompose(): String {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = ControlledComposition(UnitApplier(), recomposer)
        val state = mutableIntStateOf(0)
        val values = StringBuilder()
        var runs = 0
        var firstRemembered: Any? = null
        var rememberSurvived = false
        composition.setContent {
            runs++
            val remembered = remember { Any() }
            if (firstRemembered == null) firstRemembered = remembered
            else rememberSurvived = remembered === firstRemembered
            values.append(state.intValue).append(';')
        }
        state.intValue = 1
        composition.recordModificationsOf(setOf(state))
        val invalidated = composition.recompose()
        composition.applyChanges()
        return "values=$values runs=$runs invalidated=$invalidated rememberSurvived=$rememberSurvived"
    }
}
