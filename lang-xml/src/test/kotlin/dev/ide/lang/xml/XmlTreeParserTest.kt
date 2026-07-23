package dev.ide.lang.xml

import dev.ide.lang.dom.DomNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlTreeParserTest {

    private fun tags(node: DomNode): List<XmlNode> =
        buildList {
            fun walk(n: DomNode) {
                if (n is XmlNode && n.kind == XmlNodeKinds.TAG) add(n)
                n.children.forEach(::walk)
            }
            walk(node)
        }

    @Test
    fun parsesNestedElementsAndAttributes() {
        val parsed = parse(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:orientation="vertical">
                <TextView android:text="@string/hello"/>
            </LinearLayout>
            """.trimIndent()
        )
        val tagNames = tags(parsed).map { it.name }
        assertEquals(listOf("LinearLayout", "TextView"), tagNames)
        assertTrue(parsed.diagnostics.isEmpty(), "well-formed input should have no diagnostics")

        val textView = tags(parsed).first { it.name == "TextView" }
        assertTrue(textView.selfClosed)
        val attr = textView.attributes.single()
        assertEquals("android:text", attr.name)
        assertEquals("@string/hello", attr.valueNode!!.text().toString())
    }

    @Test
    fun recoversFromUnclosedTag() {
        // The buffer mid-typing: an open tag with no closing tag at all.
        val parsed = parse("<LinearLayout>\n    <TextView ")
        val names = tags(parsed).map { it.name }
        assertTrue("LinearLayout" in names && "TextView" in names, "got $names")
        assertTrue(parsed.diagnostics.isNotEmpty(), "expected a well-formedness diagnostic; got ${parsed.diagnostics}")
    }

    @Test
    fun recoversFromMismatchedCloseTag() {
        val parsed = parse("<a><b></a>")
        val names = tags(parsed).map { it.name }
        assertEquals(listOf("a", "b"), names)
        // <b> is implicitly closed when </a> appears; a diagnostic is reported, parsing continues.
        assertTrue(parsed.diagnostics.isNotEmpty(), "expected a well-formedness diagnostic; got ${parsed.diagnostics}")
        // The whole document is still covered (no exception, root spans the text).
        assertEquals(0, parsed.range.start)
    }

    @Test
    fun nodeAtFindsDeepestNode() {
        val xml = "<root><child android:id=\"@+id/x\"/></root>"
        val parsed = parse(xml)
        val offset = xml.indexOf("android:id")
        val node = parsed.nodeAt(offset)
        // The caret sits on the attribute (or its name) inside <child>.
        var n: DomNode? = node
        var sawAttr = false
        while (n != null) { if (n.kind == XmlNodeKinds.ATTRIBUTE) sawAttr = true; n = n.parent }
        assertTrue(sawAttr, "expected an enclosing attribute node, kind chain from ${node.kind}")
    }

    @Test
    fun handlesCommentsCdataAndProlog() {
        val parsed = parse("<?xml version=\"1.0\"?>\n<!-- hi -->\n<root><![CDATA[ <x> ]]></root>")
        assertEquals(listOf("root"), tags(parsed).map { it.name })
        assertTrue(parsed.diagnostics.isEmpty())
    }
}
