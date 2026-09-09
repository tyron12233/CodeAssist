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
 * The `LazyColumn { items(xs) { it -> Text(it) } }` pattern — the single most common Compose list idiom, and
 * the case the compose-interpreter design tracked as "Remaining": a `@Composable` content lambda passed to a
 * NON-composable builder function (`items`) must still be invoked with a threaded `Composer`.
 *
 * Real foundation `LazyColumn` defers item composition to a `LazyLayout` that needs measurement, so it composes
 * nothing under the headless `UnitApplier`. This models `items`' actual behaviour faithfully instead: the
 * builder (`FakeLazyColumn`'s content, a NON-composable `FakeLazyScope.() -> Unit`) runs to REGISTER blocks via
 * the non-composable `fakeItems`, then `FakeLazyColumn` (which IS composable) composes each registered
 * `@Composable` item block with its own composer — exactly the register-then-compose split of the real thing.
 *
 * The load-bearing assertion: because `fakeItems`' `itemContent` parameter type is `@Composable`, the
 * interpreter gives that lambda a composer-threading proxy (`composableParamFlags` -> `composableLambdaProxy`)
 * even though `fakeItems` itself is not composable, so the interpreted `FakeItem(label)` bodies compose.
 */
class LazyItemsComposableTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)

    @Test
    fun composableContentLambdaPassedToANonComposableBuilderIsComposerThreaded() {
        // fakeItems(listOf("a","b","c")) { label -> FakeItem(label) }  — a MEMBER call on the builder receiver.
        val scopeSlot = SlotId(0)   // the FakeLazyScope receiver bound into the builder lambda
        val labelSlot = SlotId(1)   // the itemContent lambda's `label` param

        // FakeItem + ItemCapture are declared in InlineOnlyScopeFunctionTest.kt (same package), so its facade owns them.
        val fakeItemFacade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"
        val itemBody = RNode.Call(
            lib("FakeItem", fakeItemFacade, isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Name(Binding.Local(labelSlot, "label", false), span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val itemContent = RNode.Lambda(
            listOf(RParam(labelSlot, "label", null)),
            RNode.Block(listOf(itemBody), false, span), emptyList(), span,
        )

        // fakeItems is NOT composable; its LAST parameter is a `@Composable (String) -> Unit` content slot.
        val fakeItemsCallee = ResolvedCallable.Library(
            displayName = "fakeItems", ownerFqn = "dev.ide.interp.compose.FakeLazyScope", methodName = "fakeItems",
            paramTypes = listOf(
                KotlinType("kotlin.collections.List"),
                KotlinType("kotlin.Function1", isComposable = true),
            ),
            isStatic = false, isConstructor = false, isInline = false, isComposable = false,
        )
        val fakeItemsCall = RNode.Call(
            fakeItemsCallee, DispatchKind.MEMBER,
            receiver = RNode.Name(Binding.Local(scopeSlot, "scope", false), span),
            args = listOf(
                RArg(RNode.Call(
                    lib("listOf", "kotlin.collections.CollectionsKt", isComposable = false),
                    DispatchKind.TOP_LEVEL, receiver = null,
                    args = listOf(
                        RArg(RNode.Const("a", null, span)),
                        RArg(RNode.Const("b", null, span)),
                        RArg(RNode.Const("c", null, span)),
                    ),
                    callSiteKey = CallSiteKey(2), source = span,
                )),
                RArg(itemContent),
            ),
            callSiteKey = CallSiteKey(3), source = span,
        )

        // FakeLazyColumn { <scope> -> fakeItems(...) { ... } }  — builder is a NON-composable receiver lambda.
        val builder = RNode.Lambda(
            listOf(RParam(scopeSlot, "scope", null)),
            RNode.Block(listOf(fakeItemsCall), false, span), emptyList(), span,
        )
        val lazyColumn = RNode.Call(
            lib("FakeLazyColumn", itemFacade, isComposable = true), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(builder)), callSiteKey = CallSiteKey(4), source = span,
        )

        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(lazyColumn), false, span), emptyList())

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce { renderer.Render(entry, emptyMap()) { failure = it } }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(listOf("a", "b", "c"), ItemCapture.items, "every items{} block should compose its item content")
    }

    /**
     * The FULL real `items` shape: an EXTENSION with DEFAULTED `key`/`contentType` parameters BETWEEN the list
     * and the trailing `@Composable itemContent`. Calling `fakeItemsExt(xs) { … }` omits the middle defaults, so
     * the dispatcher must (a) remap the trailing lambda to the LAST parameter, (b) fill `key`/`contentType` via
     * the `$default` synthetic mask, and (c) still give the itemContent lambda a composer-threading proxy. This
     * is the "varargs/named-args/defaults arg-binding" case the design tracked as still-blocking the real items().
     */
    @Test
    fun realItemsShapeWithDefaultedMiddleParamsBindsTheTrailingComposableContent() {
        val fakeItemFacade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"
        val scopeSlot = SlotId(0)
        val labelSlot = SlotId(1)

        val itemBody = RNode.Call(
            lib("FakeItem", fakeItemFacade, isComposable = true), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Name(Binding.Local(labelSlot, "label", false), span))),
            callSiteKey = CallSiteKey(11), source = span,
        )
        val itemContent = RNode.Lambda(
            listOf(RParam(labelSlot, "label", null)), RNode.Block(listOf(itemBody), false, span), emptyList(), span,
        )

        // fun FakeLazyScope.fakeItemsExt(items, key = null, contentType = {…}, itemContent: @Composable (String)->Unit)
        val fakeItemsExtCallee = ResolvedCallable.Library(
            displayName = "fakeItemsExt", ownerFqn = itemFacade, methodName = "fakeItemsExt",
            paramTypes = listOf(
                KotlinType("kotlin.collections.List"),
                KotlinType("kotlin.Function1", nullable = true),
                KotlinType("kotlin.Function1"),
                KotlinType("kotlin.Function1", isComposable = true),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = false,
        )
        val fakeItemsCall = RNode.Call(
            fakeItemsExtCallee, DispatchKind.EXTENSION,
            receiver = RNode.Name(Binding.Local(scopeSlot, "scope", false), span),
            args = listOf(
                RArg(RNode.Call(
                    lib("listOf", "kotlin.collections.CollectionsKt", isComposable = false),
                    DispatchKind.TOP_LEVEL, receiver = null,
                    args = listOf(
                        RArg(RNode.Const("x", null, span)),
                        RArg(RNode.Const("y", null, span)),
                    ),
                    callSiteKey = CallSiteKey(12), source = span,
                )),
                RArg(itemContent, trailingLambda = true),
            ),
            callSiteKey = CallSiteKey(13), source = span,
        )

        val builder = RNode.Lambda(
            listOf(RParam(scopeSlot, "scope", null)), RNode.Block(listOf(fakeItemsCall), false, span), emptyList(), span,
        )
        val lazyColumn = RNode.Call(
            lib("FakeLazyColumn", itemFacade, isComposable = true), DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(builder)), callSiteKey = CallSiteKey(14), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(lazyColumn), false, span), emptyList())

        var failure: Throwable? = null
        composeOnce { ComposePreviewRenderer().Render(entry, emptyMap()) { failure = it } }
        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(listOf("x", "y"), ItemCapture.items, "the trailing @Composable content must bind past the defaulted middle params")
    }

    private val itemFacade = "dev.ide.interp.compose.LazyItemsComposableTestKt"

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

/** A non-composable list builder scope, mirroring `LazyListScope`. */
class FakeLazyScope {
    val blocks = ArrayList<Pair<List<String>, @Composable (String) -> Unit>>()

    /** Mirrors `LazyListScope.items(items, itemContent)`: NON-composable, it only REGISTERS the content. */
    fun fakeItems(items: List<String>, itemContent: @Composable (String) -> Unit) {
        blocks.add(items to itemContent)
    }
}

/**
 * Mirrors the FULL `LazyListScope.items(items, key = null, contentType = { null }, itemContent)` signature: an
 * EXTENSION with two DEFAULTED parameters between the list and the trailing `@Composable` content. NON-composable
 * (it only registers), so the compiler emits a `fakeItemsExt$default` synthetic the interpreter drives.
 */
fun FakeLazyScope.fakeItemsExt(
    items: List<String>,
    key: ((String) -> Any)? = null,
    contentType: (String) -> Any? = { null },
    itemContent: @Composable (String) -> Unit,
) {
    blocks.add(items to itemContent)
}

/** A composable list container: runs the non-composable builder, then composes each registered item block. */
@Composable
fun FakeLazyColumn(content: FakeLazyScope.() -> Unit) {
    val scope = FakeLazyScope()
    scope.content()
    for ((items, itemContent) in scope.blocks) {
        for (label in items) itemContent(label)
    }
}
