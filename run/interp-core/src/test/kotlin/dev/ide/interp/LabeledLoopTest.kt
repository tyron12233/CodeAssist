package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Labeled `break@label` / `continue@label` targeting an OUTER loop. The resolver carries the label onto the
 * loop node and onto the jump; the interpreter's per-loop signal handling swallows a jump that names this loop
 * and rethrows one that names an enclosing loop. Unlabeled jumps keep hitting the innermost loop.
 */
class LabeledLoopTest {

    @Test fun labeledBreakExitsOuterLoop() {
        val code = """
            package demo
            fun f(): Int {
                var count = 0
                outer@ for (i in 0 until 3) {
                    for (j in 0 until 3) {
                        count++
                        if (i == 1 && j == 1) break@outer
                    }
                }
                return count
            }
        """.trimIndent()
        // i=0: j=0,1,2 → 3; i=1: j=0 (4), j=1 (5) then break@outer → 5
        assertEquals(5, runProgram(code, "f/0", emptyList()))
    }

    @Test fun labeledContinueSkipsToOuterLoop() {
        val code = """
            package demo
            fun f(): Int {
                var sum = 0
                outer@ for (i in 1..3) {
                    for (j in 1..3) {
                        if (j == 2) continue@outer
                        sum += i * 10 + j
                    }
                }
                return sum
            }
        """.trimIndent()
        // each i: j=1 adds i*10+1, j=2 → continue@outer (skips j=3). i=1:11, i=2:21, i=3:31 → 63
        assertEquals(63, runProgram(code, "f/0", emptyList()))
    }

    @Test fun labeledBreakInWhileLoop() {
        val code = """
            package demo
            fun f(): Int {
                var i = 0
                var hits = 0
                loop@ while (i < 5) {
                    i++
                    var k = 0
                    while (k < 5) {
                        k++
                        hits++
                        if (i == 2 && k == 3) break@loop
                    }
                }
                return hits
            }
        """.trimIndent()
        // i=1: k=1..5 → 5 hits; i=2: k=1,2,3 → 3 hits, then break@loop → 8
        assertEquals(8, runProgram(code, "f/0", emptyList()))
    }

    @Test fun unlabeledBreakStillStopsInnermost() {
        val code = """
            package demo
            fun f(): Int {
                var count = 0
                for (i in 0 until 3) {
                    for (j in 0 until 3) {
                        if (j == 1) break
                        count++
                    }
                }
                return count
            }
        """.trimIndent()
        // each i: j=0 → count++, j=1 → break inner. 3 iterations of outer → 3
        assertEquals(3, runProgram(code, "f/0", emptyList()))
    }
}
