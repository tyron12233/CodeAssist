package dev.ide.preview.impl

import dev.ide.preview.PreviewViewNode
import dev.ide.preview.PreviewViewProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewViewTreeCodecTest {

    private fun node(
        className: String,
        id: String? = null,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0,
        props: List<PreviewViewProperty> = emptyList(),
        children: List<PreviewViewNode> = emptyList(),
    ) = PreviewViewNode(className, id, l, t, r, b, props, children)

    @Test
    fun `round-trips a nested tree with grouped properties`() {
        val tree = node(
            "android.widget.FrameLayout", id = "root", l = 0, t = 0, r = 1080, b = 1920,
            props = listOf(
                PreviewViewProperty("Layout", "layout_width", "match_parent"),
                PreviewViewProperty("Appearance", "background", "#FFFFFFFF"),
            ),
            children = listOf(
                node(
                    "com.google.android.material.button.MaterialButton", id = "submit",
                    l = 40, t = 80, r = 400, b = 200,
                    props = listOf(
                        PreviewViewProperty("Text", "text", "Submit"),
                        PreviewViewProperty("Text", "textColor", "#FF000000"),
                    ),
                ),
                node("android.widget.TextView", l = 40, t = 220, r = 400, b = 300),
            ),
        )

        val decoded = PreviewViewTreeCodec.decode(PreviewViewTreeCodec.encode(tree))!!

        assertSame(tree, decoded)
    }

    @Test
    fun `escapes tabs and newlines in attribute values`() {
        val tree = node(
            "android.widget.TextView", id = null,
            props = listOf(PreviewViewProperty("Text", "text", "line1\nline2\tcol\\end")),
        )
        val decoded = PreviewViewTreeCodec.decode(PreviewViewTreeCodec.encode(tree))!!
        assertEquals("line1\nline2\tcol\\end", decoded.properties.single().value)
        assertNull(decoded.id)
    }

    @Test
    fun `decode returns null on blank or garbage`() {
        assertNull(PreviewViewTreeCodec.decode(""))
        assertNull(PreviewViewTreeCodec.decode("   "))
        assertNull(PreviewViewTreeCodec.decode("not\ta\tvalid\ttree"))
    }

    private fun assertSame(expected: PreviewViewNode, actual: PreviewViewNode) {
        assertEquals(expected.className, actual.className)
        assertEquals(expected.id, actual.id)
        assertEquals(listOf(expected.left, expected.top, expected.right, expected.bottom),
            listOf(actual.left, actual.top, actual.right, actual.bottom))
        assertEquals(expected.properties.map { Triple(it.group, it.name, it.value) },
            actual.properties.map { Triple(it.group, it.name, it.value) })
        assertEquals(expected.children.size, actual.children.size)
        expected.children.zip(actual.children).forEach { (e, a) -> assertSame(e, a) }
    }
}
