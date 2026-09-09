package dev.ide.lang.xml.lint

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlNodeKinds
import dev.ide.lang.xml.edit.XmlAttributeInsert

/**
 * Pure (I/O-free, index-free) Android XML lint rules over the tolerant DOM (the detection half).
 * Each rule returns the location and the data a host needs to build a quick-fix (the fix's file writes live
 * behind [XmlResourceHost]). Kept separate from the host so the detection is unit-testable on a bare parse tree.
 */
object XmlLintRules {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private val TEXT_ATTRS = setOf("android:text", "android:hint", "android:contentDescription")
    private val SIZELESS_TAGS = setOf("merge", "include", "ViewStub", "requestFocus", "tag")

    /** Elements the inflater accepts inside ANY view (not just a `ViewGroup`), so they are never an
     *  illegal child: `<requestFocus/>` and `<tag/>` are handled by `LayoutInflater` itself. */
    private val VIEW_META_CHILDREN = setOf("requestFocus", "tag")

    /** The data-binding wrapper elements a `<merge>` may sit inside while still being the layout's root. */
    private val DATA_BINDING_WRAPPERS = setOf("layout")

    /** `<resources>` children that DECLARE a resource, so each needs a `name` that is unique per R class.
     *  Anything else (`<eat-comment/>`, `<skip/>`, `<public-group>`, an unknown element) is left alone. */
    private val VALUE_TAGS = setOf(
        "string", "string-array", "integer-array", "array", "color", "dimen", "bool", "integer",
        "fraction", "style", "plurals", "declare-styleable", "attr", "item", "drawable", "id",
    )

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
     *  [insertAt] is where to splice `xmlns:prefix="uri"` (just after the root tag name), [separator] the
     *  whitespace it is written behind (a space, or a newline + indent when the root writes one per line). */
    data class MissingNamespace(
        val prefix: String, val uri: String, val range: TextRange, val insertAt: Int, val separator: String = " ",
    )

    /** A hardcoded user-facing string in [attrName]; [value] occupies [range] (text between the quotes). */
    data class HardcodedText(val range: TextRange, val attrName: String, val value: String)

    /** A view [tag] missing `android:[dim]`; the attribute would be spliced at [insertAt] behind [separator]
     *  (see [XmlAttributeInsert]). [range] underlines the tag. */
    data class MissingSize(
        val range: TextRange, val tag: String, val dim: String, val insertAt: Int, val separator: String = " ",
    )

    /** A second (or later) occurrence of [attribute] on [tag]; [range] underlines the duplicate's name. */
    data class DuplicateAttr(val range: TextRange, val tag: String, val attribute: String)

    /** A repeated `@+id/name` declaration in one file; [range] underlines the duplicate's value. */
    data class DuplicateId(val range: TextRange, val id: String)

    /** A problem with an element occurrence found by [tagProblems]. [range] is what to underline. */
    sealed interface TagProblem {
        val range: TextRange
        val tag: String

        /** [tag] names nothing that exists here (an unknown widget, or a class that isn't on the classpath).
         *  [nameRanges] are the tag-name spans a rename fix rewrites (the start tag, plus the end tag when the
         *  element has one); [suggestions] are the closest known names, best first. */
        data class Unresolved(
            override val range: TextRange, override val tag: String,
            val suggestions: List<String>, val nameRanges: List<TextRange>,
        ) : TagProblem

        /** [tag] cannot contain child elements, yet [child] is nested in it ([range] underlines the child). */
        data class IllegalChild(
            override val range: TextRange, override val tag: String, val child: String,
        ) : TagProblem
    }

    /** A layout element that breaks an inflater rule (as opposed to an XML or schema rule); [range] underlines
     *  the element's name. */
    data class StructureProblem(val range: TextRange, val kind: Kind, val tag: String) {
        enum class Kind {
            /** `<include>` with no `layout` attribute: the inflater has nothing to include. */
            INCLUDE_WITHOUT_LAYOUT,

            /** `<merge>` somewhere other than the layout's root, where it cannot be flattened. */
            MERGE_NOT_ROOT,

            /** `<fragment>` with neither `android:name` nor `class`: no fragment to instantiate. */
            FRAGMENT_WITHOUT_CLASS,
        }
    }

    /** A `<resources>` entry aapt2 would refuse: no `name`, or a name already declared in the same file.
     *  [range] underlines the element's name (missing) or the duplicated value (duplicate). */
    data class ResourceEntryProblem(val range: TextRange, val kind: Kind, val tag: String, val name: String) {
        enum class Kind { MISSING_NAME, DUPLICATE }
    }

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

    /**
     * A start tag carrying the same attribute name twice — invalid XML (well-formedness). IntelliJ flags this
     * as an error via an annotator; the tolerant PSI parser doesn't, so we detect it structurally over the DOM.
     * The first occurrence is fine; every later same-named attribute is flagged (its name underlined).
     */
    fun duplicateAttributes(parsed: ParsedFile): List<DuplicateAttr> {
        val out = ArrayList<DuplicateAttr>()
        for (tag in allTags(parsed)) {
            val seen = HashSet<String>()
            for (attr in tag.attributes) {
                val name = attr.name ?: continue
                if (!seen.add(name)) {
                    out += DuplicateAttr(TextRange(attr.startOffset, attr.startOffset + name.length), tag.name ?: "", name)
                }
            }
        }
        return out
    }

    fun missingNamespaces(parsed: ParsedFile): List<MissingNamespace> {
        val tags = allTags(parsed)
        val root = tags.firstOrNull() ?: return emptyList()
        val declared = root.attributes.mapNotNull { it.name }
            .filter { it.startsWith("xmlns:") }.mapTo(HashSet()) { it.removePrefix("xmlns:") }
        val at = XmlAttributeInsert.offsetAfterName(root)
        val separator = XmlAttributeInsert.separatorForPrepend(parsed.text(), root)
        val range = TextRange(root.startOffset, at)
        return KNOWN_NAMESPACES.mapNotNull { (prefix, uri) ->
            if (prefix in declared) return@mapNotNull null
            val used = tags.any { t -> t.attributes.any { it.name?.startsWith("$prefix:") == true } }
            if (used) MissingNamespace(prefix, uri, range, at, separator) else null
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
        val src = parsed.text()
        val out = ArrayList<MissingSize>()
        for (tag in allTags(parsed)) {
            val name = tag.name ?: continue
            if (name in SIZELESS_TAGS || !isViewLike(name)) continue
            val attrs = tag.attributes.mapNotNull { it.name }.toSet()
            val range = tagNameRange(tag, name)
            val insertAt = XmlAttributeInsert.offsetAfterAttributes(tag)
            val separator = XmlAttributeInsert.separatorForAppend(src, tag)
            for (dim in listOf("layout_width", "layout_height")) {
                if ("android:$dim" !in attrs) out += MissingSize(range, name, dim, insertAt, separator)
            }
        }
        return out
    }

    /**
     * Elements whose tag doesn't resolve, and elements nested in something that can't hold children: the
     * "non-existent view tag" check. Which occurrences are eligible is this rule's business (every element
     * with a name, minus the inflater's own `<requestFocus>`/`<tag>` children when judging containment);
     * whether a tag *exists* is the injected [checker]'s (see [XmlTagChecker], which is conservative by
     * construction, so custom views and cold indexes surface as [TagInfo.Indeterminate] and are not flagged).
     */
    fun tagProblems(parsed: ParsedFile, filePath: String, checker: XmlTagChecker): List<TagProblem> {
        val out = ArrayList<TagProblem>()
        for (tag in allTags(parsed)) {
            val name = tag.name?.takeIf { it.isNotEmpty() } ?: continue
            when (val info = checker.describe(filePath, name, enclosingTagName(tag))) {
                TagInfo.Indeterminate -> {}
                is TagInfo.Unresolved ->
                    out += TagProblem.Unresolved(tagNameRange(tag, name), name, info.suggestions, tagNameRanges(tag, name))
                is TagInfo.Recognized -> {
                    if (info.container != false) continue
                    for (child in tag.childTags) {
                        val childName = child.name?.takeIf { it.isNotEmpty() } ?: continue
                        if (childName in VIEW_META_CHILDREN) continue
                        out += TagProblem.IllegalChild(tagNameRange(child, childName), name, childName)
                    }
                }
            }
        }
        return out
    }

    /**
     * Layout elements that break a `LayoutInflater` rule the XML schema can't express: an `<include>` with
     * nothing to include, a `<merge>` that isn't the root (so there is no parent to merge into), and a
     * `<fragment>` naming no class. Pure structure: no schema or classpath knowledge needed.
     */
    fun structureProblems(parsed: ParsedFile): List<StructureProblem> {
        val out = ArrayList<StructureProblem>()
        for (tag in allTags(parsed)) {
            val name = tag.name ?: continue
            val attrs = tag.attributes.mapNotNull { it.name }.toSet()
            val range = tagNameRange(tag, name)
            when (name) {
                "include" -> if ("layout" !in attrs)
                    out += StructureProblem(range, StructureProblem.Kind.INCLUDE_WITHOUT_LAYOUT, name)
                "merge" -> {
                    val parent = enclosingTagName(tag)
                    if (parent != null && parent !in DATA_BINDING_WRAPPERS)
                        out += StructureProblem(range, StructureProblem.Kind.MERGE_NOT_ROOT, name)
                }
                "fragment" -> if ("android:name" !in attrs && "class" !in attrs)
                    out += StructureProblem(range, StructureProblem.Kind.FRAGMENT_WITHOUT_CLASS, name)
            }
        }
        return out
    }

    /**
     * The same `@+id/name` declared on two elements of one file. `findViewById` then returns whichever the
     * inflater reached first, which is virtually never what was meant. Only `@+id/` *declarations* count: a
     * `@id/` reference (a constraint target, a label-for) legitimately repeats.
     */
    fun duplicateIds(parsed: ParsedFile): List<DuplicateId> {
        val seen = HashSet<String>()
        val out = ArrayList<DuplicateId>()
        for (tag in allTags(parsed)) {
            val value = tag.attributes.firstOrNull { it.name == "android:id" }?.valueNode ?: continue
            val raw = value.text().toString().trim()
            if (!raw.startsWith("@+id/")) continue
            val id = raw.removePrefix("@+id/")
            if (id.isEmpty()) continue
            if (!seen.add(id)) out += DuplicateId(value.range, id)
        }
        return out
    }

    /**
     * `<resources>` entries aapt2 would reject: a declaration with no `name` attribute, and a name already
     * declared for the same R class in this file. Only DIRECT children of `<resources>` are declarations, so
     * a `<item>` inside a `<style>` or an `<attr>` inside a `<declare-styleable>` is never touched. The array
     * family (`array`/`string-array`/`integer-array`) shares one R class, and an `<item>` is keyed by its
     * `type`, so the duplicate check matches what aapt2 actually collides.
     */
    fun resourceEntries(parsed: ParsedFile): List<ResourceEntryProblem> {
        val root = allTags(parsed).firstOrNull()?.takeIf { it.name == "resources" } ?: return emptyList()
        val out = ArrayList<ResourceEntryProblem>()
        val seen = HashSet<String>()
        for (entry in root.childTags) {
            val tag = entry.name ?: continue
            if (tag !in VALUE_TAGS) continue
            val nameValue = entry.attributes.firstOrNull { it.name == "name" }?.valueNode
            val name = nameValue?.text()?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                out += ResourceEntryProblem(
                    tagNameRange(entry, tag), ResourceEntryProblem.Kind.MISSING_NAME, tag, "",
                )
                continue
            }
            if (!seen.add("${resourceKindOf(entry, tag)}/$name")) {
                out += ResourceEntryProblem(nameValue.range, ResourceEntryProblem.Kind.DUPLICATE, tag, name)
            }
        }
        return out
    }

    /** The R class a `<resources>` entry lands in, for duplicate detection. */
    private fun resourceKindOf(entry: XmlNode, tag: String): String = when (tag) {
        "string-array", "integer-array", "array" -> "array"
        "item" -> entry.attributes.firstOrNull { it.name == "type" }?.valueNode?.text()?.toString()?.trim()
            ?.ifEmpty { null } ?: "item"
        else -> tag
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

    /** The span of an element's *name* in its start tag (an element's range starts at `<`). */
    private fun tagNameRange(tag: XmlNode, name: String): TextRange =
        TextRange(tag.startOffset + 1, tag.startOffset + 1 + name.length)

    /** Every span of [tag]'s name that a rename must rewrite: the start tag, plus the end tag when the
     *  element is closed by one (`</name>` at its tail). */
    private fun tagNameRanges(tag: XmlNode, name: String): List<TextRange> {
        val open = tagNameRange(tag, name)
        if (tag.selfClosed) return listOf(open)
        val text = tag.text()
        val at = text.lastIndexOf("</$name")
        if (at < 0) return listOf(open)
        val start = tag.startOffset + at + 2
        // Guard against `</TextViewX>` matching the prefix of a longer name.
        val after = text.getOrNull(at + 2 + name.length)
        if (after != null && after != '>' && !after.isWhitespace()) return listOf(open)
        // On malformed input (an unclosed element that swallowed a same-named sibling) the match can be a
        // CHILD's end tag; only a tag that closes after every child is this element's own.
        if (start < (tag.childTags.maxOfOrNull { it.endOffset } ?: tag.startOffset)) return listOf(open)
        return listOf(open, TextRange(start, start + name.length))
    }

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
