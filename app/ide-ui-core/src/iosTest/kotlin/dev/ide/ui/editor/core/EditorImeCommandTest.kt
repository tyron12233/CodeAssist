package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.MoveCursorCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import dev.ide.ui.editor.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The iOS IME bridge's command mapping, against a real [EditorSession].
 *
 * These run on the simulator (`./gradlew :ide-ui-core:iosSimulatorArm64Test`) because the code under test is
 * iOS-only. They cover the half of the bridge that is arithmetic rather than plumbing: UIKit counts in code
 * points and [EditorSession] counts in UTF-16 units, so every emoji in a buffer is a chance to delete half a
 * character.
 */
class EditorImeCommandTest {

    private fun session(text: String, caret: Int) =
        EditorSession(text, CodeLanguage.Kotlin, TextRange(caret))

    @Test
    fun commitTextInsertsAndPlacesCaret() {
        val s = session("ab", 1)
        s.applyImeCommand(CommitTextCommand("XY", 1))
        assertEquals("aXYb", s.doc.text)
        assertEquals(TextRange(3), s.selection)
    }

    @Test
    fun composingTextIsMarkedThenFinished() {
        val s = session("ab", 1)
        s.applyImeCommand(SetComposingTextCommand("xy", 1))
        assertEquals("axyb", s.doc.text)
        assertEquals(TextRange(1, 3), s.composing)

        s.applyImeCommand(FinishComposingTextCommand())
        assertNull(s.composing)
        assertEquals("axyb", s.doc.text)
    }

    @Test
    fun setSelectionMovesTheCaret() {
        val s = session("abcdef", 0)
        s.applyImeCommand(SetSelectionCommand(2, 4))
        assertEquals(TextRange(2, 4), s.selection)
    }

    /** One code point, not one UTF-16 unit: the emoji is a surrogate pair and must go whole. */
    @Test
    fun deleteSurroundingInCodePointsRemovesAWholeSurrogatePair() {
        val s = session("a😀b", 3)
        s.applyImeCommand(DeleteSurroundingTextInCodePointsCommand(1, 0))
        assertEquals("ab", s.doc.text)
        assertEquals(TextRange(1), s.selection)
    }

    @Test
    fun deleteSurroundingInCodePointsRemovesForwardWholePair() {
        val s = session("a😀b", 1)
        s.applyImeCommand(DeleteSurroundingTextInCodePointsCommand(0, 1))
        assertEquals("ab", s.doc.text)
    }

    @Test
    fun backspaceWithASelectionDeletesTheSelection() {
        val s = session("abcdef", 0)
        s.applyImeCommand(SetSelectionCommand(1, 4))
        s.applyImeCommand(BackspaceCommand())
        assertEquals("aef", s.doc.text)
        assertEquals(TextRange(1), s.selection)
    }

    @Test
    fun backspaceCollapsedDeletesOneCodePoint() {
        val s = session("a😀", 3)
        s.applyImeCommand(BackspaceCommand())
        assertEquals("a", s.doc.text)
    }

    @Test
    fun moveCursorStepsOverASurrogatePairAsOneCodePoint() {
        val s = session("a😀b", 1)
        s.applyImeCommand(MoveCursorCommand(1))
        assertEquals(TextRange(3), s.selection)

        s.applyImeCommand(MoveCursorCommand(-1))
        assertEquals(TextRange(1), s.selection)
    }

    @Test
    fun offsetByCodePointsClampsAtBothEnds() {
        val s = session("ab", 1)
        assertEquals(0, s.offsetByCodePoints(1, -50))
        assertEquals(2, s.offsetByCodePoints(1, 50))
    }
}
