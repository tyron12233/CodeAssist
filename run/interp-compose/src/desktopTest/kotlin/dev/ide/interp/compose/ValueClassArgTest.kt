package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression (found on ART by ResourcePreviewOnDeviceTest): a BOXED value class reaching a composable parameter
 * typed as its unboxed underlying must be unboxed by [ComposableAbi]. `Color` is `value class Color(val value:
 * ULong)` — its underlying is ITSELF a value class over `long` — so a `colorResource(...)` value (a boxed Color)
 * reaching a mangled `long` param needs RECURSIVE unboxing (Color→ULong→long). Exercised here on the JVM with a
 * real Compose `Color` fed to a composable whose transformed JVM param is `long`.
 */
class ValueClassArgTest {

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.ValueClassArgTestKt"

    @Test
    fun aBoxedNestedValueClassArgUnboxesForAComposableParam() {
        ColorCapture.argb = 0
        // FakeColorSink(<a boxed Color>) — mirrors `FakeColorSink(colorResource(R.color.x))`.
        val call = RNode.Call(
            ResolvedCallable.Library(
                displayName = "FakeColorSink", ownerFqn = facade, methodName = "FakeColorSink",
                paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const(Color(0xFFEE1122.toInt()), null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(call), isExpression = false, span), emptyList())

        var failure: Throwable? = null
        composeOnce { ComposePreviewRenderer().Render(entry, emptyMap(), onError = { failure = it }) }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(0xFFEE1122.toInt(), ColorCapture.argb, "a boxed Color must reach the composable unboxed (Color→ULong→long)")
    }

    // --- headless composition harness (no UI) ---
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

object ColorCapture {
    var argb: Int = 0
}

/** A composable whose only value parameter is a `Color` — its transformed JVM method takes the unboxed `long`. */
@Composable
fun FakeColorSink(color: Color) {
    ColorCapture.argb = color.toArgb()
}
