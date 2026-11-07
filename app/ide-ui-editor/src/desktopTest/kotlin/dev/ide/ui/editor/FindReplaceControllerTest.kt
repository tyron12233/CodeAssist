package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.core.EditorSession
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [FindReplaceController] over a real [EditorSession]: a search selects the match nearest the caret, but an edit
 * in the buffer while the bar is open leaves the caret alone (re-selecting a match there made the next keystroke
 * type over it, so typing a new value ate the searched-for name, one occurrence per key).
 */
class FindReplaceControllerTest {

    private val code = "val count = 5\nprintln(count)\n"

    private fun search(query: String): Pair<EditorSession, FindReplaceController> {
        val editor = EditorSession(code, CodeLanguage.Kotlin, TextRange(0))
        val find = FindReplaceController(editor)
        find.openBar(replace = false)
        find.query = query
        find.recompute()
        return editor to find
    }

    @Test
    fun searchingSelectsTheNearestMatch() {
        val (editor, find) = search("count")
        val first = code.indexOf("count")
        assertEquals(TextRange(first, first + 5), editor.selection)
        assertEquals(2, find.matches.size)
    }

    @Test
    fun typingAValueWhileFindIsOpenDoesNotTouchTheMatches() {
        val (editor, find) = search("count")
        val five = code.indexOf('5')
        editor.setSelectionRange(five + 1, five + 1) // the user taps after the value
        for (key in listOf("\b", "1", "0")) {
            if (key == "\b") editor.backspace() else editor.commitText(key)
            find.recompute() // what the composable's effect does on every textRevision bump
        }
        assertEquals("val count = 10\nprintln(count)\n", editor.doc.text)
        assertEquals(TextRange(five + 2), editor.selection, "the caret stays where the user typed")
        assertEquals(2, find.matches.size, "the highlights still follow the buffer")
    }

    @Test
    fun replaceMovesOnToTheNextOccurrence() {
        val (editor, find) = search("count")
        find.replaceWith = "total"
        find.replaceCurrent()
        find.recompute()
        val next = editor.doc.text.indexOf("count")
        assertEquals(TextRange(next, next + 5), editor.selection)
    }

    @Test
    fun changingTheQuerySelectsAgain() {
        val (editor, find) = search("count")
        editor.setSelectionRange(0, 0)
        find.query = "println"
        find.recompute()
        val p = code.indexOf("println")
        assertEquals(TextRange(p, p + 7), editor.selection)
    }
}
