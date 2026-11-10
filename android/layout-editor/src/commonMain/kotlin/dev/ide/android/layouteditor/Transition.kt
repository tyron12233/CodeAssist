package dev.ide.android.layouteditor

import androidx.compose.ui.geometry.Rect
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutRenderResult
import dev.ide.android.layout.LayoutViewNode
import kotlin.math.abs

/*
 * Going from one rendered frame to the next without a jump. A frame is one picture plus the view tree that drew
 * it, with every view's bounds; the views of two frames are matched, and each matched view that moved is drawn
 * as a piece of the pictures gliding from where it was to where it is, while the rest crossfades. Only the
 * designer's own edits animate (a drop, a move, a delete, a property): a frame caused by typing just swaps in.
 */

/**
 * Where something was before an edit ([from], frame pixels) and the element it is after it ([to]). [fromPicture]
 * when [from] was a view in the old frame (a moved view), so its old pixels travel; false for a ghost, which
 * was only drawn over the frame.
 */
internal class Pin(val from: Rect, val to: SelectionKey, val fromPicture: Boolean)

/**
 * The step from frame [from] to frame [to], caused by the designer's [edits] (one list per applied edit, in
 * order, each against the text the one before produced). [pins] place views the edits moved or created.
 */
internal class FrameTransition(
    val from: LayoutRenderResult,
    val to: LayoutRenderResult,
    val edits: List<List<LayoutEdit>>,
    val pins: List<Pin>,
)

/** A view of the new frame that travels: from [from] to its own bounds; [scaled] when its size changed too. */
internal class Motion(val node: LayoutViewNode, val from: Rect, val scaled: Boolean, val fromPicture: Boolean = true)

/** The dropped view, drawn where it will go until the frame that has it lands. [rect] is in frame pixels. */
internal class Ghost(val rect: Rect, val tag: String, val becomes: SelectionKey)

internal fun LayoutViewNode.frameRect(): Rect = Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

/**
 * Where [offset] in the text before [batches] is in the text after them, or null when an edit replaced it. Each
 * batch's edits are against the same text and do not overlap; an insertion at [offset] pushes it along, since a
 * view inserted before a tag starts where that tag did.
 */
internal fun mapOffset(offset: Int, batches: List<List<LayoutEdit>>): Int? {
    var at = offset
    for (batch in batches) {
        var shift = 0
        for (e in batch.sortedBy { it.start }) {
            when {
                at < e.start -> {}
                e.start == e.end || at >= e.end -> shift += e.newText.length - (e.end - e.start)
                else -> return null
            }
        }
        at += shift
    }
    return at
}

/**
 * Where each view of [new] was in [old], by what it is rather than where it is: the same resource id when that id
 * is unique in both frames, else the same element (its source offset carried through [batches]), else, for a view
 * the layout did not declare (window decor, a library's own children), the same class at the same place in the
 * tree. Views with no match are new.
 */
internal fun matchViews(old: LayoutViewNode, new: LayoutViewNode, batches: List<List<LayoutEdit>>): Map<LayoutViewNode, Rect> {
    val out = HashMap<LayoutViewNode, Rect>()
    val oldNodes = old.walk().toList()
    val newNodes = new.walk().toList()
    fun uniqueIds(nodes: List<LayoutViewNode>) = nodes.mapNotNull { it.id }.groupingBy { it }.eachCount().filterValues { it == 1 }.keys
    val ids = uniqueIds(oldNodes).intersect(uniqueIds(newNodes))
    val oldById = oldNodes.filter { it.id in ids }.associateBy { it.id }
    val newByOffset = newNodes.filter { it.sourceOffset != null }.associateBy { it.sourceOffset }
    for (n in newNodes) n.id?.takeIf { it in ids }?.let { id -> oldById[id]?.let { out[n] = it.frameRect() } }
    for (o in oldNodes) {
        val off = o.sourceOffset ?: continue
        val n = mapOffset(off, batches)?.let { newByOffset[it] } ?: continue
        if (n !in out && n.className == o.className) out[n] = o.frameRect()
    }
    // Undeclared views: walk both trees together while the shape agrees.
    fun pair(o: LayoutViewNode, n: LayoutViewNode) {
        if (n.sourceOffset == null && o.sourceOffset == null && o.className == n.className && n !in out) out[n] = o.frameRect()
        if (o.className != n.className) return
        val oc = o.children.filter { it.sourceOffset == null }
        val nc = n.children.filter { it.sourceOffset == null }
        if (oc.size == nc.size) for (i in oc.indices) pair(oc[i], nc[i])
    }
    pair(old, new)
    return out
}

/**
 * The views to draw travelling, outermost first. A view that kept its size and moved travels whole (its
 * children with it); one that kept its place is left to the crossfade and its children are looked at; one that
 * changed size is looked into when it holds children (a list grows when a view is added: its children move, it
 * is not stretched), and travels scaled when it is a leaf.
 */
internal fun planMotion(root: LayoutViewNode, matched: Map<LayoutViewNode, Rect>): List<Motion> {
    val out = ArrayList<Motion>()
    fun visit(n: LayoutViewNode) {
        val from = matched[n]
        if (from == null) { n.children.forEach(::visit); return }
        val to = n.frameRect()
        val sameSize = abs(from.width - to.width) < 1f && abs(from.height - to.height) < 1f
        val samePlace = abs(from.left - to.left) < 1f && abs(from.top - to.top) < 1f
        when {
            sameSize && samePlace -> n.children.forEach(::visit)
            sameSize -> out += Motion(n, from, scaled = false)
            n.children.isNotEmpty() -> n.children.forEach(::visit)
            else -> out += Motion(n, from, scaled = true)
        }
    }
    visit(root)
    return out
}

/** [a] to [b] at [t] (0..1). */
internal fun lerp(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
)

/**
 * Where a view dropped on [target] will show, in frame pixels, before any frame has it: across a vertical
 * list at the insertion line, in a row at the line, at the drop point elsewhere. [size] is the view's size when
 * known (a moved view), else a typical one at [density].
 */
internal fun ghostRect(target: DropTarget, size: Pair<Float, Float>?, density: Float): Rect {
    val line = target.line
    val area = target.area
    val typicalH = 48f * density
    return when {
        line != null && line[1] == line[3] -> { // a vertical list: the line runs across it
            val h = size?.second ?: typicalH
            val w = size?.first ?: (line[2] - line[0])
            Rect(line[0], line[1] - h / 2f, line[0] + w, line[1] + h / 2f)
        }
        line != null -> { // a row: the line runs down it
            val w = size?.first ?: (96f * density)
            val h = size?.second ?: minOf(line[3] - line[1], typicalH)
            Rect(line[0] - w / 2f, line[1], line[0] + w / 2f, line[1] + h)
        }
        else -> {
            val x = (area?.left ?: 0) + target.x
            val y = (area?.top ?: 0) + target.y
            val w = size?.first ?: (120f * density)
            val h = size?.second ?: typicalH
            Rect(x, y, x + w, y + h)
        }
    }
}
