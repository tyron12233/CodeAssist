package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.ClassFlavor
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RClassParam
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end check that a project-source `data class` flows through the Compose preview render path: the
 * lowered classes reach the interpreter via [ComposePreviewRenderer.Render], which materializes a
 * `SourceObject` and reads its property inside a real (headless) composition. Reuses the `FakeItem`/
 * `ItemCapture` composable + sink defined alongside the inline-intrinsic test (same test source set).
 */
class SourceClassPreviewTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    @Test
    fun previewUsingSourceDataClassRenders() {
        // data class Item(val title: String)
        val itemClass = ResolvedClass(
            fqn = "demo.Item", simpleName = "Item", flavor = ClassFlavor.CLASS, isData = true,
            isSealed = false, isAbstract = false,
            primaryParams = listOf(RClassParam(SlotId(1), "title", null, isProperty = true, mutable = false, default = null)),
            initSteps = emptyList(), methods = emptyMap(), receiverSlot = SlotId(0),
            supertypes = emptyList(), enumEntries = emptyList(), diagnostics = emptyList(),
        )

        // fun Preview() { val item = Item("hello"); FakeItem(item.title) }
        val itemSlot = SlotId(10)
        val ctorCall = RNode.Call(
            ResolvedCallable.Source("Item", "demo.Item/1", listOf("title"), isConstructor = true),
            DispatchKind.CONSTRUCTOR, receiver = null, args = listOf(RArg(RNode.Const("hello", null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val localVar = RNode.LocalVar(itemSlot, "item", mutable = false, initializer = ctorCall, source = span)
        val titleRead = RNode.PropertyGet(
            RNode.Name(Binding.Local(itemSlot, "item", false), span),
            Binding.Property("title", "demo.Item", backingField = false), span,
        )
        val fakeItemCall = RNode.Call(
            ResolvedCallable.Library("FakeItem", facade, "FakeItem", emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(titleRead)),
            callSiteKey = CallSiteKey(2), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(localVar, fakeItemCall), false, span), emptyList())

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce { renderer.Render(entry, emptyMap(), listOf(itemClass)) { failure = it } }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(listOf("hello"), ItemCapture.items, "the source data class's property should reach the composable")
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
