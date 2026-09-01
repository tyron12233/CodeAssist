package dev.ide.lang.xml.edit

import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlNodeKinds
import dev.ide.lang.xml.parse
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where an inserted attribute lands: its own line at the siblings' indent, or inline on a compact element. */
class XmlAttributeInsertTest {

    private fun rootOf(xml: String): XmlNode =
        parse(xml).children.filterIsInstance<XmlNode>().first { it.kind == XmlNodeKinds.TAG }

    private fun appended(xml: String, attribute: String): String {
        val tag = rootOf(xml)
        val at = XmlAttributeInsert.offsetAfterAttributes(tag)
        return xml.substring(0, at) + XmlAttributeInsert.separatorForAppend(xml, tag) + attribute + xml.substring(at)
    }

    @Test
    fun oneAttributePerLineElementGetsANewLineAtTheSameIndent() {
        val xml = """
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />
        """.trimIndent()
        assertEquals(
            """
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Hi" />
            """.trimIndent(),
            appended(xml, """android:text="Hi""""),
        )
    }

    @Test
    fun compactSingleLineElementKeepsItsShape() {
        val xml = """<TextView android:layout_width="wrap_content"/>"""
        assertEquals(
            """<TextView android:layout_width="wrap_content" android:text="Hi"/>""",
            appended(xml, """android:text="Hi""""),
        )
    }

    @Test
    fun elementWithNoAttributesInsertsAfterTheName() {
        assertEquals("""<TextView android:text="Hi"/>""", appended("<TextView/>", """android:text="Hi""""))
    }

    @Test
    fun aTrailingInlineAttributeStillFollowsTheOneMoreLineStyle() {
        // The last attribute shares a line with the one before it; the last attribute that DOES start its own
        // line sets the indent, so the insertion doesn't pile onto the long line.
        val xml = "<TextView\n    android:id=\"@+id/x\" android:layout_width=\"1dp\" />"
        assertEquals(
            "<TextView\n    android:id=\"@+id/x\" android:layout_width=\"1dp\"\n    android:text=\"Hi\" />",
            appended(xml, """android:text="Hi""""),
        )
    }

    @Test
    fun namespaceDeclarationGoesBeforeTheOtherAttributesInTheirStyle() {
        val xml = "<LinearLayout\n    android:orientation=\"vertical\">\n</LinearLayout>"
        val tag = rootOf(xml)
        val at = XmlAttributeInsert.offsetAfterName(tag)
        val out = xml.substring(0, at) + XmlAttributeInsert.separatorForPrepend(xml, tag) + "xmlns:android=\"u\"" +
            xml.substring(at)
        assertEquals("<LinearLayout\n    xmlns:android=\"u\"\n    android:orientation=\"vertical\">\n</LinearLayout>", out)
    }
}
