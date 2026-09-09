package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Regression (the reported "clicking a compose menu item crashes Code Assist"): when a previewed composable's
 * event handler evaluates to null — an unprovided / null `onClick` — the interpreter used to bind a raw null to
 * the composable's non-null `() -> Unit` parameter. The real Compose runtime then stored that null and invoked
 * it WITHOUT a null-check on the next tap (`ClickableNode.onPointerEvent` → `onClick.invoke()`), a FATAL NPE on
 * the UI thread OUTSIDE the preview's render try/catch — so a tap on the preview killed the whole app.
 * [ComposableAbi.call] now substitutes a no-op for a null event-handler argument, so the tap is inert.
 */
class NullEventHandlerTest {

    @BeforeTest fun reset() { OnClickCapture.captured = null }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.NullEventHandlerTestKt"

    @Test
    fun nullOnClickIsCoercedToANoOp() = assertInertOnClick(RNode.Const(null, null, span))

    @Test
    fun unitResultOnClickIsCoercedToANoOp() =
        // `onClick = onItemClick(route)` (the reported shape — a mistake for `onClick = { onItemClick(route) }`)
        // evaluates to the handler's `Unit` RESULT, which is not a `() -> Unit`.
        assertInertOnClick(RNode.Const(Unit, null, span))

    /** Render `FakeClickable(onClick = <arg>)` through the interpreter and assert the captured onClick is a
     *  non-null, safe-to-invoke no-op — i.e. a tap would be inert rather than a fatal NPE. */
    private fun assertInertOnClick(onClickArg: RNode) {
        val call = RNode.Call(
            ResolvedCallable.Library("FakeClickable", facade, "FakeClickable", listOf(null), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(onClickArg)),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val root = ResolvedFunction("Root", emptyList(), RNode.Block(listOf(call), false, span), emptyList(), returnsUnit = true)

        val dispatcher = ComposeDispatcher()
        val runtime = ComposeRuntime(dispatcher)
        val interpreter = Interpreter(functions = emptyMap(), dispatcher = dispatcher, composableInvoker = runtime)

        composeOnce {
            dispatcher.composer = currentComposer
            try {
                runtime.invokeComposable(0x1, restartable = false, force = false, args = emptyList()) {
                    interpreter.call(root, emptyList())
                }
            } finally {
                dispatcher.composer = null
            }
        }

        val onClick = assertNotNull(
            OnClickCapture.captured,
            "a non-function onClick must be coerced to a no-op, not bound as null (a null NPE-crashes the app on tap)",
        )
        onClick() // the no-op must be safe to invoke — a preview tap is inert, never fatal
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

/** Records the `onClick` the interpreter binds — a stand-in for the real Compose clickable, which stores the
 *  handler at composition and invokes it on tap. */
object OnClickCapture {
    var captured: (() -> Unit)? = null
}

@Composable
fun FakeClickable(onClick: () -> Unit) {
    OnClickCapture.captured = onClick
}
