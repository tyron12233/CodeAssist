package dev.ide.android.support.metadata

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses an Android `attrs.xml` (the framework's, or a library/app's) into attribute definitions +
 * `<declare-styleable>` groups. Used both by the build-time generator (framework `attrs.xml`) and at
 * runtime for project/AAR custom-view attributes. Uses `javax.xml` — present on both desktop JVM and ART —
 * and `attrs.xml` files are build inputs (well-formed), so a strict DOM parser is appropriate here (unlike
 * the editor buffer, which needs the tolerant `lang-xml` parser).
 *
 * Handles: top-level `<attr name format>` definitions (with `<enum>`/`<flag>` children), `<attr>` *references*
 * inside a styleable, and `<attr>` defined inline inside a styleable. A later definition of the same attr
 * merges its formats/values into the earlier one.
 */
object AttrsXmlParser {

    data class Parsed(val attrs: Map<String, AttrEntry>, val styleables: Map<String, StyleableEntry>)

    fun parse(xml: String): Parsed {
        val attrs = LinkedHashMap<String, AttrEntry>()
        val styleables = LinkedHashMap<String, StyleableEntry>()

        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return Parsed(emptyMap(), emptyMap())

        val root = doc.documentElement ?: return Parsed(emptyMap(), emptyMap())

        fun mergeAttr(entry: AttrEntry) {
            val existing = attrs[entry.name]
            attrs[entry.name] = if (existing == null) entry else AttrEntry(
                name = entry.name,
                formats = existing.formats + entry.formats,
                enumValues = (existing.enumValues + entry.enumValues).distinct(),
                flagValues = (existing.flagValues + entry.flagValues).distinct(),
            )
        }

        fun readAttr(el: Element): String? {
            val name = el.getAttribute("name").substringAfter(':').ifEmpty { return null }
            if (name.startsWith("__")) return null // framework internal placeholders (`__removed*`) — never completable
            val formats = el.getAttribute("format").split('|').mapNotNull(AttrFormat::parse).toMutableSet()
            val enums = ArrayList<String>()
            val flags = ArrayList<String>()
            val children = el.childNodes
            for (i in 0 until children.length) {
                val c = children.item(i) as? Element ?: continue
                when (c.tagName) {
                    "enum" -> c.getAttribute("name").takeIf { it.isNotEmpty() }?.let { enums.add(it) }
                    "flag" -> c.getAttribute("name").takeIf { it.isNotEmpty() }?.let { flags.add(it) }
                }
            }
            if (enums.isNotEmpty()) formats.add(AttrFormat.ENUM)
            if (flags.isNotEmpty()) formats.add(AttrFormat.FLAG)
            // Only register a real definition (has format/enum/flag); a bare `<attr name=>` is a reference.
            if (formats.isNotEmpty()) mergeAttr(AttrEntry(name, formats, enums, flags))
            return name
        }

        val top = root.childNodes
        for (i in 0 until top.length) {
            val el = top.item(i) as? Element ?: continue
            when (el.tagName) {
                "attr" -> readAttr(el)
                "declare-styleable" -> {
                    val sName = el.getAttribute("name").ifEmpty { continue }
                    val names = ArrayList<String>()
                    val kids = el.childNodes
                    for (j in 0 until kids.length) {
                        val a = kids.item(j) as? Element ?: continue
                        if (a.tagName == "attr") readAttr(a)?.let { names.add(it) }
                    }
                    styleables[sName] = StyleableEntry(sName, names)
                }
            }
        }
        return Parsed(attrs, styleables)
    }
}
