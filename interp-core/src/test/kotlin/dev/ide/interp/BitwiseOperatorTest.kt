package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin's bitwise operators (`and`/`or`/`xor`/`shl`/`shr`/`ushr` and `inv`) are compiler intrinsics on
 * `Int`/`Long` with no invocable JVM method on the boxed type, so the reflective dispatcher can't find them —
 * they're computed as interpreter intrinsics. Covers both integer widths, the unsigned right shift, and the
 * common flag-set idiom.
 */
class BitwiseOperatorTest {

    private fun eval(expr: String): Any? = runProgram("package demo\n$expr", "f/0", emptyList())

    @Test fun andOrXorInt() {
        assertEquals(0b1000, eval("fun f(): Int = 0b1100 and 0b1010"))
        assertEquals(0b1110, eval("fun f(): Int = 0b1100 or 0b1010"))
        assertEquals(0b0110, eval("fun f(): Int = 0b1100 xor 0b1010"))
    }

    @Test fun shiftsInt() {
        assertEquals(16, eval("fun f(): Int = 1 shl 4"))
        assertEquals(64, eval("fun f(): Int = 256 shr 2"))
        assertEquals(15, eval("fun f(): Int = -1 ushr 28"))
    }

    @Test fun invInt() {
        assertEquals(-6, eval("fun f(): Int = 5.inv()"))
    }

    @Test fun longWidthIsPreserved() {
        assertEquals(0xF0L, eval("fun f(): Long = 0xF0L and 0xFFL"))
        assertEquals(1099511627776L, eval("fun f(): Long = 1L shl 40"))
        assertEquals(-1L, eval("fun f(): Long = 0L.inv()"))
    }

    @Test fun flagSetIdiom() {
        assertEquals(5, eval("fun f(): Int = (1 shl 0) or (1 shl 2)"))
    }
}
