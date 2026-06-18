package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Phase-2 language features: null-safety (`?.`, `?:`, `!!`), `throw` + `try/catch/finally`, `when` with
 * `is`/`in`, unary `!`/`-`, ranges, and destructuring declarations.
 */
class Phase2FeaturesTest {

    // --- null-safety ---

    @Test fun elvisUsesRightWhenNull() {
        val code = "package demo\nfun f(s: String?): String = s ?: \"default\""
        assertEquals("default", runProgram(code, "f/1", listOf(null)))
        assertEquals("x", runProgram(code, "f/1", listOf("x")))
    }

    @Test fun safeCallReturnsNullOnNullReceiver() {
        val code = "package demo\nfun f(s: String?): Int? = s?.length"
        assertEquals(null, runProgram(code, "f/1", listOf(null)))
        assertEquals(3, runProgram(code, "f/1", listOf("abc")))
    }

    @Test fun safeCallChainWithElvis() {
        val code = "package demo\nfun f(s: String?): Int = s?.length ?: -1"
        assertEquals(-1, runProgram(code, "f/1", listOf(null)))
        assertEquals(2, runProgram(code, "f/1", listOf("hi")))
    }

    @Test fun notNullAssertionThrowsOnNull() {
        val code = "package demo\nfun f(s: String?): Int = s!!.length"
        assertEquals(4, runProgram(code, "f/1", listOf("abcd")))
        assertFailsWith<NullPointerException> { runProgram(code, "f/1", listOf(null)) }
    }

    // --- throw / try / catch / finally ---

    @Test fun tryCatchRecoversFromThrow() {
        val code = """
            package demo
            fun f(n: Int): String {
                try {
                    if (n < 0) throw IllegalArgumentException("neg")
                    return "ok"
                } catch (e: IllegalArgumentException) {
                    return "caught"
                }
            }
        """.trimIndent()
        assertEquals("ok", runProgram(code, "f/1", listOf(1)))
        assertEquals("caught", runProgram(code, "f/1", listOf(-1)))
    }

    @Test fun finallyAlwaysRuns() {
        val code = """
            package demo
            fun f(): Int {
                var x = 0
                try {
                    x = 1
                } finally {
                    x = x + 10
                }
                return x
            }
        """.trimIndent()
        assertEquals(11, runProgram(code, "f/0", emptyList()))
    }

    @Test fun catchBySupertype() {
        val code = """
            package demo
            fun f(): String {
                try {
                    throw IllegalStateException("boom")
                } catch (e: Exception) {
                    return "handled"
                }
            }
        """.trimIndent()
        assertEquals("handled", runProgram(code, "f/0", emptyList()))
    }

    // --- when is / in ---

    @Test fun whenWithIsCondition() {
        val code = """
            package demo
            fun describe(x: Any): String = when (x) {
                is Int -> "int"
                is String -> "string"
                else -> "other"
            }
        """.trimIndent()
        assertEquals("int", runProgram(code, "describe/1", listOf(42)))
        assertEquals("string", runProgram(code, "describe/1", listOf("hi")))
        assertEquals("other", runProgram(code, "describe/1", listOf(1.5)))
    }

    @Test fun whenWithInRange() {
        val code = """
            package demo
            fun grade(n: Int): String = when (n) {
                in 0..59 -> "F"
                in 60..100 -> "P"
                else -> "?"
            }
        """.trimIndent()
        assertEquals("F", runProgram(code, "grade/1", listOf(30)))
        assertEquals("P", runProgram(code, "grade/1", listOf(85)))
        assertEquals("?", runProgram(code, "grade/1", listOf(200)))
    }

    // --- unary ---

    @Test fun unaryNot() {
        val code = "package demo\nfun f(b: Boolean): Boolean = !b"
        assertEquals(false, runProgram(code, "f/1", listOf(true)))
        assertEquals(true, runProgram(code, "f/1", listOf(false)))
    }

    @Test fun unaryMinus() {
        val code = "package demo\nfun f(n: Int): Int = -n"
        assertEquals(-5, runProgram(code, "f/1", listOf(5)))
    }

    // --- ranges / in operator ---

    @Test fun inRangeOperator() {
        val code = "package demo\nfun inside(n: Int): Boolean = n in 1..10"
        assertEquals(true, runProgram(code, "inside/1", listOf(5)))
        assertEquals(false, runProgram(code, "inside/1", listOf(11)))
    }

    @Test fun forLoopOverRange() {
        val code = """
            package demo
            fun sum(n: Int): Int {
                var total = 0
                for (i in 1..n) { total = total + i }
                return total
            }
        """.trimIndent()
        assertEquals(15, runProgram(code, "sum/1", listOf(5)))
    }

    @Test fun notInCollection() {
        val code = "package demo\nfun f(n: Int): Boolean = n !in listOf(1, 2, 3)"
        assertEquals(true, runProgram(code, "f/1", listOf(9)))
        assertEquals(false, runProgram(code, "f/1", listOf(2)))
    }

    // --- destructuring (pairs with data-class componentN) ---

    @Test fun destructuringDataClass() {
        val code = """
            data class Point(val x: Int, val y: Int)
            fun main(): Int {
                val (a, b) = Point(3, 4)
                return a + b
            }
        """.trimIndent()
        assertEquals(7, runProgram(code, "main/0", emptyList()))
    }
}
