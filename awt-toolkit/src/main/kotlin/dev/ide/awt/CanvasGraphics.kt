package dev.ide.awt

import dev.ide.preview.PaintStyle
import dev.ide.preview.RCanvas
import dev.ide.preview.RGraphics

/**
 * The [Graphics2D] the surface hands a component, drawing through the IDE's own [RCanvas]. One instance
 * serves a whole paint pass: [push]/[pop] bracket each child, mirroring the translate-and-clip a real
 * `Graphics` gets when Swing recurses into `paintChildren`.
 *
 * Two conversions matter and are easy to get wrong:
 *  - `drawString` positions text by its BASELINE, `RCanvas.drawText` by its top-left, so the ascent is
 *    subtracted here rather than by every caller.
 *  - AWT's `drawRect(x, y, w, h)` spans `x..x+w` INCLUSIVE, one pixel wider than a `w`-wide fill; the outline
 *    calls therefore stroke a rect of `w`, not `w - 1`, which is what the canvas already means.
 *
 * Ellipses and their rounded-rect cousins go out as a synthesised path rather than a canvas primitive:
 * [RCanvas] draws circles but not ovals, and [RGraphics.parsePath] already accepts the Android `pathData`
 * grammar, so a Bezier approximation costs a short string instead of a new method on an interface with
 * backends on three platforms.
 */
class CanvasGraphics(
    private val canvas: RCanvas,
    private val graphics: RGraphics,
    /** The colour `clearRect` paints, i.e. the surface's background. */
    private var background: Color = Color.WHITE,
) : Graphics2D() {

    private val paint = graphics.newPaint()
    private var color: Color = Color.BLACK
    private var font: Font = DEFAULT_FONT
    private var stroke: BasicStroke = BasicStroke(1f)
    private var antiAlias = true

    /** Translation applied since the pass started, so text measurement and shape maths stay in pixels. */
    private var originX = 0f
    private var originY = 0f
    private val saved = ArrayDeque<Pair<Float, Float>>()

    // ---- state -------------------------------------------------------------------------------------

    override fun getColor(): Color = color

    override fun setColor(c: Color?) {
        if (c != null) color = c
    }

    override fun getFont(): Font = font

    override fun setFont(f: Font?) {
        if (f != null) font = f
    }

    override fun getFontMetrics(): FontMetrics = getFontMetrics(font)

    override fun getFontMetrics(f: Font?): FontMetrics {
        val target = f ?: font
        applyText(target)
        val metrics = graphics.fontMetrics(paint)
        return FontMetrics(target, metrics.lineHeight.toInt(), metrics.ascent.toInt()) { text ->
            applyText(target)
            graphics.measureText(text, paint).toInt()
        }
    }

    override fun setRenderingHint(key: RenderingHints.Key?, value: Any?) {
        if (key === RenderingHints.KEY_ANTIALIASING || key === RenderingHints.KEY_TEXT_ANTIALIASING) {
            antiAlias = value !== RenderingHints.VALUE_ANTIALIAS_OFF && value !== RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
        }
    }

    override fun getRenderingHint(key: RenderingHints.Key?): Any? = when (key) {
        RenderingHints.KEY_ANTIALIASING, RenderingHints.KEY_TEXT_ANTIALIASING ->
            if (antiAlias) RenderingHints.VALUE_ANTIALIAS_ON else RenderingHints.VALUE_ANTIALIAS_OFF
        else -> null
    }

    override fun setStroke(stroke: BasicStroke?) {
        if (stroke != null) this.stroke = stroke
    }

    override fun getStroke(): BasicStroke = stroke

    override fun setBackground(c: Color?) {
        if (c != null) background = c
    }

    override fun getBackground(): Color = background

    // ---- coordinate space --------------------------------------------------------------------------

    override fun translate(dx: Int, dy: Int) {
        canvas.translate(dx.toFloat(), dy.toFloat())
        originX += dx
        originY += dy
    }

    override fun clipRect(x: Int, y: Int, width: Int, height: Int) {
        canvas.clipRect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
    }

    override fun setClip(x: Int, y: Int, width: Int, height: Int) = clipRect(x, y, width, height)

    /** Enter a child's coordinate space: save the canvas, translate to its origin, and clip to its bounds. */
    internal fun push(dx: Int, dy: Int, width: Int, height: Int) {
        canvas.save()
        saved.addLast(originX to originY)
        translate(dx, dy)
        clipRect(0, 0, width, height)
    }

    /** Leave the child's space, restoring the canvas and the tracked origin. */
    internal fun pop() {
        canvas.restore()
        val (x, y) = saved.removeLast()
        originX = x
        originY = y
    }

    // ---- shapes ------------------------------------------------------------------------------------

    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        stroked()
        canvas.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), paint)
    }

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        stroked()
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat(), paint)
    }

    override fun fillRect(x: Int, y: Int, width: Int, height: Int) {
        filled()
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat(), paint)
    }

    override fun drawRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) {
        stroked()
        roundRect(x, y, width, height, arcWidth, arcHeight)
    }

    override fun fillRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) {
        filled()
        roundRect(x, y, width, height, arcWidth, arcHeight)
    }

    private fun roundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) {
        canvas.drawRoundRect(
            x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat(),
            // AWT's arc width/height are the FULL axes of the corner ellipse; a canvas radius is half of that.
            arcWidth / 2f, arcHeight / 2f, paint,
        )
    }

    override fun drawOval(x: Int, y: Int, width: Int, height: Int) {
        stroked()
        oval(x, y, width, height)
    }

    override fun fillOval(x: Int, y: Int, width: Int, height: Int) {
        filled()
        oval(x, y, width, height)
    }

    private fun oval(x: Int, y: Int, width: Int, height: Int) {
        if (width == height) {
            val r = width / 2f
            canvas.drawCircle(x + r, y + r, r, paint)
            return
        }
        val path = graphics.parsePath(ovalPathData(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat()))
        if (path != null) canvas.drawPath(path, paint)
    }

    // ---- text --------------------------------------------------------------------------------------

    override fun drawString(text: String?, x: Int, y: Int) {
        if (text.isNullOrEmpty()) return
        applyText(font)
        paint.color = color.rgb
        // AWT's y is the baseline; the canvas wants the top of the line.
        val top = y - graphics.fontMetrics(paint).ascent
        canvas.drawText(text, x.toFloat(), top, paint)
    }

    override fun clearRect(x: Int, y: Int, width: Int, height: Int) {
        val previous = color
        color = background
        fillRect(x, y, width, height)
        color = previous
    }

    // ---- paint state -------------------------------------------------------------------------------

    private fun filled() {
        paint.style = PaintStyle.FILL
        paint.color = color.rgb
        paint.antiAlias = antiAlias
        paint.gradient = null
    }

    private fun stroked() {
        paint.style = PaintStyle.STROKE
        paint.color = color.rgb
        paint.strokeWidth = stroke.lineWidth
        paint.antiAlias = antiAlias
        paint.gradient = null
    }

    private fun applyText(f: Font) {
        paint.textSizePx = f.size.toFloat()
        paint.bold = f.isBold
        paint.antiAlias = antiAlias
    }

    private companion object {
        val DEFAULT_FONT = Font("SansSerif", Font.PLAIN, 12)

        /**
         * An ellipse as Android `pathData`, drawn as four cubic Beziers. `k` is the standard circular-arc
         * constant `4/3 * (sqrt(2) - 1)`: the control-point offset that makes a cubic match a quarter arc to
         * within about a thousandth of the radius.
         */
        fun ovalPathData(x: Float, y: Float, w: Float, h: Float): String {
            val rx = w / 2f
            val ry = h / 2f
            val cx = x + rx
            val cy = y + ry
            val kx = rx * 0.5522848f
            val ky = ry * 0.5522848f
            return buildString {
                append("M ").append(cx).append(' ').append(y).append(' ')
                append("C ").append(cx + kx).append(' ').append(y).append(' ')
                    .append(x + w).append(' ').append(cy - ky).append(' ')
                    .append(x + w).append(' ').append(cy).append(' ')
                append("C ").append(x + w).append(' ').append(cy + ky).append(' ')
                    .append(cx + kx).append(' ').append(y + h).append(' ')
                    .append(cx).append(' ').append(y + h).append(' ')
                append("C ").append(cx - kx).append(' ').append(y + h).append(' ')
                    .append(x).append(' ').append(cy + ky).append(' ')
                    .append(x).append(' ').append(cy).append(' ')
                append("C ").append(x).append(' ').append(cy - ky).append(' ')
                    .append(cx - kx).append(' ').append(y).append(' ')
                    .append(cx).append(' ').append(y).append(" Z")
            }
        }
    }
}
