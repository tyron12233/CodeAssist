package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The indent-guide layer's column source: [leadingIndentCols] (pure) and the per-line indent cache on
 * [EditorRenderState] that replaced the per-frame rope walk. The cache is validated by the line's style
 * revision, so an edit that changes a line's indent — or shifts lines up/down — must be reflected.
 */
class IndentGuideTest {

    @Test
    fun leadingIndentColsCountsSpacesTabsAndBlank() {
        val doc = EditorDocument.of(
            "code\n" +        // 0: no indent
                "  x\n" +     // 1: two spaces
                "\t\ty\n" +   // 2: two tabs → 8 (flat, 4 each)
                " \tz\n" +    // 3: space + tab → 1 + 4 = 5
                "   \n" +     // 4: only whitespace → blank (-1)
                ""           // 5: empty last line → blank (-1)
        )
        assertEquals(0, leadingIndentCols(doc, 0, INDENT_GUIDE_TAB_WIDTH))
        assertEquals(2, leadingIndentCols(doc, 1, INDENT_GUIDE_TAB_WIDTH))
        assertEquals(8, leadingIndentCols(doc, 2, INDENT_GUIDE_TAB_WIDTH))
        assertEquals(5, leadingIndentCols(doc, 3, INDENT_GUIDE_TAB_WIDTH))
        assertEquals(-1, leadingIndentCols(doc, 4, INDENT_GUIDE_TAB_WIDTH))
        assertEquals(-1, leadingIndentCols(doc, 5, INDENT_GUIDE_TAB_WIDTH))
    }

    @Test
    fun indentCacheReflectsEditsAndLineShifts() {
        val session = EditorSession("    a\n  b\nc\n", CodeLanguage.Kotlin)
        val rs = EditorRenderState(session)
        assertEquals(4, rs.indentColsFor(0))
        assertEquals(2, rs.indentColsFor(1))
        assertEquals(0, rs.indentColsFor(2))

        // Change line 0's indent in place (remove two leading spaces): its style revision bumps → recompute.
        session.replaceRange(0, 2, "", TextRange(0))
        assertEquals(2, rs.indentColsFor(0))
        assertEquals(2, rs.indentColsFor(1))

        // Insert a new, more-indented line at the top: old lines shift down by one. The cached entries are keyed
        // by the (globally unique) style revision, so the moved lines self-correct without an explicit shift.
        session.replaceRange(0, 0, "      X\n", TextRange(0))
        assertEquals(6, rs.indentColsFor(0)) // the new line
        assertEquals(2, rs.indentColsFor(1)) // was line 0 ("  a")
        assertEquals(2, rs.indentColsFor(2)) // was line 1 ("  b")
        assertEquals(0, rs.indentColsFor(3)) // was line 2 ("c")
    }

    @Test
    fun indentColsForOutOfRangeIsBlank() {
        val session = EditorSession("a\nb\n", CodeLanguage.Kotlin)
        val rs = EditorRenderState(session)
        assertEquals(-1, rs.indentColsFor(-1))
        assertEquals(-1, rs.indentColsFor(99))
    }
}
