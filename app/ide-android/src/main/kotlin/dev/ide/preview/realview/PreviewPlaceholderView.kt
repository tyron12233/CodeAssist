package dev.ide.preview.realview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * What the real-view preview draws for a view it could not create: a hatched box naming the class and why, in
 * place of the view, so the rest of the layout still renders around it.
 *
 * It is a real [FrameLayout] on purpose. A custom VIEW GROUP that fails still has children in the layout, and
 * they inflate into this and render; a custom leaf view simply has none. Its size comes from the tag's own
 * `layout_width`/`layout_height` (the parent reads those from the attributes, not from the view), with a minimum
 * so a `wrap_content` stand-in is still visible and tappable in the design surface.
 *
 * [originalTag] is the class the layout named, which the hierarchy capture reports in place of this class, so
 * the design surface still maps the stand-in to its element and shows it under its real name.
 */
class PreviewPlaceholderView(
    context: Context,
    attrs: AttributeSet?,
    val originalTag: String,
    val reason: String,
) : FrameLayout(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 120, 120, 120)
        strokeWidth = 1f * density
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 120, 120, 120)
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val fill = Paint().apply { color = Color.argb(28, 120, 120, 120) }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 70, 70)
        textSize = 12f * density
        isFakeBoldText = true
    }
    private val detail = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 110, 110)
        textSize = 10f * density
    }
    private val path = Path()

    init {
        setWillNotDraw(false)
        minimumWidth = (48 * density).toInt()
        minimumHeight = (36 * density).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fill)
        // Diagonal hatching, clipped to the box.
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        val step = 10f * density
        path.reset()
        var x = -h
        while (x < w) {
            path.moveTo(x, h)
            path.lineTo(x + h, 0f)
            x += step
        }
        hatch.style = Paint.Style.STROKE
        canvas.drawPath(path, hatch)
        canvas.restore()
        val inset = border.strokeWidth / 2
        canvas.drawRect(RectF(inset, inset, w - inset, h - inset), border)

        // The class and the reason, as much as fits.
        val pad = 6f * density
        val avail = (w - 2 * pad).coerceAtLeast(0f)
        if (avail <= 0f) return
        val name = TextUtils.ellipsize(originalTag.substringAfterLast('.'), title, avail, TextUtils.TruncateAt.END)
        val titleY = pad - title.ascent()
        if (titleY > h) return
        canvas.drawText(name, 0, name.length, pad, titleY, title)
        val reasonY = titleY + title.descent() - detail.ascent() + 2 * density
        if (reasonY <= h - pad / 2) {
            val why = TextUtils.ellipsize(reason, detail, avail, TextUtils.TruncateAt.END)
            canvas.drawText(why, 0, why.length, pad, reasonY, detail)
        }
    }
}
