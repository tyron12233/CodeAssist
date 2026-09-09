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
 * The reported `darkColorScheme(...)` failure ("no static darkColorScheme(17)"): material3's `darkColorScheme`
 * has 30-40 defaulted color params — MORE than 32 — so its `$default` synthetic carries TWO Int mask words
 * (`realParams…, int mask0, int mask1, Object marker`), and a call that provides only some of them must route
 * through it. The dispatcher assumed a single mask word (`n = params.size - 2`), which mis-slots a >32-param
 * synthetic. [bigScheme] mirrors that shape deterministically (34 defaulted params).
 */
class BigDefaultSyntheticDispatchTest {

    @Test
    fun partialCallToAFunctionWithMoreThan32DefaultedParamsUsesTheDefaultSynthetic() {
        val span = SourceSpan(0, 0)
        fun arg(v: Any?) = RArg(RNode.Const(v, null, span))
        val callee = ResolvedCallable.Library(
            displayName = "bigScheme", ownerFqn = "dev.ide.interp.BigDefaultSyntheticDispatchTestKt",
            methodName = "bigScheme", paramTypes = List(3) { null },
            isStatic = true, isConstructor = false, isInline = false,
        )
        val call = RNode.Call(
            callee, DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(arg(1L), arg(1L), arg(1L)), callSiteKey = CallSiteKey(1), source = span,
        )
        val fn = ResolvedFunction("f", emptyList(), call, emptyList())
        val result = Interpreter(emptyMap()).call(fn, emptyList())
        // 3 provided (1 each) + 31 defaulted (1000 each) = 3 + 31000 = 31003.
        assertEquals(31003L, result, "3 args to a 34-defaulted-param fn must route through bigScheme\$default (two mask words)")
    }

    /** The actual reported shape: value-class (Color-like) params, so the function is MANGLED and the args are
     *  boxed value-class instances — the exact `darkColorScheme(...)` case that threw "no static". */
    @Test
    fun partialCallToAValueClassParamFunctionWithMoreThan32DefaultsUsesTheDefaultSynthetic() {
        val span = SourceSpan(0, 0)
        fun arg(v: Any?) = RArg(RNode.Const(v, null, span))
        val callee = ResolvedCallable.Library(
            displayName = "bigColorScheme", ownerFqn = "dev.ide.interp.BigDefaultSyntheticDispatchTestKt",
            methodName = "bigColorScheme", paramTypes = List(3) { null },
            isStatic = true, isConstructor = false, isInline = false,
        )
        val call = RNode.Call(
            callee, DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(arg(Tone(1L)), arg(Tone(1L)), arg(Tone(1L))), callSiteKey = CallSiteKey(1), source = span,
        )
        val fn = ResolvedFunction("f", emptyList(), call, emptyList())
        val result = Interpreter(emptyMap()).call(fn, emptyList())
        assertEquals(31003L, result, "3 Tone args to a 34-defaulted-param fn must route through bigColorScheme\$default (mangled + two mask words)")
    }
}

/** Color-like value class → its host function is name-mangled and its args arrive boxed. */
@JvmInline
value class Tone(val v: Long)

/** 34 defaulted params (> 32) → the `$default` synthetic carries TWO Int mask words. Mirrors darkColorScheme. */
@Suppress("LongParameterList")
fun bigScheme(
    p0: Long = 1000, p1: Long = 1000, p2: Long = 1000, p3: Long = 1000, p4: Long = 1000,
    p5: Long = 1000, p6: Long = 1000, p7: Long = 1000, p8: Long = 1000, p9: Long = 1000,
    p10: Long = 1000, p11: Long = 1000, p12: Long = 1000, p13: Long = 1000, p14: Long = 1000,
    p15: Long = 1000, p16: Long = 1000, p17: Long = 1000, p18: Long = 1000, p19: Long = 1000,
    p20: Long = 1000, p21: Long = 1000, p22: Long = 1000, p23: Long = 1000, p24: Long = 1000,
    p25: Long = 1000, p26: Long = 1000, p27: Long = 1000, p28: Long = 1000, p29: Long = 1000,
    p30: Long = 1000, p31: Long = 1000, p32: Long = 1000, p33: Long = 1000,
): Long = p0 + p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13 + p14 + p15 + p16 +
    p17 + p18 + p19 + p20 + p21 + p22 + p23 + p24 + p25 + p26 + p27 + p28 + p29 + p30 + p31 + p32 + p33

/** 34 defaulted VALUE-CLASS params (> 32) — mirrors material3's `darkColorScheme` (Color params → mangled). */
@Suppress("LongParameterList")
fun bigColorScheme(
    c0: Tone = Tone(1000), c1: Tone = Tone(1000), c2: Tone = Tone(1000), c3: Tone = Tone(1000), c4: Tone = Tone(1000),
    c5: Tone = Tone(1000), c6: Tone = Tone(1000), c7: Tone = Tone(1000), c8: Tone = Tone(1000), c9: Tone = Tone(1000),
    c10: Tone = Tone(1000), c11: Tone = Tone(1000), c12: Tone = Tone(1000), c13: Tone = Tone(1000), c14: Tone = Tone(1000),
    c15: Tone = Tone(1000), c16: Tone = Tone(1000), c17: Tone = Tone(1000), c18: Tone = Tone(1000), c19: Tone = Tone(1000),
    c20: Tone = Tone(1000), c21: Tone = Tone(1000), c22: Tone = Tone(1000), c23: Tone = Tone(1000), c24: Tone = Tone(1000),
    c25: Tone = Tone(1000), c26: Tone = Tone(1000), c27: Tone = Tone(1000), c28: Tone = Tone(1000), c29: Tone = Tone(1000),
    c30: Tone = Tone(1000), c31: Tone = Tone(1000), c32: Tone = Tone(1000), c33: Tone = Tone(1000),
): Long = c0.v + c1.v + c2.v + c3.v + c4.v + c5.v + c6.v + c7.v + c8.v + c9.v + c10.v + c11.v + c12.v + c13.v +
    c14.v + c15.v + c16.v + c17.v + c18.v + c19.v + c20.v + c21.v + c22.v + c23.v + c24.v + c25.v + c26.v +
    c27.v + c28.v + c29.v + c30.v + c31.v + c32.v + c33.v
