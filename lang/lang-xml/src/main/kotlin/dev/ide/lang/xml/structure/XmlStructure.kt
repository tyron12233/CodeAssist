package dev.ide.lang.xml.structure

import dev.ide.lang.resolve.StructureItem
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.xml.XmlNode

/**
 * The element tree as a file structure, for the outline and the sticky headers. Each element is named the way
 * it is told apart from its siblings: a view by its tag and `id` (`TextView` · `title`), a declaration by its
 * `name` (`app_name` · `string`, `.MainActivity` · `activity`), anything else by its tag. An element with
 * children is a [SymbolKind.CLASS] (a container the sticky headers pin), a leaf a [SymbolKind.FIELD].
 * Vocabulary-free: no Android knowledge beyond the `id`/`name` attribute convention.
 */
object XmlStructure {

    private const val MAX_ITEMS = 2000

    fun of(root: XmlNode): List<StructureItem> {
        val out = ArrayList<StructureItem>()
        fun visit(node: XmlNode, depth: Int) {
            for (tag in node.childTags) {
                if (out.size >= MAX_ITEMS) return
                out += itemFor(tag, depth)
                visit(tag, depth + 1)
            }
        }
        visit(root, 0)
        return out
    }

    private fun itemFor(tag: XmlNode, depth: Int): StructureItem {
        val tagName = tag.name.orEmpty()
        val simple = tagName.substringAfterLast('.')
        val id = attr(tag) { it == "id" || it.endsWith(":id") }
            ?.substringAfter("@+id/")?.substringAfter("@id/")
        val name = attr(tag) { it == "name" || it.endsWith(":name") }
        val (label, detail) = when {
            id != null -> simple to id
            name != null -> name to simple
            else -> simple to null
        }
        val kind = if (tag.childTags.isNotEmpty()) SymbolKind.CLASS else SymbolKind.FIELD
        return StructureItem(label, detail, kind, tag.startOffset + 1, tag.endOffset, depth)
    }

    private fun attr(tag: XmlNode, matches: (String) -> Boolean): String? =
        tag.attributes.firstOrNull { a -> a.name?.let(matches) == true }
            ?.valueNode?.text()?.toString()?.trim()?.ifEmpty { null }
}
