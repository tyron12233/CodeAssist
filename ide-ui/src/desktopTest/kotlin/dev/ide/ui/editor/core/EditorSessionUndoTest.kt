package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Undo/redo semantics of [EditorSession]: coalescing, batch grouping, selection restore, redo invalidation. */
class EditorSessionUndoTest {

    private fun session(text: String, caret: Int = text.length) =
        EditorSession(text, CodeLanguage.Plain, TextRange(caret))

    @Test
    fun consecutiveTypingUndoesAndRedoesAsOneRun() {
        val s = session("", 0)
        s.commitText("a"); s.commitText("b"); s.commitText("c")
        assertEquals("abc", s.doc.text)
        assertTrue(s.canUndo)

        assertTrue(s.undo())
        assertEquals("", s.doc.text, "one undo removes the whole typed run")
        assertEquals(TextRange(0), s.selection)
        assertFalse(s.canUndo)

        assertTrue(s.redo())
        assertEquals("abc", s.doc.text)
        assertEquals(TextRange(3), s.selection)
    }

    @Test
    fun caretMoveStartsANewUndoStep() {
        val s = session("", 0)
        s.commitText("a"); s.commitText("b")
        s.setCaret(0)          // breaks the typing run
        s.commitText("x")
        assertEquals("xab", s.doc.text)

        assertTrue(s.undo())
        assertEquals("ab", s.doc.text, "first undo reverts only the post-move char")
        assertEquals(TextRange(0), s.selection)
        assertTrue(s.undo())
        assertEquals("", s.doc.text, "second undo reverts the earlier run")
    }

    @Test
    fun backspaceRunCoalesces() {
        val s = session("hello", 5)
        s.backspace(); s.backspace(); s.backspace() // delete o, l, l → "he"
        assertEquals("he", s.doc.text)

        assertTrue(s.undo())
        assertEquals("hello", s.doc.text, "one undo restores the whole deleted run")
        assertEquals(TextRange(5), s.selection)
    }

    @Test
    fun multiEditBatchIsASingleUndoStep() {
        val s = session("foo", 3)
        s.applyEdits(listOf(RangeEdit(0, 0, "x", 1), RangeEdit(3, 3, "y", 4)), TextRange(5))
        assertEquals("xfooy", s.doc.text)

        assertTrue(s.undo())
        assertEquals("foo", s.doc.text, "both edits of the batch revert together")
        assertTrue(s.redo())
        assertEquals("xfooy", s.doc.text)
    }

    @Test
    fun typingAfterUndoDiscardsRedo() {
        val s = session("", 0)
        s.commitText("a")
        assertTrue(s.undo())
        assertTrue(s.canRedo)
        s.commitText("b")
        assertFalse(s.canRedo, "a fresh edit invalidates the redo branch")
        assertFalse(s.redo())
        assertEquals("b", s.doc.text)
    }

    @Test
    fun setTextClearsHistory() {
        val s = session("hello", 5)
        s.commitText("!")
        assertTrue(s.canUndo)
        s.setText("brand new")
        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
        assertFalse(s.undo())
    }

    @Test
    fun nothingToUndoOrRedoOnAFreshSession() {
        val s = session("x", 1)
        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
        assertFalse(s.undo())
        assertFalse(s.redo())
    }

    @Test
    fun undoRedoRoundTripsAcrossManySteps() {
        val s = session("", 0)
        s.commitText("hello")          // run 1
        s.setCaret(0); s.commitText("X") // run 2 (separate)
        s.setCaret(s.doc.length); s.commitText("!") // run 3
        val full = s.doc.text
        // unwind everything, then redo everything
        var undos = 0
        while (s.undo()) undos++
        assertEquals("", s.doc.text)
        assertTrue(undos >= 3)
        var redos = 0
        while (s.redo()) redos++
        assertEquals(full, s.doc.text)
        assertEquals(undos, redos)
    }
}
