package dev.ide.android.layouteditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [within] is what keeps the designer's edits honest now that its parse can trail the buffer: an element taken
 * from the tree on screen is found again in a parse of the buffer as it is, so the edit uses where it is now.
 */
class WithinTest {

    private val xml = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView android:id="@+id/title" android:text="Hi"/>
            <Button android:text="Go"/>
        </LinearLayout>
    """.trimIndent()

    @Test
    fun anElementOfTheSameTreeIsItself() {
        val doc = parseTestLayout(xml)
        val button = doc.root!!.children[1]
        assertSame(button, button.within(doc))
    }

    @Test
    fun anElementWithAnIdIsFoundWhereItIsNowAfterTextBeforeItMoved() {
        val old = parseTestLayout(xml)
        val title = old.root!!.children[0]
        // Typed in the code view: a view inserted before the title moves it.
        val typed = xml.replace("    <TextView android:id", "    <Space/>\n    <TextView android:id")
        val now = parseTestLayout(typed)
        val found = title.within(now)!!
        assertEquals("title", found.id)
        assertEquals(typed.indexOf("<TextView"), found.start)
    }

    @Test
    fun anElementWithoutAnIdIsFoundByItsPlace() {
        val old = parseTestLayout(xml)
        val button = old.root!!.children[1]
        val typed = xml.replace("android:text=\"Hi\"", "android:text=\"Hello there\"")
        val found = button.within(parseTestLayout(typed))!!
        assertEquals("Button", found.tag)
        assertEquals(typed.indexOf("<Button"), found.start)
    }

    @Test
    fun anElementThatIsGoneIsNull() {
        val old = parseTestLayout(xml)
        val button = old.root!!.children[1]
        val typed = xml.replace("    <Button android:text=\"Go\"/>\n", "")
        assertNull(button.within(parseTestLayout(typed)))
    }
}
