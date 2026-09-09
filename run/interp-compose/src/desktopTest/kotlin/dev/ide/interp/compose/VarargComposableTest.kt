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
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A NON-last vararg composable (`FakeProvide(vararg values, content)` — the shape of the real
 * `CompositionLocalProvider(vararg values, content)`) compiles to a plain `FakeProvided[]` parameter the compiler
 * packs at the call site. The interpreter must fold the loose leading args into that typed array (driven by the
 * callee's `varargParamIndex` threaded from the resolver) — otherwise the reflective invoke gets a scalar where
 * the array is expected (the reported `key` ABI mismatch, generalized).
 */
class VarargComposableTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    @Test
    fun nonLastVarargComposablePacksItsLooseArgs() {
        // FakeProvide(FakeProvided("a"), FakeProvided("b")) { FakeItem("c") }
        val content = RNode.Lambda(
            emptyList(),
            RNode.Block(listOf(item("c")), isExpression = false, span),
            emptyList(), span,
        )
        val provide = RNode.Call(
            ResolvedCallable.Library(
                displayName = "FakeProvide", ownerFqn = "dev.ide.interp.compose.VarargComposableTestKt", methodName = "FakeProvide",
                paramTypes = listOf(KotlinType("dev.ide.interp.compose.FakeProvided"), KotlinType("kotlin.Function0")),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
                varargParamIndex = 0,
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(
                RArg(RNode.Const(FakeProvided("a"), null, span)),
                RArg(RNode.Const(FakeProvided("b"), null, span)),
                RArg(content),
            ),
            callSiteKey = CallSiteKey(2), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(provide), isExpression = false, span), emptyList())

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce { renderer.Render(entry, emptyMap(), onError = { failure = it }) }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(listOf("a", "b", "c"), ItemCapture.items, "both vararg values + the content should have composed")
    }

    private fun item(text: String) = RNode.Call(
        ResolvedCallable.Library(
            displayName = "FakeItem", ownerFqn = facade, methodName = "FakeItem",
            paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
        ),
        DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const(text, null, span))),
        callSiteKey = CallSiteKey(text.hashCode()), source = span,
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

/** A `ProvidedValue` stand-in — the element type of the fake provider's vararg. */
class FakeProvided(val tag: String)

/** A fake `CompositionLocalProvider`: a NON-last vararg composable that records each provided value, then runs
 *  its content. Its transformed JVM method takes `FakeProvided[]` — which the loose call args must pack into. */
@Composable
fun FakeProvide(vararg values: FakeProvided, content: @Composable () -> Unit) {
    values.forEach { ItemCapture.items.add(it.tag) }
    content()
}
