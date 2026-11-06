package dev.ide.ui.editor.core

import dev.ide.ui.editor.languageFor

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The trackpad's floating cursor: free of the text, with the caret snapped to the position nearest it. */
class FloatingCaretTest {
    private val text = "short\nmuch longer line here\nab\nlast line"
    private fun session() = EditorSession(text, languageFor("a.txt"), TextRange(2))
    private fun lineCol(s: EditorSession): Pair<Int, Int> {
        val line = s.doc.lineForOffset(s.selection.start)
        return line to s.selection.start - s.doc.lineStart(line)
    }

    @Test
    fun pastALinesEndTheCaretWaitsAtTheEndInsteadOfWrappingOntoTheNextLine() {
        val s = session()
        s.beginFloatingCaret()
        s.moveFloatingCaret(10f, 0f)
        assertEquals(0 to 5, lineCol(s), "held at the end of `short`, not carried onto line 1")
        assertEquals(12f, s.floatingCaret?.column)
    }

    @Test
    fun goingDownAndUpKeepsTheFloatingColumnNotTheOneAShortLineClampedTo() {
        val s = session()
        s.beginFloatingCaret()
        s.moveFloatingCaret(10f, 1f)
        assertEquals(1 to 12, lineCol(s))
        s.moveFloatingCaret(0f, 1f)
        assertEquals(2 to 2, lineCol(s), "the end of `ab`")
        s.moveFloatingCaret(0f, -1f)
        assertEquals(1 to 12, lineCol(s), "back to column 12, not 2")
    }

    @Test
    fun theCaretSnapsToTheNearestLineAndColumnAndLettingGoEndsIt() {
        val s = session()
        s.beginFloatingCaret()
        s.moveFloatingCaret(0.4f, 0.4f)
        assertEquals(0 to 2, lineCol(s), "less than half a line and a column away: still here")
        s.moveFloatingCaret(0.2f, 0.2f)
        assertEquals(1 to 3, lineCol(s))
        s.endFloatingCaret()
        assertNull(s.floatingCaret)
        assertEquals(1 to 3, lineCol(s), "the caret stays where it snapped")
    }

    @Test
    fun itCannotFloatOffTheDocumentOrFarPastTheLongestLineAroundIt() {
        val s = session()
        s.beginFloatingCaret()
        s.moveFloatingCaret(-50f, -5f)
        assertEquals(0f, s.floatingCaret?.line)
        assertEquals(0f, s.floatingCaret?.column)
        s.moveFloatingCaret(500f, 50f)
        assertEquals(3f, s.floatingCaret?.line)
        assertEquals("much longer line here".length + 1f, s.floatingCaret?.column)
        assertEquals(3 to 9, lineCol(s))
    }
}
