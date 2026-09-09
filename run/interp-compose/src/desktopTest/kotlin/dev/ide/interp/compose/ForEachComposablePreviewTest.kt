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
 * Regression for the preview failure `Column { list.forEach { Item(it) } }` — emitting composables through
 * `forEach` list iteration. Like `repeat` (see [InlineOnlyScopeFunctionTest]), `forEach` is now run as an
 * interpreter intrinsic so its loop body composes into the ambient composition, instead of being reflectively
 * dispatched to the real `CollectionsKt.forEach` (which ran the body inside a library frame that broke the
 * interpreter's composable-group discipline → a partial/blank preview). Runs the lowered structure against real
 * Compose-transformed fake composables, headlessly.
 */
class ForEachComposablePreviewTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    @Test
    fun forEachOfComposableInsideColumnComposesEveryItem() {
        // Column { listOf("a","b","c").forEach { s -> FakeItem(s) } }
        val elemSlot = SlotId(0)
        val itemCall = RNode.Call(
            lib("FakeItem", facade, isComposable = true), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Name(Binding.Local(elemSlot, "s", false), span))),
            callSiteKey = CallSiteKey(3), source = span,
        )
        val forEachLambda = RNode.Lambda(
            listOf(RParam(elemSlot, "s", null)), RNode.Block(listOf(itemCall), false, span), emptyList(), span,
        )
        val forEachCall = RNode.Call(
            ResolvedCallable.Library(
                displayName = "forEach", ownerFqn = "kotlin.collections.CollectionsKt", methodName = "forEach",
                paramTypes = emptyList(), isStatic = false, isConstructor = false, isInline = true, isComposable = false,
            ),
            DispatchKind.EXTENSION,
            receiver = RNode.Const(listOf("a", "b", "c"), null, span),
            args = listOf(RArg(forEachLambda)),
            callSiteKey = CallSiteKey(4), source = span,
        )
        val contentLambda = RNode.Lambda(emptyList(), RNode.Block(listOf(forEachCall), false, span), emptyList(), span)
        val columnCall = RNode.Call(
            lib("FakeColumn", facade, isComposable = true), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(
                RArg(RNode.Name(Binding.ObjectRef("dev.ide.interp.compose.FakeModifier", "FakeModifier"), span), name = "modifier"),
                RArg(contentLambda),
            ),
            callSiteKey = CallSiteKey(5), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(columnCall), false, span), emptyList())

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce { renderer.Render(entry, emptyMap()) { failure = it } }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(listOf("a", "b", "c"), ItemCapture.items, "every forEach iteration should compose its item")
    }

    private fun lib(name: String, owner: String, isComposable: Boolean) = ResolvedCallable.Library(
        displayName = name, ownerFqn = owner, methodName = name, paramTypes = emptyList(),
        isStatic = true, isConstructor = false, isInline = false, isComposable = isComposable,
    )

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
