package dev.ide.preview.impl

import dev.ide.preview.AttrReader
import dev.ide.preview.Props
import dev.ide.preview.RenderContext
import dev.ide.preview.RenderNode
import dev.ide.preview.ResolvedValue
import dev.ide.preview.ValueFormat

/**
 * Maps the standard `View` attributes (`id`, `layout_*`, padding, margins, background, visibility) onto a
 * node's [Props]. Applied by the inflater to *every* node — built-in or custom — so the owned base honours
 * them even when a custom view never reads them itself (§7). Widget-specific attributes are added by the
 * individual renderer's `applyAttrs`.
 */
internal object CommonAttrs {

    fun apply(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {
        val p = node.props
        attrs.android("id")?.let { p.id = it.substringAfterLast('/') }

        p.layoutWidth = layoutDim(attrs.android("layout_width"), ctx)
        p.layoutHeight = layoutDim(attrs.android("layout_height"), ctx)
        attrs.android("layout_weight")?.toFloatOrNull()?.let { p.weight = it }
        attrs.android("layout_gravity")?.let { p.gravity = parseGravity(it) }
        attrs.android("gravity")?.let { p.contentGravity = parseGravity(it) }

        // Padding: the all-edges value first, then per-edge overrides.
        dimenPx(attrs.android("padding"), ctx)?.let { p.paddingLeft = it; p.paddingTop = it; p.paddingRight = it; p.paddingBottom = it }
        dimenPx(attrs.android("paddingLeft"), ctx)?.let { p.paddingLeft = it }
        dimenPx(attrs.android("paddingTop"), ctx)?.let { p.paddingTop = it }
        dimenPx(attrs.android("paddingRight"), ctx)?.let { p.paddingRight = it }
        dimenPx(attrs.android("paddingBottom"), ctx)?.let { p.paddingBottom = it }
        dimenPx(attrs.android("paddingStart"), ctx)?.let { p.paddingLeft = it }
        dimenPx(attrs.android("paddingEnd"), ctx)?.let { p.paddingRight = it }

        dimenPx(attrs.android("layout_margin"), ctx)?.let { p.marginLeft = it; p.marginTop = it; p.marginRight = it; p.marginBottom = it }
        dimenPx(attrs.android("layout_marginLeft"), ctx)?.let { p.marginLeft = it }
        dimenPx(attrs.android("layout_marginTop"), ctx)?.let { p.marginTop = it }
        dimenPx(attrs.android("layout_marginRight"), ctx)?.let { p.marginRight = it }
        dimenPx(attrs.android("layout_marginBottom"), ctx)?.let { p.marginBottom = it }
        dimenPx(attrs.android("layout_marginStart"), ctx)?.let { p.marginLeft = it }
        dimenPx(attrs.android("layout_marginEnd"), ctx)?.let { p.marginRight = it }

        attrs.android("background")?.let { bg ->
            colorOf(bg, ctx)?.let { p.backgroundColor = it }
                ?: (ctx.res as? ProjectPreviewResources)?.backgroundDrawable(bg)?.let { p.extras["bgDrawable"] = it }
        }
        attrs.android("visibility")?.let {
            p.visibility = when (it) { "gone" -> Props.GONE; "invisible" -> Props.INVISIBLE; else -> Props.VISIBLE }
        }

        // RelativeLayout child rules — captured here (every node) for RelativeLayoutRenderer to consume.
        var rules: HashMap<String, String>? = null
        for (rule in RELATIVE_RULES) attrs.android(rule)?.let {
            (rules ?: HashMap<String, String>().also { m -> rules = m })[rule] = it
        }
        rules?.let { p.extras["relativeRules"] = it }

        // ConstraintLayout child constraints (app:-namespaced) — captured for ConstraintLayoutRenderer.
        var cons: HashMap<String, String>? = null
        for (c in CONSTRAINT_ATTRS) attrs.app(c)?.let {
            (cons ?: HashMap<String, String>().also { m -> cons = m })[c] = it
        }
        cons?.let { p.extras["constraints"] = it }

        // CoordinatorLayout child behaviour (e.g. `@string/appbar_scrolling_view_behavior`) — the content that
        // scrolls under the app bar. Captured for CoordinatorLayoutRenderer to offset below the app bar.
        attrs.app("layout_behavior")?.let { p.extras["layoutBehavior"] = it }
    }

    private val CONSTRAINT_ATTRS = listOf(
        "layout_constraintLeft_toLeftOf", "layout_constraintLeft_toRightOf",
        "layout_constraintRight_toLeftOf", "layout_constraintRight_toRightOf",
        "layout_constraintStart_toStartOf", "layout_constraintStart_toEndOf",
        "layout_constraintEnd_toStartOf", "layout_constraintEnd_toEndOf",
        "layout_constraintTop_toTopOf", "layout_constraintTop_toBottomOf",
        "layout_constraintBottom_toTopOf", "layout_constraintBottom_toBottomOf",
        "layout_constraintBaseline_toBaselineOf",
        "layout_constraintHorizontal_bias", "layout_constraintVertical_bias",
    )

    private val RELATIVE_RULES = listOf(
        "layout_toRightOf", "layout_toLeftOf", "layout_toStartOf", "layout_toEndOf",
        "layout_above", "layout_below", "layout_alignTop", "layout_alignBottom",
        "layout_alignLeft", "layout_alignRight", "layout_alignStart", "layout_alignEnd",
        "layout_alignParentTop", "layout_alignParentBottom", "layout_alignParentLeft",
        "layout_alignParentRight", "layout_alignParentStart", "layout_alignParentEnd",
        "layout_centerInParent", "layout_centerHorizontal", "layout_centerVertical",
    )

    private fun layoutDim(raw: String?, ctx: RenderContext): Int = when (raw) {
        null -> Props.WRAP_CONTENT
        "match_parent", "fill_parent" -> Props.MATCH_PARENT
        "wrap_content" -> Props.WRAP_CONTENT
        else -> dimenPx(raw, ctx) ?: Props.WRAP_CONTENT
    }

    fun dimenPx(raw: String?, ctx: RenderContext): Int? =
        raw?.let { (ctx.res.resolve(it, ValueFormat.DIMENSION) as? ResolvedValue.Dimension)?.px?.toInt() }

    fun colorOf(raw: String?, ctx: RenderContext): Int? =
        raw?.let { (ctx.res.resolve(it, ValueFormat.COLOR) as? ResolvedValue.Color)?.argb }

    fun parseGravity(raw: String): Int = raw.split('|').fold(0) { acc, token ->
        acc or when (token.trim()) {
            "start", "left" -> Props.GRAVITY_START
            "end", "right" -> Props.GRAVITY_END
            "top" -> Props.GRAVITY_TOP
            "bottom" -> Props.GRAVITY_BOTTOM
            "center" -> Props.GRAVITY_CENTER
            "center_horizontal" -> Props.GRAVITY_CENTER_HORIZONTAL
            "center_vertical" -> Props.GRAVITY_CENTER_VERTICAL
            else -> 0
        }
    }
}
