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
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `@Composable` called DIRECTLY inside a non-composable list builder — `LazyColumn { Text("x") }` instead of
 * `LazyColumn { item { Text("x") } }` — must NOT compose. The builder (`LazyListScope.() -> Unit`) runs at
 * MEASURE, after the composition pass, when the dispatcher's `composer` still holds the finished composition's
 * composer; composing there re-enters a completed composition and triggers endless recomposition (the reported
 * IDE freeze). The dispatcher suppresses a composable call inside such a lambda. A composable content lambda
 * (`items { … }`) re-enables them, so the valid list idiom still composes.
 */
class LazyScopeComposableGuardTest {

    private val span = SourceSpan(0, 0)
    private val lazyFacade = "dev.ide.interp.compose.LazyItemsComposableTestKt"
    private val itemFacade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private fun lib(name: String, owner: String, composable: Boolean) = ResolvedCallable.Library(
        displayName = name, ownerFqn = owner, methodName = name, paramTypes = emptyList(),
        isStatic = true, isConstructor = false, isInline = false, isComposable = composable,
    )

    // FakeLazyColumn(content: FakeLazyScope.() -> Unit) — content is a NON-composable builder lambda, exactly
    // as the resolver decodes real `LazyColumn`'s `LazyListScope.() -> Unit` param (isComposable = false).
    private fun fakeLazyColumn() = ResolvedCallable.Library(
        displayName = "FakeLazyColumn", ownerFqn = lazyFacade, methodName = "FakeLazyColumn",
        paramTypes = listOf(KotlinType("kotlin.Function1", isComposable = false)),
        isStatic = true, isConstructor = false, isInline = false, isComposable = true,
    )

    private fun fakeItem(text: String, key: Int) = RNode.Call(
        lib("FakeItem", itemFacade, composable = true), DispatchKind.TOP_LEVEL, receiver = null,
        args = listOf(RArg(RNode.Const(text, null, span))), callSiteKey = CallSiteKey(key), source = span,
    )

    private fun render(entry: ResolvedFunction) {
        var failure: Throwable? = null
        composeOnce { ComposePreviewRenderer().Render(entry, emptyMap()) { failure = it } }
        if (failure != null) throw AssertionError("preview failed", failure)
    }

    @Test
    fun composableDirectlyInABuilderLambdaIsSuppressed() {
        // FakeLazyColumn { FakeItem("direct") }  — a composable called straight in the non-composable builder.
        val builder = RNode.Lambda(
            listOf(RParam(SlotId(0), "scope", null)),
            RNode.Block(listOf(fakeItem("direct", 1)), false, span), emptyList(), span,
        )
        val entry = ResolvedFunction(
            "Preview", emptyList(),
            RNode.Block(listOf(RNode.Call(
                fakeLazyColumn(), DispatchKind.TOP_LEVEL, receiver = null,
                args = listOf(RArg(builder)), callSiteKey = CallSiteKey(2), source = span,
            )), false, span),
            emptyList(),
        )
        render(entry)
        assertEquals(emptyList(), ItemCapture.items, "a composable directly in a builder lambda must be skipped, not composed")
    }

    @Test
    fun composableInsideItemsContentStillComposes() {
        // FakeLazyColumn { fakeItems(listOf("a","b")) { FakeItem(it) } }  — the VALID idiom must still compose.
        val labelSlot = SlotId(1)
        val itemContent = RNode.Lambda(
            listOf(RParam(labelSlot, "label", null)),
            RNode.Block(listOf(RNode.Call(
                lib("FakeItem", itemFacade, composable = true), DispatchKind.TOP_LEVEL, receiver = null,
                args = listOf(RArg(RNode.Name(Binding.Local(labelSlot, "label", false), span))),
                callSiteKey = CallSiteKey(11), source = span,
            )), false, span),
            emptyList(), span,
        )
        val fakeItemsCall = RNode.Call(
            ResolvedCallable.Library(
                displayName = "fakeItems", ownerFqn = "dev.ide.interp.compose.FakeLazyScope", methodName = "fakeItems",
                paramTypes = listOf(KotlinType("kotlin.collections.List"), KotlinType("kotlin.Function1", isComposable = true)),
                isStatic = false, isConstructor = false, isInline = false, isComposable = false,
            ),
            DispatchKind.MEMBER, receiver = RNode.Name(Binding.Local(SlotId(0), "scope", false), span),
            args = listOf(
                RArg(RNode.Call(
                    lib("listOf", "kotlin.collections.CollectionsKt", composable = false), DispatchKind.TOP_LEVEL, receiver = null,
                    args = listOf(RArg(RNode.Const("a", null, span)), RArg(RNode.Const("b", null, span))),
                    callSiteKey = CallSiteKey(12), source = span,
                )),
                RArg(itemContent),
            ),
            callSiteKey = CallSiteKey(13), source = span,
        )
        val builder = RNode.Lambda(
            listOf(RParam(SlotId(0), "scope", null)),
            RNode.Block(listOf(fakeItemsCall), false, span), emptyList(), span,
        )
        val entry = ResolvedFunction(
            "Preview", emptyList(),
            RNode.Block(listOf(RNode.Call(
                fakeLazyColumn(), DispatchKind.TOP_LEVEL, receiver = null,
                args = listOf(RArg(builder)), callSiteKey = CallSiteKey(14), source = span,
            )), false, span),
            emptyList(),
        )
        render(entry)
        assertEquals(listOf("a", "b"), ItemCapture.items, "a composable inside items{} content must still compose")
    }

    private val recomposers = ArrayList<Recomposer>()
    private fun composeOnce(content: @Composable () -> Unit) {
        val recomposer = Recomposer(CoroutineScope(BroadcastFrameClock()).coroutineContext)
        recomposers += recomposer
        val composition = Composition(UnitApplier, recomposer)
        composition.setContent(content)
        composition.dispose()
    }

    @AfterTest fun cleanup() { recomposers.forEach { it.cancel() } }

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
