package dev.ide.ui

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A file changed outside the editor (an agent's edit) lands in the open tab as one edit, not a new tab. */
class ExternalWriteTabTest {

    @Test
    fun anExternalWriteIsOneUndoableEditThatKeepsTheCaretAndTriggersAnalysis() {
        val before = "fun a() = 1\nfun b() = 2\nfun c() = 3\n"
        val tab = OpenFile("/p/A.kt", "A.kt", before)
        val session = tab.session
        // Caret on the last line, after the span the write changes.
        val caret = before.indexOf("fun c")
        session.replaceRange(caret, caret, "", TextRange(caret))
        val revision = session.textRevision

        val after = "fun a() = 1\nfun bee(x: Int) = x\nfun c() = 3\n"
        tab.applyExternalText(after)

        assertEquals(after, tab.text)
        assertNotEquals(revision, session.textRevision, "the daemon re-runs on a revision change")
        assertFalse(tab.modified, "the file now holds this text")
        assertEquals(TextRange(after.indexOf("fun c")), session.selection, "the caret moves with its text")

        assertTrue(session.undo())
        assertEquals(before, tab.text, "one undo step reverts the whole external change")
    }

    @Test
    fun anUnchangedWriteLeavesTheBufferAlone() {
        val tab = OpenFile("/p/A.kt", "A.kt", "val x = 1\n")
        val revision = tab.session.textRevision
        tab.applyExternalText("val x = 1\n")
        assertEquals(revision, tab.session.textRevision)
        assertFalse(tab.modified)
    }
}
