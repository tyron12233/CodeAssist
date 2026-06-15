package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.applySmartEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [EditorSession] edit semantics: smart-edit parity with the legacy path, IME flows, host sync. */
class EditorSessionTest {

    private fun session(text: String, caret: Int = text.length, language: CodeLanguage = CodeLanguage.Java): EditorSession =
        EditorSession(text, language, TextRange(caret))

    // ---- smart-edit parity: typing a char through the session == applySmartEdit over BasicTextField values ----

    private fun assertTypingParity(text: String, caret: Int, ch: Char) {
        val s = session(text, caret)
        s.commitText(ch.toString())

        val old = TextFieldValue(text, TextRange(caret))
        val rawText = text.substring(0, caret) + ch + text.substring(caret)
        val raw = TextFieldValue(rawText, TextRange(caret + 1))
        val expected = applySmartEdit(old, raw, CodeLanguage.Java)

        assertEquals(expected.text, s.doc.text, "text after typing '$ch' into \"$text\" at $caret")
        assertEquals(expected.selection, s.selection, "caret after typing '$ch'")
    }

    @Test
    fun smartParity() {
        assertTypingParity("foo", 3, '(')            // auto-close
        assertTypingParity("foo()", 4, ')')          // skip-over
        assertTypingParity("val s = ", 8, '"')       // quote auto-close
        assertTypingParity("ab", 1, 'x')             // plain insert
        assertTypingParity("if (x) {}", 8, '\n')     // pair expansion on Enter
        assertTypingParity("    indented", 12, '\n') // indent continuation
        assertTypingParity("a(b", 3, ')')            // no skip (nothing to skip over)
    }

    @Test
    fun backspaceRemovesEmptyPair() {
        val s = session("foo()", 4)
        s.backspace()
        assertEquals("foo", s.doc.text)
        assertEquals(TextRange(3), s.selection)
    }

    @Test
    fun selectionTypingReplaces() {
        val s = session("hello world", 0)
        s.setSelectionRange(0, 5)
        s.commitText("X")
        assertEquals("X world", s.doc.text)
        assertEquals(TextRange(1), s.selection)
    }

    // ---- IME flows ----

    @Test
    fun composingTextReplacesItsRegion() {
        val s = session("ab ", 3)
        s.imeSetComposingText("he", 1)
        assertEquals("ab he", s.doc.text)
        assertEquals(TextRange(3, 5), s.composing)
        assertEquals(TextRange(5), s.selection)
        s.imeSetComposingText("hello", 1) // IME re-sends the grown composition
        assertEquals("ab hello", s.doc.text)
        assertEquals(TextRange(3, 8), s.composing)
        s.imeCommitText("hello!", 1)
        assertEquals("ab hello!", s.doc.text)
        assertNull(s.composing)
        assertEquals(TextRange(9), s.selection)
    }

    @Test
    fun deleteSurroundingIsBackspace() {
        // Gboard backspace = deleteSurroundingText(1, 0) — must stay pair-aware
        val t = session("foo()", 4)
        t.imeDeleteSurrounding(1, 0)
        assertEquals("foo", t.doc.text)
        assertEquals(TextRange(3), t.selection)
    }

    @Test
    fun deleteSurroundingBothSides() {
        val s = session("abcdef", 3)
        s.imeDeleteSurrounding(2, 2)
        assertEquals("af", s.doc.text)
        assertEquals(TextRange(1), s.selection)
    }

    @Test
    fun batchedImeEditsPushImeOnce() {
        // The session has no host text mirror to coalesce anymore; what a batch coalesces is the IME push.
        val s = session("x", 1)
        var imePushes = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() { imePushes++ }
            override fun onRestartInput() {}
        }
        s.beginBatch()
        s.imeSetComposingText("ab", 1)
        s.imeSetComposingText("abc", 1)
        s.endBatch()
        assertEquals(1, imePushes)
        assertEquals("xabc", s.doc.text)
    }

    @Test
    fun textRevisionBumpsOnTextEditsOnly() {
        val s = session("abc", 0)
        val r0 = s.textRevision
        s.setCaret(2)                 // selection move — no text change
        assertEquals(r0, s.textRevision)
        s.commitText("x")             // text edit
        assertEquals(r0 + 1, s.textRevision)
    }

    @Test
    fun onTextEditReportsTheSpan() {
        val s = session("hello", 5)
        val spans = ArrayList<Triple<Int, Int, Int>>()
        s.onTextEdit = { spans.add(Triple(it.start, it.removed, it.added)) }
        s.commitText("!")             // insert 1 char at offset 5
        s.setCaret(0)                 // pure caret move — must NOT fire onTextEdit
        s.setSelectionRange(0, 6)
        s.commitText("")              // replace selection [0,6) with "" → delete 6
        assertEquals(listOf(Triple(5, 0, 1), Triple(0, 6, 0)), spans)
    }

    // ---- external replacement ----

    @Test
    fun setTextReplacesBufferAndBumpsRevision() {
        val s = session("abc", 3)
        val r0 = s.textRevision
        s.setText("totally new\ncontent", TextRange(5))
        assertEquals("totally new\ncontent", s.doc.text)
        assertEquals(2, s.doc.lineCount)
        assertEquals(TextRange(5), s.selection)
        assertEquals(r0 + 1, s.textRevision, "an out-of-band replace must re-trigger analysis")
    }

    // ---- diagnostics are spans the session shifts itself ----

    @Test
    fun editorShiftsDiagnosticsLikeSpans() {
        // A diagnostic on "baz" [8,11) in "foo bar baz". Typing before it must push it right — the session
        // shifts its own diagnostics in [replaceRange], exactly as it shifts the line index and token spans.
        val s = session("foo bar baz", 0)
        s.applyAnalysis(listOf(UiDiagnostic(UiSeverity.Error, 1, 9, "bad", startOffset = 8, endOffset = 11)))
        s.commitText("XY") // insert 2 chars at offset 0 → "XYfoo bar baz"
        val d = s.diagnostics.single()
        assertEquals(10, d.startOffset, "diagnostic start shifts by the insert length")
        assertEquals(13, d.endOffset, "diagnostic end shifts by the insert length")

        // an edit strictly inside the range stretches it (start holds, end moves); typing at offset 11 is
        // between the 'b' and 'a' of "baz" (range is now [10,13))
        s.setSelectionRange(11, 11)
        s.commitText("zzz") // inside the range → it grows by 3
        assertEquals(10, s.diagnostics.single().startOffset)
        assertEquals(16, s.diagnostics.single().endOffset)

        // a wholesale replace drops the now-meaningless anchors (re-analysis refills)
        s.setText("totally different")
        assertTrue(s.diagnostics.isEmpty())
    }

    // ---- caret navigation ----

    @Test
    fun wordNavAndVerticalGoalColumn() {
        val s = session("alpha beta\nx\nlonger line", 0)
        s.moveHorizontal(1, select = false, word = true)
        assertEquals(TextRange(5), s.selection) // end of "alpha"
        s.moveLineEnd(select = false)
        assertEquals(TextRange(10), s.selection)
        s.moveVertical(1, select = false) // down to "x" — clamped to its length
        assertEquals(TextRange(12), s.selection)
        s.moveVertical(1, select = false) // down to "longer line" — goal column 10 restored
        val line2Start = s.doc.lineStart(2)
        assertEquals(TextRange(line2Start + 10), s.selection)
    }

    @Test
    fun selectWordAndSelectedText() {
        val s = session("foo barBaz qux", 0)
        s.selectWordAt(6)
        assertEquals("barBaz", s.selectedText())
    }

    @Test
    fun applyEditsWithAutoImport() {
        // completion accept: replace token at 20.. + insert an import line above
        val s = session("package p;\n\nclass A { Lis }", 25)
        val edits = listOf(
            RangeEdit(22, 25, "List", 0),
            RangeEdit(11, 11, "\nimport java.util.List;\n", 0),
        )
        s.applyEdits(edits, TextRange(26 + 24))
        assertEquals("package p;\n\nimport java.util.List;\n\nclass A { List }", s.doc.text)
    }
}
