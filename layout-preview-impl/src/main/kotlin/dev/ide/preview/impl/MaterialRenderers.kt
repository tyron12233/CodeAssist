package dev.ide.preview.impl

import dev.ide.preview.AttrReader
import dev.ide.preview.PaintStyle
import dev.ide.preview.RCanvas
import dev.ide.preview.RenderContext
import dev.ide.preview.RenderNode
import dev.ide.preview.Renderer
import dev.ide.preview.ResolvedValue
import dev.ide.preview.Size
import dev.ide.preview.ValueFormat
import kotlin.math.max
import kotlin.math.min

// Material-ish defaults used when a theme colour can't be resolved (the renderers are theme-free).
private const val M_PRIMARY = 0xFF6750A4.toInt()
private const val M_ON_PRIMARY = 0xFFFFFFFF.toInt()
private const val M_SURFACE_VARIANT = 0xFFE7E0EC.toInt()
private const val M_ON_SURFACE = 0xFF1D1B20.toInt()
private const val M_NEUTRAL = 0xFFE0E0E0.toInt()

/**
 * A button-family widget (`Button`, `MaterialButton`, `AppCompatButton`): a rounded filled pill with
 * single-line centred label, a 48dp min height, and default horizontal padding. [bgColor]/[fgColor] are the
 * fallback fill/label colours when the layout doesn't override `background`/`textColor`.
 */
class ButtonRenderer(private val bgColor: Int, private val fgColor: Int, private val cornerDp: Float = 8f) : Renderer {
    override fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        val p = node.props
        attrs.android("text")?.let { p.text = (ctx.res.resolve(it, ValueFormat.STRING) as? ResolvedValue.Str)?.text ?: it }
        attrs.android("textSize")?.let { p.textSizePx = (ctx.res.resolve(it, ValueFormat.DIMENSION) as? ResolvedValue.Dimension)?.px ?: p.textSizePx }
        attrs.android("textColor")?.let { c -> CommonAttrs.colorOf(c, ctx)?.let { p.textColor = it } ?: run { p.textColor = fgColor } } ?: run { p.textColor = fgColor }
    }

    private fun paint(node: RenderNode, ctx: RenderContext) = ctx.gfx.newPaint().apply {
        color = node.props.textColor; antiAlias = true; bold = true
        textSizePx = if (node.props.textSizePx > 0f) node.props.textSizePx else 14f * ctx.scaledDensity
    }

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val tp = paint(node, ctx)
        val tw = ctx.gfx.measureText(node.props.text, tp)
        val lh = ctx.gfx.fontMetrics(tp).lineHeight
        val padH = if (node.props.hPadding > 0) node.props.hPadding else (32 * ctx.density).toInt()
        val minH = (48 * ctx.density).toInt()
        val w = resolveSize(node.props.layoutWidth, widthSpec, tw.toInt() + padH)
        val h = resolveSize(node.props.layoutHeight, heightSpec, max(minH, lh.toInt() + node.props.vPadding))
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) = node.setBounds(l, t, r, b)

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        val l = node.left.toFloat(); val t = node.top.toFloat(); val rr = node.right.toFloat(); val bb = node.bottom.toFloat()
        val radius = cornerDp * ctx.density
        canvas.drawRoundRect(l, t, rr, bb, radius, radius, ctx.gfx.newPaint().apply { color = node.props.backgroundColor ?: bgColor; style = PaintStyle.FILL })
        val tp = paint(node, ctx)
        val tw = ctx.gfx.measureText(node.props.text, tp)
        val fm = ctx.gfx.fontMetrics(tp)
        canvas.drawText(node.props.text, l + ((rr - l) - tw) / 2f, t + ((bb - t) - fm.lineHeight) / 2f, tp)
    }
}

/** A `FloatingActionButton`: a filled accent circle (icon content not rendered). */
object FabRenderer : Renderer {
    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val d = (56 * ctx.density).toInt()
        return Size(resolveSize(node.props.layoutWidth, widthSpec, d), resolveSize(node.props.layoutHeight, heightSpec, d)).also { node.measured = it }
    }
    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) = node.setBounds(l, t, r, b)
    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        val cx = (node.left + node.right) / 2f; val cy = (node.top + node.bottom) / 2f
        val radius = min(node.width, node.height) / 2f
        canvas.drawCircle(cx, cy, radius, ctx.gfx.newPaint().apply { color = node.props.backgroundColor ?: M_PRIMARY; style = PaintStyle.FILL })
    }
}

/** A `Chip`: a pill with a single-line label. */
class ChipRenderer(private val bgColor: Int = M_SURFACE_VARIANT, private val fgColor: Int = M_ON_SURFACE) : Renderer {
    override fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        attrs.android("text")?.let { node.props.text = (ctx.res.resolve(it, ValueFormat.STRING) as? ResolvedValue.Str)?.text ?: it }
        node.props.textColor = fgColor
    }
    private fun paint(node: RenderNode, ctx: RenderContext) = ctx.gfx.newPaint().apply {
        color = node.props.textColor; antiAlias = true; textSizePx = if (node.props.textSizePx > 0f) node.props.textSizePx else 13f * ctx.scaledDensity
    }
    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val tp = paint(node, ctx)
        val tw = ctx.gfx.measureText(node.props.text, tp).toInt()
        val w = resolveSize(node.props.layoutWidth, widthSpec, tw + (24 * ctx.density).toInt())
        val h = resolveSize(node.props.layoutHeight, heightSpec, (32 * ctx.density).toInt())
        return Size(w, h).also { node.measured = it }
    }
    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) = node.setBounds(l, t, r, b)
    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        val l = node.left.toFloat(); val t = node.top.toFloat(); val rr = node.right.toFloat(); val bb = node.bottom.toFloat()
        val radius = node.height / 2f
        canvas.drawRoundRect(l, t, rr, bb, radius, radius, ctx.gfx.newPaint().apply { color = node.props.backgroundColor ?: bgColor; style = PaintStyle.FILL })
        val tp = paint(node, ctx)
        val tw = ctx.gfx.measureText(node.props.text, tp)
        val fm = ctx.gfx.fontMetrics(tp)
        canvas.drawText(node.props.text, l + ((rr - l) - tw) / 2f, t + ((bb - t) - fm.lineHeight) / 2f, tp)
    }
}

/** A `CardView`/`MaterialCardView`: a rounded surface holding stacked children (Frame layout + card chrome). */
object CardRenderer : Renderer {
    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size =
        FrameLayoutRenderer.measure(node, widthSpec, heightSpec, ctx)
    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) =
        FrameLayoutRenderer.layout(node, l, t, r, b, ctx)
    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        val l = node.left.toFloat(); val t = node.top.toFloat(); val rr = node.right.toFloat(); val bb = node.bottom.toFloat()
        val radius = 12f * ctx.density
        canvas.drawRoundRect(l, t, rr, bb, radius, radius, ctx.gfx.newPaint().apply { color = node.props.backgroundColor ?: 0xFFFFFFFF.toInt(); style = PaintStyle.FILL })
        for (c in node.children) if (c.props.visibility != dev.ide.preview.Props.GONE) c.renderer.draw(c, canvas, ctx)
    }
}

/** Registers Material + AppCompat tag aliases onto an existing registry (resolved by simple name). */
fun dev.ide.preview.RendererRegistry.withMaterial(): dev.ide.preview.RendererRegistry = this
    .register(listOf("Button", "AppCompatButton"), ButtonRenderer(M_NEUTRAL, M_ON_SURFACE))
    .register(listOf("MaterialButton"), ButtonRenderer(M_PRIMARY, M_ON_PRIMARY))
    .register(listOf("AppCompatTextView", "MaterialTextView", "TextInputEditText", "TextInputLayout", "AppCompatEditText", "AppCompatCheckBox", "MaterialCheckBox", "SwitchMaterial", "MaterialRadioButton"), TextRenderer)
    .register(listOf("AppCompatImageView", "AppCompatImageButton", "ShapeableImageView"), ImageRenderer)
    .register(listOf("CardView", "MaterialCardView"), CardRenderer)
    .register(listOf("FloatingActionButton", "ExtendedFloatingActionButton"), FabRenderer)
    .register(listOf("Chip"), ChipRenderer())
    .register(listOf("ConstraintLayout"), ConstraintLayoutRenderer)
    .register(listOf("CoordinatorLayout"), CoordinatorLayoutRenderer)
    .register(listOf("AppBarLayout"), AppBarLayoutRenderer)
    .register(listOf("Toolbar", "MaterialToolbar", "ActionBar"), ToolbarRenderer)
    .register(listOf("BottomNavigationView", "BottomAppBar", "NavigationRailView"), BottomNavRenderer)
    // List/pager containers: their items come from an adapter at runtime, so they preview as an empty box
    // (a registered renderer, NOT the custom-view path — they aren't the user's class and need no compile/dex).
    .register(listOf("RecyclerView", "ViewPager", "ViewPager2", "ListView", "GridView", "ExpandableListView"), FrameLayoutRenderer)
