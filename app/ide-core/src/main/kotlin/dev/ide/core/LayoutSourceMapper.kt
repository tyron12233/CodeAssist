package dev.ide.core

import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlNodeKinds
import dev.ide.lang.xml.XmlParsedFile
import dev.ide.preview.PreviewViewNode
import java.util.IdentityHashMap

/**
 * Traces each node of the REAL-view render's captured [PreviewViewNode] tree back to the `<Tag …>` in the live
 * layout XML it was inflated from, stamping its [PreviewViewNode.sourceOffset]. The captured tree comes from
 * the real Android framework (via `ViewHierarchyCapture`) and carries no source positions; this aligns it to
 * the parsed source so the Preview can open an editable attribute editor for a tapped view.
 *
 * Two independent passes, id-first:
 * - **By id** (authoritative): any captured node whose resource-id entry name matches an `@+id/…`/`@id/…` in the
 *   source is stamped with that element's offset, regardless of where it sits structurally. Ids are unique
 *   within a layout, so this is exact and survives framework-synthesized children.
 * - **Structural** (fills in the un-id'd views): from the layout root down, when a container's captured child
 *   count equals the source child-tag count they are aligned position-by-position (the framework preserves
 *   child order, so this holds even under AppCompat/Material view substitution). A count mismatch — a container
 *   that synthesizes internal children (Toolbar, RecyclerView) — stops structural descent there; its id'd
 *   descendants are still covered by the id pass. `<merge>` is transparent; `<include>` maps the include
 *   element itself and does not descend into the included layout's body.
 *
 * Best-effort: a node that can't be traced keeps `sourceOffset = null` (read-only in the editor). Pure over the
 * parsed DOM + the captured tree, so it is host-testable without a device.
 */
internal object LayoutSourceMapper {

    fun stamp(tree: PreviewViewNode, parsed: XmlParsedFile): PreviewViewNode {
        val rootTag = firstTag(parsed) ?: return tree
        val ids = HashMap<String, Int>()
        collectIds(rootTag, ids)

        val offsets = IdentityHashMap<PreviewViewNode, Int>()
        assignByIds(tree, ids, offsets)

        val content = preorderFind(tree) { it.id == "content" }
        if (rootTag.name == "merge") {
            // `<merge>` inflates its children directly into the container (the window content), so align the
            // container's children against the merge's child tags — the merge itself has no view.
            alignChildren((content ?: tree).children, rootTag.childTags, offsets)
        } else {
            val layoutRoot = content?.children?.firstOrNull()
                ?: preorderFind(tree) { simpleName(it.className) == simpleName(rootTag.name) }
            if (layoutRoot != null) alignNode(layoutRoot, rootTag, offsets)
        }
        return rebuild(tree, offsets)
    }

    private fun alignNode(inflated: PreviewViewNode, sourceTag: XmlNode, offsets: MutableMap<PreviewViewNode, Int>) {
        when (sourceTag.name) {
            // The included layout's inflated root is a different element; map the `<include>` element itself
            // (its id / layout params are editable) but do not descend into the included body.
            "include" -> offsets.putIfAbsent(inflated, sourceTag.startOffset)
            // Transparent: the merge's children were adopted by this container.
            "merge" -> alignChildren(inflated.children, sourceTag.childTags, offsets)
            else -> {
                // The id pass wins on conflict (unique + authoritative), so only fill when unset.
                offsets.putIfAbsent(inflated, sourceTag.startOffset)
                alignChildren(inflated.children, sourceTag.childTags, offsets)
            }
        }
    }

    private fun alignChildren(
        inflatedKids: List<PreviewViewNode>, sourceKids: List<XmlNode>, offsets: MutableMap<PreviewViewNode, Int>
    ) {
        val src = sourceKids.filter { it.name != null }
        // Conservative: only map positionally when the counts line up. A synthesized-child container (count
        // mismatch) is left to the id pass so we never mis-attribute a tapped view to the wrong element.
        if (src.isEmpty() || src.size != inflatedKids.size) return
        for (i in src.indices) alignNode(inflatedKids[i], src[i], offsets)
    }

    private fun rebuild(n: PreviewViewNode, offsets: Map<PreviewViewNode, Int>): PreviewViewNode =
        PreviewViewNode(
            className = n.className, id = n.id,
            left = n.left, top = n.top, right = n.right, bottom = n.bottom,
            properties = n.properties,
            children = n.children.map { rebuild(it, offsets) },
            sourceOffset = offsets[n],
        )

    private fun assignByIds(node: PreviewViewNode, ids: Map<String, Int>, offsets: MutableMap<PreviewViewNode, Int>) {
        node.id?.let { ids[it]?.let { off -> offsets[node] = off } }
        node.children.forEach { assignByIds(it, ids, offsets) }
    }

    private fun collectIds(tag: XmlNode, out: MutableMap<String, Int>) {
        tag.attributes.firstOrNull { it.name == "android:id" }?.valueNode?.text()?.toString()
            ?.let(::idEntryName)?.let { out.putIfAbsent(it, tag.startOffset) }
        tag.childTags.forEach { collectIds(it, out) }
    }

    /** The entry name of a project `@+id/name` / `@id/name`, or null for a framework `@android:id/…` ref. */
    private fun idEntryName(value: String): String? = when {
        value.startsWith("@android:") -> null
        value.startsWith("@+id/") -> value.removePrefix("@+id/").ifEmpty { null }
        value.startsWith("@id/") -> value.removePrefix("@id/").ifEmpty { null }
        else -> null
    }

    private inline fun preorderFind(root: PreviewViewNode, predicate: (PreviewViewNode) -> Boolean): PreviewViewNode? {
        val stack = ArrayDeque<PreviewViewNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (predicate(n)) return n
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
        return null
    }

    /** The document's first element (the layout root), or null. */
    private fun firstTag(parsed: XmlParsedFile): XmlNode? {
        fun find(node: dev.ide.lang.dom.DomNode): XmlNode? {
            if (node is XmlNode && node.kind == XmlNodeKinds.TAG) return node
            for (child in node.children) find(child)?.let { return it }
            return null
        }
        return find(parsed)
    }

    private fun simpleName(className: String?): String = className?.substringAfterLast('.') ?: ""
}
