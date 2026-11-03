package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.languageFor
import kotlin.test.Test
import kotlin.test.assertEquals

class BookmarksTest {
    private fun session() = EditorSession("a\nb\nc\nd\ne\n", languageFor("a.txt"), TextRange(0))

    @Test
    fun toggleAddsAndRemovesTheCaretLine() {
        val s = session()
        s.setCaret(4) // line 2
        s.toggleBookmark()
        assertEquals(setOf(2), s.bookmarks)
        s.toggleBookmark()
        assertEquals(emptySet(), s.bookmarks)
    }

    @Test
    fun bookmarksMoveWithLinesAddedOrRemovedAboveThem() {
        val s = session()
        s.bookmarks = setOf(1, 3)
        s.replaceRange(0, 0, "new\nnew\n", TextRange(0)) // two lines above both
        assertEquals(setOf(3, 5), s.bookmarks)
        s.replaceRange(0, 8, "", TextRange(0)) // and gone again
        assertEquals(setOf(1, 3), s.bookmarks)
        val lineEnd = s.doc.lineEnd(2)
        s.replaceRange(lineEnd, lineEnd + 1, "", TextRange(lineEnd)) // join line 3 onto line 2
        assertEquals(setOf(1, 2), s.bookmarks, "the joined line's bookmark lands on the line it joined")
    }
}
