package dev.ide.preview.impl

import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.GradientKind
import dev.ide.android.support.preview.GradientSpec
import dev.ide.android.support.preview.ShapeKind
import dev.ide.preview.Gradient
import dev.ide.preview.GradientType
import dev.ide.preview.PaintStyle
import dev.ide.preview.RCanvas
import dev.ide.preview.RPaint
import dev.ide.preview.RenderContext
import kotlin.math.min

/**
 * Renders a [DrawablePreview] (parsed from an `android:background="@drawable/…"`) into an [RCanvas] within
 * the given bounds. Covers the common background shapes — solid/stroked/rounded rectangles, ovals, layer-
 * lists, and the default layer of a selector. Gradients are approximated by their start colour (the
 * [RCanvas] paint model is solid-only for now); vectors/bitmaps as a background are skipped.
 */
internal object DrawableBackgroundRenderer {

    fun draw(d: DrawablePreview, l: Float, t: Float, r: Float, b: Float, canvas: RCanvas, ctx: RenderContext) {
        when (d) {
            is DrawablePreview.SolidColor -> fillRect(canvas, ctx, l, t, r, b, d.color.toInt(), 0f)
            is DrawablePreview.Shape -> drawShape(d, l, t, r, b, canvas, ctx)
            is DrawablePreview.Layers -> for (layer in d.layers) {
                val dp = ctx.density
                draw(layer.drawable, l + layer.insetLeftDp * dp, t + layer.insetTopDp * dp,
                    r - layer.insetRightDp * dp, b - layer.insetBottomDp * dp, canvas, ctx)
            }
            is DrawablePreview.States -> d.defaultLayer?.let { draw(it, l, t, r, b, canvas, ctx) }
            else -> { /* Vector / BitmapRef / Unsupported background: skip */ }
        }
    }

    private fun drawShape(s: DrawablePreview.Shape, l: Float, t: Float, r: Float, b: Float, canvas: RCanvas, ctx: RenderContext) {
        val spec = s.spec
        val radius = maxOf(spec.cornerTopLeftDp, spec.cornerTopRightDp, spec.cornerBottomLeftDp, spec.cornerBottomRightDp) * ctx.density
        // The fill is a gradient, a solid colour, or absent.
        val fillPaint: RPaint? = when {
            spec.gradient != null -> ctx.gfx.newPaint().apply { style = PaintStyle.FILL; gradient = toGradient(spec.gradient!!) }
            spec.solidColor != null -> ctx.gfx.newPaint().apply { style = PaintStyle.FILL; color = spec.solidColor!!.toInt() }
            else -> null
        }
        when (spec.shape) {
            ShapeKind.OVAL -> {
                val rad = min(r - l, b - t) / 2f
                fillPaint?.let { canvas.drawRoundRect(l, t, r, b, rad, rad, it) }
                stroke(spec, canvas, ctx, l, t, r, b, rad)
            }
            ShapeKind.LINE -> {
                val y = (t + b) / 2f
                val color = (spec.strokeColor ?: spec.solidColor ?: 0xFF888888L).toInt()
                canvas.drawLine(l, y, r, y, ctx.gfx.newPaint().apply { this.color = color; strokeWidth = (spec.strokeWidthDp * ctx.density).coerceAtLeast(ctx.density) })
            }
            else -> { // RECTANGLE / RING (approximated as rect)
                fillPaint?.let { canvas.drawRoundRect(l, t, r, b, radius, radius, it) }
                stroke(spec, canvas, ctx, l, t, r, b, radius)
            }
        }
    }

    private fun toGradient(g: GradientSpec): Gradient {
        val colors = buildList {
            add(g.startColor.toInt())
            g.centerColor?.let { add(it.toInt()) }
            add(g.endColor.toInt())
        }.toIntArray()
        val type = when (g.kind) {
            GradientKind.RADIAL -> GradientType.RADIAL
            GradientKind.SWEEP -> GradientType.SWEEP
            else -> GradientType.LINEAR
        }
        return Gradient(type, colors, g.angle, g.centerX, g.centerY, g.radiusFraction)
    }

    private fun stroke(spec: dev.ide.android.support.preview.ShapeSpec, canvas: RCanvas, ctx: RenderContext, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        val sc = spec.strokeColor ?: return
        if (spec.strokeWidthDp <= 0f) return
        canvas.drawRoundRect(l, t, r, b, radius, radius, ctx.gfx.newPaint().apply {
            color = sc.toInt(); style = PaintStyle.STROKE; strokeWidth = spec.strokeWidthDp * ctx.density
        })
    }

    private fun fillRect(canvas: RCanvas, ctx: RenderContext, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float) {
        canvas.drawRoundRect(l, t, r, b, radius, radius, ctx.gfx.newPaint().apply { this.color = color; style = PaintStyle.FILL })
    }
}
