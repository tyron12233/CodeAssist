package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Short-circuiting `&&` / `||`. They lower to a short-circuit `if` (`a && b` → `if (a) b else false`), so the
 * RHS is only evaluated when the LHS doesn't already decide the result — and the boolean truth table holds.
 */
class LogicalOperatorTest {

    @Test fun andTruthTable() {
        val code = "package demo\nfun f(a: Boolean, b: Boolean): Boolean = a && b"
        assertEquals(true, runProgram(code, "f/2", listOf(true, true)))
        assertEquals(false, runProgram(code, "f/2", listOf(true, false)))
        assertEquals(false, runProgram(code, "f/2", listOf(false, true)))
    }

    @Test fun orTruthTable() {
        val code = "package demo\nfun f(a: Boolean, b: Boolean): Boolean = a || b"
        assertEquals(true, runProgram(code, "f/2", listOf(false, true)))
        assertEquals(false, runProgram(code, "f/2", listOf(false, false)))
        assertEquals(true, runProgram(code, "f/2", listOf(true, false)))
    }

    @Test fun andShortCircuitsAndDoesNotEvaluateTheRhs() {
        // `n != 0 && 10 / n > 1` — when n == 0 the RHS (`10 / n`) must NOT run, or it throws ArithmeticException.
        val code = "package demo\nfun f(n: Int): Boolean = n != 0 && 10 / n > 1"
        assertEquals(false, runProgram(code, "f/1", listOf(0)))
        assertEquals(true, runProgram(code, "f/1", listOf(3)))
        assertEquals(false, runProgram(code, "f/1", listOf(20)))
    }

    @Test fun orShortCircuitsAndDoesNotEvaluateTheRhs() {
        val code = "package demo\nfun f(n: Int): Boolean = n == 0 || 10 / n > 1"
        assertEquals(true, runProgram(code, "f/1", listOf(0)))
        assertEquals(true, runProgram(code, "f/1", listOf(3)))
        assertEquals(false, runProgram(code, "f/1", listOf(20)))
    }
}
