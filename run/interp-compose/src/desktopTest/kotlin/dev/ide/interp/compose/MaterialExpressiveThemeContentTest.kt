package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import dev.ide.interp.InterpretedLambda
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Repro for the reported "material 3 expressive" preview crash `Attempt to invoke interface method
 * Function2.invoke(...) on a null object reference`: a theme wrapper forwards its `content: @Composable () ->
 * Unit` to `MaterialExpressiveTheme(colorScheme = …, motionScheme = …, content = content)` — all NAMED args,
 * the two middle params (`shapes`/`typography`) omitted. The content lambda must reach the last parameter, not
 * be left null (which the real composable then invokes → NPE). Driven through the real ComposeDispatcher against
 * the real Material3 `MaterialExpressiveTheme`.
 */
class MaterialExpressiveThemeContentTest {

    private val span = SourceSpan(0, 0)

    @Test
    fun namedContentArgReachesMaterialExpressiveThemeContentSlot() {
        var contentRan = false
        val content = object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? { contentRan = true; return null }
        }
        fun arg(name: String, node: RNode) = RArg(node, name, false, false)
        val callee = ResolvedCallable.Library(
            displayName = "MaterialExpressiveTheme",
            ownerFqn = "androidx.compose.material3.MaterialThemeKt",
            methodName = "MaterialExpressiveTheme",
            paramTypes = listOf(
                KotlinType("androidx.compose.material3.ColorScheme", nullable = true),
                KotlinType("androidx.compose.material3.MotionScheme", nullable = true),
                KotlinType("androidx.compose.material3.Shapes", nullable = true),
                KotlinType("androidx.compose.material3.Typography", nullable = true),
                KotlinType("kotlin.Function0", isComposable = true),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            paramNames = listOf("colorScheme", "motionScheme", "shapes", "typography", "content"),
        )
        // MaterialExpressiveTheme(colorScheme = null, motionScheme = null, content = content) — colorScheme and
        // motionScheme SUPPLIED (as the user supplies them), shapes/typography omitted, content named last.
        val call = RNode.Call(
            callee, DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(
                arg("colorScheme", RNode.Const(null, null, span)),
                arg("motionScheme", RNode.Const(null, null, span)),
                arg("content", RNode.Const(null, null, span)),
            ),
            callSiteKey = CallSiteKey(51), source = span,
        )
        val dispatcher = ComposeDispatcher()
        var failure: Throwable? = null
        composeOnce {
            dispatcher.composer = currentComposer
            try {
                dispatcher.dispatch(call, receiver = null, args = listOf<Any?>(null, null, content))
            } catch (t: Throwable) {
                failure = t
            }
        }
        if (failure != null) throw AssertionError("MaterialExpressiveTheme dispatch failed", failure)
        assertEquals(true, contentRan, "the named `content` lambda must reach MaterialExpressiveTheme's content slot and run")
    }

    // --- harness (mirrors ComposableAbiDefaultsTest) ---

    private val recomposers = ArrayList<Recomposer>()

    @AfterTest
    fun tearDown() = recomposers.forEach { it.cancel() }

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
