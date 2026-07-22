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
 * A material3 `AlertDialog` (and other multi-slot dialogs) opens a real OS window/scrim the in-app preview
 * can't host, and it has SEVERAL `@Composable` content slots (`icon`/`title`/`text`/`confirmButton`/
 * `dismissButton`) rather than one — so the single-content windowed-inline path couldn't render it. The
 * dispatcher now composes every `@Composable`-typed slot lambda INLINE, while the plain `onDismissRequest`
 * (a `() -> Unit`) is correctly skipped.
 *
 * The dispatcher intercepts by owner prefix + method name BEFORE any real dispatch (it never invokes the real
 * `AlertDialog`), so this drives the interception with a material3-owned callee and interpreted content slots,
 * asserting each composable slot composed and the non-composable dismiss handler did not.
 */
class AlertDialogInlineTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)

    @Test
    fun alertDialogComposesEveryComposableSlotInlineAndSkipsTheDismissHandler() {
        fun slot(text: String, key: Int) = RArg(RNode.Lambda(
            emptyList(),
            RNode.Block(listOf(RNode.Call(
                ResolvedCallable.Library(
                    "FakeItem", "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt", "FakeItem",
                    paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
                ),
                DispatchKind.TOP_LEVEL, receiver = null,
                args = listOf(RArg(RNode.Const(text, null, span))), callSiteKey = CallSiteKey(key), source = span,
            )), false, span),
            emptyList(), span,
        ))
        // onDismissRequest — a plain (NON-composable) lambda that must NOT be composed as a slot.
        val onDismiss = RArg(RNode.Lambda(emptyList(), RNode.Block(listOf(
            RNode.Call(
                ResolvedCallable.Library("FakeItem", "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt", "FakeItem",
                    emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
                DispatchKind.TOP_LEVEL, null, listOf(RArg(RNode.Const("DISMISS", null, span))), CallSiteKey(99), span,
            ),
        ), false, span), emptyList(), span))

        val comp = KotlinType("kotlin.Function0", isComposable = true)
        val plain = KotlinType("kotlin.Function0")
        val alertDialog = RNode.Call(
            ResolvedCallable.Library(
                displayName = "AlertDialog",
                ownerFqn = "androidx.compose.material3.AndroidAlertDialog_androidKt", methodName = "AlertDialog",
                paramTypes = listOf(plain, comp, comp, comp), // onDismissRequest, confirmButton, title, text
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(onDismiss, slot("OK", 1), slot("Title", 2), slot("Body", 3)),
            callSiteKey = CallSiteKey(10), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(alertDialog), false, span), emptyList())

        var failure: Throwable? = null
        composeOnce { ComposePreviewRenderer().Render(entry, emptyMap()) { failure = it } }
        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(listOf("OK", "Title", "Body"), ItemCapture.items, "every @Composable slot composes inline; onDismissRequest does not")
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
