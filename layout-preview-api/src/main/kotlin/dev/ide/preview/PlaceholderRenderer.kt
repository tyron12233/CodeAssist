package dev.ide.preview

/**
 * The never-fail fallback: a translucent box outlined and labelled with the widget tag. Any unknown tag or a
 * renderer that threw degrades to this for that one node, so a single bad widget can't blank the whole
 * preview (§12, "always fall back, never crash the tree"). It honours an explicit layout size and otherwise
 * takes a small default footprint.
 */
object PlaceholderRenderer : Renderer {
    private const val DEFAULT_DP = 64

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val def = (DEFAULT_DP * ctx.density).toInt()
        val w = resolveDim(node.props.layoutWidth, widthSpec, def)
        val h = resolveDim(node.props.layoutHeight, heightSpec, def)
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) {
        node.setBounds(l, t, r, b)
    }

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        val l = node.left.toFloat(); val t = node.top.toFloat()
        val rr = node.right.toFloat(); val bb = node.bottom.toFloat()
        val fill = ctx.gfx.newPaint().apply { color = 0x22808080; style = PaintStyle.FILL }
        canvas.drawRect(l, t, rr, bb, fill)
        val stroke = ctx.gfx.newPaint().apply { color = 0x66808080; style = PaintStyle.STROKE; strokeWidth = ctx.density }
        canvas.drawRect(l, t, rr, bb, stroke)

        val label = node.tag.substringAfterLast('.').ifEmpty { "view" }
        val textPaint = ctx.gfx.newPaint().apply {
            color = 0xFF606060.toInt(); style = PaintStyle.FILL; textSizePx = 11f * ctx.scaledDensity; antiAlias = true
        }
        val tw = ctx.gfx.measureText(label, textPaint)
        val fm = ctx.gfx.fontMetrics(textPaint)
        val tx = l + ((rr - l) - tw) / 2f
        val ty = t + ((bb - t) - fm.lineHeight) / 2f
        if (rr - l > tw) canvas.drawText(label, tx, ty, textPaint)
    }

    /** A WRAP/MATCH/explicit layout dim resolved against the parent spec, with a fallback default. */
    private fun resolveDim(layoutDim: Int, spec: Int, default: Int): Int = when (layoutDim) {
        Props.MATCH_PARENT -> if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) default else MeasureSpec.getSize(spec)
        Props.WRAP_CONTENT -> default
        else -> layoutDim
    }
}
