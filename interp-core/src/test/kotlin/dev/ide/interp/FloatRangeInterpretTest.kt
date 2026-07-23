package dev.ide.interp

import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `0f..50f` lowers to the stdlib `rangeTo` EXTENSION (`RangesKt.rangeTo(0f, 50f)` → ClosedFloatingPointRange).
 * The interpreter must dispatch that extension against the real stdlib and produce a usable range — the value
 * a `Slider(valueRange = 0f..50f)` needs. (Lowering is covered by lang-kotlin `RangeLoweringTest`.)
 */
class FloatRangeInterpretTest {

    private val span = SourceSpan(0, 0)

    @Test
    fun rangeToExtensionConstructsAFloatRange() {
        val call = RNode.Call(
            ResolvedCallable.Library("rangeTo", "kotlin.ranges.RangesKt", "rangeTo", listOf(null), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.EXTENSION,
            RNode.Const(0f, null, span),
            listOf(RArg(RNode.Const(50f, null, span))),
            CallSiteKey(1), span,
        )
        val fn = ResolvedFunction("f", emptyList(), call, emptyList())
        val result = Interpreter(emptyMap()).call(fn, emptyList())
        val range = assertNotNull(result as? ClosedFloatingPointRange<*>, "0f..50f must produce a ClosedFloatingPointRange, got ${result?.javaClass?.name}")
        assertTrue(range.start == 0f && range.endInclusive == 50f, "range endpoints must be 0f..50f, got ${range.start}..${range.endInclusive}")
        @Suppress("UNCHECKED_CAST")
        assertTrue((range as ClosedFloatingPointRange<Float>).contains(25f), "25f must be in 0f..50f")
    }
}
