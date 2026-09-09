package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the preview failure `Column(Modifier.verticalScroll(rememberScrollState())) { repeat(50) {
 * Text(...) } }` blowing up with a Compose "Start/end imbalance". The root cause: `repeat` is
 * `@kotlin.internal.InlineOnly`, so it has NO callable JVM method — the reflective dispatcher threw
 * mid-composition (leaving Compose groups open). The interpreter now executes the `@InlineOnly` scope
 * functions as intrinsics (running the interpreted lambda in-process), which resolves the missing-method error
 * AND keeps the ambient composer intact so composables inside `repeat` compose into the enclosing group —
 * exactly as the inlined form would.
 *
 * Runs the exact lowered structure against REAL Compose-transformed fake composables (so it executes
 * headlessly) with the REAL stdlib `repeat`. Also exercises: a composable result in a local `val`, a
 * non-composable extension on an object receiver, and a named modifier arg + trailing content lambda.
 */
class InlineOnlyScopeFunctionTest {

    @BeforeTest fun reset() { ItemCapture.items.clear(); ItemCapture.scrollState = -1 }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    @Test
    fun repeatOfComposableInsideColumnComposesEveryItem() {
        // val scrollState = fakeRememberScrollState()
        val rememberCall = RNode.Call(
            lib("fakeRememberScrollState", facade, isComposable = true), DispatchKind.TOP_LEVEL,
            receiver = null, args = emptyList(), callSiteKey = CallSiteKey(1), source = span,
        )
        val scrollSlot = SlotId(0)
        val localVar = RNode.LocalVar(scrollSlot, "scrollState", mutable = false, initializer = rememberCall, source = span)

        // Modifier.fakeVerticalScroll(scrollState) — a NON-composable extension on the FakeModifier object.
        val verticalScroll = RNode.Call(
            ResolvedCallable.Library(
                displayName = "fakeVerticalScroll", ownerFqn = facade, methodName = "fakeVerticalScroll",
                paramTypes = emptyList(), isStatic = false, isConstructor = false, isInline = false,
            ),
            DispatchKind.EXTENSION,
            receiver = RNode.Name(Binding.ObjectRef("dev.ide.interp.compose.FakeModifier", "FakeModifier"), span),
            args = listOf(RArg(RNode.Name(Binding.Local(scrollSlot, "scrollState", false), span))),
            callSiteKey = CallSiteKey(2), source = span,
        )

        // repeat(50) { index -> FakeItem("Item number $index") }
        val indexSlot = SlotId(1)
        val itemCall = RNode.Call(
            lib("FakeItem", facade, isComposable = true), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.StringConcat(listOf(
                RNode.Const("Item number ", null, span),
                RNode.Name(Binding.Local(indexSlot, "index", false), span),
            ), span))),
            callSiteKey = CallSiteKey(3), source = span,
        )
        val repeatLambda = RNode.Lambda(listOf(RParam(indexSlot, "index", null)), RNode.Block(listOf(itemCall), false, span), emptyList(), span)
        val repeatCall = RNode.Call(
            lib("repeat", "kotlin.StandardKt", isComposable = false), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const(50, null, span)), RArg(repeatLambda)), callSiteKey = CallSiteKey(4), source = span,
        )

        // FakeColumn(modifier = ...) { repeat(...) }
        val contentLambda = RNode.Lambda(emptyList(), RNode.Block(listOf(repeatCall), false, span), emptyList(), span)
        val columnCall = RNode.Call(
            lib("FakeColumn", facade, isComposable = true), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(verticalScroll, name = "modifier"), RArg(contentLambda)),
            callSiteKey = CallSiteKey(5), source = span,
        )

        val body = RNode.Block(listOf(localVar, columnCall), isExpression = false, source = span)
        val entry = ResolvedFunction("Preview", emptyList(), body, emptyList())

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce { renderer.Render(entry, emptyMap()) { failure = it } }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(7, ItemCapture.scrollState, "the composable val should have been remembered + passed through")
        assertEquals((0 until 50).map { "Item number $it" }, ItemCapture.items, "every repeat iteration should compose its item")
    }

    private fun lib(name: String, owner: String, isComposable: Boolean) = ResolvedCallable.Library(
        displayName = name, ownerFqn = owner, methodName = name, paramTypes = emptyList(),
        isStatic = true, isConstructor = false, isInline = false, isComposable = isComposable,
    )

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

/** A fake `rememberScrollState` — a composable returning a value, stored into a local `val`. */
@Composable
fun fakeRememberScrollState(): Int = 7

/** A fake non-composable `Modifier.verticalScroll` extension on an object receiver. */
fun FakeModifier.fakeVerticalScroll(state: Int): FakeModifier { ItemCapture.scrollState = state; return this }

/** A fake `Column`: a composable taking a modifier (defaulted) + a trailing `@Composable` content lambda. */
@Composable
fun FakeColumn(modifier: FakeModifier = FakeModifier, content: @Composable () -> Unit) { content() }

/** A fake `Text`: a composable that records what it was asked to render. */
@Composable
fun FakeItem(text: String) { ItemCapture.items.add(text) }

object FakeModifier
object ItemCapture {
    val items = ArrayList<String>()
    var scrollState = -1
}
