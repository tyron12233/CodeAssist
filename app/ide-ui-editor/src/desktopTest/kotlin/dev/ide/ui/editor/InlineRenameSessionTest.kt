package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.core.EditorSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-place rename session over a real [EditorSession]: the edited name is mirrored into every other
 * occurrence, the caret stays in the edited name, the old name can be put back after any number of edits, and
 * typing outside every occurrence reads as the user moving on.
 */
class InlineRenameSessionTest {

    private val code = "fun greet() {}\nfun main() {\n    greet()\n    val g = ::greet\n}\n"

    private fun occurrences(text: String, name: String): List<IntRange> =
        Regex("""\b${Regex.escape(name)}\b""").findAll(text).map { it.range }.toList()

    /** A session with the caret on the declaration's name, wired the way the editor wires it. */
    private fun start(): Pair<EditorSession, InlineRenameSession> {
        val caret = code.indexOf("greet") + 2
        val editor = EditorSession(code, CodeLanguage.Kotlin, TextRange(caret))
        val session = assertNotNull(
            InlineRenameSession.startOrNull(editor, occurrences(code, "greet"), caret, code, "greet", "function"),
        )
        editor.onSnippetEdit = { span -> session.onEdit(span) }
        session.selectPrimary()
        return editor to session
    }

    @Test
    fun typingTheNameRenamesEveryOccurrence() {
        val (editor, session) = start()
        editor.commitText("welcome") // replaces the selected name, as typing over a selection does
        session.mirror()
        assertEquals("welcome", session.currentName())
        assertEquals(code.replace("greet", "welcome"), editor.doc.text)
        assertFalse(session.strayEdit)
        assertEquals(code, session.originalText, "the text the project-wide rename resolves from is unchanged")
    }

    @Test
    fun theCaretStaysInTheEditedName() {
        val (editor, session) = start()
        editor.commitText("hi")
        session.mirror()
        val declStart = editor.doc.text.indexOf("hi")
        assertEquals(TextRange(declStart + 2), editor.selection, "the caret follows the typed text, not the copies")
    }

    @Test
    fun restorePutsTheOldNameBackAfterSeveralEdits() {
        val (editor, session) = start()
        editor.commitText("abc")
        session.mirror()
        editor.commitText("d")
        session.mirror()
        assertEquals("abcd", session.currentName())
        session.restore()
        assertEquals(code, editor.doc.text)
    }

    @Test
    fun anEditOutsideEveryOccurrenceIsAStrayEdit() {
        val (editor, session) = start()
        val end = editor.doc.length
        editor.replaceRange(end, end, "// later\n", TextRange(end + 9))
        assertTrue(session.strayEdit)
    }

    @Test
    fun typingAgainstAnotherOccurrenceIsAStrayEditNotPartOfIt() {
        val (editor, session) = start()
        val second = editor.doc.text.indexOf("greet()", editor.doc.text.indexOf("main"))
        editor.replaceRange(second, second, "x", TextRange(second + 1))
        assertTrue(session.strayEdit, "only the edited name is the user's to type in")
    }

    @Test
    fun noSessionWithoutAnOccurrenceAtTheCaretOrWithAMismatchedSpelling() {
        val editor = EditorSession(code, CodeLanguage.Kotlin, TextRange(0))
        assertNull(InlineRenameSession.startOrNull(editor, occurrences(code, "greet"), 0, code, "greet", "function"))
        val caret = code.indexOf("greet")
        val wrong = listOf(caret until caret + 5, code.indexOf("main") until code.indexOf("main") + 4)
        assertNull(InlineRenameSession.startOrNull(editor, wrong, caret, code, "greet", "function"))
    }

    @Test
    fun identifiersAreCheckedBeforeTheProjectWideRename() {
        assertTrue(isRenameableIdentifier("welcome_2"))
        assertTrue(isRenameableIdentifier("\$x"))
        assertFalse(isRenameableIdentifier("2fast"))
        assertFalse(isRenameableIdentifier("a b"))
        assertFalse(isRenameableIdentifier("a("))
    }
}
