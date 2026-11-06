package dev.ide.ui.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeConflictsTest {

    private val store = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
        <<<<<<< Your version
            <string name="app_name">My XML Tool</string>
        =======
            <string name="app_name">XML Formatter</string>
        >>>>>>> New version (1.0.2)
        </resources>
    """.trimIndent() + "\n"

    @Test
    fun findsTheStoresConflictWithBothSidesAndLabels() {
        val c = findMergeConflicts(store).single()
        assertEquals(2, c.startLine)
        assertEquals(4, c.separatorLine)
        assertEquals(6, c.endLine)
        assertNull(c.baseLine)
        assertEquals("Your version", c.currentLabel)
        assertEquals("New version (1.0.2)", c.incomingLabel)
        assertEquals("    <string name=\"app_name\">My XML Tool</string>\n", store.substring(c.currentStart, c.currentEnd))
        assertEquals("    <string name=\"app_name\">XML Formatter</string>\n", store.substring(c.incomingStart, c.incomingEnd))
    }

    @Test
    fun eachResolutionReplacesTheWholeBlock() {
        val c = findMergeConflicts(store).single()
        fun resolved(choice: ConflictChoice) = store.substring(0, c.start) + c.resolution(store, choice) + store.substring(c.end)
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"app_name\">XML Formatter</string>\n</resources>\n",
            resolved(ConflictChoice.INCOMING),
        )
        assertTrue("My XML Tool" in resolved(ConflictChoice.CURRENT) && "XML Formatter" !in resolved(ConflictChoice.CURRENT))
        val both = resolved(ConflictChoice.BOTH)
        assertTrue(both.indexOf("My XML Tool") in 0 until both.indexOf("XML Formatter"), "current first, then incoming")
        assertTrue("<<<<<<<" !in both && "=======" !in both && ">>>>>>>" !in both)
    }

    @Test
    fun gitsDiff3BaseSectionIsNeitherSide() {
        val text = "<<<<<<< HEAD\nours\n||||||| base\nancestor\n=======\ntheirs\n>>>>>>> feature\n"
        val c = findMergeConflicts(text).single()
        assertEquals(2, c.baseLine)
        assertEquals("ours\n", c.resolution(text, ConflictChoice.CURRENT))
        assertEquals("theirs\n", c.resolution(text, ConflictChoice.INCOMING))
    }

    @Test
    fun severalBlocksAnEmptySideAndNoFinalNewline() {
        val text = "<<<<<<< a\none\n=======\n>>>>>>> b\nmiddle\n<<<<<<< a\n=======\ntwo\n>>>>>>> b"
        val (first, second) = findMergeConflicts(text)
        assertEquals("", first.resolution(text, ConflictChoice.INCOMING), "the incoming side deleted the line")
        assertEquals("two\n", second.resolution(text, ConflictChoice.INCOMING))
        assertEquals(text.length, second.end)
    }

    @Test
    fun anUnfinishedBlockOrLookalikeLinesAreNotConflicts() {
        assertEquals(emptyList(), findMergeConflicts("<<<<<<< a\nhalf deleted\n=======\nno end marker\n"))
        assertEquals(emptyList(), findMergeConflicts("// <<<<<<< not at the line start\n=======\n>>>>>>> x\n"))
        assertEquals(emptyList(), findMergeConflicts("<<<<<<<<< eight\n========\n>>>>>>>> eight\n"))
        assertEquals(emptyList(), findMergeConflicts("val x = 1\n"))
    }

    @Test
    fun windowsLineEndings() {
        val text = "<<<<<<< a\r\nmine\r\n=======\r\ntheirs\r\n>>>>>>> b\r\n"
        assertEquals("theirs\r\n", findMergeConflicts(text).single().resolution(text, ConflictChoice.INCOMING))
    }
}
