package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.interp.SourceObject
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.ClassFlavor
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RTypeArg
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
 * A `@Preview` containing a Navigation-Compose `NavHost { composable<T> { } }` renders its START destination.
 * The interpreter can't run the real androidx.navigation runtime headlessly (no NavController/back-stack/Looper),
 * so [ComposeDispatcher] INTERCEPTS `rememberNavController`/`NavHost`/`composable<T>`: it runs the builder to
 * collect destinations keyed by the route type argument (Tier-A `RNode.Call.typeArguments`), matches the start
 * route, and composes that destination's content lambda directly. Only the start screen composes (a static
 * preview of a NavHost shows the start destination); other destinations are registered but not rendered.
 *
 * Reuses the `FakeItem` composable + `ItemCapture` sink from the same test source set (see
 * [InlineOnlyScopeFunctionTest]).
 */
class NavHostPreviewTest {

    @BeforeTest fun reset() { ItemCapture.items.clear() }

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.InlineOnlyScopeFunctionTestKt"
    private val navPkg = "androidx.navigation.compose"

    /** A `FakeItem("...")` composable call (records the text into [ItemCapture]). */
    private fun fakeItem(text: String, key: Int) = RNode.Call(
        ResolvedCallable.Library("FakeItem", facade, "FakeItem", emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
        DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const(text, null, span))),
        callSiteKey = CallSiteKey(key), source = span,
    )

    /** A `composable<route> { fakeItem(screen) }` registration call. */
    private fun composableCall(routeFqn: String, screen: String, key: Int) = RNode.Call(
        ResolvedCallable.Library("composable", "$navPkg.NavGraphBuilderKt", "composable", emptyList(), isStatic = true, isConstructor = false, isInline = true, isComposable = false),
        DispatchKind.EXTENSION, receiver = null,
        args = listOf(RArg(RNode.Lambda(emptyList(), fakeItem(screen, key + 1), emptyList(), span), trailingLambda = true)),
        callSiteKey = CallSiteKey(key), source = span,
        typeArguments = listOf(RTypeArg(routeFqn, listOf(routeFqn))),
    )

    @Test
    fun navHostRendersTheStartDestination() {
        // A route type: `@Serializable data object Home` — a project SOURCE object, so its start-destination
        // value is a SourceObject whose class FQN keys the destination.
        val homeClass = ResolvedClass(
            fqn = "demo.Home", simpleName = "Home", flavor = ClassFlavor.OBJECT, isData = false,
            isSealed = false, isAbstract = false, primaryParams = emptyList(), initSteps = emptyList(),
            methods = emptyMap(), receiverSlot = SlotId(0), supertypes = emptyList(),
            enumEntries = emptyList(), diagnostics = emptyList(),
        )
        val homeRoute = SourceObject(homeClass)

        // val nav = rememberNavController()  →  intercepted to a placeholder
        val rememberNav = RNode.Call(
            ResolvedCallable.Library("rememberNavController", "$navPkg.NavHostControllerKt", "rememberNavController", emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(1), source = span,
        )
        // NavHost(nav, startDestination = Home) { composable<Home>{FakeItem("Home")}; composable<Detail>{FakeItem("Detail")} }
        val builder = RNode.Lambda(
            emptyList(),
            RNode.Block(listOf(composableCall("demo.Home", "Home", 20), composableCall("demo.Detail", "Detail", 30)), false, span),
            emptyList(), span,
        )
        val navHost = RNode.Call(
            ResolvedCallable.Library(
                "NavHost", "$navPkg.NavHostKt", "NavHost", emptyList(),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
                paramNames = listOf("navController", "startDestination", "builder"),
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(rememberNav), RArg(RNode.Const(homeRoute, null, span)), RArg(builder, trailingLambda = true)),
            callSiteKey = CallSiteKey(10), source = span,
        )
        val entry = ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(navHost), false, span), emptyList(), returnsUnit = true)

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce { renderer.Render(entry, emptyMap(), emptyList()) { failure = it } }

        if (failure != null) throw AssertionError("preview failed", failure)
        assertEquals(listOf("Home"), ItemCapture.items,
            "the NavHost preview should compose ONLY the start destination (Home), not Detail")
    }

    @Test
    fun serializerCallDegradesGracefullyInsteadOfCrashing() {
        // A reified `kotlinx.serialization.serializer<Foo>()` is inline-only (no JVM method to reflect); before
        // the intrinsic it crashed the whole preview with "inline-only function not modeled". Now it is
        // intercepted and degrades to null when a serializer can't be obtained (a project-source `@Serializable`
        // type has no compiled `$serializer` at preview time), so the surrounding composition keeps rendering.
        val serializerCall = RNode.Call(
            ResolvedCallable.Library("serializer", "kotlinx.serialization.SerializersKt", "serializer", emptyList(), isStatic = true, isConstructor = false, isInline = true, isComposable = false),
            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(5), source = span,
            typeArguments = listOf(RTypeArg("demo.Foo", listOf("demo.Foo"))),
        )
        val entry = ResolvedFunction(
            "Preview", emptyList(),
            RNode.Block(listOf(serializerCall, fakeItem("rendered", 6)), false, span), emptyList(), returnsUnit = true,
        )

        var failure: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce { renderer.Render(entry, emptyMap(), emptyList()) { failure = it } }

        if (failure != null) throw AssertionError("serializer<T>() must not crash the preview", failure)
        assertEquals(listOf("rendered"), ItemCapture.items,
            "the preview should keep rendering after a gracefully-handled serializer<T>() call")
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
