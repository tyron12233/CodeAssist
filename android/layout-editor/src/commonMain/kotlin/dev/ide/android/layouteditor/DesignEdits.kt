package dev.ide.android.layouteditor

import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutViewNode

/*
 * The structural edits the design surface makes, computed over the parsed layout and returned as text edits
 * for the editor to apply (so each is one undo step and the code view follows). Pure functions of the buffer:
 * nothing here touches the renderer or the UI, which is what lets them be tested on their own.
 */

/**
 * Remove [element] and the whitespace that only existed for it: when it sits on lines of its own, the whole
 * lines go (its indentation and its line break), so the code view does not keep an empty line behind; when it
 * shares a line with something else, only the element itself goes.
 */
internal fun deleteElementEdit(text: String, element: LayoutElement): LayoutEdit {
    var start = element.start
    var end = element.end
    val lineStart = lineStartOf(text, start)
    val ownLineBefore = text.substring(lineStart, start).isBlank()
    var after = end
    while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
    val ownLineAfter = after >= text.length || text[after] == '\n' || text[after] == '\r'
    if (ownLineBefore && ownLineAfter) {
        start = lineStart
        end = when {
            after >= text.length -> after
            text[after] == '\r' && after + 1 < text.length && text[after + 1] == '\n' -> after + 2
            else -> after + 1
        }
    }
    return LayoutEdit(start, end, "")
}

/**
 * Insert a copy of [element] right after it, on its own line at its indentation, with every id the copy
 * declares renamed to one unused in [document] (`button` → `button2`), and references to those ids inside the
 * copy following the rename. Two views with one id is a layout bug, not a duplicate.
 */
internal fun duplicateElementEdit(document: LayoutDocument, element: LayoutElement): LayoutEdit {
    val text = document.text
    val taken = document.elements().mapNotNull { it.id }.toMutableSet()
    var copy = text.substring(element.start, element.end)
    // Rename each id the copy declares, longest first so `title` cannot rewrite inside `title_bar`.
    val declared = element.walk().mapNotNull { it.id }.distinct().sortedByDescending { it.length }.toList()
    for (id in declared) {
        val fresh = freshId(id, taken).also { taken += it }
        copy = copy.replace(Regex("""(@\+?id/)${Regex.escape(id)}(?![A-Za-z0-9_])"""), "$1$fresh")
    }
    val lineStart = lineStartOf(text, element.start)
    val indent = text.substring(lineStart, element.start).takeIf { it.isBlank() }
    return if (indent != null) {
        LayoutEdit(element.end, element.end, "\n" + indent + copy)
    } else {
        LayoutEdit(element.end, element.end, copy)
    }
}

/** [base] with a number appended (or its trailing number bumped) until no element in [taken] uses it. */
internal fun freshId(base: String, taken: Set<String>): String {
    val stem = base.trimEnd { it.isDigit() }.ifEmpty { base }
    var n = base.substring(stem.length).toIntOrNull()?.plus(1) ?: 2
    while ("$stem$n" in taken) n++
    return "$stem$n"
}

private fun lineStartOf(text: String, offset: Int): Int {
    var i = offset.coerceIn(0, text.length)
    while (i > 0 && text[i - 1] != '\n' && text[i - 1] != '\r') i--
    return i
}

/**
 * The element a tap on the render at ([x], [y]) (image pixels) selects: the deepest rendered view there that
 * came from an element of the buffer. A tap on a view the framework created internally (a Toolbar's title, a
 * TextInputLayout's inner frame) walks up to the nearest view that maps, which is what the user meant.
 */
internal fun elementAtPoint(root: LayoutViewNode, document: LayoutDocument, x: Float, y: Float): LayoutElement? {
    fun visit(node: LayoutViewNode): LayoutElement? {
        if (x < node.left || x >= node.right || y < node.top || y >= node.bottom) return null
        for (i in node.children.indices.reversed()) visit(node.children[i])?.let { return it }
        return node.sourceOffset?.let { off -> document.elements().firstOrNull { it.start == off } }
    }
    return visit(root)
}

/** The rendered view [element] became, matched by its source offset, else by id. */
internal fun nodeFor(root: LayoutViewNode, element: LayoutElement): LayoutViewNode? =
    root.walk().firstOrNull { it.sourceOffset == element.start }
        ?: element.id?.let { id -> root.walk().firstOrNull { it.id == id && it.sourceOffset != null } }

/**
 * A selection that survives edits: the element's id when it has one, else its index path from the root. Both
 * re-resolve against a re-parsed buffer, where the raw offset would have moved.
 */
internal data class SelectionKey(val id: String?, val path: List<Int>) {
    fun resolve(document: LayoutDocument): LayoutElement? {
        if (id != null) document.elements().firstOrNull { it.id == id }?.let { return it }
        var el = document.root ?: return null
        for (i in path) el = el.children.getOrNull(i) ?: return null
        return el
    }

    companion object {
        fun of(element: LayoutElement): SelectionKey {
            val path = ArrayList<Int>()
            var cur = element
            while (true) {
                val parent = cur.parent ?: break
                path.add(0, parent.children.indexOf(cur))
                cur = parent
            }
            return SelectionKey(element.id, path)
        }
    }
}
