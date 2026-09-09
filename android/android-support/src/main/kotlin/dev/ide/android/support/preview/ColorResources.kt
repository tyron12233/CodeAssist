package dev.ide.android.support.preview

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/** One swatch in a color-resource preview: the `@color` name, its raw value, and the resolved ARGB (or null). */
data class ColorEntry(val name: String, val rawValue: String, val argb: Long?)

/**
 * Extracts the `<color>` (and `<item type="color">`) entries of a `res/values` XML document for the color
 * preview, resolving `@color/…` indirection through a [DrawableResolver] (reusing its `resolveColor`).
 * Pure JAXP; entries that don't resolve keep a null [ColorEntry.argb] so the UI can show a placeholder.
 */
object ColorResources {

    fun parse(text: String, resolver: DrawableResolver = DrawableResolver.NONE): List<ColorEntry> {
        val root = runCatching {
            builder().parse(text.byteInputStream(Charsets.UTF_8)).documentElement
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<ColorEntry>()
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val el = kids.item(i) as? Element ?: continue
            if (el.nodeType != Node.ELEMENT_NODE) continue
            val isColor = el.tagName == "color" || (el.tagName == "item" && el.getAttribute("type") == "color")
            if (!isColor) continue
            val name = el.getAttribute("name").ifEmpty { continue }
            val raw = el.textContent?.trim().orEmpty()
            out += ColorEntry(name, raw, resolveColorValue(raw, resolver))
        }
        return out
    }

    /** Resolve a color cell value (`#…`, `@color/…`, `@android:color/…`) to ARGB, or null. */
    private fun resolveColorValue(raw: String, r: DrawableResolver): Long? {
        if (raw.isEmpty()) return null
        if (raw.startsWith("#")) return AndroidColor.parseHex(raw)
        if (raw.startsWith("@")) {
            val name = raw.substringAfterLast('/')
            return if (raw.contains("android:")) AndroidColor.framework(name) else r.resolveColor(raw)
        }
        return null
    }

    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder()
}
