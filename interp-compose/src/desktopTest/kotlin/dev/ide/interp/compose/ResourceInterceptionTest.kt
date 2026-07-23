package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.interp.PreviewResourceResolver
import dev.ide.lang.kotlin.interp.Binding
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

/**
 * The full interpreter-mediated resource path: `FakeItem(stringResource(R.string.greeting))` must render the
 * PROJECT's string, not fail. `R.string.greeting` resolves to its id via the [PreviewResourceResolver] (the
 * binding-owner hook), then `stringResource(id)` is short-circuited by [ComposeDispatcher] to the resolver's
 * value — the real `androidx.compose.ui.res.stringResource` (which would read the IDE app's own Resources) is
 * never invoked.
 */
class ResourceInterceptionTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"

    private val resources = object : PreviewResourceResolver {
        override fun rClassField(ownerFqn: String, fieldName: String): Int? =
            if (ownerFqn == "com.example.R.string" && fieldName == "greeting") GREETING_ID else null
        override fun string(id: Int): String? = if (id == GREETING_ID) "Hello project" else null
        override fun stringArray(id: Int): List<String>? = null
        override fun plural(id: Int, quantity: Int): String? = null
        override fun color(id: Int): Any? = null
        override fun dimension(id: Int): Any? = null
        override fun painter(id: Int): Any? = null
    }

    @Test
    fun stringResourceOfAnRReferenceRendersTheProjectValue() {
        // R.string.greeting (the nested PropertyGet chain the lowerer emits).
        val rGreeting = RNode.PropertyGet(
            RNode.PropertyGet(
                RNode.Name(Binding.ObjectRef("com.example.R", "R"), span),
                Binding.Property("string", "com.example.R", backingField = false), span,
            ),
            Binding.Property("greeting", "com.example.R.string", backingField = false), span,
        )
        // stringResource(R.string.greeting)
        val stringRes = RNode.Call(
            ResolvedCallable.Library(
                displayName = "stringResource", ownerFqn = "androidx.compose.ui.res.StringResources_androidKt",
                methodName = "stringResource", paramTypes = emptyList(), isStatic = true, isConstructor = false,
                isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(rGreeting)),
            callSiteKey = CallSiteKey(1), source = span,
        )
        // FakeItem(stringResource(...))
        val itemCall = RNode.Call(
            ResolvedCallable.Library(
                displayName = "FakeItem", ownerFqn = facade, methodName = "FakeItem",
                paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(stringRes)),
            callSiteKey = CallSiteKey(2), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(itemCall), false, span), emptyList())

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer(resources = resources)
        composeOnce { renderer.Render(entry, emptyMap(), onError = { failure = it }) }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(listOf("Hello project"), ItemCapture.items, "stringResource(R.string.greeting) should render the project string")
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

    private companion object {
        const val GREETING_ID = 0x7f0e0002
    }
}
