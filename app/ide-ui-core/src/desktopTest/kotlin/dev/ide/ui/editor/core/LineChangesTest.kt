package dev.ide.ui.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LineChangesTest {
    private val base = "a\nb\nc\nd\ne\n"

    private fun reverted(current: String): String {
        var doc = EditorDocument.of(current)
        var text = current
        // Undo the stretches bottom-up, so earlier offsets stay valid.
        for (c in lineChanges(base, current).reversed()) {
            val (s, e, r) = revertEdit(doc, base, c)
            text = text.substring(0, s) + r + text.substring(e)
            doc = EditorDocument.of(text)
        }
        return text
    }

    @Test
    fun classifiesAddedModifiedAndDeletedStretches() {
        val current = "a\nB\nc\nnew1\nnew2\nd\n" // b modified, two lines added after c, e deleted
        val changes = lineChanges(base, current)
        assertEquals(
            listOf(
                LineChange(1, 2, 1, 2) to LineChange.Kind.MODIFIED,
                LineChange(3, 5, 3, 3) to LineChange.Kind.ADDED,
                LineChange(6, 6, 4, 5) to LineChange.Kind.DELETED,
            ),
            changes.map { it to it.kind },
        )
    }

    @Test
    fun revertingEveryStretchGivesTheBaseBack() {
        assertEquals(base, reverted("a\nB\nc\nnew1\nnew2\nd\n"))
        assertEquals(base, reverted("x\na\nb\nc\nd\ne\n"), "an insertion at the top")
        assertEquals(base, reverted("a\nb\nc\nd\ne\nmore\nlines\n"), "an insertion at the end")
        assertEquals(base, reverted(""), "everything deleted")
        assertEquals("a\nb\nc", run { // no trailing newline, last line changed
            val b = "a\nb\nc"; val cur = "a\nb\nC"
            val c = lineChanges(b, cur).single()
            val (s, e, r) = revertEdit(EditorDocument.of(cur), b, c)
            cur.substring(0, s) + r + cur.substring(e)
        })
    }

    @Test
    fun bufferLinesMapToTheirCommittedLines() {
        val changes = lineChanges(base, "a\nB\nc\nnew1\nnew2\nd\n")
        assertEquals(listOf(0, null, 2, null, null, 3, 5), (0..6).map { baseLineFor(changes, it) })
    }

    @Test
    fun identicalTextHasNoChangesAndAWholesaleRewriteIsOneStretch() {
        assertEquals(emptyList(), lineChanges(base, base))
        val big = (1..300).joinToString("\n") { "line $it" }
        val other = (1..300).joinToString("\n") { "other $it" }
        assertEquals(listOf(LineChange(0, 300, 0, 300)), lineChanges(big, other, maxEdits = 50))
    }
}
