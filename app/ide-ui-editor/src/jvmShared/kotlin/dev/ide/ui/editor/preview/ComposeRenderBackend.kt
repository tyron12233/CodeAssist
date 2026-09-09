package dev.ide.ui.editor.preview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.ide.preview.FontMetrics
import dev.ide.preview.PreviewResources
import dev.ide.preview.ResolvedValue
import dev.ide.preview.ValueFormat
import dev.ide.preview.PaintStyle
import dev.ide.preview.RCanvas
import dev.ide.preview.RGraphics
import dev.ide.preview.RImage
import dev.ide.preview.RPaint
import dev.ide.preview.RPath

/** A mutable [RPaint] holder mapped to a Compose [Paint] per draw call. */
internal class ComposePaint : RPaint {
    override var color: Int = 0xFF000000.toInt()
    override var style: PaintStyle = PaintStyle.FILL
    override var strokeWidth: Float = 0f
    override var antiAlias: Boolean = true
    override var textSizePx: Float = 14f
    override var bold: Boolean = false
    override var gradient: dev.ide.preview.Gradient? = null
}

/** Wraps a Compose [androidx.compose.ui.graphics.Path] parsed from Android `pathData`. */
internal class ComposePath(val path: androidx.compose.ui.graphics.Path) : RPath

/** Wraps a decoded Compose image. */
internal class ComposeImage(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : RImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/**
 * Wraps the engine's [PreviewResources] (resolve = colours/dimensions, delegated) with image decoding the UI
 * side owns: a `@drawable/@mipmap` reference is mapped to a file path by the backend, read, and decoded to a
 * Compose [ComposeImage] via the platform [decodeImageBytes].
 */
internal class UiPreviewResources(
    private val delegate: PreviewResources,
    private val imageFile: (String) -> String?,
) : PreviewResources {
    override fun resolve(raw: String, format: ValueFormat): ResolvedValue? = delegate.resolve(raw, format)

    override fun image(ref: String): RImage? {
        val path = imageFile(ref) ?: return null
        val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return null
        return decodeImageBytes(bytes)?.let { ComposeImage(it) }
    }
}

/**
 * [RGraphics] backed by Compose: paints map to `androidx.compose.ui.graphics.Paint`, and text metrics use a
 * [TextMeasurer] (real line breaking / advance widths). A paint's `textSizePx` is converted to `sp` via the
 * previewed [density] so the measured pixel size matches what the engine laid out.
 */
internal class ComposeGraphics(private val measurer: TextMeasurer) : RGraphics {
    override fun newPaint(): RPaint = ComposePaint()

    override fun parsePath(pathData: String): RPath? =
        runCatching { ComposePath(AndroidPathParser.parse(pathData)) }.getOrNull()

    // The measurer is built with Density(1f) so `textSizePx.sp` measures to exactly `textSizePx` pixels —
    // the engine works in device pixels throughout, independent of the host display density.
    override fun measureText(text: CharSequence, paint: RPaint): Float =
        measurer.measure(text.toString(), styleOf(paint)).size.width.toFloat()

    override fun fontMetrics(paint: RPaint): FontMetrics {
        val r = measurer.measure("Ag", styleOf(paint))
        return FontMetrics(lineHeight = r.size.height.toFloat(), ascent = r.firstBaseline)
    }

    private fun styleOf(paint: RPaint) = TextStyle(
        fontSize = paint.textSizePx.sp,
        fontWeight = if (paint.bold) FontWeight.Bold else FontWeight.Normal,
    )
}

/**
 * [RCanvas] over a Compose [DrawScope]: vector ops go through the underlying canvas (so manual
 * save/restore/translate compose with the scope), and text goes through [DrawScope.drawText] with the same
 * [TextMeasurer] the metrics used. This is the surface both built-in renderers and (later) a custom view's
 * unwrapped native canvas composite into.
 */
internal class ComposeRCanvas(
    private val ds: DrawScope,
    private val measurer: TextMeasurer,
) : RCanvas, dev.ide.preview.NativeCanvasHolder {
    private val canvas = ds.drawContext.canvas

    // The platform-native canvas (android.graphics.Canvas / skia.Canvas) a custom view's onDraw draws into.
    override fun nativeCanvas(): Any? = canvas.nativeCanvas

    private fun paint(p: RPaint): Paint = Paint().also {
        it.color = Color(p.color)
        it.style = if (p.style == PaintStyle.STROKE) PaintingStyle.Stroke else PaintingStyle.Fill
        it.strokeWidth = p.strokeWidth
        it.isAntiAlias = p.antiAlias
    }

    /** Like [paint], but installs a gradient shader sized to the drawn rect when the paint carries one. */
    private fun paintFor(p: RPaint, l: Float, t: Float, r: Float, b: Float): Paint = paint(p).also {
        val g = p.gradient ?: return@also
        val colors = g.colors.map { c -> Color(c) }
        it.shader = when (g.type) {
            dev.ide.preview.GradientType.RADIAL -> androidx.compose.ui.graphics.RadialGradientShader(
                center = Offset(l + (r - l) * g.centerX, t + (b - t) * g.centerY),
                radius = minOf(r - l, b - t) * g.radiusFraction,
                colors = colors,
            )
            dev.ide.preview.GradientType.SWEEP -> androidx.compose.ui.graphics.SweepGradientShader(
                center = Offset(l + (r - l) * g.centerX, t + (b - t) * g.centerY), colors = colors,
            )
            else -> {
                val rad = g.angleDeg * (kotlin.math.PI.toFloat() / 180f)
                val dx = kotlin.math.cos(rad); val dy = -kotlin.math.sin(rad)
                val cx = (l + r) / 2f; val cy = (t + b) / 2f
                androidx.compose.ui.graphics.LinearGradientShader(
                    from = Offset(cx - dx * (r - l) / 2f, cy - dy * (b - t) / 2f),
                    to = Offset(cx + dx * (r - l) / 2f, cy + dy * (b - t) / 2f),
                    colors = colors,
                )
            }
        }
    }

    override fun save(): Int { canvas.save(); return 0 }
    override fun restore() { canvas.restore() }
    override fun translate(dx: Float, dy: Float) { canvas.translate(dx, dy) }
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) { canvas.clipRect(l, t, r, b) }

    override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: RPaint) =
        canvas.drawRect(l, t, r, b, paintFor(paint, l, t, r, b))

    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: RPaint) =
        canvas.drawRoundRect(l, t, r, b, rx, ry, paintFor(paint, l, t, r, b))

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: RPaint) =
        canvas.drawCircle(Offset(cx, cy), radius, paintFor(paint, cx - radius, cy - radius, cx + radius, cy + radius))

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: RPaint) =
        canvas.drawLine(Offset(x0, y0), Offset(x1, y1), paint(paint))

    override fun drawPath(path: RPath, paint: RPaint) {
        if (path is ComposePath) canvas.drawPath(path.path, paint(paint))
    }

    override fun drawImage(img: RImage, l: Float, t: Float, r: Float, b: Float, tintArgb: Int?) {
        if (img !is ComposeImage) return
        val p = Paint()
        if (tintArgb != null) p.colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(tintArgb))
        canvas.drawImageRect(
            image = img.bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.bitmap.width, img.bitmap.height),
            dstOffset = IntOffset(l.toInt(), t.toInt()),
            dstSize = IntSize((r - l).toInt(), (b - t).toInt()),
            paint = p,
        )
    }

    override fun drawText(text: CharSequence, x: Float, y: Float, paint: RPaint) {
        // The DrawScope renders sp at the host density; divide so `textSizePx` lands as raw pixels — the
        // same pixel size the Density(1f) measurer reported during layout.
        ds.drawText(
            textMeasurer = measurer,
            text = text.toString(),
            topLeft = Offset(x, y),
            style = TextStyle(
                color = Color(paint.color),
                fontSize = (paint.textSizePx / ds.density).sp,
                fontWeight = if (paint.bold) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}
