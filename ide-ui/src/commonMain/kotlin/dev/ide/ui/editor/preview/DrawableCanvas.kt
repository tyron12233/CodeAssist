package dev.ide.ui.editor.preview

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiGradient
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/** ARGB long (`0xAARRGGBB`) → Compose [Color] via the unambiguous Int (ARGB) constructor. */
internal fun argbColor(v: Long): Color = Color(v.toInt())

/**
 * Draws a [UiDrawable] into the rectangle [topLeft]+[size]. Recursive (layer-list/inset/selector default).
 * Bitmaps and unsupported nodes draw a labelled placeholder — the pane renders a *top-level* bitmap with a
 * real decoded image instead (it needs async byte loading the canvas can't do).
 */
fun DrawScope.drawUiDrawable(d: UiDrawable, topLeft: Offset, size: Size) {
    when (d) {
        is UiDrawable.SolidColor -> drawRect(argbColor(d.color), topLeft, size)
        is UiDrawable.Shape -> drawShape(d, topLeft, size)
        is UiDrawable.Vector -> drawVector(d, topLeft, size)
        is UiDrawable.Layers -> for (layer in d.layers) {
            val l = layer.insetLeftDp.dp.toPx(); val t = layer.insetTopDp.dp.toPx()
            val r = layer.insetRightDp.dp.toPx(); val b = layer.insetBottomDp.dp.toPx()
            drawUiDrawable(
                layer.drawable,
                Offset(topLeft.x + l, topLeft.y + t),
                Size((size.width - l - r).coerceAtLeast(0f), (size.height - t - b).coerceAtLeast(0f)),
            )
        }
        is UiDrawable.States -> d.defaultLayer?.let { drawUiDrawable(it, topLeft, size) }
        is UiDrawable.Bitmap -> drawPlaceholder(topLeft, size)
        is UiDrawable.Unsupported -> drawPlaceholder(topLeft, size)
    }
}

private fun DrawScope.drawShape(s: UiDrawable.Shape, topLeft: Offset, size: Size) {
    val sw = s.strokeWidthDp.dp.toPx()
    val inset = sw / 2f
    val rect = Rect(topLeft.x + inset, topLeft.y + inset, topLeft.x + size.width - inset, topLeft.y + size.height - inset)
    val fillBrush: Brush? = when {
        s.gradient != null -> gradientBrush(s.gradient, rect)
        s.solidColor != null -> Brush.linearGradient(0f to argbColor(s.solidColor), 1f to argbColor(s.solidColor))
        else -> null
    }

    when (s.shape) {
        "oval" -> {
            fillBrush?.let { drawOval(it, Offset(rect.left, rect.top), Size(rect.width, rect.height)) }
            if (s.strokeColor != null && sw > 0) drawOval(
                argbColor(s.strokeColor), Offset(rect.left, rect.top), Size(rect.width, rect.height),
                style = strokeStyle(s, sw),
            )
        }
        "line" -> {
            val y = rect.center.y
            val color = s.strokeColor ?: s.solidColor ?: 0xFF888888L
            drawLine(argbColor(color), Offset(rect.left, y), Offset(rect.right, y), strokeWidth = if (sw > 0) sw else 2.dp.toPx())
        }
        "ring" -> {
            val cx = rect.center.x; val cy = rect.center.y
            val outer = min(rect.width, rect.height) / 2f
            val thickness = outer * s.thicknessFraction.coerceIn(0.05f, 0.9f)
            val color = s.solidColor ?: s.strokeColor ?: 0xFF888888L
            drawCircle(argbColor(color), radius = outer - thickness / 2f, center = Offset(cx, cy), style = Stroke(width = thickness))
        }
        else -> { // rectangle (default), honouring per-corner radii
            val path = roundRectPath(rect, s)
            fillBrush?.let { drawPath(path, it, style = Fill) }
            if (s.strokeColor != null && sw > 0) drawPath(path, argbColor(s.strokeColor), style = strokeStyle(s, sw))
        }
    }
}

private fun roundRectPath(rect: Rect, s: UiDrawable.Shape): Path = Path().apply {
    addRoundRect(
        RoundRect(
            rect,
            topLeft = CornerRadius(s.cornerTopLeftDp), topRight = CornerRadius(s.cornerTopRightDp),
            bottomRight = CornerRadius(s.cornerBottomRightDp), bottomLeft = CornerRadius(s.cornerBottomLeftDp),
        ),
    )
}

private fun DrawScope.strokeStyle(s: UiDrawable.Shape, sw: Float): Stroke {
    val effect = if (s.dashWidthDp > 0f) {
        PathEffect.dashPathEffect(floatArrayOf(s.dashWidthDp.dp.toPx(), s.dashGapDp.dp.toPx()))
    } else null
    return Stroke(width = sw, pathEffect = effect)
}

private fun DrawScope.gradientBrush(g: UiGradient, rect: Rect): Brush {
    val stops = buildList {
        add(0f to argbColor(g.startColor))
        if (g.centerColor != null) add(0.5f to argbColor(g.centerColor))
        add(1f to argbColor(g.endColor))
    }.toTypedArray()
    val center = Offset(rect.left + rect.width * g.centerX, rect.top + rect.height * g.centerY)
    return when (g.kind) {
        "radial" -> Brush.radialGradient(*stops, center = center, radius = min(rect.width, rect.height) * g.radiusFraction)
        "sweep" -> Brush.sweepGradient(*stops, center = center)
        else -> {
            val rad = g.angle * PI.toFloat() / 180f
            val dx = cos(rad); val dy = -sin(rad)
            val start = Offset(rect.center.x - dx * rect.width / 2f, rect.center.y - dy * rect.height / 2f)
            val end = Offset(rect.center.x + dx * rect.width / 2f, rect.center.y + dy * rect.height / 2f)
            Brush.linearGradient(*stops, start = start, end = end)
        }
    }
}

private fun DrawScope.drawVector(v: UiDrawable.Vector, topLeft: Offset, size: Size) {
    if (v.viewportWidth <= 0f || v.viewportHeight <= 0f) return
    // Fit the viewport into the bounds preserving aspect ratio, centred.
    val scaleF = min(size.width / v.viewportWidth, size.height / v.viewportHeight)
    val drawnW = v.viewportWidth * scaleF; val drawnH = v.viewportHeight * scaleF
    val ox = topLeft.x + (size.width - drawnW) / 2f
    val oy = topLeft.y + (size.height - drawnH) / 2f
    translate(ox, oy) {
        scale(scaleF, scaleF, pivot = Offset.Zero) {
            for (p in v.paths) {
                val path = AndroidPathParser.parse(p.pathData)
                p.fillColor?.let { drawPath(path, argbColor(it), alpha = (p.fillAlpha * v.rootAlpha).coerceIn(0f, 1f), style = Fill) }
                if (p.strokeColor != null && p.strokeWidthVp > 0f) {
                    drawPath(
                        path, argbColor(p.strokeColor),
                        alpha = (p.strokeAlpha * v.rootAlpha).coerceIn(0f, 1f),
                        style = Stroke(width = p.strokeWidthVp),
                    )
                }
            }
        }
    }
}

/** A light/dark checkerboard, the conventional "transparency" backdrop behind a previewed drawable/image. */
fun DrawScope.drawCheckerboard(cell: Float = 10f) {
    val light = Color(0xFFFFFFFF); val dark = Color(0xFFE0E0E0)
    drawRect(light)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(dark, Offset(x, y), Size(min(cell, size.width - x), min(cell, size.height - y)))
            x += 2 * cell
        }
        y += cell; row++
    }
}

private fun DrawScope.drawPlaceholder(topLeft: Offset, size: Size) {
    drawRect(Color(0x22808080), topLeft, size)
    drawRect(Color(0x44808080), topLeft, size, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
}
