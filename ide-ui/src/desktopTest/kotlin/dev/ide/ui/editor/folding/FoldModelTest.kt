package dev.ide.ui.editor.folding

import dev.ide.ui.editor.core.EditorDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure folding projection: hidden-line intervals, the doc-line ⇄ visual-row mapping, and composite text. */
class FoldModelTest {

    // Lines:        0          1            2            3       4
    private val code = "fun f() {\n  val x = 1\n  print(x)\n}\nval after = 2\n"
    private val doc = EditorDocument.of(code)

    /** A collapsed fold of f's body: between `{` (end of line 0) and `}` (start of line 3). */
    private fun bodyFold(collapsed: Boolean = true): FoldRegion {
        val start = code.indexOf('{') + 1
        val end = code.indexOf('}')
        return FoldRegion(start, end, "...", "functionBody", collapsed)
    }

    @Test
    fun noFoldsIsIdentity() {
        val m = FoldModel.build(doc, listOf(bodyFold(collapsed = false)))
        assertFalse(m.hasFolds)
        assertEquals(doc.lineCount, m.visualLineCount)
        assertEquals(2, m.docLineForVisual(2))
        assertEquals(3, m.visualForDocLine(3))
    }

    @Test
    fun collapsedFoldHidesInteriorLines() {
        val m = FoldModel.build(doc, listOf(bodyFold()))
        assertTrue(m.hasFolds)
        // Lines 1,2,3 hide (body + the `}` line); 0 (start), 4 (after), 5 (trailing) stay → 3 visual rows.
        assertEquals(3, m.visualLineCount)
        assertFalse(m.isHidden(0))
        assertTrue(m.isHidden(1)); assertTrue(m.isHidden(2)); assertTrue(m.isHidden(3))
        assertFalse(m.isHidden(4))
    }

    @Test
    fun visualRowMapsAroundTheHiddenBlock() {
        val m = FoldModel.build(doc, listOf(bodyFold()))
        assertEquals(0, m.docLineForVisual(0)) // first row = fold start
        assertEquals(4, m.docLineForVisual(1)) // second row jumps past the hidden block to `val after`
        assertEquals(0, m.visualForDocLine(0))
        assertEquals(1, m.visualForDocLine(4))
        assertEquals(0, m.visualForDocLine(2)) // a hidden line collapses onto its fold-start row
    }

    @Test
    fun compositeTextJoinsPrefixPlaceholderSuffix() {
        val m = FoldModel.build(doc, listOf(bodyFold()))
        assertEquals("fun f() {...}", m.compositeText(0, doc))
        val info = m.foldStartingAt(0)
        assertTrue(info != null && info.endLine == 3)
    }

    @Test
    fun twoCollapsedFoldsMapVisualRowsCorrectly() {
        // package, 3 imports, blank, a 3-line fun, then two trailing lines.
        val src = "package p\n" +              // 0
            "import a.B\n" +                    // 1  import fold start
            "import c.D\n" +                    // 2  hidden
            "import e.F\n" +                    // 3  hidden (imports endLine)
            "\n" +                              // 4
            "fun foo() {\n" +                   // 5  fun fold start
            "  work()\n" +                      // 6  hidden
            "}\n" +                             // 7  hidden (fun endLine)
            "val x = 1\n" +                     // 8
            "val y = 2\n"                       // 9 (+ trailing empty line 10)
        val d = EditorDocument.of(src)
        val impStart = src.indexOf("import a.B")
        val impEnd = src.indexOf("import e.F") + "import e.F".length
        val funStart = src.indexOf('{') + 1
        val funEnd = src.indexOf('}')
        val m = FoldModel.build(d, listOf(
            FoldRegion(impStart, impEnd, "import ...", "imports", collapsed = true),
            FoldRegion(funStart, funEnd, "...", "functionBody", collapsed = true),
        ))
        // Visible doc lines: 0,1,4,5,8,9,10 → 7 rows (hidden: 2,3 and 6,7 = 4).
        assertEquals(d.lineCount - 4, m.visualLineCount)
        val visible = listOf(0, 1, 4, 5, 8, 9, 10)
        for (row in visible.indices) assertEquals(visible[row], m.docLineForVisual(row), "row $row")
        // The fold below the collapsed imports must map round-trip (this is the reported bug).
        assertEquals(3, m.visualForDocLine(5)) // fun start is the 4th visible row (index 3)
        assertEquals("fun foo() {...}", m.compositeText(5, d))
        assertEquals("import ...", m.compositeText(1, d))
    }

    @Test
    fun importsCollapseToOneRow() {
        val src = "package p\nimport a.B\nimport c.D\nimport e.F\nclass X\n"
        val d = EditorDocument.of(src)
        val start = src.indexOf("import a.B")
        val end = src.indexOf("import e.F") + "import e.F".length
        val m = FoldModel.build(d, listOf(FoldRegion(start, end, "import ...", "imports", collapsed = true)))
        // start line (1) stays as `import ...`; lines 2,3 hide.
        assertEquals(d.lineCount - 2, m.visualLineCount)
        assertEquals("import ...", m.compositeText(1, d))
        assertEquals(4, m.docLineForVisual(2)) // row 0=pkg,1=composite imports,2=class X
    }
}
