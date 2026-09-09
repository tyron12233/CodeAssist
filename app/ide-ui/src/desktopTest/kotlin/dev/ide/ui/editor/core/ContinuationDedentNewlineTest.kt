package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Enter at the end of a *completed* continuation returns to the statement's own indent rather than staying at
 * the deeper continuation indent. The motivating case is an expression-body function broken across two lines
 * (`fun getAll(): List<Note> =` / `    dao.getAll()`): after the expression finishes, the next line lines up
 * with `fun`, not with `dao.getAll()`. Continuation lines that are still going (`a +`) keep their indent.
 */
class ContinuationDedentNewlineTest {

    /** The leading spaces of the line the Enter handler opens at the `|` caret marker (which is stripped). */
    private fun newIndent(code: String, language: CodeLanguage = CodeLanguage.Kotlin): String {
        val pos = code.indexOf('|')
        require(pos >= 0) { "no caret marker '|'" }
        val clean = code.removeRange(pos, pos + 1)
        val inserted = newlineHandlerFor(language).onEnter(clean, pos).text
        require(inserted.startsWith("\n")) { "smart Enter must insert a newline first: '$inserted'" }
        return inserted.substring(1).takeWhile { it == ' ' || it == '\t' }
    }

    @Test
    fun expressionBodyTailReturnsToDeclarationIndent() {
        // `suspend fun getAll(): List<Note> =` at 4, body `dao.getAll()` at 8 → next line back at 4.
        assertEquals("    ", newIndent("    suspend fun getAll(): List<Note> =\n        dao.getAll()|"))
    }

    @Test
    fun expressionBodyTailAtTopLevel() {
        assertEquals("", newIndent("fun getAll(): List<Note> =\n    dao.getAll()|"))
    }

    @Test
    fun assignmentContinuationTailReturnsToStatementIndent() {
        // `val x =` / `a + b` → after the expression finishes, back to `val`'s indent.
        assertEquals("    ", newIndent("    val x =\n        a + b|"))
    }

    @Test
    fun multiLineOperatorChainTailReturnsToStatementIndent() {
        // `val x = a +` / `b +` / `c` → the final line drops all the way back to `val`.
        assertEquals("    ", newIndent("    val x = a +\n        b +\n        c|"))
    }

    @Test
    fun midOperatorChainStaysAtContinuationIndent() {
        // Still dangling (`b +`) → the next continuation line keeps the continuation indent, no dedent.
        assertEquals("        ", newIndent("    val x = a +\n        b +|"))
    }

    @Test
    fun danglingEqualsIndentsDeeper() {
        // The line that STARTS the continuation still steps one level deeper (unchanged behavior).
        assertEquals("        ", newIndent("    suspend fun getAll(): List<Note> =|"))
    }

    @Test
    fun plainStatementUnaffected() {
        // Not a continuation tail → normal same-indent Enter.
        assertEquals("    ", newIndent("    val x = 1\n    println(x)|"))
    }

    @Test
    fun completedTailInsideBracketsStaysAlignedWithSibling() {
        // `foo(x = a +` / `b)` finishes the call → next line at the call's indent, not deeper.
        assertEquals("    ", newIndent("    foo(x = a +\n        b)|"))
    }

    @Test
    fun listItemAfterCommaDoesNotDedentToOpener() {
        // A trailing `,` is a sibling separator, not a dangle: the last item stays at the item indent.
        assertEquals("        ", newIndent("    listOf(\n        1,\n        2|"))
    }

    @Test
    fun javaExpressionTailReturnsToStatementIndent() {
        assertEquals("    ", newIndent("    int x =\n        a + b;|", CodeLanguage.Java))
    }
}
