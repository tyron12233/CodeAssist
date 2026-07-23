package dev.ide.android.support.tasks

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * ART-safe DOM -> XML serializer. Deliberately avoids `javax.xml.transform` (TransformerFactory): Android does
 * NOT ship a reliable JAXP Transformer, and its identity transform's serialization path reaches for a legacy
 * SAX driver (`org.apache.xerces.parsers.SAXParser`, via `XMLReaderFactory`) that is absent on ART, so on
 * device `TransformerFactory` either fails outright or silently produces a broken document ("cannot load SAX
 * parser"). [dev.ide.android.support.manifest.ManifestMerger] already hand-rolls its own writer for the same
 * reason; this is the shared, general form used by the manifest-instrumentation and resource-merge tasks.
 *
 * It emits elements, attributes, text, CDATA and comments faithfully and does NOT re-indent, so mixed text
 * content (a styled `<string>Hello <b>world</b></string>`, a `<![CDATA[...]]>` block) is preserved exactly —
 * which is all that resource / manifest XML consumed by aapt2 needs.
 */
internal object XmlDomWriter {

    /** Serialize [node] (a [Document] -> its root element, or any [Node]) to XML, with a UTF-8 declaration. */
    fun toXml(node: Node, xmlDeclaration: Boolean = true): String {
        val sb = StringBuilder()
        if (xmlDeclaration) sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        when (node) {
            is Document -> node.documentElement?.let { write(it, sb) }
            else -> write(node, sb)
        }
        return sb.toString()
    }

    private fun write(node: Node, sb: StringBuilder) {
        when (node.nodeType) {
            Node.ELEMENT_NODE -> writeElement(node as Element, sb)
            Node.TEXT_NODE -> sb.append(escapeText(node.nodeValue ?: ""))
            Node.CDATA_SECTION_NODE -> sb.append("<![CDATA[").append(node.nodeValue ?: "").append("]]>")
            Node.COMMENT_NODE -> sb.append("<!--").append(node.nodeValue ?: "").append("-->")
            Node.ENTITY_REFERENCE_NODE -> sb.append('&').append(node.nodeName).append(';')
            Node.PROCESSING_INSTRUCTION_NODE ->
                sb.append("<?").append(node.nodeName).append(' ').append(node.nodeValue ?: "").append("?>")
            // DOCUMENT_TYPE and anything else is not meaningful for the manifest/resource XML we emit.
        }
    }

    private fun writeElement(el: Element, sb: StringBuilder) {
        sb.append('<').append(el.tagName)
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            sb.append(' ').append(a.name).append("=\"").append(escapeAttr(a.value)).append('"')
        }
        val children = el.childNodes
        if (children.length == 0) {
            sb.append("/>")
            return
        }
        sb.append('>')
        for (i in 0 until children.length) write(children.item(i), sb)
        sb.append("</").append(el.tagName).append('>')
    }

    private fun escapeText(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }

    private fun escapeAttr(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            '\t' -> append("&#9;")
            else -> append(c)
        }
    }
}
