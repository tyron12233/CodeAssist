package dev.ide.android.layouteditor

import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutViewNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesignEditsTest {

    private val xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:orientation="vertical">
            <TextView android:id="@+id/title" android:text="Hi"/>
            <Button
                android:id="@+id/go"
                android:text="Go"/>
        </LinearLayout>
    """.trimIndent()

    private fun apply(text: String, e: LayoutEdit) = text.substring(0, e.start) + e.newText + text.substring(e.end)

    @Test
    fun deleteRemovesTheWholeLinesOfAnElementOnItsOwnLines() {
        val doc = parseTestLayout(xml)
        val button = doc.root!!.children[1]
        val out = apply(xml, deleteElementEdit(xml, button))
        assertEquals(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:orientation="vertical">
                <TextView android:id="@+id/title" android:text="Hi"/>
            </LinearLayout>
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun deleteLeavesNeighboursOnASharedLine() {
        val text = """<FrameLayout><View/><View android:id="@+id/b"/></FrameLayout>"""
        val doc = parseTestLayout(text)
        val out = apply(text, deleteElementEdit(text, doc.root!!.children[0]))
        assertEquals("""<FrameLayout><View android:id="@+id/b"/></FrameLayout>""", out)
    }

    @Test
    fun duplicateCopiesOnItsOwnLineWithAFreshId() {
        val doc = parseTestLayout(xml)
        val title = doc.root!!.children[0]
        val out = apply(xml, duplicateElementEdit(doc, title))
        assertTrue(
            """
                <TextView android:id="@+id/title" android:text="Hi"/>
                <TextView android:id="@+id/title2" android:text="Hi"/>
            """.trimIndent().lines().joinToString("\n") { "    $it".trimEnd() }.let { it in out } ||
                out.contains("<TextView android:id=\"@+id/title\" android:text=\"Hi\"/>\n    <TextView android:id=\"@+id/title2\" android:text=\"Hi\"/>"),
            out,
        )
        // The copy parses back as a sibling right after the original.
        val again = parseTestLayout(out)
        assertEquals(listOf("title", "title2", "go"), again.root!!.children.map { it.id })
    }

    @Test
    fun freshIdBumpsATrailingNumberAndSkipsTakenOnes() {
        assertEquals("button2", freshId("button", setOf("button")))
        assertEquals("button3", freshId("button", setOf("button", "button2")))
        assertEquals("item4", freshId("item3", setOf("item3")))
    }

    @Test
    fun tapMapsToTheDeepestElementThatCameFromTheBuffer() {
        val doc = parseTestLayout(xml)
        val title = doc.root!!.children[0]
        // The framework wrapped the layout in decor and gave the TextView an internal child with no offset.
        val tree = LayoutViewNode(
            "DecorView", null, 0, 0, 100, 200, null,
            children = listOf(
                LayoutViewNode(
                    "LinearLayout", null, 0, 20, 100, 200, 0,
                    children = listOf(
                        LayoutViewNode(
                            "MaterialTextView", "title", 0, 20, 100, 60, title.start,
                            children = listOf(LayoutViewNode("Inner", null, 10, 30, 90, 50, null)),
                        ),
                    ),
                ),
            ),
        )
        assertSame(title, elementAtPoint(tree, doc, 50f, 40f))
        assertSame(doc.root, elementAtPoint(tree, doc, 50f, 150f))
        assertNull(elementAtPoint(tree, doc, 50f, 5f), "the status bar is decor, not the layout")
        assertEquals(title.start, nodeFor(tree, title)?.sourceOffset)
    }

    @Test
    fun selectionKeySurvivesAnEditAboveIt() {
        val doc = parseTestLayout(xml)
        val button = doc.root!!.children[1]
        val key = SelectionKey.of(button)
        val edited = xml.replace("android:text=\"Hi\"", "android:text=\"Hello there\"")
        assertEquals("go", key.resolve(parseTestLayout(edited))?.id)
        val anonymous = SelectionKey(null, listOf(0))
        assertEquals("title", anonymous.resolve(parseTestLayout(edited))?.id)
    }

    @Test
    fun commonAttributesLeadWithSizeAndId() {
        val c = commonAttributesFor("com.google.android.material.button.MaterialButton")
        assertEquals(listOf("android:id", "android:layout_width", "android:layout_height"), c.take(3))
        assertTrue("android:text" in c)
        assertTrue(isLayoutFile("/p/app/src/main/res/layout-land/main.xml"))
        assertTrue(!isLayoutFile("/p/app/src/main/res/values/strings.xml"))
    }
}
