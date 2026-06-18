package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Float arithmetic + numeric conversions. `(progress * 100).toInt()` (the `"${(progress * 100).toInt()}%"`
 * Compose shape) broke twice at runtime: `Float * Int` had no case in `arithmetic` so it fell to integer
 * math (`0.82f * 100` → `0`, an `Int`), and `toInt()` on a boxed number has no JVM method to reflect. Both
 * are computed intrinsically now.
 */
class NumericConversionTest {

    @Test fun floatTimesIntKeepsFloatPrecision() {
        // `0.82f * 100` must be 82.0f (Float), not 0 (the old integer-math fallthrough).
        assertEquals(82.0f, runProgram("package d\nfun f(p: Float): Float = p * 100", "f/1", listOf(0.82f)))
    }

    @Test fun toIntOnAFloatArithmeticResult() {
        // `(p * 100).toInt()` → 82. Exercises both the Float `*` case and the `toInt` conversion intrinsic.
        assertEquals(82, runProgram("package d\nfun f(p: Float): Int = (p * 100).toInt()", "f/1", listOf(0.82f)))
    }

    @Test fun numericConversionsAcrossTypes() {
        assertEquals(3.0, runProgram("package d\nfun f(n: Int): Double = n.toDouble()", "f/1", listOf(3)))
        assertEquals(5.0f, runProgram("package d\nfun f(n: Int): Float = n.toFloat()", "f/1", listOf(5)))
        assertEquals(7L, runProgram("package d\nfun f(n: Int): Long = n.toLong()", "f/1", listOf(7)))
        assertEquals(2, runProgram("package d\nfun f(x: Double): Int = x.toInt()", "f/1", listOf(2.9)))
    }
}
