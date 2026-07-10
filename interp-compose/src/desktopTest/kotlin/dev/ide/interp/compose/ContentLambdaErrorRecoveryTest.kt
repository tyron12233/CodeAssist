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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression: a malformed buffer (the common case while editing) makes an interpreted composable throw
 * mid-composition — here `FakeItem(123)` passes an `Int` where the transformed `FakeItem(String, …)` expects a
 * `String`, an ABI invoke mismatch (the exact failure class behind the reported LazyColumn-item crash). Because
 * lazy/Scaffold content composes during Compose's measure pass — outside the preview renderer's try/catch — a
 * propagating throw would kill the host thread. [ComposeDispatcher.composableLambdaProxy] now swallows it so the
 * content lambda returns normally, the enclosing composable balances its groups, and composition completes.
 */
class ContentLambdaErrorRecoveryTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)
    private val selfFacade = "dev.ide.interp.compose.ContentLambdaErrorRecoveryTestKt"
    private val itemFacade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    @Test
    fun aThrowingComposableInsideAContentLambdaDoesNotEscapeComposition() {
        // FakeItem(123) — Int arg to a String param → arg-type mismatch when the ABI invokes the transformed method.
        val badItem = RNode.Call(
            ResolvedCallable.Library("FakeItem", itemFacade, "FakeItem", listOf(null), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const(123, null, span))),
            callSiteKey = CallSiteKey(10), source = span,
        )
        // FakeWrap { FakeItem(123) } — the throw happens inside FakeWrap's @Composable content lambda.
        val content = RNode.Lambda(emptyList(), RNode.Block(listOf(badItem), false, span), emptyList(), span)
        val wrap = RNode.Call(
            ResolvedCallable.Library("FakeWrap", selfFacade, "FakeWrap", listOf(null), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(content)),
            callSiteKey = CallSiteKey(11), source = span,
        )
        val root = ResolvedFunction("Root", emptyList(), RNode.Block(listOf(wrap), false, span), emptyList(), returnsUnit = true)

        val dispatcher = ComposeDispatcher()
        val runtime = ComposeRuntime(dispatcher)
        val interpreter = Interpreter(functions = emptyMap(), dispatcher = dispatcher, composableInvoker = runtime)

        // composeOnce throws if the composition throws — so reaching the asserts already proves no crash.
        composeOnce {
            dispatcher.composer = currentComposer
            try {
                runtime.invokeComposable(0x5ACE, restartable = false, force = false, args = emptyList()) {
                    interpreter.call(root, emptyList())
                }
            } finally {
                dispatcher.composer = null
            }
        }

        assertTrue(ItemCapture.items.isEmpty(), "the broken FakeItem never composed (it threw before recording)")
        assertNotNull(dispatcher.contentLambdaError, "the swallowed error is recorded for the host to surface")
        assertEquals("java.lang.IllegalArgumentException", causeChainHead(dispatcher.contentLambdaError!!))
    }

    /** The root of the cause chain's simple-ish identity — the ABI surfaces an `IllegalArgumentException`. */
    private fun causeChainHead(t: Throwable): String {
        var cur: Throwable = t
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return cur.javaClass.name
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

/** A fake `@Composable` wrapper that just invokes its content — the boundary whose interpreted content lambda
 *  throws in the test. */
@Composable
fun FakeWrap(content: @Composable () -> Unit) { content() }
