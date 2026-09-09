package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Regression for the reported `ABI invoke mismatch for androidx.compose.material3.TextKt.Text-…:
 * params=[String, Modifier, …] args=[java.lang.String, java.lang.Long, …]` — a value (here a value-class
 * `long`) bound to `Text`'s typed `Modifier` slot. Reflection threw an argument-type mismatch that unwound
 * the whole composition (the preview failed instead of rendering). [ComposableAbi.call] now drops a supplied
 * argument that can't fit its parameter and lets the composable use its own default, so this no longer throws
 * the mismatch. Runs against the REAL Material3 on the desktop test classpath.
 */
class Material3TextModifierFallbackTest {

    @Test
    fun aLongOnTheModifierSlotDoesNotProduceAnAbiInvokeMismatch() {
        // `Text("Title", <long>)` shape: the second arg is bound positionally to `modifier: Modifier`, where a
        // `long` can't go. Before the fix this surfaced as `IllegalArgumentException: argument type mismatch`
        // (re-thrown as "ABI invoke mismatch"); after it, the bad arg is dropped and the modifier defaults.
        val outcome = runCatching {
            composeOnce {
                val composer: Any = currentComposer
                ComposableAbi.startGroup(composer, 4321)
                try {
                    ComposableAbi.call(
                        ownerFqn = "androidx.compose.material3.TextKt",
                        method = "Text",
                        originalArgs = listOf<Any?>("Title", 0L),
                        composer = composer,
                        declaredParamCount = 16,
                    )
                } finally {
                    ComposableAbi.endGroup(composer)
                }
            }
        }.exceptionOrNull()
        // The invoke either succeeds or fails only because this headless harness has no Compose UI environment
        // (e.g. `CompositionLocal LocalFontFamilyResolver not present`) — never an argument-type mismatch.
        val root = rootCause(outcome)
        assertFalse(
            root.contains("argument type mismatch") || root.contains("ABI invoke mismatch"),
            "a non-fitting modifier arg must fall back to the default, not crash the ABI invoke; got: $root",
        )
    }

    private fun rootCause(t: Throwable?): String {
        if (t == null) return "OK (no exception)"
        var cur: Throwable = t
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return "${cur.javaClass.name}: ${cur.message}"
    }

    private val recomposers = ArrayList<Recomposer>()
    @AfterTest fun tearDown() = recomposers.forEach { it.cancel() }

    private fun composeOnce(content: @Composable () -> Unit) {
        val recomposer = Recomposer(CoroutineScope(BroadcastFrameClock()).coroutineContext)
        recomposers += recomposer
        val composition = Composition(UnitApplier, recomposer)
        composition.setContent(content)
        composition.dispose()
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
