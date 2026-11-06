package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** No completion inside a comment: the word is prose, and Enter at the end of the line must not accept a class. */
class KotlinCommentCompletionTest {

    private fun labels(code: String) = runBlocking { analyzer.completeAtCaret(srcDir, "C.kt", code) }.items.map { it.label }

    @Test
    fun nothingInALineComment() {
        assertEquals(emptyList(), labels("package demo\n// Strin|\nfun f() {}"))
        assertEquals(emptyList(), labels("package demo\nfun f() {\n    val x = 1 // Strin|\n}"))
    }

    @Test
    fun nothingInABlockComment() {
        assertEquals(emptyList(), labels("package demo\n/* Strin| */\nfun f() {}"))
    }

    @Test
    fun codeAfterACommentStillCompletes() {
        assertTrue("String" in labels("package demo\n// a comment\nval s: Strin|"), "the line after a comment is code")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Placeholder.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
