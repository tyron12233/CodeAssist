package dev.ide.android.preview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.asAndroidPath
import dev.ide.preview.FontMetrics
import dev.ide.preview.Gradient
import dev.ide.preview.PaintStyle
import dev.ide.preview.RCanvas
import dev.ide.preview.RGraphics
import dev.ide.preview.RImage
import dev.ide.preview.RPaint
import dev.ide.preview.RPath
import dev.ide.ui.editor.preview.AndroidPathParser

/**
 * The owned drawing surface over a real `android.graphics.Canvas`.
 *
 * This is what a Swing program's `paintComponent` ultimately reaches on device: the toolkit's `Graphics2D`
 * calls arrive as [RCanvas] primitives and go straight to the framework. Drawing onto a plain
 * [android.graphics.Bitmap] canvas is enough because the owned toolkit needs no framework view hierarchy,
 * unlike the Compose preview next door, which has to render through a real `ComposeView`.
 *
 * The two roles are separate because they have different lifetimes. [AndroidGraphics] (measurement, paints,
 * paths) belongs to a whole run and is what a window attaches to, since a program measures text while it is
 * building its UI. [AndroidCanvas] is bound to one canvas for one paint pass, and is rebuilt whenever the
 * target bitmap is.
 */
internal class AndroidCanvas(private val canvas: Canvas) : RCanvas {

    override fun save(): Int = canvas.save()

    override fun restore() = canvas.restore()

    override fun translate(dx: Float, dy: Float) = canvas.translate(dx, dy)

    override fun clipRect(l: Float, t: Float, r: Float, b: Float) {
        canvas.clipRect(l, t, r, b)
    }

    override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: RPaint) =
        canvas.drawRect(l, t, r, b, paint.native())

    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: RPaint) =
        canvas.drawRoundRect(l, t, r, b, rx, ry, paint.native())

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: RPaint) =
        canvas.drawCircle(cx, cy, radius, paint.native())

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: RPaint) =
        canvas.drawLine(x0, y0, x1, y1, paint.native())

    override fun drawPath(path: RPath, paint: RPaint) {
        canvas.drawPath((path as AndroidPath).path, paint.native())
    }

    override fun drawImage(img: RImage, l: Float, t: Float, r: Float, b: Float, tintArgb: Int?) {
        // Images reach the toolkit only through `Graphics.drawImage`, which it does not implement yet.
        throw UnsupportedOperationException("the toolkit draws no images yet")
    }

    override fun drawText(text: CharSequence, x: Float, y: Float, paint: RPaint) {
        val native = paint.native()
        // RCanvas positions text by its top-left; Android draws from the baseline, and its ascent is negative,
        // so subtracting it steps down from the top of the line.
        canvas.drawText(text.toString(), x, y - native.fontMetrics.ascent, native)
    }

    private fun RPaint.native(): Paint = (this as AndroidPaint).paint
}

/** Everything the toolkit needs that is not drawing: paints, parsed paths, and real device text metrics. */
internal class AndroidGraphics : RGraphics {

    override fun newPaint(): RPaint = AndroidPaint()

    /** The toolkit emits path data for a non-circular oval; reuse the preview's parser rather than a second one. */
    override fun parsePath(pathData: String): RPath? =
        runCatching { AndroidPath(AndroidPathParser.parse(pathData).asAndroidPath()) }.getOrNull()

    override fun measureText(text: CharSequence, paint: RPaint): Float =
        (paint as AndroidPaint).paint.measureText(text.toString())

    override fun fontMetrics(paint: RPaint): FontMetrics {
        val fm = (paint as AndroidPaint).paint.fontMetrics
        return FontMetrics(lineHeight = fm.descent - fm.ascent, ascent = -fm.ascent)
    }
}

/** Both roles over one canvas, for a caller that paints and measures with a single object. */
internal class AndroidRenderBackend(canvas: Canvas) :
    RCanvas by AndroidCanvas(canvas),
    RGraphics by AndroidGraphics()

/** An [RPaint] that IS an `android.graphics.Paint`, so nothing has to be copied at draw time. */
internal class AndroidPaint : RPaint {

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
        }

    override var style: PaintStyle = PaintStyle.FILL
        set(value) {
            field = value
            paint.style = when (value) {
                PaintStyle.FILL -> Paint.Style.FILL
                PaintStyle.STROKE -> Paint.Style.STROKE
                PaintStyle.FILL_AND_STROKE -> Paint.Style.FILL_AND_STROKE
            }
        }

    override var strokeWidth: Float
        get() = paint.strokeWidth
        set(value) {
            paint.strokeWidth = value
        }

    override var antiAlias: Boolean
        get() = paint.isAntiAlias
        set(value) {
            paint.isAntiAlias = value
        }

    override var textSizePx: Float
        get() = paint.textSize
        set(value) {
            paint.textSize = value
        }

    override var bold: Boolean = false
        set(value) {
            field = value
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, if (value) Typeface.BOLD else Typeface.NORMAL)
        }

    /** Gradients are an XML-preview feature; the toolkit's `Graphics2D` never sets one. */
    override var gradient: Gradient? = null
}

/** A parsed path, kept as the framework type the canvas draws. */
internal class AndroidPath(val path: android.graphics.Path) : RPath
