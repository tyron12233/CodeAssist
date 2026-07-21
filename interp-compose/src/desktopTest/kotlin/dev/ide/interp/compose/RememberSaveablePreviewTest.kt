package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.interp.Binding
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `rememberSaveable { … }` must not crash the preview. The real one needs a `SaveableStateRegistry` from a
 * `SavedStateRegistryOwner`, which the in-app preview composition has none of, so reflecting it throws.
 * [ComposeDispatcher] intercepts it and models it as a plain remember (runs `init`, returns its value), so a
 * `@Preview` using `rememberSaveable` renders instead of failing.
 */
class RememberSaveablePreviewTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    @Test
    fun rememberSaveableRendersInsteadOfCrashing() {
        // val v = rememberSaveable { "saved" } ; FakeItem(v)
        val slot = SlotId(10)
        val initLambda = RNode.Lambda(emptyList(), RNode.Const("saved", null, span), emptyList(), span)
        val rememberSaveable = RNode.Call(
            ResolvedCallable.Library(
                "rememberSaveable", "androidx.compose.runtime.saveable.RememberSaveableKt", "rememberSaveable",
                emptyList(), isStatic = true, isConstructor = false, isInline = true, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(initLambda, trailingLambda = true)),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val local = RNode.LocalVar(slot, "v", mutable = false, initializer = rememberSaveable, source = span)
        val fakeItem = RNode.Call(
            ResolvedCallable.Library("FakeItem", facade, "FakeItem", emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Name(Binding.Local(slot, "v", false), span))),
            callSiteKey = CallSiteKey(2), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(local, fakeItem), false, span), emptyList(), returnsUnit = true)

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce { renderer.Render(entry, emptyMap(), emptyList()) { failure = it } }

        if (failure != null) throw AssertionError("rememberSaveable must not crash the preview", failure)
        assertEquals(listOf("saved"), ItemCapture.items, "the rememberSaveable init value should reach the composable")
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
