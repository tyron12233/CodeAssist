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
 * A pragmatic `ConstraintLayout`: resolves the common `app:layout_constraint*` edges (left/right/start/end,
 * top/bottom, baseline≈top) against `parent` or sibling ids, with bias-based centring when both opposing
 * edges are constrained and `0dp` (match-constraint) widths/heights filling the span between them. Not the
 * full Cassowary solver — no chains, ratios, guidelines, or barriers — but covers the bulk of real layouts.
 * Children are placed by repeated passes (anchors resolve as their targets settle).
 */
object ConstraintLayoutRenderer : Renderer {

    private class Bounds(var left: Int, var top: Int, var right: Int, var bottom: Int)

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val visible = node.children.filter { it.props.visibility != Props.GONE }
        for (c in visible) {
            // 0dp (match-constraint) measures at 0 initially; resolved to the span between its edges below.
            val wSpec = if (c.props.layoutWidth == 0) MeasureSpec.exactly(0) else containerChildSpec(widthSpec, node.props.hPadding, c.props.layoutWidth)
            val hSpec = if (c.props.layoutHeight == 0) MeasureSpec.exactly(0) else containerChildSpec(heightSpec, node.props.vPadding, c.props.layoutHeight)
            c.renderer.measure(c, wSpec, hSpec, ctx)
        }
        val parentW = (MeasureSpec.getSize(widthSpec) - node.props.hPadding).coerceAtLeast(0)
        val parentH = (MeasureSpec.getSize(heightSpec) - node.props.vPadding).coerceAtLeast(0)
        place(visible, parentW, parentH, ctx)

        var cw = 0; var ch = 0
        for (c in visible) { cw = max(cw, c.right); ch = max(ch, c.bottom) }
        val w = resolveSize(node.props.layoutWidth, widthSpec, cw + node.props.hPadding)
        val h = resolveSize(node.props.layoutHeight, heightSpec, ch + node.props.vPadding)
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) {
        node.setBounds(l, t, r, b)
        val cl = l + node.props.paddingLeft; val ct = t + node.props.paddingTop
        for (c in node.children) {
            if (c.props.visibility == Props.GONE) continue
            c.renderer.layout(c, cl + c.left, ct + c.top, cl + c.left + c.measured.width, ct + c.top + c.measured.height, ctx)
        }
    }

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        drawBackground(node, canvas, ctx)
        for (c in node.children) if (c.props.visibility != Props.GONE) c.renderer.draw(c, canvas, ctx)
    }

    private fun place(children: List<RenderNode>, parentW: Int, parentH: Int, ctx: RenderContext) {
        val byId = children.associateBy { it.props.id }
        val placed = HashSet<RenderNode>()
        val parent = Bounds(0, 0, parentW, parentH)

        fun boundsOf(target: String?): Bounds? {
            if (target == null) return null
            if (target == "parent") return parent
            val a = byId[target.substringAfterLast('/').replace('.', '_')] ?: return null
            return if (a in placed) Bounds(a.left, a.top, a.right, a.bottom) else null
        }

        for (pass in 0..children.size) {
            var progressed = false
            for (c in children) {
                if (c in placed) continue
                @Suppress("UNCHECKED_CAST")
                val cons = c.props.extras["constraints"] as? Map<String, String> ?: emptyMap()
                if (cons.isEmpty()) { c.setBounds(c.props.marginLeft, c.props.marginTop, c.props.marginLeft + c.measured.width, c.props.marginTop + c.measured.height); placed.add(c); progressed = true; continue }
                if (ready(cons, byId, placed)) {
                    positionChild(c, cons, ::boundsOf, parentW, parentH)
                    placed.add(c); progressed = true
                }
            }
            if (!progressed) break
        }
        // Unresolved children (cyclic/missing anchors) pin to the top-left.
        for (c in children) if (c !in placed) c.setBounds(c.props.marginLeft, c.props.marginTop, c.props.marginLeft + c.measured.width, c.props.marginTop + c.measured.height)
    }

    private fun ready(cons: Map<String, String>, byId: Map<String?, RenderNode>, placed: Set<RenderNode>): Boolean =
        cons.filterKeys { it.endsWith("Of") }.values.all { target ->
            target == "parent" || byId[target.substringAfterLast('/').replace('.', '_')]?.let { it in placed } ?: true
        }

    private fun positionChild(c: RenderNode, cons: Map<String, String>, bounds: (String?) -> Bounds?, parentW: Int, parentH: Int) {
        val w0 = c.measured.width; val h0 = c.measured.height

        val startToStart = bounds(cons["layout_constraintStart_toStartOf"] ?: cons["layout_constraintLeft_toLeftOf"])
        val startToEnd = bounds(cons["layout_constraintStart_toEndOf"] ?: cons["layout_constraintLeft_toRightOf"])
        val endToEnd = bounds(cons["layout_constraintEnd_toEndOf"] ?: cons["layout_constraintRight_toRightOf"])
        val endToStart = bounds(cons["layout_constraintEnd_toStartOf"] ?: cons["layout_constraintRight_toLeftOf"])
        val leftPos = startToStart?.let { it.left + c.props.marginLeft } ?: startToEnd?.let { it.right + c.props.marginLeft }
        val rightPos = endToEnd?.let { it.right - c.props.marginRight } ?: endToStart?.let { it.left - c.props.marginRight }
        val biasH = cons["layout_constraintHorizontal_bias"]?.toFloatOrNull() ?: 0.5f

        var x = c.props.marginLeft; var w = w0
        when {
            leftPos != null && rightPos != null ->
                if (c.props.layoutWidth == 0) { x = leftPos; w = max(0, rightPos - leftPos) }
                else { x = leftPos + (((rightPos - leftPos) - w0) * biasH).toInt() }
            leftPos != null -> x = leftPos
            rightPos != null -> x = rightPos - w0
        }

        val topToTop = bounds(cons["layout_constraintTop_toTopOf"]) ?: bounds(cons["layout_constraintBaseline_toBaselineOf"])
        val topToBottom = bounds(cons["layout_constraintTop_toBottomOf"])
        val bottomToBottom = bounds(cons["layout_constraintBottom_toBottomOf"])
        val bottomToTop = bounds(cons["layout_constraintBottom_toTopOf"])
        val topPos = topToTop?.let { it.top + c.props.marginTop } ?: topToBottom?.let { it.bottom + c.props.marginTop }
        val botPos = bottomToBottom?.let { it.bottom - c.props.marginBottom } ?: bottomToTop?.let { it.top - c.props.marginBottom }
        val biasV = cons["layout_constraintVertical_bias"]?.toFloatOrNull() ?: 0.5f

        var y = c.props.marginTop; var h = h0
        when {
            topPos != null && botPos != null ->
                if (c.props.layoutHeight == 0) { y = topPos; h = max(0, botPos - topPos) }
                else { y = topPos + (((botPos - topPos) - h0) * biasV).toInt() }
            topPos != null -> y = topPos
            botPos != null -> y = botPos - h0
        }

        if (w != w0 || h != h0) c.measured = Size(w, h)
        c.setBounds(x, y, x + w, y + h)
    }
}
