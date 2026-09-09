package dev.ide.preview.impl

import dev.ide.preview.AttrReader
import dev.ide.preview.MeasureSpec
import dev.ide.preview.PaintStyle
import dev.ide.preview.Props
import dev.ide.preview.RCanvas
import dev.ide.preview.RenderContext
import dev.ide.preview.RenderNode
import dev.ide.preview.Renderer
import dev.ide.preview.ResolvedValue
import dev.ide.preview.Size
import dev.ide.preview.ValueFormat
import kotlin.math.max

// Material baseline colours used only as the very last fallback (theme resolution is tried first).
private const val MC_PRIMARY = 0xFF6200EE.toInt()
private const val MC_ON_PRIMARY = 0xFFFFFFFF.toInt()
private const val MC_SURFACE = 0xFFFFFFFF.toInt()
private const val MC_ON_SURFACE = 0xFF1D1B20.toInt()
private const val MC_ON_SURFACE_VARIANT = 0xFF757575.toInt()
private const val MC_DIVIDER = 0x1F000000

/** Resolve a `?attr/<name>` theme colour to ARGB, falling back to [default] when the theme can't supply it. */
internal fun RenderContext.themeColor(attr: String, default: Int): Int =
    (res.resolve("?attr/$attr", ValueFormat.COLOR) as? ResolvedValue.Color)?.argb ?: default

/** Resolve a `?attr/<name>` theme dimension to px, falling back to [defaultDp] dp at the previewed density. */
internal fun RenderContext.themeDimenPx(attr: String, defaultDp: Float): Int =
    (res.resolve("?attr/$attr", ValueFormat.DIMENSION) as? ResolvedValue.Dimension)?.px?.toInt()
        ?: (defaultDp * density).toInt()

/**
 * A `Toolbar`/`MaterialToolbar`/`ActionBar`: a `colorPrimary` bar (its conventional `actionBarSize` height
 * when not given an explicit one) showing the `app:title`/`android:title` start-aligned and vertically
 * centred. Any declared child views are placed in a start-aligned row (logos, action items) and, when
 * present, take the place of the title text.
 */
object ToolbarRenderer : Renderer {
    override fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        node.props.textColor = ctx.themeColor("colorOnPrimary", MC_ON_PRIMARY)
        (attrs.app("title") ?: attrs.android("title"))?.let {
            node.props.text = (ctx.res.resolve(it, ValueFormat.STRING) as? ResolvedValue.Str)?.text ?: it
        }
        (attrs.app("titleTextColor") ?: attrs.android("titleTextColor"))?.let { c ->
            CommonAttrs.colorOf(c, ctx)?.let { node.props.textColor = it }
        }
    }

    private fun titlePaint(node: RenderNode, ctx: RenderContext) = ctx.gfx.newPaint().apply {
        color = node.props.textColor; antiAlias = true; bold = true
        textSizePx = if (node.props.textSizePx > 0f) node.props.textSizePx else 20f * ctx.scaledDensity
    }

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val minH = ctx.themeDimenPx("actionBarSize", 56f)
        var cw = 0; var ch = 0
        for (c in node.children) {
            if (c.props.visibility == Props.GONE) continue
            val s = c.renderer.measure(c, containerChildSpec(widthSpec, node.props.hPadding, c.props.layoutWidth), containerChildSpec(heightSpec, node.props.vPadding, c.props.layoutHeight), ctx)
            cw += s.width + c.props.marginLeft + c.props.marginRight; ch = max(ch, s.height)
        }
        val w = resolveSize(node.props.layoutWidth, widthSpec, cw + node.props.hPadding)
        val h = resolveSize(node.props.layoutHeight, heightSpec, max(minH, ch + node.props.vPadding))
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) {
        node.setBounds(l, t, r, b)
        val availH = node.height - node.props.vPadding
        var x = l + node.props.paddingLeft
        for (c in node.children) {
            if (c.props.visibility == Props.GONE) continue
            val cy = t + node.props.paddingTop + (availH - c.measured.height) / 2
            val cl = x + c.props.marginLeft
            c.renderer.layout(c, cl, cy, cl + c.measured.width, cy + c.measured.height, ctx)
            x = cl + c.measured.width + c.props.marginRight
        }
    }

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        val bg = node.props.backgroundColor ?: ctx.themeColor("colorPrimary", MC_PRIMARY)
        canvas.drawRect(node.left.toFloat(), node.top.toFloat(), node.right.toFloat(), node.bottom.toFloat(),
            ctx.gfx.newPaint().apply { color = bg; style = PaintStyle.FILL })
        val hasChildren = node.children.any { it.props.visibility != Props.GONE }
        if (!hasChildren && node.props.text.isNotEmpty()) {
            val tp = titlePaint(node, ctx)
            val fm = ctx.gfx.fontMetrics(tp)
            val startPad = max(node.props.paddingLeft, (16 * ctx.density).toInt())
            canvas.drawText(node.props.text, (node.left + startPad).toFloat(), node.top + (node.height - fm.lineHeight) / 2f, tp)
        }
        for (c in node.children) if (c.props.visibility != Props.GONE) c.renderer.draw(c, canvas, ctx)
    }
}

/** An `AppBarLayout`: a vertical container (its children — usually a toolbar — stack top-to-bottom). */
object AppBarLayoutRenderer : Renderer {
    override fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        node.props.orientation = Props.VERTICAL
    }
    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size =
        LinearLayoutRenderer.measure(node, widthSpec, heightSpec, ctx)
    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) =
        LinearLayoutRenderer.layout(node, l, t, r, b, ctx)
    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) =
        LinearLayoutRenderer.draw(node, canvas, ctx)
}

/**
 * A `BottomNavigationView`/`BottomAppBar`/`NavigationRailView`: a `colorSurface` bar (its conventional 56dp
 * height when not given an explicit one) with a hairline top divider. When `app:menu` resolves, the menu's
 * item titles are drawn as evenly-spaced labels with a small icon dot (the first item shown selected).
 */
object BottomNavRenderer : Renderer {
    override fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        attrs.app("menu")?.let { node.props.extras["menuRef"] = it }
    }

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val defaultH = (56 * ctx.density).toInt()
        val w = resolveSize(node.props.layoutWidth, widthSpec, MeasureSpec.getSize(widthSpec))
        val h = resolveSize(node.props.layoutHeight, heightSpec, defaultH)
        return Size(w, h).also { node.measured = it }
    }

    override fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext) = node.setBounds(l, t, r, b)

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        val l = node.left.toFloat(); val t = node.top.toFloat(); val r = node.right.toFloat(); val bb = node.bottom.toFloat()
        val bg = node.props.backgroundColor ?: ctx.themeColor("colorSurface", MC_SURFACE)
        canvas.drawRect(l, t, r, bb, ctx.gfx.newPaint().apply { color = bg; style = PaintStyle.FILL })
        canvas.drawLine(l, t, r, t, ctx.gfx.newPaint().apply { color = MC_DIVIDER; strokeWidth = ctx.density; style = PaintStyle.STROKE })

        val titles = (node.props.extras["menuRef"] as? String)
            ?.let { (ctx.res as? ProjectPreviewResources)?.menuTitles(it) }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        if (titles.isEmpty()) return

        val labelPaint = ctx.gfx.newPaint().apply { antiAlias = true; textSizePx = 12f * ctx.scaledDensity }
        val fm = ctx.gfx.fontMetrics(labelPaint)
        val colW = node.width / titles.size
        val iconR = 4f * ctx.density
        val activeColor = ctx.themeColor("colorPrimary", MC_PRIMARY)
        val inactiveColor = ctx.themeColor("colorOnSurfaceVariant", MC_ON_SURFACE_VARIANT)
        for (i in titles.indices) {
            val tint = if (i == 0) activeColor else inactiveColor
            val cx = node.left + colW * i + colW / 2f
            canvas.drawCircle(cx, t + node.height * 0.32f, iconR, ctx.gfx.newPaint().apply { color = tint; style = PaintStyle.FILL })
            labelPaint.color = tint
            val tw = ctx.gfx.measureText(titles[i], labelPaint)
            canvas.drawText(titles[i], cx - tw / 2f, t + node.height * 0.55f, labelPaint)
        }
    }
}

/**
 * A pragmatic `CoordinatorLayout`: app-bar children (`AppBarLayout`) pin to the top, and a child with a
 * scrolling `app:layout_behavior` (the Material `appbar_scrolling_view_behavior`) is offset to start below
 * the app bar and measured against the remaining height — so the content doesn't sit under the toolbar.
 * Everything else behaves like a `FrameLayout` (stacked, gravity-positioned). No nested-scroll collapse.
 */
object CoordinatorLayoutRenderer : Renderer {

    private fun isAppBar(c: RenderNode): Boolean =
        c.renderer === AppBarLayoutRenderer || c.tag?.substringAfterLast('.') == "AppBarLayout"

    private fun scrollsUnderAppBar(c: RenderNode): Boolean {
        val behavior = c.props.extras["layoutBehavior"] as? String ?: return false
        return behavior.contains("scrolling_view_behavior", ignoreCase = true) ||
            behavior.contains("ScrollingViewBehavior") ||
            behavior.contains("appbar_scrolling", ignoreCase = true)
    }

    override fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size {
        val visible = node.children.filter { it.props.visibility != Props.GONE }
        var appBarH = 0
        for (c in visible) if (isAppBar(c)) {
            c.renderer.measure(c, containerChildSpec(widthSpec, node.props.hPadding, c.props.layoutWidth), containerChildSpec(heightSpec, node.props.vPadding, c.props.layoutHeight), ctx)
            appBarH = max(appBarH, c.measured.height + c.props.marginTop + c.props.marginBottom)
        }
        for (c in visible) if (!isAppBar(c)) {
            val reduce = if (scrollsUnderAppBar(c)) appBarH else 0
            val hAvail = MeasureSpec.makeMeasureSpec(max(0, MeasureSpec.getSize(heightSpec) - reduce), MeasureSpec.getMode(heightSpec))
            c.renderer.measure(c, containerChildSpec(widthSpec, node.props.hPadding, c.props.layoutWidth), containerChildSpec(hAvail, node.props.vPadding, c.props.layoutHeight), ctx)
        }
        var cw = 0; var ch = 0
        for (c in visible) {
            cw = max(cw, c.measured.width)
            ch = max(ch, c.measured.height + (if (!isAppBar(c) && scrollsUnderAppBar(c)) appBarH else 0))
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
        var appBarBottom = ct
        for (c in node.children) {
            if (c.props.visibility == Props.GONE || !isAppBar(c)) continue
            val g = GravityUtil.effective(c.props.gravity, node.props.contentGravity)
            val x = cl + GravityUtil.horizontal(g, availW, c.measured.width, c.props.marginLeft, c.props.marginRight)
            val y = ct + c.props.marginTop
            c.renderer.layout(c, x, y, x + c.measured.width, y + c.measured.height, ctx)
            appBarBottom = max(appBarBottom, c.bottom)
        }
        for (c in node.children) {
            if (c.props.visibility == Props.GONE || isAppBar(c)) continue
            val g = GravityUtil.effective(c.props.gravity, node.props.contentGravity)
            val x = cl + GravityUtil.horizontal(g, availW, c.measured.width, c.props.marginLeft, c.props.marginRight)
            val y = if (scrollsUnderAppBar(c)) appBarBottom + c.props.marginTop
            else ct + GravityUtil.vertical(g, availH, c.measured.height, c.props.marginTop, c.props.marginBottom)
            c.renderer.layout(c, x, y, x + c.measured.width, y + c.measured.height, ctx)
        }
    }

    override fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext) {
        drawBackground(node, canvas, ctx)
        for (c in node.children) if (c.props.visibility != Props.GONE) c.renderer.draw(c, canvas, ctx)
    }
}
