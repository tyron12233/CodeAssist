package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Operator overloads on PROJECT-SOURCE classes. The resolver already resolves a source `operator fun` and emits
 * an OPERATOR/INVOKE call; the interpreter now routes those to the class's member (`dispatchSourceMember`)
 * instead of failing to reflect a synthetic callee. Covers `plus` (and, via desugaring, `+=`), `compareTo`
 * (`<`/`>=`), `invoke`, `get` (indexed access), and `iterator` (`for (x in …)`).
 */
class SourceOperatorTest {

    @Test fun plusOperator() {
        val code = """
            package demo
            class V(val x: Int) { operator fun plus(o: V): V = V(x + o.x) }
            fun f(): Int = (V(1) + V(2)).x
        """.trimIndent()
        assertEquals(3, runProgram(code, "f/0", emptyList()))
    }

    @Test fun plusAssignDesugarsThroughPlus() {
        val code = """
            package demo
            class V(val x: Int) { operator fun plus(o: V): V = V(x + o.x) }
            fun f(): Int { var v = V(1); v += V(4); return v.x }
        """.trimIndent()
        assertEquals(5, runProgram(code, "f/0", emptyList()))
    }

    @Test fun compareToDrivesRelationalOperators() {
        val code = """
            package demo
            class T(val n: Int) : Comparable<T> { override fun compareTo(other: T): Int = n - other.n }
            fun lt(): Boolean = T(1) < T(2)
            fun ge(): Boolean = T(5) >= T(3)
            fun gtFalse(): Boolean = T(1) > T(9)
        """.trimIndent()
        assertTrue(runProgram(code, "lt/0", emptyList()) as Boolean)
        assertTrue(runProgram(code, "ge/0", emptyList()) as Boolean)
        assertFalse(runProgram(code, "gtFalse/0", emptyList()) as Boolean)
    }

    @Test fun invokeOperator() {
        val code = """
            package demo
            class Adder { operator fun invoke(a: Int, b: Int): Int = a + b }
            fun f(): Int { val g = Adder(); return g(2, 3) }
        """.trimIndent()
        assertEquals(5, runProgram(code, "f/0", emptyList()))
    }

    @Test fun getOperatorIndexedAccess() {
        val code = """
            package demo
            class Box { val data = mutableListOf(10, 20, 30); operator fun get(i: Int): Int = data[i] }
            fun f(): Int = Box()[1]
        """.trimIndent()
        assertEquals(20, runProgram(code, "f/0", emptyList()))
    }

    @Test fun iteratorOperatorDrivesForLoop() {
        val code = """
            package demo
            class Three { operator fun iterator(): Iterator<Int> = listOf(1, 2, 3).iterator() }
            fun f(): Int { var s = 0; for (x in Three()) s += x; return s }
        """.trimIndent()
        assertEquals(6, runProgram(code, "f/0", emptyList()))
    }
}
