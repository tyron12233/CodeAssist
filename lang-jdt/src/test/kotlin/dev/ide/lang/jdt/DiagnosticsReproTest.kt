package dev.ide.lang.jdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A statement that fails to parse can't be type-checked reliably: ecj's statement recovery emits a spurious
 * *semantic* error for an unterminated `…map(it -> 1)` (it recovers the inline lambda as just its body, so
 * `map` looks called with an `int`). Diagnostics drop non-syntax problems inside a syntactically-broken
 * statement — keeping the genuine missing-`;` and any real type errors in well-formed statements.
 */
class DiagnosticsReproTest {

    private fun diags(code: String): List<String> {
        val (analyzer, dir) = workspaceWith()
        return try {
            analyzer.parse(StubFile(dir.resolve("app/T.java").toString(), code), code).diagnostics.map { it.message }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun inlineLambdaMissingSemicolonReportsNoSpuriousTypeError() {
        val msgs = diags("package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1) } }")
        assertFalse(msgs.any { "is not applicable for the arguments" in it }, "spurious type error must be suppressed: $msgs")
        assertTrue(msgs.any { "insert \";\"" in it }, "missing-semicolon syntax error must remain: $msgs")
    }

    @Test
    fun blockLambdaMissingSemicolonReportsOnlySyntaxError() {
        val msgs = diags("package app; class T { void m() { java.util.List.of(1).stream().map(vp -> { return 1; }) } }")
        assertEquals(listOf("Syntax error, insert \";\" to complete BlockStatements"), msgs, "block lambda: $msgs")
    }

    @Test
    fun wellFormedInlineLambdaHasNoDiagnostics() {
        assertTrue(diags("package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1); } }").isEmpty())
    }

    @Test
    fun genuineTypeErrorWithoutSyntaxErrorIsKept() {
        val msgs = diags("package app; class T { void m() { String s = 5; } }")
        assertTrue(msgs.any { "Type mismatch" in it }, "real type error must be reported: $msgs")
    }

    @Test
    fun typeErrorInWellFormedStatementSurvivesABrokenSiblingStatement() {
        // statement 1 has a real type error and ends with ';'; statement 2 (lambda chain) is missing its ';'.
        val msgs = diags("package app; class T { void m() { String s = 5; java.util.List.of(1).stream().map(it -> 1) } }")
        assertTrue(msgs.any { "Type mismatch" in it }, "real type error in a well-formed statement must survive: $msgs")
        assertFalse(msgs.any { "is not applicable for the arguments" in it }, "spurious error from the broken statement must be gone: $msgs")
    }
}
