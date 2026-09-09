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
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression for the reported live-edit failure `ABI invoke mismatch for
 * androidx.compose.runtime.ComposablesKt.key: params=[Object[], Function2, Composer, int] args=[kotlin.Unit,
 * $Proxy…, ComposerImpl, Integer]`. `key(vararg keys, block)` is an inline @Composable whose vararg `keys`
 * compiles to a NON-last `Object[]` the compiler packs at the call site; the reflective ComposableAbi couldn't
 * bind it (the key landed in the array slot as a scalar). The interpreter now runs `key`'s block as an inline
 * intrinsic (like `repeat`), so its content composes into the enclosing group.
 *
 * Runs the lowered structure against a REAL Compose-transformed fake composable, headlessly.
 */
class KeyIntrinsicTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    @Test
    fun keyRunsItsBlockInsteadOfAbiMismatch() {
        // key("k") { FakeItem("keyed") }
        val itemCall = RNode.Call(
            ResolvedCallable.Library(
                displayName = "FakeItem", ownerFqn = facade, methodName = "FakeItem",
                paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const("keyed", null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val keyBlock = RNode.Lambda(emptyList(), RNode.Block(listOf(itemCall), false, span), emptyList(), span)
        val keyCall = RNode.Call(
            ResolvedCallable.Library(
                displayName = "key", ownerFqn = "androidx.compose.runtime.ComposablesKt", methodName = "key",
                paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = true, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const("k", null, span)), RArg(keyBlock)),
            callSiteKey = CallSiteKey(2), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(keyCall), false, span), emptyList())

        var rootError: Throwable? = null
        var partialError: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce {
            renderer.Render(
                entry, emptyMap(), emptyList(), emptyList(),
                onError = { rootError = it },
                onPartialError = { if (it != null) partialError = it },
            )
        }

        assertNull(rootError, "key should not fail the render: $rootError")
        assertNull(partialError, "key should not partially fail: $partialError")
        assertEquals(listOf("keyed"), ItemCapture.items, "key's block should have composed its item")
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
