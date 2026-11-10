package dev.ide.android.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LayoutDocumentTest {

    private fun el(tag: String, start: Int, end: Int, id: String? = null, vararg children: LayoutElement) = LayoutElement(
        tag = tag, start = start, end = end, startTagEnd = start + 1, selfClosing = children.isEmpty(),
        attributes = listOfNotNull(id?.let { LayoutAttribute("android:id", "@+id/$it", start + 1, start + 2, start + 1, start + 2) }),
        children = children.toList(),
    )

    @Test
    fun findPrefersIdThenOffset() {
        val button = el("Button", 20, 40, "go")
        val text = el("TextView", 45, 60)
        val root = el("LinearLayout", 0, 100, null, button, text)
        val doc = LayoutDocument("", root)
        // The id wins even when the offset is stale (an edit above shifted it).
        assertSame(button, doc.find(ElementRef(999, "go")))
        assertSame(text, doc.find(ElementRef(45)))
        assertSame(root, button.parent)
        assertNull(root.parent)
        assertEquals(1, text.depth)
        assertEquals(listOf("LinearLayout", "Button", "TextView"), doc.elements().map { it.tag }.toList())
    }

    @Test
    fun elementAtIsInnermost() {
        val inner = el("TextView", 30, 50)
        val group = el("FrameLayout", 10, 80, null, inner)
        val doc = LayoutDocument("", el("LinearLayout", 0, 100, null, group))
        assertSame(inner, doc.elementAt(35))
        assertSame(group, doc.elementAt(60))
        assertEquals("go", el("Button", 0, 5, "go").id)
    }
}
