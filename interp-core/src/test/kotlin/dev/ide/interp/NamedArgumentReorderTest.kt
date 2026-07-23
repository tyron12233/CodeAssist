package dev.ide.interp

import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Named-argument binding: the interpreter evaluates args in SOURCE order, then [reorderNamedArgs] puts them
 * back in declaration order (with [OmittedArg] holes for omitted defaults) before dispatch — so a call like
 * `describe(c = 30, a = 10)` reaches the right parameters and the omitted `b` falls back to its default. The
 * dispatch path is exercised end-to-end against REAL compiled Kotlin (the fixtures below get actual
 * `…$default` synthetics from the test compiler).
 */
class NamedArgumentReorderTest {

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.NamedArgumentReorderTestKt"

    private fun run(call: RNode): Any? =
        Interpreter(emptyMap()).call(ResolvedFunction("f", emptyList(), RNode.Block(listOf(call), false, span), emptyList()), emptyList())

    private fun describeCall(vararg args: RArg) = RNode.Call(
        ResolvedCallable.Library(
            displayName = "describe", ownerFqn = facade, methodName = "describe",
            paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false,
            descriptorPrecise = true, paramNames = listOf("a", "b", "c"),
        ),
        DispatchKind.TOP_LEVEL, receiver = null, args = args.toList(), callSiteKey = CallSiteKey(0), source = span,
    )

    private fun named(name: String?, value: Any?) = RArg(RNode.Const(value, null, span), name)

    @Test
    fun allNamedOutOfOrderBindsToTheRightParameters() {
        // describe(b = 20, a = 10, c = 30) — every param supplied, but out of declaration order.
        assertEquals("10-20-30", run(describeCall(named("b", 20), named("a", 10), named("c", 30))))
    }

    @Test
    fun namedArgsOmittingAMiddleDefaultFallBackToItsDefault() {
        // describe(c = 30, a = 10) — `b` omitted → its default (2) applies via the `describe$default` synthetic.
        assertEquals("10-2-30", run(describeCall(named("c", 30), named("a", 10))))
    }

    @Test
    fun positionalThenNamedMix() {
        // describe(10, c = 30) — leading positional `a`, then named `c`; `b` defaults.
        assertEquals("10-2-30", run(describeCall(named(null, 10), named("c", 30))))
    }

    @Test
    fun purelyPositionalCallIsUnchanged() {
        // No named args → the reorder is a no-op and the exact-arity method is invoked directly.
        assertEquals("1-2-3", run(describeCall(named(null, 1), named(null, 2), named(null, 3))))
    }

    @Test
    fun namedThenPositionalBindsPositionalToItsArgumentIndex() {
        // describe(a = 10, 20) — the named `a` fills slot 0, then the POSITIONAL `20` must bind to parameter 1
        // (`b`, its argument index), NOT reuse slot 0. This is the `Icon(imageVector = X, "")` shape: a running
        // positional counter overwrote the named arg, dropping it (→ a null painter for Icon at render).
        assertEquals("10-20-3", run(describeCall(named("a", 10), named(null, 20))))
    }

    @Test
    fun namedThenPositionalReorderKeepsBothArgs() {
        // The reducer directly: `f(a = 10, 20)` → [10, 20] (both preserved), not [20] (the counter bug).
        val raw = listOf(named("a", 10), named(null, 20))
        assertEquals(listOf<Any?>(10, 20), reorderNamedArgs(listOf("a", "b", "c"), raw, listOf<Any?>(10, 20)))
    }

    @Test
    fun reorderNamedArgsTrimsTrailingOmittedSlots() {
        // describe(a = 10) keeps only [10] (b, c are trailing omissions filled by `$default`), not [10, Om, Om].
        val raw = listOf(named("a", 10))
        val out = reorderNamedArgs(listOf("a", "b", "c"), raw, listOf<Any?>(10))
        assertEquals(listOf<Any?>(10), out)
    }

    @Test
    fun reorderNamedArgsKeepsInteriorOmittedSlots() {
        // describe(a = 10, c = 30) → [10, OmittedArg, 30] (the interior `b` gap is preserved).
        val raw = listOf(named("a", 10), named("c", 30))
        val out = reorderNamedArgs(listOf("a", "b", "c"), raw, listOf<Any?>(10, 30))
        assertEquals(3, out.size)
        assertEquals(10, out[0])
        assertSame(OmittedArg, out[1])
        assertEquals(30, out[2])
    }

    @Test
    fun reorderIsANoOpWithoutNamedArgs() {
        val raw = listOf(named(null, 1), named(null, 2))
        val original = listOf<Any?>(1, 2)
        assertSame(original, reorderNamedArgs(listOf("a", "b"), raw, original), "no names → return the same list")
    }
}

/** A top-level function with all-defaulted params → a `describe$default(int,int,int,int,Object)` synthetic. */
fun describe(a: Int = 1, b: Int = 2, c: Int = 3): String = "$a-$b-$c"
