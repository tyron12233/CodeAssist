package dev.ide.preview.realview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * What System UI draws over an app's window, drawn into a preview frame: the status bar's clock and icons and
 * gesture navigation's handle. The window draws only the bars' colours; the rest belongs to another process,
 * which a preview does not have, so without this a preview's bars are empty bands.
 *
 * The clock is a fixed 9:41, so renders and screenshots do not change by the minute. The icons are dark or light
 * by the colour actually under them, as System UI chooses (a light status bar gets dark icons).
 */
internal object SystemBars {

    private const val CLOCK = "9:41"

    /**
     * Draw onto [canvas] (already scaled from the [w] x [h] frame to [frame]'s pixels): the status bar across the
     * top [statusPx] and the navigation bar across the bottom [navPx], at [density] pixels per dp.
     */
    fun draw(canvas: Canvas, frame: Bitmap, w: Int, h: Int, statusPx: Int, navPx: Int, density: Float) {
        val sx = frame.width.toFloat() / w
        val sy = frame.height.toFloat() / h
        if (statusPx > 0) drawStatusBar(canvas, w, statusPx.toFloat(), density, iconColorOver(frame, 0f, statusPx * sy, sx * w))
        if (navPx > 0) drawHandle(canvas, w, h, navPx.toFloat(), density, iconColorOver(frame, (h - navPx) * sy, h * sy, sx * w))
    }

    private fun drawStatusBar(canvas: Canvas, w: Int, barH: Float, dp: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 14f * dp
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val mid = barH / 2f
        canvas.drawText(CLOCK, 16f * dp, mid - (text.ascent() + text.descent()) / 2f, text)

        // Right to left: battery, signal, wifi.
        var right = w - 16f * dp
        val bw = 7f * dp
        val bh = 13f * dp
        val battery = RectF(right - bw, mid - bh / 2f + dp, right, mid + bh / 2f + dp)
        canvas.drawRoundRect(battery, 1.5f * dp, 1.5f * dp, paint)
        canvas.drawRect(battery.centerX() - 1.5f * dp, battery.top - 1.5f * dp, battery.centerX() + 1.5f * dp, battery.top, paint)
        right = battery.left - 6f * dp

        val s = 12f * dp
        val signal = Path().apply {
            moveTo(right, mid - s / 2f)
            lineTo(right, mid + s / 2f)
            lineTo(right - s, mid + s / 2f)
            close()
        }
        canvas.drawPath(signal, paint)
        right -= s + 6f * dp

        // Wifi: a quarter-circle fan pointing up, its tip at the bottom.
        val r = 9f * dp
        val tipY = mid + s / 2f
        val cx = right - r * 0.71f
        canvas.drawArc(RectF(cx - r, tipY - r, cx + r, tipY + r), 225f, 90f, true, paint)
    }

    private fun drawHandle(canvas: Canvas, w: Int, h: Int, barH: Float, dp: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val hw = 108f * dp / 2f
        val cy = h - barH / 2f
        canvas.drawRoundRect(RectF(w / 2f - hw, cy - 2f * dp, w / 2f + hw, cy + 2f * dp), 2f * dp, 2f * dp, paint)
    }

    /** Dark icons over a light bar, light over a dark one, by the bar's pixels between [top] and [bottom]. */
    private fun iconColorOver(frame: Bitmap, top: Float, bottom: Float, width: Float): Int {
        var lum = 0.0
        var n = 0
        val y0 = top.toInt().coerceIn(0, frame.height - 1)
        val y1 = (bottom.toInt() - 1).coerceIn(y0, frame.height - 1)
        for (fy in listOf(0.25f, 0.5f, 0.75f)) {
            val y = (y0 + (y1 - y0) * fy).toInt()
            for (fx in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
                val c = frame.getPixel((width * fx).toInt().coerceIn(0, frame.width - 1), y)
                lum += 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
                n++
            }
        }
        return if (n > 0 && lum / n > 150.0) Color.argb(222, 0, 0, 0) else Color.WHITE
    }
}
