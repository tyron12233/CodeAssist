package dev.ide.vm.fixtures

import androidx.compose.runtime.Applier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.coroutines.EmptyCoroutineContext

/** An applier that builds no node tree: the composition driver exercises the slot table and invalidation only. */
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
 * Drivers for the real `androidx.compose.runtime`: under the VM, the whole snapshot system, slot table and
 * recomposer run interpreted from the runtime's own jar, with nothing below them but the JDK floor.
 */
object ComposeRuntime {

    /** Create a primitive int state, then write and read it in a loop. Returns the sum of the reads. */
    @JvmStatic
    fun roundTripIntState(writes: Int): Long {
        val state = mutableIntStateOf(0)
        var sum = 0L
        for (i in 0 until writes) {
            state.intValue = i
            sum += state.intValue
        }
        return sum
    }

    /** Create a boxed state and toggle it, returning the final length (the general state path). */
    @JvmStatic
    fun roundTripBoxedState(writes: Int): Int {
        var text by mutableStateOf("")
        for (i in 0 until writes) text = if (text.length > 3) "" else text + "x"
        return text.length
    }

    /** An initial composition with `remember` and a state read, a write, and a controlled recomposition. */
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
