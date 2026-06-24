package dev.ide.lang.xml.lint

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlNodeKinds

/**
 * Pure (I/O-free, index-free) Android XML lint rules over the tolerant DOM (the detection half).
 * Each rule returns the location and the data a host needs to build a quick-fix (the fix's file writes live
 * behind [XmlResourceHost]). Kept separate from the host so the detection is unit-testable on a bare parse tree.
 */
object XmlLintRules {

    private val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private val TEXT_ATTRS = setOf("android:text", "android:hint", "android:contentDescription")
    private val SIZELESS_TAGS = setOf("merge", "include", "ViewStub", "requestFocus", "tag")

    /** Standard Android namespace URIs by conventional prefix — the ones a missing declaration is offered for. */
    private val KNOWN_NAMESPACES = linkedMapOf(
        "android" to ANDROID_NS,
        "app" to "http://schemas.android.com/apk/res-auto",
        "tools" to "http://schemas.android.com/tools",
    )

    /** A namespace [prefix] (`android`/`app`/`tools`) used in attributes but not declared on the root.
     *  [insertAt] is where to splice ` xmlns:prefix="uri"` (just after the root tag name). */
    data class MissingNamespace(val prefix: String, val uri: String, val range: TextRange, val insertAt: Int)

    /** A hardcoded user-facing string in [attrName]; [value] occupies [range] (text between the quotes). */
    data class HardcodedText(val range: TextRange, val attrName: String, val value: String)

    /** A view [tag] missing `android:[dim]`; the attribute would be spliced at [insertAt]. [range] underlines the tag. */
    data class MissingSize(val range: TextRange, val tag: String, val dim: String, val insertAt: Int)

    fun allTags(parsed: ParsedFile): List<XmlNode> {
        val out = ArrayList<XmlNode>()
        fun walk(n: DomNode) {
            if (n is XmlNode && n.kind == XmlNodeKinds.TAG) out += n
            n.children.forEach(::walk)
        }
        walk(parsed)
        return out
    }

    fun missingNamespaces(parsed: ParsedFile): List<MissingNamespace> {
        val tags = allTags(parsed)
        val root = tags.firstOrNull() ?: return emptyList()
        val declared = root.attributes.mapNotNull { it.name }
            .filter { it.startsWith("xmlns:") }.mapTo(HashSet()) { it.removePrefix("xmlns:") }
        val at = root.startOffset + 1 + (root.name?.length ?: 0)
        val range = TextRange(root.startOffset, at)
        return KNOWN_NAMESPACES.mapNotNull { (prefix, uri) ->
            if (prefix in declared) return@mapNotNull null
            val used = tags.any { t -> t.attributes.any { it.name?.startsWith("$prefix:") == true } }
            if (used) MissingNamespace(prefix, uri, range, at) else null
        }
    }

    fun hardcodedText(parsed: ParsedFile): List<HardcodedText> {
        val out = ArrayList<HardcodedText>()
        for (tag in allTags(parsed)) for (attr in tag.attributes) {
            val an = attr.name ?: continue
            if (an !in TEXT_ATTRS) continue
            val vnode = attr.valueNode ?: continue
            val value = vnode.text().toString()
            if (value.isBlank() || value.startsWith("@") || value.startsWith("?")) continue
            out += HardcodedText(vnode.range, an, value)
        }
        return out
    }

    fun missingSize(parsed: ParsedFile, isViewLike: (String) -> Boolean): List<MissingSize> {
        val out = ArrayList<MissingSize>()
        for (tag in allTags(parsed)) {
            val name = tag.name ?: continue
            if (name in SIZELESS_TAGS || !isViewLike(name)) continue
            val attrs = tag.attributes.mapNotNull { it.name }.toSet()
            val range = TextRange(tag.startOffset + 1, tag.startOffset + 1 + name.length)
            val insertAt = tag.startOffset + 1 + name.length
            for (dim in listOf("layout_width", "layout_height")) {
                if ("android:$dim" !in attrs) out += MissingSize(range, name, dim, insertAt)
            }
        }
        return out
    }
}
