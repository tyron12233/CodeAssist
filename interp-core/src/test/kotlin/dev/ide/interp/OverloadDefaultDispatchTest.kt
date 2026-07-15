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

/**
 * The reported `Color(Random.nextFloat(), ...)` crash: `ColorKt.Color` has a 3-arg `Color(Int, Int, Int)` AND a
 * defaulted `Color(Float, Float, Float, alpha = …)`. Three Float args fit NO exact-arity overload (the Int one
 * doesn't accept Floats; the Float one has more parameters), so the dispatcher must fall through to the Float
 * overload's `Color$default` synthetic. Before the fix, `findMethod`'s same-arity fallback returned the
 * non-accepting `Color(Int, Int, Int)` and invoked it with Floats -> IllegalArgumentException. Reproduced here
 * with [makeThing]'s mirrored shape (deterministic on any JVM, unlike the platform-dependent real `Color`).
 */
class OverloadDefaultDispatchTest {

    @Test
    fun floatArgsWithNoExactOverloadUseTheDefaultSyntheticNotAWrongIntOverload() {
        val span = SourceSpan(0, 0)
        fun arg(v: Any?) = RArg(RNode.Const(v, null, span))
        val callee = ResolvedCallable.Library(
            displayName = "makeThing", ownerFqn = "dev.ide.interp.OverloadDefaultDispatchTestKt",
            methodName = "makeThing", paramTypes = List(3) { null },
            isStatic = true, isConstructor = false, isInline = false,
        )
        val call = RNode.Call(
            callee, DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(arg(0.1f), arg(0.2f), arg(0.3f)), callSiteKey = CallSiteKey(1), source = span,
        )
        val fn = ResolvedFunction("f", emptyList(), call, emptyList())
        val result = Interpreter(emptyMap()).call(fn, emptyList())
        assertEquals(
            "float:0.1,0.2,0.3,1.0", result,
            "3 Float args must resolve to the Float overload via its \$default synthetic, not crash on makeThing(Int,Int,Int)",
        )
    }
}

/** The `Color(Int,Int,Int)` shape: a same-arity overload that does NOT accept Float args. */
fun makeThing(a: Int, b: Int, c: Int): String = "int:$a,$b,$c"

/** The `Color(Float,Float,Float, alpha = 1f)` shape: the intended overload, reached via `makeThing$default`. */
fun makeThing(a: Float, b: Float, c: Float, d: Float = 1f): String = "float:$a,$b,$c,$d"
