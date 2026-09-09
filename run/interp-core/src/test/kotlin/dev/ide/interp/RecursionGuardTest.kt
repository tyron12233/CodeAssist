package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Compose preview interprets user code in-process (a `@Composable` body on the UI thread), so unbounded
 * recursion would StackOverflow the host thread — an app crash, not just a bad preview. The interpreter's call
 * depth guard bounds interpreted recursion and throws a plain [InterpreterException] first, so the preview
 * surfaces an error instead of crashing. A finite recursion within the bound must still run untouched.
 */
class RecursionGuardTest {

    @Test
    fun unboundedRecursionAbortsInsteadOfStackOverflow() {
        // `fun f(): Int = f()` — endless self-recursion. Must surface as a bounded InterpreterException, NOT a
        // StackOverflowError propagating (which `assertFailsWith<InterpreterException>` would fail on). Either the
        // depth guard or its StackOverflowError backstop fires depending on the thread's stack size; both mention
        // "recursion".
        val ex = assertFailsWith<InterpreterException> {
            runProgram("fun f(): Int = f()", "f/0", emptyList())
        }
        assertTrue(ex.message?.contains("recursion") == true, "expected the recursion guard: ${ex.message}")
    }

    @Test
    fun deepFiniteRecursionBeyondTheBoundIsAlsoCaught() {
        // A terminating recursion whose depth exceeds what the stack allows still can't be run (it would overflow
        // before returning), so it is bounded the same way rather than crashing.
        val ex = assertFailsWith<InterpreterException> {
            runProgram(
                "fun f(n: Int): Int = if (n <= 0) 0 else n + f(n - 1)",
                "f/1", listOf(100_000),
            )
        }
        assertTrue(ex.message?.contains("recursion") == true, "expected the recursion guard: ${ex.message}")
    }

    @Test
    fun boundedRecursionWithinTheDepthLimitStillCompletes() {
        // The guard must not perturb ordinary recursion: sum 1..100 recursively (depth 100, well under the limit).
        val result = runProgram(
            "fun f(n: Int): Int = if (n <= 0) 0 else n + f(n - 1)",
            "f/1", listOf(100),
        )
        assertEquals(5050, result)
    }
}
