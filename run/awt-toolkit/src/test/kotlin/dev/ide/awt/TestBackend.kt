package dev.ide.awt

import dev.ide.preview.FontMetrics
import dev.ide.preview.Gradient
import dev.ide.preview.PaintStyle
import dev.ide.preview.RCanvas
import dev.ide.preview.RGraphics
import dev.ide.preview.RImage
import dev.ide.preview.RPaint
import dev.ide.preview.RPath

/**
 * A drawing backend that records instead of rasterising, so the whole toolkit is testable with no display and
 * no Skia: a test lays out a real UI, paints it, and asserts on the ops that came out. Text metrics are a
 * fixed grid ([CHAR_WIDTH] per character), which makes every size assertion exact arithmetic rather than a
 * font-dependent approximation.
 */
class RecordingBackend : RCanvas, RGraphics {

    /** One recorded drawing call, with the paint's colour and style captured at the time of the call. */
    data class Op(
        val kind: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f,
        val text: String? = null,
        val color: Int = 0,
        val style: PaintStyle = PaintStyle.FILL,
    )

    val ops = ArrayList<Op>()

    /** Translation currently in effect, so a recorded op can be checked in window coordinates. */
    private var dx = 0f
    private var dy = 0f
    private val stack = ArrayDeque<Pair<Float, Float>>()

    fun clear() {
        ops.clear()
    }

    fun kinds(): List<String> = ops.map { it.kind }

    /** Every recorded text draw, in order. */
    fun texts(): List<String> = ops.mapNotNull { it.text }

    fun opsOf(kind: String): List<Op> = ops.filter { it.kind == kind }

    // ---- RCanvas -----------------------------------------------------------------------------------

    override fun save(): Int {
        stack.addLast(dx to dy)
        return stack.size
    }

    override fun restore() {
        val (x, y) = stack.removeLast()
        dx = x
        dy = y
    }

    override fun translate(dx: Float, dy: Float) {
        this.dx += dx
        this.dy += dy
    }

    override fun clipRect(l: Float, t: Float, r: Float, b: Float) {}

    override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: RPaint) {
        record("rect", l, t, r, b, null, paint)
    }

    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: RPaint) {
        record("roundRect", l, t, r, b, null, paint)
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: RPaint) {
        record("circle", cx - radius, cy - radius, cx + radius, cy + radius, null, paint)
    }

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: RPaint) {
        record("line", x0, y0, x1, y1, null, paint)
    }

    override fun drawPath(path: RPath, paint: RPaint) {
        record("path", 0f, 0f, 0f, 0f, (path as TestPath).data, paint)
    }

    override fun drawImage(img: RImage, l: Float, t: Float, r: Float, b: Float, tintArgb: Int?) {
        record("image", l, t, r, b, null, newPaint())
    }

    override fun drawText(text: CharSequence, x: Float, y: Float, paint: RPaint) {
        record("text", x, y, x + measureText(text, paint), y, text.toString(), paint)
    }

    private fun record(kind: String, l: Float, t: Float, r: Float, b: Float, text: String?, paint: RPaint) {
        ops.add(Op(kind, l + dx, t + dy, r + dx, b + dy, text, paint.color, paint.style))
    }

    // ---- RGraphics ---------------------------------------------------------------------------------

    override fun newPaint(): RPaint = TestPaint()

    override fun parsePath(pathData: String): RPath = TestPath(pathData)

    override fun measureText(text: CharSequence, paint: RPaint): Float =
        text.length * CHAR_WIDTH * (paint.textSizePx / BASE_TEXT_SIZE)

    override fun fontMetrics(paint: RPaint): FontMetrics {
        val scale = paint.textSizePx / BASE_TEXT_SIZE
        return FontMetrics(LINE_HEIGHT * scale, ASCENT * scale)
    }

    private class TestPaint : RPaint {
        override var color: Int = 0
        override var style: PaintStyle = PaintStyle.FILL
        override var strokeWidth: Float = 1f
        override var antiAlias: Boolean = true
        override var textSizePx: Float = BASE_TEXT_SIZE
        override var bold: Boolean = false
        override var gradient: Gradient? = null
    }

    private class TestPath(val data: String) : RPath

    companion object {
        /** A 10pt font is the reference: one character is 6px wide, the line is 12px tall, ascent 10px. */
        const val BASE_TEXT_SIZE = 10f
        const val CHAR_WIDTH = 6f
        const val LINE_HEIGHT = 12f
        const val ASCENT = 10f
    }
}
