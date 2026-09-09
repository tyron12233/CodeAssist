package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A preview runs interpreted on the app's UI thread (a `@Composable` body / a `LaunchedEffect` coroutine on the
 * main dispatcher), so an unbounded loop hangs the WHOLE app (an ANR). Loops can become unbounded with no fault
 * of the source: `tolerateGaps` elides an unsupported suspend call (`delay`), turning `while (!done) { delay();
 * progress() }` into a busy loop that never exits — the reported Memory-Match timer ANR. The interpreter's
 * runaway guard bounds every loop so a pathological preview aborts instead of freezing.
 */
class LoopGuardTest {

    @Test
    fun runawayWhileLoopAbortsInsteadOfHanging() {
        // `while (true) { }` — the shape a skipped `delay` leaves behind. Must throw (guard), not spin forever.
        val ex = assertFailsWith<InterpreterException> {
            runProgram("fun f() { while (true) { } }", "f/0", emptyList())
        }
        assertTrue(ex.message?.contains("aborting to avoid hanging") == true, "expected the runaway-loop guard: ${ex.message}")
    }

    @Test
    fun runawayForEachAbortsInsteadOfHanging() {
        // A `for` over a huge range (far past the iteration cap) must be bounded too — an interpreted loop that
        // large would freeze the preview regardless of whether it eventually terminates.
        val ex = assertFailsWith<InterpreterException> {
            runProgram("fun f() { var n = 0; for (x in 0..5000000) { n = x } }", "f/0", emptyList())
        }
        assertTrue(ex.message?.contains("aborting to avoid hanging") == true, "expected the runaway-loop guard: ${ex.message}")
    }

    @Test
    fun aNormalBoundedLoopStillCompletes() {
        // The guard must not perturb an ordinary loop: sum 1..100.
        val result = runProgram(
            "fun f(): Int { var s = 0; var i = 1; while (i <= 100) { s = s + i; i = i + 1 }; return s }",
            "f/0", emptyList(),
        )
        assertEquals(5050, result)
    }
}
