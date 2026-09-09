package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Smart-Enter block-comment continuation must be driven by real lexer state, not a textual last-index
 * comparison. Regression: a block-comment opener inside a STRING literal (or a line comment) made the naive
 * check believe the caret was perpetually inside a block comment, so every Enter prepended a stray star. A
 * genuine block comment must still continue.
 */
class BlockCommentNewlineTest {

    /** The text the Enter handler splices at the `|` caret marker (which is stripped). */
    private fun enter(code: String, language: CodeLanguage = CodeLanguage.Kotlin): String {
        val pos = code.indexOf('|')
        require(pos >= 0) { "no caret marker '|'" }
        val clean = code.removeRange(pos, pos + 1)
        return newlineHandlerFor(language).onEnter(clean, pos).text
    }

    @Test
    fun slashStarInStringDoesNotContinueAComment() {
        assertFalse('*' in enter("fun f() {\n    val s = \"/*\"|\n}"), "a /* inside a string is not a block comment")
    }

    @Test
    fun slashStarInLineCommentDoesNotContinueAComment() {
        assertFalse('*' in enter("fun f() {\n    // opens /* here\n    val x = 1|\n}"), "a /* in a // comment is not a block comment")
    }

    @Test
    fun realBlockCommentStillContinues() {
        assertTrue("* " in enter("    /* a real comment|"), "an open block comment must continue with a leading *")
    }

    @Test
    fun insideMultiLineBlockCommentContinues() {
        assertTrue("*" in enter("    /*\n     * line one|"), "a subsequent * line must continue")
    }

    @Test
    fun closedCommentThenStringDoesNotContinue() {
        // A properly closed comment, then a string with a `/*` — the last textual `/*` is in the string, so the
        // old check (last `/*` after last `*/`) wrongly fired; the state scan correctly sees code here.
        assertFalse('*' in enter("/* done */\nval s = \"/*\"|"), "closed comment + /* in a string is not open")
    }

    @Test
    fun enterAtEndOfLineCommentDoesNotContinue() {
        // IntelliJ: Enter at the END of a `//` line comment drops a plain new line, NOT another `//`.
        assertFalse("//" in enter("    // im in a comment|"), "end-of-line comment must not continue")
    }

    @Test
    fun enterAtEndOfBlankLineCommentDoesNotContinue() {
        assertFalse("//" in enter("    //|"), "empty line comment must not continue")
    }

    @Test
    fun splittingLineCommentMidTextStaysCommented() {
        // Splitting in the middle keeps the moved text a comment (IntelliJ behavior).
        assertTrue("//" in enter("    // hello |world"), "mid-comment split must keep the tail commented")
    }
}
