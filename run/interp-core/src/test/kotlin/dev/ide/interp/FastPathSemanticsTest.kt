package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The interpreter's direct paths for primitive arithmetic and for iterating an integer range must give exactly what
 * Kotlin gives: they replaced string-keyed dispatch and a reflective iterator, so each case here is one a shortcut
 * could get wrong (a boundary, a promotion, an overflow, a step that skips the end).
 */
class FastPathSemanticsTest {

    private fun run(body: String): Any? = runProgram("package demo\nfun f(): Any? {\n$body\n}", "f/0", emptyList())

    // ---- ranges ------------------------------------------------------------------------------------------

    @Test fun anAscendingRangeIncludesBothEnds() =
        assertEquals("1,2,3,4,", run("var out = \"\"\nfor (i in 1..4) { out += i.toString() + \",\" }\nreturn out"))

    @Test fun anEmptyRangeRunsNoIterations() =
        assertEquals(0, run("var n = 0\nfor (i in 5..1) { n++ }\nreturn n"))

    @Test fun downToCountsDown() =
        assertEquals("5,4,3,2,1,", run("var out = \"\"\nfor (i in 5 downTo 1) { out += i.toString() + \",\" }\nreturn out"))

    @Test fun aStepStopsBeforeTheEndWhenItWouldPassIt() =
        assertEquals("1,4,7,10,", run("var out = \"\"\nfor (i in 1..11 step 3) { out += i.toString() + \",\" }\nreturn out"))

    @Test fun aDownwardStepStopsBeforeTheEnd() =
        assertEquals("10,7,4,1,", run("var out = \"\"\nfor (i in 10 downTo 0 step 3) { out += i.toString() + \",\" }\nreturn out"))

    @Test fun aRangeEndingAtIntMaxValueTerminates() =
        assertEquals(3, run("var n = 0\nfor (i in Int.MAX_VALUE - 2..Int.MAX_VALUE) { n++ }\nreturn n"))

    @Test fun aRangeStartingAtIntMinValueTerminatesDownward() =
        assertEquals(3, run("var n = 0\nfor (i in Int.MIN_VALUE + 2 downTo Int.MIN_VALUE) { n++ }\nreturn n"))

    @Test fun breakAndContinueWorkInARangeLoop() =
        assertEquals("1,2,4,", run("var out = \"\"\nfor (i in 1..10) { if (i == 3) continue; if (i == 5) break; out += i.toString() + \",\" }\nreturn out"))

    @Test fun eachIterationOfARangeLoopCapturesItsOwnVariable() =
        assertEquals("1,2,3", run("val fs = mutableListOf<() -> Int>()\nfor (i in 1..3) { fs.add { i } }\nreturn fs.joinToString(\",\") { it().toString() }"))

    @Test fun aCharRangeAndACollectionStillIterate() {
        assertEquals("a,b,c,", run("var out = \"\"\nfor (c in 'a'..'c') { out += c.toString() + \",\" }\nreturn out"))
        assertEquals(6, run("var s = 0\nfor (x in listOf(1, 2, 3)) { s += x }\nreturn s"))
        assertEquals("k=1", run("var r = \"\"\nfor ((k, v) in mapOf(\"k\" to 1)) { r = k + \"=\" + v }\nreturn r"))
    }

    // ---- arithmetic and comparison -----------------------------------------------------------------------

    @Test fun intArithmeticWrapsLikeKotlin() {
        assertEquals(Int.MIN_VALUE, run("val a = Int.MAX_VALUE\nreturn a + 1"))
        assertEquals(-2, run("val a = 7\nval b = -3\nreturn a / b"))
        assertEquals(1, run("val a = 7\nval b = -3\nreturn a % b"))
    }

    @Test fun mixedIntAndLongWidenToLong() {
        assertEquals(5_000_000_001L, run("val a = 5_000_000_000L\nval b = 1\nreturn a + b"))
        assertEquals(5_000_000_001L, run("val a = 1\nval b = 5_000_000_000L\nreturn a + b"))
        assertEquals(10L, run("var s = 0L\nfor (i in 1..4) { s += i }\nreturn s"))
    }

    @Test fun doubleArithmeticAndPromotion() {
        assertEquals(2.5, run("val a = 5.0\nval b = 2.0\nreturn a / b"))
        assertEquals(3.5, run("val a = 1.5\nval b = 2\nreturn a + b"))
    }

    @Test fun divisionByZeroStillThrows() {
        assertFailsWith<Throwable> { run("val z = 0\nval a = 1\nreturn a / z") }
        assertFailsWith<Throwable> { run("val z = 0L\nval a = 1L\nreturn a % z") }
    }

    @Test fun comparisonsAcrossIntAndLong() {
        assertEquals(true, run("val a = 3\nval b = 4L\nreturn a < b"))
        assertEquals(false, run("val a = 3L\nval b = 3\nreturn a > b"))
        assertEquals(true, run("val a = 2.0\nval b = 2.5\nreturn a <= b"))
    }

    @Test fun equalityStaysStructural() {
        assertEquals(true, run("val a = 3\nval b = 3\nreturn a == b"))
        assertEquals(true, run("val a = 3L\nval b = 3L\nreturn a == b"))
        assertEquals(true, run("val a = 3\nval b = 4\nreturn a != b"))
        assertEquals(true, run("val a = \"x\"\nval b = \"x\"\nreturn a == b"))
        assertEquals(true, run("val a = 0.0 / 0.0\nval b = 0.0 / 0.0\nreturn a == b")) // NaN: boxed equals, as before
    }

    @Test fun stringPlusAndSourceOperatorsAreNotTakenByTheFastPath() {
        assertEquals("ab1", run("val a = \"a\"\nreturn a + \"b\" + 1"))
        assertEquals(7, runProgram(
            "package demo\ndata class V(val n: Int) { operator fun plus(o: V) = V(n + o.n) }\nfun f(): Int = (V(3) + V(4)).n",
            "f/0", emptyList(),
        ))
    }

    @Test fun theSameCallNodeGivesTheRightAnswerForDifferentOperandTypes() =
        // One node evaluated with Int operands and then with Double ones: the cached operator number is the operator,
        // not the operand types, so it must not stick to the first pair.
        assertEquals("3,3.5", run("fun add(a: Number, b: Number): Any = if (a is Int && b is Int) a + b else (a.toDouble() + b.toDouble())\nreturn add(1, 2).toString() + \",\" + add(1.5, 2).toString()"))
}
