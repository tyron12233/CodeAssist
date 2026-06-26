package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Gap tolerance (the Compose preview path): a function containing an unsupported construct still runs, with the
 * whole-statement gap SKIPPED, instead of being refused outright — so one unsupported widget doesn't blank the
 * whole preview. Off by default (the console Run / tests fail loudly).
 */
class PartialRenderingTest {

    private val code = """
        package demo
        fun f(): Int {
            val a = 1
            bogusUnresolvedThing()
            return a + 41
        }
    """.trimIndent()

    @Test fun gapToleranceSkipsAnUnsupportedStatementAndRunsTheRest() {
        val (functions, classes) = lowerProgramFull(code)
        val fn = functions["f/0"] ?: error("no f/0")
        val result = Interpreter(functions, classes = classes, tolerateGaps = true).call(fn, emptyList())
        assertEquals(42, result, "the unsupported `bogusUnresolvedThing()` statement should be skipped, the rest run")
    }

    @Test fun withoutGapToleranceTheWholeFunctionIsRefused() {
        val (functions, classes) = lowerProgramFull(code)
        val fn = functions["f/0"] ?: error("no f/0")
        val ex = assertFailsWith<InterpreterException> {
            Interpreter(functions, classes = classes).call(fn, emptyList())
        }
        assertEquals(true, ex.message?.contains("unsupported") == true, "message=${ex.message}")
    }
}
