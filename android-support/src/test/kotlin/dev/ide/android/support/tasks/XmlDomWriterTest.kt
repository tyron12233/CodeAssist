package dev.ide.android.support.tasks

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [XmlDomWriter] is the ART-safe replacement for the `javax.xml.transform` identity Transformer (which on
 * device reaches for a SAX driver the platform lacks). These round-trips prove it serializes the manifest and
 * resource XML the build produces without losing attributes, namespaces, mixed text content, or CDATA.
 */
class XmlDomWriterTest {

    private fun parse(xml: String, namespaceAware: Boolean): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = namespaceAware }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun Document.elements(tag: String): List<Element> {
        val nl = getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun Document.byName(tag: String, name: String): Element? =
        elements(tag).firstOrNull { it.getAttribute("name") == name }

    @Test
    fun manifestRoundTripsAttributesAndNamespaces() {
        // Attribute-only, prefixed attrs, an escaped `&` in an attribute value, and a nested <queries> tree —
        // exactly what InjectAppLogProviderTask emits (parsed non-namespace-aware, like that task).
        val src = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
                <application android:label="A &amp; B">
                    <activity android:name=".Main" android:exported="true"/>
                </application>
                <queries><intent><action android:name="x.y.Sink"/></intent></queries>
            </manifest>
        """.trimIndent()

        val out = XmlDomWriter.toXml(parse(src, namespaceAware = false))
        assertTrue(out.startsWith("<?xml"), "expected an XML declaration; got:\n$out")

        val re = parse(out, namespaceAware = false)
        assertEquals("manifest", re.documentElement.tagName)
        assertEquals("http://schemas.android.com/apk/res/android", re.documentElement.getAttribute("xmlns:android"))
        assertEquals("A & B", re.elements("application").single().getAttribute("android:label"), "attr `&` must round-trip")
        val activity = re.elements("activity").single()
        assertEquals(".Main", activity.getAttribute("android:name"))
        assertEquals("x.y.Sink", re.elements("action").single().getAttribute("android:name"), "queries/intent/action preserved")
    }

    @Test
    fun valuesRoundTripsMixedContentAndCdata() {
        // Styled (mixed-content) string, a CDATA block, a string-array, and an xmlns on <resources> — what the
        // resource merger (ValuesMerger.writeTo) emits. The identity Transformer with INDENT could reflow text;
        // XmlDomWriter preserves it verbatim.
        val src = """
            <resources xmlns:tools="http://schemas.android.com/tools">
                <string name="styled">Hello <b>world</b> &amp; <i>friends</i></string>
                <string name="cdata"><![CDATA[<b>raw</b> & stuff]]></string>
                <string-array name="arr"><item>one</item><item>two</item></string-array>
                <color name="c">#FF0000</color>
            </resources>
        """.trimIndent()

        val out = XmlDomWriter.toXml(parse(src, namespaceAware = true))
        assertTrue("<![CDATA[<b>raw</b> & stuff]]>" in out, "CDATA must be preserved verbatim; got:\n$out")
        assertTrue("<b>world</b>" in out, "inline styling markup must survive; got:\n$out")

        val re = parse(out, namespaceAware = true)
        assertEquals("http://schemas.android.com/tools", re.documentElement.getAttribute("xmlns:tools"))
        assertEquals("Hello world & friends", re.byName("string", "styled")?.textContent, "styled string text")
        assertEquals("<b>raw</b> & stuff", re.byName("string", "cdata")?.textContent, "CDATA text content")
        assertEquals("#FF0000", re.byName("color", "c")?.textContent)
        val items = re.elements("item").map { it.textContent }
        assertEquals(listOf("one", "two"), items, "string-array items preserved in order")
        assertNotNull(re.byName("string-array", "arr"))
    }

    @Test
    fun emptyElementSelfCloses() {
        val re = parse(XmlDomWriter.toXml(parse("<root><a x=\"1\"/></root>", namespaceAware = false)), namespaceAware = false)
        assertEquals("1", re.elements("a").single().getAttribute("x"))
    }
}
