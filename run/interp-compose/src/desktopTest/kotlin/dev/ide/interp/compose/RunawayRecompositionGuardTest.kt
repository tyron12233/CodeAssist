package dev.ide.interp.compose

import dev.ide.interp.InterpretedLambda
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The runaway-recomposition breaker for LIBRARY composables (the `BlueprintPreview` freeze class): a library
 * composable that writes a state it reads during composition (e.g. one walking the live host view's semantics)
 * recomposes nonstop, re-invoking its interpreted content lambda every pass with no frame boundary — an IDE
 * freeze the interpreter's per-pass guards never see. [ComposeDispatcher.contentLambdaStorm] bounds re-invocations
 * of a single content lambda per rolling window so the render can abort instead of freezing. Mirrors the
 * source-path guard in [ComposeRuntime].
 */
class RunawayRecompositionGuardTest {

    private fun lambda() = object : InterpretedLambda {
        override val paramCount = 0
        override fun invoke(args: List<Any?>): Any? = null
    }

    @Test
    fun aSingleContentLambdaTripsAfterTheThreshold() {
        val d = ComposeDispatcher()
        val lam = lambda()
        // The first 1000 invocations within the window are allowed; the next is the storm signal.
        repeat(1000) { assertFalse(d.contentLambdaStorm(lam), "invocation ${it + 1} should be under the bound") }
        assertTrue(d.contentLambdaStorm(lam), "the 1001st re-invocation within the window is a storm")
    }

    @Test
    fun distinctContentLambdasCountIndependently() {
        val d = ComposeDispatcher()
        val a = lambda()
        val b = lambda()
        repeat(1001) { d.contentLambdaStorm(a) } // storm `a`
        // A different content lambda has its own counter — a busy sibling must not trip an innocent one.
        assertFalse(d.contentLambdaStorm(b), "a distinct content lambda must not inherit another's storm count")
    }
}
