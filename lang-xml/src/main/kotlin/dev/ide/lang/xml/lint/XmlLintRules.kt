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

    /** Attribute prefixes never checked for validity: namespace declarations and design-time tooling
     *  (`tools:` accepts anything by design). Unprefixed attributes (`style`, data-binding `<variable>`) are
     *  skipped separately (the framework schema is namespaced). */
    private val UNCHECKED_ATTR_PREFIXES = setOf("xmlns", "tools")

    /** A namespace [prefix] (`android`/`app`/`tools`) used in attributes but not declared on the root.
     *  [insertAt] is where to splice ` xmlns:prefix="uri"` (just after the root tag name). */
    data class MissingNamespace(val prefix: String, val uri: String, val range: TextRange, val insertAt: Int)

    /** A hardcoded user-facing string in [attrName]; [value] occupies [range] (text between the quotes). */
    data class HardcodedText(val range: TextRange, val attrName: String, val value: String)

    /** A view [tag] missing `android:[dim]`; the attribute would be spliced at [insertAt]. [range] underlines the tag. */
    data class MissingSize(val range: TextRange, val tag: String, val dim: String, val insertAt: Int)

    /** A problem with an attribute occurrence found by [attributeProblems]. [range] is what to underline. */
    sealed interface AttributeProblem {
        val range: TextRange
        val tag: String
        val attribute: String

        /** [attribute] is not a valid attribute on [tag]; [range] underlines the attribute name, [removalRange]
         *  spans the whole `name="value"` (plus one leading space) for a remove-attribute fix. */
        data class Unknown(
            override val range: TextRange, override val tag: String, override val attribute: String,
            val removalRange: TextRange,
        ) : AttributeProblem

        /** [value] is not one of [attribute]'s [allowed] values; [range] underlines the value (between quotes). */
        data class InvalidValue(
            override val range: TextRange, override val tag: String, override val attribute: String,
            val value: String, val allowed: Set<String>,
        ) : AttributeProblem
    }

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

    /**
     * Attribute-level problems over the tree: a *wrong attribute* (one not valid on its element) and a *wrong
     * value* (a literal value outside the attribute's closed enum/flag/boolean set). Validity and the allowed
     * value sets come from the injected [checker] (the host's Android schema); this rule owns *which* attribute
     * occurrences are eligible: it skips namespace declarations (`xmlns:`), design-time (`tools:`) attributes,
     * unprefixed attributes (`style`, data-binding), and any value that is a resource/theme reference or a
     * data-binding / placeholder expression (`@…`, `?…`, `@{…}`, `${…}`), none of which a closed set describes.
     */
    fun attributeProblems(
        parsed: ParsedFile, filePath: String, checker: XmlAttributeChecker,
    ): List<AttributeProblem> {
        val src = parsed.text()
        val out = ArrayList<AttributeProblem>()
        for (tag in allTags(parsed)) {
            val tagName = tag.name ?: continue
            val parent = enclosingTagName(tag)
            for (attr in tag.attributes) {
                val attrName = attr.name ?: continue
                val prefix = attrName.substringBefore(':', "")
                if (prefix.isEmpty() || prefix in UNCHECKED_ATTR_PREFIXES) continue
                when (val info = checker.describe(filePath, tagName, parent, attrName)) {
                    AttrInfo.Indeterminate -> {}
                    AttrInfo.NotAllowed ->
                        out += AttributeProblem.Unknown(
                            attrNameRange(attr, attrName), tagName, attrName, removalRange(attr, src),
                        )
                    is AttrInfo.Recognized -> {
                        val allowed = info.allowedValues ?: continue
                        val vnode = attr.valueNode ?: continue
                        val value = vnode.text().toString()
                        if (!isLiteralValue(value)) continue
                        val bad = if (info.isFlag)
                            value.split('|').map { it.trim() }.any { it.isNotEmpty() && it !in allowed }
                        else value.trim() !in allowed
                        if (bad) out += AttributeProblem.InvalidValue(vnode.range, tagName, attrName, value, allowed)
                    }
                }
            }
        }
        return out
    }

    /** A value the closed-set check can speak to: a plain literal, not a `@resource`/`?theme` reference nor a
     *  data-binding (`@{…}`) / manifest-placeholder (`${…}`) expression. */
    private fun isLiteralValue(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        return !(v.startsWith("@") || v.startsWith("?") || v.startsWith("\${") || v.contains("@{"))
    }

    /** The span of an attribute's *name* (the [XmlNode] starts at the name, before `=`). */
    private fun attrNameRange(attr: XmlNode, name: String): TextRange =
        TextRange(attr.startOffset, attr.startOffset + name.length)

    /** The whole `name="value"` span plus one preceding whitespace char (so removing it leaves no double space). */
    private fun removalRange(attr: XmlNode, src: CharSequence): TextRange {
        val start = if (attr.startOffset > 0 && src[attr.startOffset - 1].isWhitespace()) attr.startOffset - 1
        else attr.startOffset
        return TextRange(start, attr.endOffset)
    }

    /** The name of [tag]'s nearest ancestor element, or null when [tag] is a top-level element. */
    private fun enclosingTagName(tag: XmlNode): String? {
        var p = tag.parent
        while (p != null) {
            if (p is XmlNode && p.kind == XmlNodeKinds.TAG && p.name != null) return p.name
            p = p.parent
        }
        return null
    }
}
