package dev.ide.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.applySmartEdit
import dev.ide.ui.editor.matchingBracket
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies the editor's smart-edit logic (auto-close, skip-over, smart Enter, bracket match). */
class SmartEditTest {

    private fun tv(text: String, caret: Int) = TextFieldValue(text, TextRange(caret))

    /** Simulate the raw value BasicTextField produces when [c] is typed at [old]'s caret. */
    private fun type(old: TextFieldValue, c: Char): TextFieldValue {
        val p = old.selection.start
        return TextFieldValue(old.text.substring(0, p) + c + old.text.substring(p), TextRange(p + 1))
    }

    private fun edit(old: TextFieldValue, c: Char) = applySmartEdit(old, type(old, c), CodeLanguage.Java)

    /** Simulate Backspace: delete the char before [old]'s caret. */
    private fun backspace(old: TextFieldValue): TextFieldValue {
        val p = old.selection.start
        return TextFieldValue(old.text.removeRange(p - 1, p), TextRange(p - 1))
    }

    private fun delete(old: TextFieldValue) = applySmartEdit(old, backspace(old), CodeLanguage.Java)

    @Test
    fun autoClosesBracket() {
        val r = edit(tv("", 0), '(')
        assertEquals("()", r.text)
        assertEquals(1, r.selection.start) // caret between the pair
    }

    @Test
    fun skipsOverTypedClosingBracket() {
        // Caret sits inside "()" and the user types ')' — should move past, not insert another.
        val r = edit(tv("()", 1), ')')
        assertEquals("()", r.text)
        assertEquals(2, r.selection.start)
    }

    @Test
    fun skipsOverTypedClosingQuote() {
        val r = edit(tv("\"\"", 1), '"')
        assertEquals("\"\"", r.text)
        assertEquals(2, r.selection.start)
    }

    @Test
    fun enterContinuesIndentEvenBeforeAnExistingLine() {
        // The bug case: Enter at end of an indented line that is followed by another line.
        val old = tv("    foo\nbar", 7) // caret right after "    foo", before the existing '\n'
        val r = edit(old, '\n')
        assertEquals("    foo\n    \nbar", r.text)
        assertEquals(12, r.selection.start) // on the new line, after the 4-space indent — not column 0
    }

    @Test
    fun enterExpandsEmptyBraceBlock() {
        val old = tv("class A {}", 9) // caret between { and }
        val r = edit(old, '\n')
        assertEquals("class A {\n    \n}", r.text)
        assertEquals(14, r.selection.start) // indented middle line
    }

    @Test
    fun enterOnPlainLineKeepsZeroIndent() {
        val old = tv("foo", 3)
        val r = edit(old, '\n')
        assertEquals("foo\n", r.text)
        assertEquals(4, r.selection.start)
    }

    @Test
    fun backspaceDeletesEmptyBracketPair() {
        // "(|)" + Backspace -> both gone.
        val r = delete(tv("()", 1))
        assertEquals("", r.text)
        assertEquals(0, r.selection.start)
    }

    @Test
    fun backspaceDeletesEmptyQuotePair() {
        val r = delete(tv("\"\"", 1))
        assertEquals("", r.text)
        assertEquals(0, r.selection.start)
    }

    @Test
    fun backspaceKeepsCloserWhenPairNotEmpty() {
        // "(a|)" deleting 'a' must NOT eat the ')'.
        val r = delete(tv("(a)", 2))
        assertEquals("()", r.text)
        assertEquals(1, r.selection.start)
    }

    @Test
    fun backspaceOnLoneOpenerKeepsFollowingText() {
        // "(|x" deleting '(' leaves "x" (no partner to remove).
        val r = delete(tv("(x", 1))
        assertEquals("x", r.text)
        assertEquals(0, r.selection.start)
    }

    @Test
    fun matchesBracketAtCaret() {
        assertEquals(0 to 4, matchingBracket("(a+b)", 1)) // caret just after '('
        assertEquals(0 to 4, matchingBracket("(a+b)", 5)) // caret just after ')'
    }
}
