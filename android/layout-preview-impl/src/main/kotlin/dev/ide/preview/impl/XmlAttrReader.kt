package dev.ide.preview.impl

import dev.ide.lang.xml.XmlNode
import dev.ide.preview.AttrReader

/** One parsed attribute: its raw (possibly prefixed) [name], the prefix, the local name, and the [value]. */
internal data class RawAttr(val name: String, val prefix: String?, val local: String, val value: String)

/**
 * A neutral [AttrReader] over an element's XML attributes. Namespaces are resolved by *prefix* convention
 * (`android:` / `app:`), matching how the rest of the IDE's Android layer keys attributes — full xmlns URI
 * resolution isn't needed for the standard/`app:` split the renderers care about.
 */
internal class XmlAttrReader(element: XmlNode) : AttrReader {
    private val attrs: List<RawAttr> = element.attributes.map { a ->
        val raw = a.name ?: ""
        val value = a.valueNode?.text()?.toString() ?: ""
        val prefix = raw.substringBefore(':', "").ifEmpty { null }
        val local = raw.substringAfter(':')
        RawAttr(raw, prefix, local, value)
    }

    override val count: Int get() = attrs.size
    override fun name(i: Int): String = attrs[i].local
    override fun namespace(i: Int): String? = attrs[i].prefix
    override fun value(i: Int): String = attrs[i].value

    override fun android(localName: String): String? =
        attrs.firstOrNull { it.prefix == "android" && it.local == localName }?.value

    override fun app(localName: String): String? =
        attrs.firstOrNull { (it.prefix == "app" || it.prefix == null) && it.local == localName }?.value

    override fun local(localName: String): String? =
        attrs.firstOrNull { it.prefix == "android" && it.local == localName }?.value
            ?: attrs.firstOrNull { it.local == localName }?.value
}

/**
 * Overlays a `<style>`'s flattened items beneath an element's explicit attributes: an explicit attribute
 * wins, otherwise the style value is used. Drives `style="@style/…"` (and, merged in, `textAppearance`).
 */
internal class StyleAttrReader(private val base: AttrReader, private val items: Map<String, String>) : AttrReader by base {
    override fun android(localName: String): String? = base.android(localName) ?: items["android:$localName"] ?: items[localName]
    override fun app(localName: String): String? = base.app(localName) ?: items["app:$localName"] ?: items[localName]
    override fun local(localName: String): String? = base.local(localName) ?: items["android:$localName"] ?: items[localName]
}
