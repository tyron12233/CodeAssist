package dev.ide.preview.impl

import dev.ide.preview.AttrReader
import dev.ide.preview.MeasureSpec
import dev.ide.preview.PaintStyle
import dev.ide.preview.Props
import dev.ide.preview.RCanvas
import dev.ide.preview.RenderContext
import dev.ide.preview.RenderNode
import dev.ide.preview.Renderer
import dev.ide.preview.RendererRegistry
import dev.ide.preview.ResolvedValue
import dev.ide.preview.Size
import dev.ide.preview.ValueFormat
import kotlin.math.max
import kotlin.math.min

/** Paints the node's resolved background (a colour, or a `@drawable` shape/layer-list) over its bounds. */
internal fun drawBackground(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
    val l = node.left.toFloat(); val t = node.top.toFloat(); val r = node.right.toFloat(); val b = node.bottom.toFloat()
    node.props.backgroundColor?.let { bg ->
        canvas.drawRect(l, t, r, b, ctx.gfx.newPaint().apply { color = bg; style = PaintStyle.FILL })
        return
    }
    (node.props.extras["bgDrawable"] as? dev.ide.android.support.preview.DrawablePreview)?.let {
        DrawableBackgroundRenderer.draw(it, l, t, r, b, canvas, ctx)
    }
}

/**
 * Resolve a `layout_*` dimension against the parent spec into a concrete content size. An `EXACTLY` spec
 * always wins (the parent dictates — e.g. a `match_parent` cross axis, or a weighted `0dp` main axis the
 * LinearLayout forces during weight distribution), regardless of the requested layout dimension.
 */
internal fun resolveSize(layoutDim: Int, spec: Int, contentSize: Int): Int {
    val mode = MeasureSpec.getMode(spec)
    val size = MeasureSpec.getSize(spec)
    return when (layoutDim) {
        Props.MATCH_PARENT -> if (mode == MeasureSpec.UNSPECIFIED) contentSize else size
        Props.WRAP_CONTENT -> when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(contentSize, size)
            else -> contentSize
        }
        else -> if (mode == MeasureSpec.EXACTLY) size else layoutDim
    }
}

/** A child MeasureSpec from the parent spec, the parent's padding on that axis, and the child's layout dim. */
internal fun containerChildSpec(parentSpec: Int, padding: Int, layoutDim: Int): Int {
    val avail = max(0, MeasureSpec.getSize(parentSpec) - padding)
    return when (layoutDim) {
        Props.MATCH_PARENT -> MeasureSpec.makeMeasureSpec(avail, MeasureSpec.EXACTLY)
        Props.WRAP_CONTENT -> MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST)
        else -> MeasureSpec.makeMeasureSpec(min(layoutDim, avail), MeasureSpec.EXACTLY)
    }
}

/** A paint configured from a node's text props. */
private fun RenderNode.textPaint(ctx: RenderContext): dev.ide.preview.RPaint = ctx.gfx.newPaint().apply {
    color = props.textColor
    style = PaintStyle.FILL
    antiAlias = true
    bold = props.bold
    textSizePx = if (props.textSizePx > 0f) props.textSizePx else 14f * ctx.scaledDensity
}

/** `TextView` / `Button` / `EditText`: greedy word-wrapped text via the backend's text metrics. */
object TextRenderer : Renderer {
    override fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        val p = node.props
        // textAppearance first (lowest precedence); explicit/style attrs below override it.
        attrs.android("textAppearance")?.let { ta ->
            (ctx.res as? ProjectPreviewResources)?.styleItems(ta.substringAfterLast('/'))?.let { items ->
                items["android:textSize"]?.let { p.textSizePx = (ctx.res.resolve(it, ValueFormat.DIMENSION) as? ResolvedValue.Dimension)?.px ?: p.textSizePx }
                items["android:textColor"]?.let { c -> CommonAttrs.colorOf(c, ctx)?.let { p.textColor = it } }
                items["android:textStyle"]?.let { p.bold = it.contains("bold") }
            }
        }
        attrs.android("text")?.let { p.text = (ctx.res.resolve(it, ValueFormat.STRING) as? ResolvedValue.Str)?.text ?: it }
        attrs.android("textSize")?.let { p.textSizePx = (ctx.res.resolve(it, ValueFormat.DIMENSION) as? ResolvedValue.Dimension)?.px ?: p.textSizePx }
        attrs.android("textColor")?.let { c -> CommonAttrs.colorOf(c, ctx)?.let { p.textColor = it } }
        attrs.android("textStyle")?.let { p.bold = it.contains("bold") }
        attrs.android("hint")?.let { h -> if (p.text.isEmpty()) p.text = (ctx.res.resolve(h, ValueFormat.STRING) as? ResolvedValue.Str)?.text ?: h }
        if (attrs.android("singleLine") == "true") p.maxLines = 1
        attrs.android("maxLines")?.toIntOrNull()?.let { p.maxLines = it }
        attrs.android("ellipsize")?.let { p.ellipsize = it.isNotEmpty() && it != "none" }
    }

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val paint = node.textPaint(ctx)
        val avail = when (MeasureSpec.getMode(widthSpec)) {
            MeasureSpec.UNSPECIFIED -> Float.MAX_VALUE
            else -> (MeasureSpec.getSize(widthSpec) - node.props.hPadding).toFloat()
        }
        val lines = wrap(node, paint, avail, ctx)
        node.props.extras["lines"] = lines
        val fm = ctx.gfx.fontMetrics(paint)
        val widest = lines.maxOfOrNull { ctx.gfx.measureText(it, paint) } ?: 0f
        val contentW = widest.toInt() + node.props.hPadding
        val contentH = (lines.size * fm.lineHeight).toInt() + node.props.vPadding
        val w = resolveSize(node.props.layoutWidth, widthSpec, contentW)
        val h = resolveSize(node.props.layoutHeight, heightSpec, contentH)
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) = node.setBounds(l, t, r, b)

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        drawBackground(node, canvas, ctx)
        val paint = node.textPaint(ctx)
        val fm = ctx.gfx.fontMetrics(paint)
        @Suppress("UNCHECKED_CAST")
        val lines = node.props.extras["lines"] as? List<CharSequence>
            ?: wrap(node, paint, (node.width - node.props.hPadding).toFloat(), ctx)

        // android:gravity aligns the text block within the view's content box (default top-start).
        val g = node.props.contentGravity
        val availW = node.width - node.props.hPadding
        val availH = node.height - node.props.vPadding
        val blockH = (lines.size * fm.lineHeight).toInt()
        val baseX = node.left + node.props.paddingLeft
        var y = node.top + node.props.paddingTop + GravityUtil.vertical(g, availH, blockH, 0, 0).toFloat()
        for (line in lines) {
            val lineW = ctx.gfx.measureText(line, paint).toInt()
            val x = baseX + GravityUtil.horizontal(g, availW, lineW, 0, 0)
            canvas.drawText(line, x.toFloat(), y, paint)
            y += fm.lineHeight
        }
    }

    private fun wrap(node: RenderNode, paint: dev.ide.preview.RPaint, maxWidth: Float, ctx: RenderContext): List<CharSequence> {
        val text = node.props.text
        val maxLines = node.props.maxLines
        if (text.isEmpty()) return listOf("")
        val lines: List<CharSequence> = if (maxWidth <= 0f || maxWidth == Float.MAX_VALUE) {
            text.toString().split('\n')
        } else {
            val out = ArrayList<CharSequence>()
            for (paragraph in text.toString().split('\n')) {
                val words = paragraph.split(' ')
                var line = StringBuilder()
                for (w in words) {
                    val candidate = if (line.isEmpty()) w else "$line $w"
                    if (line.isEmpty() || ctx.gfx.measureText(candidate, paint) <= maxWidth) {
                        line = StringBuilder(candidate)
                    } else {
                        out.add(line.toString()); line = StringBuilder(w)
                    }
                }
                out.add(line.toString())
            }
            out
        }
        if (maxLines <= 0 || lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        if (node.props.ellipsize) kept[maxLines - 1] = ellipsizeLine(kept[maxLines - 1], paint, maxWidth, ctx)
        return kept
    }

    /** Trim [line] so `line…` fits in [maxWidth]. */
    private fun ellipsizeLine(line: CharSequence, paint: dev.ide.preview.RPaint, maxWidth: Float, ctx: RenderContext): CharSequence {
        if (maxWidth == Float.MAX_VALUE) return "$line…"
        var s = line.toString()
        while (s.isNotEmpty() && ctx.gfx.measureText("$s…", paint) > maxWidth) s = s.dropLast(1)
        return "$s…"
    }
}

/** `ImageView`: draws the resolved `src` drawable/bitmap into the content box honouring `scaleType`. */
object ImageRenderer : Renderer {
    override fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        node.props.imageRef = attrs.android("src") ?: attrs.app("srcCompat")
        node.props.extras["scaleType"] = attrs.android("scaleType") ?: "fitCenter"
        (attrs.android("tint") ?: attrs.app("tint"))?.let { t -> CommonAttrs.colorOf(t, ctx)?.let { node.props.extras["tint"] = it } }
    }

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val img = node.props.imageRef?.let { ctx.res.image(it) }
        val iw = (img?.width ?: (24 * ctx.density).toInt()) + node.props.hPadding
        val ih = (img?.height ?: (24 * ctx.density).toInt()) + node.props.vPadding
        val w = resolveSize(node.props.layoutWidth, widthSpec, iw)
        val h = resolveSize(node.props.layoutHeight, heightSpec, ih)
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) = node.setBounds(l, t, r, b)

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        drawBackground(node, canvas, ctx)
        val img = node.props.imageRef?.let { ctx.res.image(it) } ?: return
        val bl = (node.left + node.props.paddingLeft).toFloat()
        val bt = (node.top + node.props.paddingTop).toFloat()
        val bw = (node.width - node.props.hPadding).toFloat()
        val bh = (node.height - node.props.vPadding).toFloat()
        val scaleType = node.props.extras["scaleType"] as? String ?: "fitCenter"
        val (dl, dt, dr, db) = fit(scaleType, img.width.toFloat(), img.height.toFloat(), bl, bt, bw, bh)
        val tint = node.props.extras["tint"] as? Int
        val clip = scaleType == "centerCrop"
        if (clip) { canvas.save(); canvas.clipRect(bl, bt, bl + bw, bt + bh) }
        canvas.drawImage(img, dl, dt, dr, db, tint)
        if (clip) canvas.restore()
    }

    /** Destination rect (l,t,r,b) for the image in the box per [scaleType]. */
    private fun fit(scaleType: String, iw: Float, ih: Float, bl: Float, bt: Float, bw: Float, bh: Float): List<Float> {
        if (iw <= 0f || ih <= 0f) return listOf(bl, bt, bl + bw, bt + bh)
        fun centered(w: Float, h: Float) = listOf(bl + (bw - w) / 2f, bt + (bh - h) / 2f, bl + (bw + w) / 2f, bt + (bh + h) / 2f)
        return when (scaleType) {
            "fitXY" -> listOf(bl, bt, bl + bw, bt + bh)
            "center" -> centered(iw, ih)
            "centerCrop" -> (max(bw / iw, bh / ih)).let { centered(iw * it, ih * it) }
            "centerInside" -> (min(min(bw / iw, bh / ih), 1f)).let { centered(iw * it, ih * it) }
            else -> (min(bw / iw, bh / ih)).let { centered(iw * it, ih * it) } // fitCenter
        }
    }
}

/** `LinearLayout`: sequential placement along the orientation, distributing `layout_weight` over slack. */
object LinearLayoutRenderer : Renderer {
    override fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        node.props.orientation = if (attrs.android("orientation") == "vertical") Props.VERTICAL else Props.HORIZONTAL
    }

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val vertical = node.props.orientation == Props.VERTICAL
        val mainPad = if (vertical) node.props.vPadding else node.props.hPadding
        val crossPad = if (vertical) node.props.hPadding else node.props.vPadding
        val visible = node.children.filter { it.props.visibility != Props.GONE }

        var mainUsed = 0; var crossMax = 0; var totalWeight = 0f
        for (c in visible) {
            totalWeight += c.props.weight
            val s = c.renderer.measure(c, childSpec(widthSpec, node.props.hPadding, c.props.layoutWidth), childSpec(heightSpec, node.props.vPadding, c.props.layoutHeight), ctx)
            mainUsed += mainOf(s, vertical) + mainMargin(c, vertical)
            crossMax = max(crossMax, crossOf(s, vertical) + crossMargin(c, vertical))
        }

        // Distribute slack along the main axis to weighted children (re-measure each at its new main size).
        val mainSpec = if (vertical) heightSpec else widthSpec
        if (totalWeight > 0f && MeasureSpec.getMode(mainSpec) != MeasureSpec.UNSPECIFIED) {
            val slack = MeasureSpec.getSize(mainSpec) - mainPad - mainUsed
            if (slack != 0) {
                for (c in visible) if (c.props.weight > 0f) {
                    val share = (slack * (c.props.weight / totalWeight)).toInt()
                    val newMain = max(0, mainOf(c.measured, vertical) + share)
                    val crossSpec = childSpec(if (vertical) widthSpec else heightSpec, crossPad, if (vertical) c.props.layoutWidth else c.props.layoutHeight)
                    c.renderer.measure(c,
                        if (vertical) crossSpec else MeasureSpec.exactly(newMain),
                        if (vertical) MeasureSpec.exactly(newMain) else crossSpec, ctx)
                }
                mainUsed = MeasureSpec.getSize(mainSpec) - mainPad
                crossMax = max(crossMax, visible.maxOfOrNull { crossOf(it.measured, vertical) + crossMargin(it, vertical) } ?: 0)
            }
        }

        val contentMain = mainUsed + mainPad
        val contentCross = crossMax + crossPad
        val w = resolveSize(node.props.layoutWidth, widthSpec, if (vertical) contentCross else contentMain)
        val h = resolveSize(node.props.layoutHeight, heightSpec, if (vertical) contentMain else contentCross)
        return Size(w, h).also { node.measured = it }
    }

    private fun mainOf(s: Size, vertical: Boolean) = if (vertical) s.height else s.width
    private fun crossOf(s: Size, vertical: Boolean) = if (vertical) s.width else s.height
    private fun mainMargin(c: RenderNode, vertical: Boolean) = if (vertical) c.props.marginTop + c.props.marginBottom else c.props.marginLeft + c.props.marginRight
    private fun crossMargin(c: RenderNode, vertical: Boolean) = if (vertical) c.props.marginLeft + c.props.marginRight else c.props.marginTop + c.props.marginBottom

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) {
        node.setBounds(l, t, r, b)
        val vertical = node.props.orientation == Props.VERTICAL
        val visible = node.children.filter { it.props.visibility != Props.GONE }

        // The whole run is offset along the main axis by the container's gravity (center/end) over the slack.
        val mainAvail = if (vertical) node.height - node.props.vPadding else node.width - node.props.hPadding
        val usedMain = visible.sumOf { mainOf(it.measured, vertical) + mainMargin(it, vertical) }
        val mainStart = (if (vertical) t + node.props.paddingTop else l + node.props.paddingLeft) +
            mainGravityOffset(node.props.contentGravity, vertical, mainAvail, usedMain)
        val crossBase = if (vertical) l + node.props.paddingLeft else t + node.props.paddingTop
        val crossAvail = if (vertical) node.width - node.props.hPadding else node.height - node.props.vPadding

        var cursor = mainStart
        for (c in visible) {
            val g = GravityUtil.effective(c.props.gravity, node.props.contentGravity)
            if (vertical) {
                val cl = crossBase + GravityUtil.horizontal(g, crossAvail, c.measured.width, c.props.marginLeft, c.props.marginRight)
                val ct = cursor + c.props.marginTop
                c.renderer.layout(c, cl, ct, cl + c.measured.width, ct + c.measured.height, ctx)
                cursor = ct + c.measured.height + c.props.marginBottom
            } else {
                val cl = cursor + c.props.marginLeft
                val ct = crossBase + GravityUtil.vertical(g, crossAvail, c.measured.height, c.props.marginTop, c.props.marginBottom)
                c.renderer.layout(c, cl, ct, cl + c.measured.width, ct + c.measured.height, ctx)
                cursor = cl + c.measured.width + c.props.marginRight
            }
        }
    }

    /** Main-axis start shift for the whole child run, per the container's gravity over the leftover space. */
    private fun mainGravityOffset(gravity: Int, vertical: Boolean, available: Int, used: Int): Int {
        val slack = (available - used).coerceAtLeast(0)
        val centered = if (vertical) gravity and Props.GRAVITY_CENTER_VERTICAL != 0 else gravity and Props.GRAVITY_CENTER_HORIZONTAL != 0
        val end = if (vertical) gravity and Props.GRAVITY_BOTTOM != 0 else gravity and Props.GRAVITY_END != 0
        return when {
            centered -> slack / 2
            end -> slack
            else -> 0
        }
    }

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        drawBackground(node, canvas, ctx)
        for (c in node.children) if (c.props.visibility != Props.GONE) c.renderer.draw(c, canvas, ctx)
    }

    private fun childSpec(parentSpec: Int, padding: Int, layoutDim: Int): Int {
        val avail = max(0, MeasureSpec.getSize(parentSpec) - padding)
        return when (layoutDim) {
            Props.MATCH_PARENT -> MeasureSpec.makeMeasureSpec(avail, MeasureSpec.EXACTLY)
            Props.WRAP_CONTENT -> MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(layoutDim, MeasureSpec.EXACTLY)
        }
    }
}

/** `FrameLayout` (and the default container): children stacked at the top-left, sized to the largest. */
object FrameLayoutRenderer : Renderer {
    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        var cw = 0; var ch = 0
        for (c in node.children) {
            if (c.props.visibility == Props.GONE) continue
            val s = c.renderer.measure(c, childSpec(widthSpec, node.props.hPadding, c.props.layoutWidth), childSpec(heightSpec, node.props.vPadding, c.props.layoutHeight), ctx)
            cw = max(cw, s.width); ch = max(ch, s.height)
        }
        val w = resolveSize(node.props.layoutWidth, widthSpec, cw + node.props.hPadding)
        val h = resolveSize(node.props.layoutHeight, heightSpec, ch + node.props.vPadding)
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) {
        node.setBounds(l, t, r, b)
        val cl = l + node.props.paddingLeft; val ct = t + node.props.paddingTop
        val availW = node.width - node.props.hPadding
        val availH = node.height - node.props.vPadding
        for (c in node.children) {
            if (c.props.visibility == Props.GONE) continue
            val g = GravityUtil.effective(c.props.gravity, node.props.contentGravity)
            val x = cl + GravityUtil.horizontal(g, availW, c.measured.width, c.props.marginLeft, c.props.marginRight)
            val y = ct + GravityUtil.vertical(g, availH, c.measured.height, c.props.marginTop, c.props.marginBottom)
            c.renderer.layout(c, x, y, x + c.measured.width, y + c.measured.height, ctx)
        }
    }

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        drawBackground(node, canvas, ctx)
        for (c in node.children) if (c.props.visibility != Props.GONE) c.renderer.draw(c, canvas, ctx)
    }

    private fun childSpec(parentSpec: Int, padding: Int, layoutDim: Int): Int {
        val avail = max(0, MeasureSpec.getSize(parentSpec) - padding)
        return when (layoutDim) {
            Props.MATCH_PARENT -> MeasureSpec.makeMeasureSpec(avail, MeasureSpec.EXACTLY)
            Props.WRAP_CONTENT -> MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(min(layoutDim, avail), MeasureSpec.EXACTLY)
        }
    }
}

/** The built-in renderer set for the MVP: text family, image, linear + frame containers. */
object DefaultRenderers {
    fun registry(): RendererRegistry = RendererRegistry()
        .register(listOf("TextView", "EditText", "CheckBox", "RadioButton", "TextClock", "Switch"), TextRenderer)
        .register(listOf("ImageView", "ImageButton"), ImageRenderer)
        .register(listOf("LinearLayout"), LinearLayoutRenderer)
        .register(listOf("RelativeLayout"), RelativeLayoutRenderer)
        .register(listOf("ScrollView", "NestedScrollView"), ScrollRenderer(horizontal = false))
        .register(listOf("HorizontalScrollView"), ScrollRenderer(horizontal = true))
        .register(listOf("FrameLayout"), FrameLayoutRenderer)
        .withMaterial() // Button family, Material/AppCompat aliases, Card/FAB/Chip, ConstraintLayout
}
