package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * More `@InlineOnly` collection intrinsics (`elementAtOrElse`, `firstNotNullOf`/`firstNotNullOfOrNull`), and the
 * reflective-dispatch error unwrapping: a method invoked reflectively that throws must surface its REAL cause,
 * not the opaque `java.lang.reflect.InvocationTargetException` wrapper (so a preview error is actionable).
 */
class MoreIntrinsicsTest {

    @Test fun elementAtOrElseInRangeThenDefault() {
        assertEquals(20, runProgram("package demo\nfun f(): Int = listOf(10, 20, 30).elementAtOrElse(1) { -1 }", "f/0", emptyList()))
        assertEquals(-1, runProgram("package demo\nfun f(): Int = listOf(10, 20, 30).elementAtOrElse(9) { -1 }", "f/0", emptyList()))
    }

    @Test fun firstNotNullOfOrNullReturnsFirstNonNull() {
        val code = "package demo\nfun f(): Int? = listOf(\"a\", \"2\", \"3\").firstNotNullOfOrNull { it.toIntOrNull() }"
        assertEquals(2, runProgram(code, "f/0", emptyList()))
    }

    @Test fun firstNotNullOfOrNullReturnsNullWhenNone() {
        val code = "package demo\nfun f(): Int? = listOf(\"a\", \"b\").firstNotNullOfOrNull { it.toIntOrNull() }"
        assertNull(runProgram(code, "f/0", emptyList()))
    }

    @Test fun firstNotNullOfThrowsWhenNone() {
        val code = "package demo\nfun f(): Int = listOf(\"a\", \"b\").firstNotNullOf { it.toIntOrNull() }"
        assertFailsWith<NoSuchElementException> { runProgram(code, "f/0", emptyList()) }
    }

    @Test fun reflectiveThrowSurfacesRealCauseNotInvocationTargetException() {
        // `emptyList().first()` reflects into CollectionsKt.first, which throws NoSuchElementException on an
        // empty list — the interpreter must surface THAT, not the InvocationTargetException reflection wraps it
        // in. (`first()` is a real method with bytecode; unlike the @InlineOnly `toInt()`, it genuinely reflects.)
        assertFailsWith<NoSuchElementException> { runProgram("package demo\nfun f(): Int = emptyList<Int>().first()", "f/0", emptyList()) }
    }
}
