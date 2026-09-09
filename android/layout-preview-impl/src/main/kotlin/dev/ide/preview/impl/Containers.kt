package dev.ide.preview.impl

import dev.ide.preview.MeasureSpec
import dev.ide.preview.Props
import dev.ide.preview.RCanvas
import dev.ide.preview.RenderContext
import dev.ide.preview.RenderNode
import dev.ide.preview.Renderer
import dev.ide.preview.Size
import kotlin.math.max

/**
 * `ScrollView` / `NestedScrollView` (vertical) and `HorizontalScrollView`: measures its single child
 * unbounded along the scroll axis (so content taller/wider than the viewport keeps its natural size) and
 * clips drawing to the viewport. Scroll offset is 0 in the preview.
 */
class ScrollRenderer(private val horizontal: Boolean) : Renderer {
    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val child = node.children.firstOrNull { it.props.visibility != Props.GONE }
        var cw = 0; var ch = 0
        if (child != null) {
            val childWSpec = if (horizontal) MeasureSpec.unspecified() else containerChildSpec(widthSpec, node.props.hPadding, child.props.layoutWidth)
            val childHSpec = if (!horizontal) MeasureSpec.unspecified() else containerChildSpec(heightSpec, node.props.vPadding, child.props.layoutHeight)
            val s = child.renderer.measure(child, childWSpec, childHSpec, ctx)
            cw = s.width; ch = s.height
        }
        val w = resolveSize(node.props.layoutWidth, widthSpec, cw + node.props.hPadding)
        val h = resolveSize(node.props.layoutHeight, heightSpec, ch + node.props.vPadding)
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) {
        node.setBounds(l, t, r, b)
        val child = node.children.firstOrNull { it.props.visibility != Props.GONE } ?: return
        val cl = l + node.props.paddingLeft; val ct = t + node.props.paddingTop
        child.renderer.layout(child, cl, ct, cl + child.measured.width, ct + child.measured.height, ctx)
    }

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        drawBackground(node, canvas, ctx)
        canvas.save()
        canvas.clipRect(node.left.toFloat(), node.top.toFloat(), node.right.toFloat(), node.bottom.toFloat())
        for (c in node.children) if (c.props.visibility != Props.GONE) c.renderer.draw(c, canvas, ctx)
        canvas.restore()
    }
}

/**
 * `RelativeLayout`: positions children by their relative rules (`layout_alignParent*`, `layout_center*`,
 * `layout_below`/`above`, `layout_toStartOf`/`toEndOf`/`toLeftOf`/`toRightOf`, `layout_align*`). Rules are
 * captured by [CommonAttrs] into `props.relativeRules`. Anchors are resolved by repeated passes over the
 * children (cheap; preview layouts are small), so forward references converge.
 */
object RelativeLayoutRenderer : Renderer {
    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val visible = node.children.filter { it.props.visibility != Props.GONE }
        for (c in visible) c.renderer.measure(c, containerChildSpec(widthSpec, node.props.hPadding, c.props.layoutWidth), containerChildSpec(heightSpec, node.props.vPadding, c.props.layoutHeight), ctx)

        val parentW = (MeasureSpec.getSize(widthSpec) - node.props.hPadding).coerceAtLeast(0)
        val parentH = (MeasureSpec.getSize(heightSpec) - node.props.vPadding).coerceAtLeast(0)
        place(visible, parentW, parentH)

        var contentW = 0; var contentH = 0
        for (c in visible) { contentW = max(contentW, c.right); contentH = max(contentH, c.bottom) }
        val w = resolveSize(node.props.layoutWidth, widthSpec, contentW + node.props.hPadding)
        val h = resolveSize(node.props.layoutHeight, heightSpec, contentH + node.props.vPadding)
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) {
        node.setBounds(l, t, r, b)
        val cl = l + node.props.paddingLeft; val ct = t + node.props.paddingTop
        for (c in node.children) {
            if (c.props.visibility == Props.GONE) continue
            // Local positions were computed in measure (relative to the content box); shift into place.
            val lx = c.left; val ly = c.top
            c.renderer.layout(c, cl + lx, ct + ly, cl + lx + c.measured.width, ct + ly + c.measured.height, ctx)
        }
    }

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        drawBackground(node, canvas, ctx)
        for (c in node.children) if (c.props.visibility != Props.GONE) c.renderer.draw(c, canvas, ctx)
    }

    /** Compute each child's content-box-local left/top (stored in node.left/top) from its rules. */
    private fun place(children: List<RenderNode>, parentW: Int, parentH: Int) {
        val byId = children.associateBy { it.props.id }
        val placed = HashSet<RenderNode>()
        for (pass in 0..children.size) {
            var progressed = false
            for (c in children) {
                if (c in placed) continue
                @Suppress("UNCHECKED_CAST")
                val rules = c.props.extras["relativeRules"] as? Map<String, String> ?: emptyMap()
                if (anchorsReady(rules, byId, placed)) {
                    positionChild(c, rules, byId, parentW, parentH)
                    placed.add(c); progressed = true
                }
            }
            if (!progressed) break
        }
        // Any unresolved (cyclic/missing anchor) child falls back to the top-left of the content box.
        for (c in children) if (c !in placed) c.setBounds(c.props.marginLeft, c.props.marginTop, c.props.marginLeft + c.measured.width, c.props.marginTop + c.measured.height)
    }

    private fun anchorsReady(rules: Map<String, String>, byId: Map<String?, RenderNode>, placed: Set<RenderNode>): Boolean =
        ANCHOR_RULES.all { rule -> rules[rule]?.let { byId[idOf(it)]?.let { a -> a in placed } ?: true } ?: true }

    private fun positionChild(c: RenderNode, rules: Map<String, String>, byId: Map<String?, RenderNode>, parentW: Int, parentH: Int) {
        val w = c.measured.width; val h = c.measured.height
        var x = c.props.marginLeft
        var y = c.props.marginTop

        fun anchor(rule: String): RenderNode? = rules[rule]?.let { byId[idOf(it)] }

        // Horizontal
        anchor("layout_toRightOf")?.let { x = it.right + c.props.marginLeft } ?: anchor("layout_toEndOf")?.let { x = it.right + c.props.marginLeft }
        anchor("layout_toLeftOf")?.let { x = it.left - w - c.props.marginRight } ?: anchor("layout_toStartOf")?.let { x = it.left - w - c.props.marginRight }
        (anchor("layout_alignLeft") ?: anchor("layout_alignStart"))?.let { x = it.left + c.props.marginLeft }
        (anchor("layout_alignRight") ?: anchor("layout_alignEnd"))?.let { x = it.right - w - c.props.marginRight }
        if (flag(rules, "layout_alignParentRight") || flag(rules, "layout_alignParentEnd")) x = parentW - w - c.props.marginRight
        if (flag(rules, "layout_alignParentLeft") || flag(rules, "layout_alignParentStart")) x = c.props.marginLeft
        if (flag(rules, "layout_centerHorizontal") || flag(rules, "layout_centerInParent")) x = (parentW - w) / 2

        // Vertical
        anchor("layout_below")?.let { y = it.bottom + c.props.marginTop }
        anchor("layout_above")?.let { y = it.top - h - c.props.marginBottom }
        anchor("layout_alignTop")?.let { y = it.top + c.props.marginTop }
        anchor("layout_alignBottom")?.let { y = it.bottom - h - c.props.marginBottom }
        if (flag(rules, "layout_alignParentBottom")) y = parentH - h - c.props.marginBottom
        if (flag(rules, "layout_alignParentTop")) y = c.props.marginTop
        if (flag(rules, "layout_centerVertical") || flag(rules, "layout_centerInParent")) y = (parentH - h) / 2

        c.setBounds(x, y, x + w, y + h)
    }

    private fun flag(rules: Map<String, String>, name: String) = rules[name]?.equals("true", ignoreCase = true) == true
    private fun idOf(ref: String) = ref.substringAfterLast('/').replace('.', '_')

    private val ANCHOR_RULES = listOf(
        "layout_toRightOf", "layout_toEndOf", "layout_toLeftOf", "layout_toStartOf",
        "layout_alignLeft", "layout_alignStart", "layout_alignRight", "layout_alignEnd",
        "layout_below", "layout_above", "layout_alignTop", "layout_alignBottom",
    )
}
