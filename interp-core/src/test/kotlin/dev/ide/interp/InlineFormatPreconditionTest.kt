package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `@InlineOnly` stdlib functions have no callable JVM method (the compiler only inlines them), so the reflective
 * dispatcher can't find them — they must be modeled as intrinsics. Covers `String.format` (both the extension
 * `"fmt".format(args)` and the companion `String.format(fmt, args)` forms) and the precondition family
 * (`require`/`check`/`error`/`requireNotNull`/`checkNotNull`/`TODO`), whose failures throw the real exception a
 * compiled app would (surfacing as a preview error) rather than the opaque "inline-only function not modeled".
 */
class InlineFormatPreconditionTest {

    @Test fun stringFormatExtensionForm() {
        val code = "package demo\nfun f(): String = \"%d items\".format(3)"
        assertEquals("3 items", runProgram(code, "f/0", emptyList()))
    }

    @Test fun stringFormatCompanionForm() {
        val code = "package demo\nfun f(): String = String.format(\"%.1f\", 2.5)"
        assertEquals("2.5", runProgram(code, "f/0", emptyList()))
    }

    @Test fun stringFormatMultipleArgs() {
        val code = "package demo\nfun f(): String = \"%s = %d\".format(\"n\", 42)"
        assertEquals("n = 42", runProgram(code, "f/0", emptyList()))
    }

    @Test fun errorThrowsIllegalState() {
        val code = "package demo\nfun f(): Int = error(\"boom\")"
        val e = assertFailsWith<IllegalStateException> { runProgram(code, "f/0", emptyList()) }
        assertEquals("boom", e.message)
    }

    @Test fun requireFalseThrowsWithLazyMessage() {
        val code = "package demo\nfun f() { require(false) { \"nope\" } }"
        val e = assertFailsWith<IllegalArgumentException> { runProgram(code, "f/0", emptyList()) }
        assertEquals("nope", e.message)
    }

    @Test fun checkFalseThrowsIllegalState() {
        val code = "package demo\nfun f() { check(1 == 2) }"
        assertFailsWith<IllegalStateException> { runProgram(code, "f/0", emptyList()) }
    }

    @Test fun requireNotNullReturnsValueOrThrows() {
        assertEquals(7, runProgram("package demo\nfun f(): Int = requireNotNull(7)", "f/0", emptyList()))
        val code = "package demo\nfun f(x: Any?): Any = requireNotNull(x)"
        assertFailsWith<IllegalArgumentException> { runProgram(code, "f/1", listOf<Any?>(null)) }
    }

    @Test fun todoThrowsNotImplemented() {
        val code = "package demo\nfun f(): Int = TODO(\"later\")"
        assertFailsWith<NotImplementedError> { runProgram(code, "f/0", emptyList()) }
    }
}
