package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `kotlin.math.*` functions are `@InlineOnly` delegations to `java.lang.Math`, so they have no callable JVM
 * method for the reflective dispatcher to find — they must be modeled as intrinsics. Covers the common numeric
 * functions UI/preview code hits: `sqrt`/`floor`/`ceil`, type-preserving `abs`/`min`/`max`, `Double.pow`, and
 * `Double.roundToInt`.
 */
class MathIntrinsicTest {

    private fun eval(expr: String, entry: String = "f/0", args: List<Any?> = emptyList()): Any? =
        runProgram("package demo\nimport kotlin.math.*\n$expr", entry, args)

    @Test fun sqrtFloorCeil() {
        assertEquals(4.0, eval("fun f(): Double = sqrt(16.0)"))
        assertEquals(2.0, eval("fun f(): Double = floor(2.7)"))
        assertEquals(3.0, eval("fun f(): Double = ceil(2.1)"))
    }

    @Test fun absIsTypePreserving() {
        assertEquals(5, eval("fun f(): Int = abs(-5)"))
        assertEquals(2.5, eval("fun f(): Double = abs(-2.5)"))
    }

    @Test fun minMaxReturnTheChosenValue() {
        assertEquals(7, eval("fun f(): Int = max(3, 7)"))
        assertEquals(3, eval("fun f(): Int = min(3, 7)"))
    }

    @Test fun powDoubleAndIntExponent() {
        assertEquals(1024.0, eval("fun f(): Double = 2.0.pow(10.0)"))
        assertEquals(8.0, eval("fun f(): Double = 2.0.pow(3)"))
    }

    @Test fun roundToIntTiesUp() {
        assertEquals(3, eval("fun f(): Int = 2.7.roundToInt()"))
        assertEquals(2, eval("fun f(): Int = 2.3.roundToInt()"))
    }

    @Test fun hypot() {
        assertEquals(5.0, eval("fun f(): Double = hypot(3.0, 4.0)"))
    }
}
