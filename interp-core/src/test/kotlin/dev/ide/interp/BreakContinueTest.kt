package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/** Unlabeled `break` / `continue` in `while` and `for` loops (control-flow signals caught by the loop). */
class BreakContinueTest {

    @Test fun breakExitsAWhileLoop() {
        val code = """
            package demo
            fun f(): Int {
                var i = 0
                var sum = 0
                while (i < 10) {
                    if (i == 3) break
                    sum = sum + i
                    i = i + 1
                }
                return sum
            }
        """.trimIndent()
        assertEquals(3, runProgram(code, "f/0", emptyList())) // 0 + 1 + 2
    }

    @Test fun continueSkipsToTheNextIteration() {
        val code = """
            package demo
            fun f(): Int {
                var sum = 0
                for (i in 0..5) {
                    if (i % 2 == 0) continue
                    sum = sum + i
                }
                return sum
            }
        """.trimIndent()
        assertEquals(9, runProgram(code, "f/0", emptyList())) // 1 + 3 + 5
    }

    @Test fun breakInsideAForLoop() {
        val code = """
            package demo
            fun f(): Int {
                var count = 0
                for (i in 0..100) {
                    if (count == 4) break
                    count = count + 1
                }
                return count
            }
        """.trimIndent()
        assertEquals(4, runProgram(code, "f/0", emptyList()))
    }

    @Test fun breakInsideATryWithAnUnrelatedCatchStillBreaks() {
        // A `break` is control flow, not an exception — a surrounding `catch (e: Exception)` must NOT swallow it
        // (else this would loop forever or return -100).
        val code = """
            package demo
            fun f(): Int {
                var n = 0
                while (true) {
                    try {
                        if (n == 2) break
                        n = n + 1
                    } catch (e: Exception) {
                        n = -100
                    }
                }
                return n
            }
        """.trimIndent()
        assertEquals(2, runProgram(code, "f/0", emptyList()))
    }
}
