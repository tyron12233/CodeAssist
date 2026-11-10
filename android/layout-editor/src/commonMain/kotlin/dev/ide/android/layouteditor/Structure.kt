package dev.ide.android.layouteditor

import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutViewNode
import kotlin.math.roundToInt

/*
 * Structural editing: where a dragged view would land, and the text edits that insert a new view or move an
 * existing one there. Pure functions of the parsed buffer and the rendered tree, like DesignEdits.
 */

/**
 * Containers that hold exactly one child: the framework throws ("ScrollView can host only one direct child")
 * when given a second, so a drop that would add one is refused.
 */
private val SINGLE_CHILD = setOf("ScrollView", "HorizontalScrollView", "NestedScrollView", "SwipeRefreshLayout")

internal fun isSingleChildContainer(element: LayoutElement): Boolean = element.simpleName in SINGLE_CHILD

/**
 * Whether [parent] can take one more child, not counting [moving] (a view being moved that is already one of its
 * children, so moving it around inside does not add one).
 */
internal fun acceptsChild(parent: LayoutElement, moving: LayoutElement? = null): Boolean =
    !isSingleChildContainer(parent) || parent.children.none { it !== moving }

/** How a container arranges its children, which decides what a drop inside it means. */
internal enum class ContainerKind { VERTICAL, HORIZONTAL, CONSTRAINT, FREE }

internal fun containerKind(element: LayoutElement): ContainerKind {
    val n = element.simpleName
    return when {
        n == "ConstraintLayout" -> ContainerKind.CONSTRAINT
        n == "LinearLayout" || n == "LinearLayoutCompat" -> when (element.attribute("android:orientation")?.value) {
            "vertical" -> ContainerKind.VERTICAL
            else -> ContainerKind.HORIZONTAL
        }
        n == "RadioGroup" || n == "TableLayout" -> if (element.attribute("android:orientation")?.value == "horizontal") ContainerKind.HORIZONTAL else ContainerKind.VERTICAL
        n == "ChipGroup" || n == "TableRow" -> ContainerKind.HORIZONTAL
        else -> ContainerKind.FREE
    }
}

/**
 * Where a drop lands: as child [index] of [parent]. [line] is the insertion line to draw for a linear parent
 * (image pixels), [area] the parent's bounds to highlight. [x]/[y] are the drop point relative to the parent's
 * top-left, in image pixels, which an absolutely positioned parent (ConstraintLayout) turns into margins.
 */
internal class DropTarget(
    val parent: LayoutElement,
    val index: Int,
    val area: LayoutViewNode?,
    val line: FloatArray?,
    val x: Float,
    val y: Float,
)

/**
 * The drop target at ([x], [y]) in image pixels: the deepest view there that holds children ([isViewGroup]
 * answers by tag), with the insertion index among its children. [moving] (an element being moved) and its
 * descendants never receive the drop, since a view cannot move into itself.
 */
internal fun dropTargetAt(
    root: LayoutViewNode,
    document: LayoutDocument,
    x: Float,
    y: Float,
    isViewGroup: (LayoutElement) -> Boolean,
    moving: LayoutElement? = null,
): DropTarget? {
    val inside = moving?.walk()?.toSet().orEmpty()
    // The mapped views under the point, outermost first.
    val chain = ArrayList<Pair<LayoutViewNode, LayoutElement>>()
    fun visit(node: LayoutViewNode) {
        if (x < node.left || x >= node.right || y < node.top || y >= node.bottom) return
        node.sourceOffset?.let { off -> document.elements().firstOrNull { it.start == off }?.let { chain += node to it } }
        for (i in node.children.indices.reversed()) {
            val c = node.children[i]
            if (x >= c.left && x < c.right && y >= c.top && y < c.bottom) { visit(c); return }
        }
    }
    visit(root)
    // The deepest container that can take the view: a full single-child one (a ScrollView with its content) is
    // passed over for the one around it, so a drop over its padding lands beside it, not as a second child.
    val (area, parent) = chain.lastOrNull { (_, el) -> isViewGroup(el) && el !in inside && acceptsChild(el, moving) } ?: return null
    val kind = containerKind(parent)
    val children = parent.children.filter { it !in inside || it === moving }
    var index = parent.children.size
    var line: FloatArray? = null
    if (kind == ContainerKind.VERTICAL || kind == ContainerKind.HORIZONTAL) {
        val vertical = kind == ContainerKind.VERTICAL
        val nodes = children.map { it to nodeFor(root, it) }
        val before = nodes.firstOrNull { (_, n) -> n != null && (if (vertical) y < (n.top + n.bottom) / 2f else x < (n.left + n.right) / 2f) }
        index = before?.let { parent.children.indexOf(it.first) } ?: parent.children.size
        // The line between the neighbours either side of the insertion point.
        val prev = nodes.lastOrNull { (el, n) -> n != null && parent.children.indexOf(el) < index }?.second
        val next = before?.second
        line = if (vertical) {
            val ly = when {
                prev != null && next != null -> (prev.bottom + next.top) / 2f
                next != null -> next.top.toFloat()
                prev != null -> prev.bottom.toFloat()
                else -> (area.top + area.bottom) / 2f
            }
            floatArrayOf(area.left.toFloat(), ly, area.right.toFloat(), ly)
        } else {
            val lx = when {
                prev != null && next != null -> (prev.right + next.left) / 2f
                next != null -> next.left.toFloat()
                prev != null -> prev.right.toFloat()
                else -> (area.left + area.right) / 2f
            }
            floatArrayOf(lx, area.top.toFloat(), lx, area.bottom.toFloat())
        }
    }
    return DropTarget(parent, index, area, line, x - area.left, y - area.top)
}

/** A namespace a written attribute may need declared on the root. */
private val NAMESPACES = mapOf(
    "app" to "http://schemas.android.com/apk/res-auto",
    "tools" to "http://schemas.android.com/tools",
)

/**
 * The attributes a new [tag] gets when placed in [target]: an id, a size, a starting text for the views that
 * show one, and for a ConstraintLayout the constraints and margins that keep it where it was dropped. [taken]
 * are the ids already in the layout. [density] converts the drop point to dp.
 */
internal fun defaultAttributes(
    tag: String,
    isViewGroup: Boolean,
    target: DropTarget?,
    taken: Set<String>,
    density: Float,
): List<Pair<String, String>> {
    val n = tag.substringAfterLast('.')
    val base = n.replaceFirstChar { it.lowercase() }.ifEmpty { "view" }
    val id = freshIdOrSame(base, taken)
    val parentKind = target?.parent?.let(::containerKind)
    val fullWidth = isViewGroup || n.endsWith("EditText") || n.endsWith("TextInputLayout") || n.endsWith("RecyclerView") ||
        n == "ListView" || n.endsWith("ProgressIndicator") || n == "SeekBar" || n == "Slider"
    val out = ArrayList<Pair<String, String>>()
    out += "android:id" to "@+id/$id"
    val constraint = parentKind == ContainerKind.CONSTRAINT
    out += "android:layout_width" to when {
        constraint && fullWidth -> "0dp"
        fullWidth -> "match_parent"
        n.endsWith("ImageView") -> "48dp"
        else -> "wrap_content"
    }
    out += "android:layout_height" to when {
        n.endsWith("ImageView") -> "48dp"
        else -> "wrap_content"
    }
    when {
        n.endsWith("EditText") -> { out += "android:hint" to n; out += "android:inputType" to "text" }
        n.endsWith("TextInputLayout") -> out += "android:hint" to n
        n.endsWith("ImageView") || n.endsWith("ImageButton") -> out += "android:src" to "@android:drawable/ic_menu_gallery"
        n.endsWith("TextView") || n.endsWith("Button") || n.endsWith("CheckBox") || n.contains("Switch") ||
            n.endsWith("RadioButton") || n.endsWith("Chip") -> out += "android:text" to n
        n == "LinearLayout" || n == "LinearLayoutCompat" -> out += "android:orientation" to "vertical"
    }
    if (constraint && target != null) {
        // Pinned to the parent's top-start at the drop point (on an 8dp grid), so it stays where it was let go.
        fun snap(px: Float) = ((px / density / 8f).roundToInt() * 8).coerceAtLeast(0)
        out += "app:layout_constraintStart_toStartOf" to "parent"
        out += "app:layout_constraintTop_toTopOf" to "parent"
        if (fullWidth) out += "app:layout_constraintEnd_toEndOf" to "parent"
        out += "android:layout_marginStart" to "${snap(target.x)}dp"
        out += "android:layout_marginTop" to "${snap(target.y)}dp"
    }
    return out
}

/** [base] when no element uses it, else the next free numbered form. */
internal fun freshIdOrSame(base: String, taken: Set<String>): String = if (base !in taken) base else freshId(base, taken)

/** The indentation of the line [offset] is on, when only whitespace precedes it there; else null. */
private fun indentAt(text: String, offset: Int): String? {
    var i = offset.coerceIn(0, text.length)
    while (i > 0 && text[i - 1] != '\n') i--
    val prefix = text.substring(i, offset.coerceIn(0, text.length))
    return prefix.takeIf { it.isBlank() }
}

/** One level of indentation, as the buffer writes it (the first indented line's whitespace), else 4 spaces. */
private fun indentUnit(document: LayoutDocument): String {
    val root = document.root ?: return "    "
    val child = root.children.firstOrNull() ?: return "    "
    val rootIndent = indentAt(document.text, root.start) ?: ""
    val childIndent = indentAt(document.text, child.start) ?: return "    "
    return childIndent.removePrefix(rootIndent).ifEmpty { "    " }
}

/** Where children of [parent] are indented: its first child's indent, else one level deeper than it. */
private fun childIndent(document: LayoutDocument, parent: LayoutElement): String {
    parent.children.firstOrNull()?.let { c -> indentAt(document.text, c.start)?.let { return it } }
    return (indentAt(document.text, parent.start) ?: "") + indentUnit(document)
}

/** A new element's text in the Android style: one attribute per line, `/>` on the last. */
internal fun elementText(tag: String, attributes: List<Pair<String, String>>, indent: String, unit: String): String {
    if (attributes.isEmpty()) return "<$tag />"
    val attrIndent = indent + unit
    return buildString {
        append('<').append(tag)
        for ((name, value) in attributes) {
            append('\n').append(attrIndent).append(name).append("=\"").append(escapeAttr(value)).append('"')
        }
        append(" />")
    }
}

private fun escapeAttr(v: String) = v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

/**
 * The edits that put [content] (an element's text, already indented for its new place) into [parent] as child
 * [index], on its own line, opening a self-closing parent when it had no children.
 */
private fun insertionEdits(document: LayoutDocument, parent: LayoutElement, index: Int, content: String, indent: String): List<LayoutEdit> {
    val text = document.text
    val parentIndent = indentAt(text, parent.start) ?: ""
    val children = parent.children
    return when {
        index < children.size -> {
            val anchor = children[index]
            val lineStart = indentAt(text, anchor.start)?.let { anchor.start - it.length }
            if (lineStart != null) listOf(LayoutEdit(lineStart, lineStart, indent + content + "\n"))
            else listOf(LayoutEdit(anchor.start, anchor.start, content))
        }
        children.isNotEmpty() -> {
            val last = children.last()
            listOf(LayoutEdit(last.end, last.end, "\n" + indent + content))
        }
        parent.selfClosing -> {
            // `<Parent ... />` becomes `<Parent ...>` + the child + `</Parent>`.
            val close = text.lastIndexOf("/>", parent.startTagEnd)
            var from = close
            while (from > parent.start && text[from - 1].isWhitespace() && text[from - 1] != '\n') from--
            listOf(LayoutEdit(from, parent.startTagEnd, ">\n" + indent + content + "\n" + parentIndent + "</" + parent.tag + ">"))
        }
        else -> {
            val inner = text.substring(parent.startTagEnd, text.lastIndexOf("</", parent.end).coerceAtLeast(parent.startTagEnd))
            if ('\n' in inner) listOf(LayoutEdit(parent.startTagEnd, parent.startTagEnd, "\n" + indent + content))
            else listOf(LayoutEdit(parent.startTagEnd, parent.startTagEnd + inner.length, "\n" + indent + content + "\n" + parentIndent))
        }
    }
}

/** The edit that declares a namespace on the root for any `prefix:` the [attributes] use and it lacks. */
private fun namespaceEdits(document: LayoutDocument, attributes: List<String>): List<LayoutEdit> {
    val root = document.root ?: return emptyList()
    val needed = attributes.map { it.substringBefore(':', "") }.filter { it in NAMESPACES }.distinct()
        .filter { p -> root.attribute("xmlns:$p") == null }
    if (needed.isEmpty()) return emptyList()
    val at = root.start + 1 + root.tag.length
    val first = root.attributes.minByOrNull { it.start }
    val sep = first?.let { indentAt(document.text, it.start) }?.let { "\n$it" } ?: " "
    return listOf(LayoutEdit(at, at, needed.joinToString("") { sep + "xmlns:" + it + "=\"" + NAMESPACES.getValue(it) + "\"" }))
}

/** The edits that insert a new [tag] with [attributes] as child [index] of [parent]. */
internal fun insertElementEdits(
    document: LayoutDocument,
    parent: LayoutElement,
    index: Int,
    tag: String,
    attributes: List<Pair<String, String>>,
): List<LayoutEdit> {
    val indent = childIndent(document, parent)
    val content = elementText(tag, attributes, indent, indentUnit(document))
    return insertionEdits(document, parent, index, content, indent) + namespaceEdits(document, attributes.map { it.first })
}

/** Layout parameters that only mean something to one kind of parent. */
private val CONSTRAINT_PARAMS = Regex("""^(app:layout_constraint|app:layout_goneMargin|app:layout_editor_)""")
private val RELATIVE_PARAMS = Regex(
    """^android:layout_(alignParent\w+|align(Start|End|Left|Right|Top|Bottom|Baseline)|below|above|to(Start|End|Left|Right)Of|center(InParent|Horizontal|Vertical))$""",
)

/**
 * The edits that move [element] to child [index] of [parent] (indices as in the current buffer). The element's
 * text is re-indented for its new depth, and layout parameters only its old kind of parent understood are
 * dropped (constraints leaving a ConstraintLayout, alignment rules leaving a RelativeLayout, a weight leaving a
 * LinearLayout). Moving into a ConstraintLayout pins it at the drop point, like a new view. Empty when nothing
 * moves.
 */
internal fun moveElementEdits(
    document: LayoutDocument,
    element: LayoutElement,
    parent: LayoutElement,
    index: Int,
    target: DropTarget?,
    density: Float,
): List<LayoutEdit> {
    if (parent === element || element.walk().any { it === parent }) return emptyList()
    val oldParent = element.parent ?: return emptyList()
    val oldIndex = oldParent.children.indexOf(element)
    if (oldParent === parent && (index == oldIndex || index == oldIndex + 1)) return emptyList()

    val text = document.text
    val oldIndent = indentAt(text, element.start) ?: ""
    val newIndent = childIndent(document, parent).let { if (parent === oldParent) oldIndent else it }
    val unit = indentUnit(document)

    // Drop what the new parent would not understand, then re-indent line by line.
    val newKind = containerKind(parent)
    var attrs = element.attributes.map { it.name to it.value }
    val keepConstraint = newKind == ContainerKind.CONSTRAINT && oldParent.simpleName == parent.simpleName
    val dropped = attrs.filter { (n, _) ->
        (CONSTRAINT_PARAMS.containsMatchIn(n) && !keepConstraint && newKind != ContainerKind.CONSTRAINT) ||
            (RELATIVE_PARAMS.matches(n) && parent.simpleName != "RelativeLayout") ||
            (n == "android:layout_weight" && newKind != ContainerKind.VERTICAL && newKind != ContainerKind.HORIZONTAL)
    }.map { it.first }.toSet()
    var body = text.substring(element.start, element.end)
    for (a in element.attributes.filter { it.name in dropped }.sortedByDescending { it.start }) {
        var s = a.start - element.start
        val e = a.end - element.start
        while (s > 0 && body[s - 1].isWhitespace()) s--
        body = body.substring(0, s) + body.substring(e)
    }
    attrs = attrs.filter { it.first !in dropped }
    // Arriving in a ConstraintLayout with no constraints: pin it where it was dropped.
    val addConstraints = newKind == ContainerKind.CONSTRAINT && attrs.none { CONSTRAINT_PARAMS.containsMatchIn(it.first) } && target != null
    if (addConstraints) {
        fun snap(px: Float) = ((px / density / 8f).roundToInt() * 8).coerceAtLeast(0)
        val extra = listOf(
            "app:layout_constraintStart_toStartOf" to "parent",
            "app:layout_constraintTop_toTopOf" to "parent",
            "android:layout_marginStart" to "${snap(target!!.x)}dp",
            "android:layout_marginTop" to "${snap(target.y)}dp",
        ).filter { (n, _) -> attrs.none { it.first == n } }
        val close = if (element.selfClosing) body.lastIndexOf("/>") else (element.startTagEnd - element.start - 1)
        var at = close
        while (at > 0 && body[at - 1].isWhitespace()) at--
        val attrIndent = oldIndent + unit
        body = body.substring(0, at) + extra.joinToString("") { (n, v) -> "\n$attrIndent$n=\"$v\"" } + body.substring(at)
    }
    val reindented = body.lines().mapIndexed { i, line ->
        if (i == 0) line else if (line.startsWith(oldIndent)) newIndent + line.removePrefix(oldIndent) else line
    }.joinToString("\n")

    val removal = deleteElementEdit(text, element)
    val insertion = insertionEdits(document, parent, index, reindented, newIndent)
    val namespaces = if (addConstraints) namespaceEdits(document, listOf("app:x")) else emptyList()
    return listOf(removal) + insertion + namespaces
}
