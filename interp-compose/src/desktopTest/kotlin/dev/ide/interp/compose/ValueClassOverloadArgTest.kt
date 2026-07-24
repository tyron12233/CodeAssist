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
 * Sibling of [ValueClassArgTest] for the OVERLOAD-SELECTION path. A `@Composable` whose value parameter is an
 * inline value class (`color: Color` → mangled name, unboxed `long` param) fed a BOXED `Color` (as
 * `State<Color>.value` / `animateColorAsState` produce) must still select the correct overload. Before the fix,
 * `ComposableAbi.firstParamsAccept` rejected the boxed value class at the mangled `long` slot (it handled only
 * the unboxed→boxed direction), so a real overload with a value-class param — mirroring `Text(String,…)` vs
 * `Text(AnnotatedString,…)`, both carrying a `color` — was wrongly rejected during selection.
 *
 * The fixture makes the hazard DETERMINISTIC: the wrong [Badge] overload has a broad `Any` second parameter
 * that ACCEPTS the boxed `Color`, and an extra (defaulted) third parameter so the correct 2-param overload wins
 * the fewest-params tiebreak once BOTH accept. Pre-fix only the wrong (`Any`) overload accepts → it is
 * mis-selected → `which == "wrong:hi"`. Post-fix the value-class overload also accepts and, being narrower
 * (fewer params), is chosen → `which == "string:hi"`. (With a same-arity sibling the pre-fix pick is instead
 * order-dependent — the same latent bug, just non-deterministic — which is why the sibling differs in arity.)
 */
class ValueClassOverloadArgTest {

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.ValueClassOverloadArgTestKt"

    @Test
    fun aBoxedValueClassArgSelectsTheRightOverloadAmongSiblings() {
        BadgeCapture.which = ""
        BadgeCapture.argb = 0
        // Badge("hi", <boxed Color>) — the value-class (`Color`) overload must win over the broad `Any` sibling.
        val call = RNode.Call(
            ResolvedCallable.Library(
                displayName = "Badge", ownerFqn = facade, methodName = "Badge",
                paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(
                RArg(RNode.Const("hi", null, span)),
                RArg(RNode.Const(Color(0xFF6750A4.toInt()), null, span)),
            ),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(call), isExpression = false, span), emptyList())

        var failure: Throwable? = null
        composeOnce { ComposePreviewRenderer().Render(entry, emptyMap(), onError = { failure = it }) }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals("string:hi", BadgeCapture.which, "the value-class `Color` overload must be selected, not the broad `Any` sibling")
        assertEquals(0xFF6750A4.toInt(), BadgeCapture.argb, "the boxed Color arg must reach the composable unboxed")
    }

    // --- headless composition harness (no UI), mirroring ValueClassArgTest ---
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

object BadgeCapture {
    var which: String = ""
    var argb: Int = 0
}

/** The correct overload: a value-class `color: Color` at the second slot (→ mangled JVM name, unboxed `long`
 *  param). A boxed `Color` arg is only accepted here once selection sees through the value class. */
@Composable
fun Badge(label: String, color: Color) {
    BadgeCapture.which = "string:$label"
    BadgeCapture.argb = color.toArgb()
}

/** The decoy overload: a broad `Any` second slot that accepts the boxed `Color` unconditionally, plus a third
 *  (defaulted) param so it is NOT chosen once the value-class overload also accepts (fewest-params tiebreak).
 *  Pre-fix this is the ONLY accepting overload, so it is wrongly selected. */
@Composable
fun Badge(label: String, tag: Any, note: Int = 0) {
    BadgeCapture.which = "wrong:$label"
    BadgeCapture.argb = 0
}
