package dev.ide.preview.impl.headless

import dev.ide.preview.RCanvas
import dev.ide.preview.RImage
import dev.ide.preview.RPaint
import dev.ide.preview.RPath

/** One recorded draw operation, with the (translated) coordinates and the paint colour at the time. */
data class DrawOp(
    val kind: String,
    val l: Float = 0f, val t: Float = 0f, val r: Float = 0f, val b: Float = 0f,
    val color: Int = 0,
    val text: String? = null,
)

/**
 * An [RCanvas] that records draw calls (with the current translation folded into the coordinates) instead of
 * rasterizing. Lets a headless test assert *what* the engine painted and *where* — the foundation for the
 * render-loop unit tests, and a cheap golden-image substitute.
 */
class RecordingCanvas : RCanvas {
    val ops: MutableList<DrawOp> = ArrayList()

    private var tx = 0f
    private var ty = 0f
    private val stack = ArrayDeque<Pair<Float, Float>>()

    override fun save(): Int { stack.addLast(tx to ty); return stack.size }
    override fun restore() { stack.removeLastOrNull()?.let { tx = it.first; ty = it.second } }
    override fun translate(dx: Float, dy: Float) { tx += dx; ty += dy }
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) {}

    override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: RPaint) =
        record(DrawOp("rect", l + tx, t + ty, r + tx, b + ty, paint.color))

    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: RPaint) =
        record(DrawOp("roundRect", l + tx, t + ty, r + tx, b + ty, paint.color))

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: RPaint) =
        record(DrawOp("circle", cx + tx - radius, cy + ty - radius, cx + tx + radius, cy + ty + radius, paint.color))

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: RPaint) =
        record(DrawOp("line", x0 + tx, y0 + ty, x1 + tx, y1 + ty, paint.color))

    override fun drawPath(path: RPath, paint: RPaint) = record(DrawOp("path", color = paint.color))

    override fun drawImage(img: RImage, l: Float, t: Float, r: Float, b: Float, tintArgb: Int?) =
        record(DrawOp("image", l + tx, t + ty, r + tx, b + ty, tintArgb ?: 0))

    override fun drawText(text: CharSequence, x: Float, y: Float, paint: RPaint) =
        record(DrawOp("text", x + tx, y + ty, color = paint.color, text = text.toString()))

    private fun record(op: DrawOp) { ops.add(op) }

    /** All recorded text strings, in paint order — convenient for assertions. */
    fun texts(): List<String> = ops.mapNotNull { it.text }
}
