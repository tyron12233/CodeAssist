package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A project-source top-level EXTENSION function is interpreted (its body is available), not reflected — a
 * library extension keeps its `…Kt` facade owner and is reflected, but a source one has no compiled class, so
 * the interpreter must run its body with the extension receiver bound. Previously such a call fell through to
 * the reflective dispatcher and threw "extension has no owner" (the reported Compose-preview failure).
 */
class SourceExtensionInterpretTest {

    @Test
    fun sourceExtensionOnAPrimitiveReceiverRuns() {
        // `this` in the extension body is the receiver value.
        val code = "package demo\n" +
            "fun Int.doubled(): Int = this * 2\n" +
            "fun run(): Int = 5.doubled()"
        assertEquals(10, runProgram(code, "run/0", emptyList()))
    }

    @Test
    fun sourceExtensionWithAValueParameterRuns() {
        val code = "package demo\n" +
            "fun Int.plus(n: Int, m: Int): Int = this + n + m\n" +
            "fun run(): Int = 1.plus(2, 3)"
        assertEquals(6, runProgram(code, "run/0", emptyList()))
    }

    @Test
    fun sourceExtensionOnASourceObjectReceiverReadsItsMembers() {
        // A bare member reference in the body (`x`) resolves against the extension receiver (a SourceObject).
        val code = "package demo\n" +
            "data class P(val x: Int)\n" +
            "fun P.plusX(n: Int): Int = x + n\n" +
            "fun run(): Int = P(3).plusX(4)"
        assertEquals(7, runProgram(code, "run/0", emptyList()))
    }

    @Test
    fun sourceExtensionCalledOnAnEvaluatedReceiverExpression() {
        val code = "package demo\n" +
            "fun String.shout(): String = this + \"!\"\n" +
            "fun run(): String { val s = \"hi\"\n  return s.shout() }"
        assertEquals("hi!", runProgram(code, "run/0", emptyList()))
    }
}
