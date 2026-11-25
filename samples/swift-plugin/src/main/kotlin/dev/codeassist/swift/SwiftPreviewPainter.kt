package dev.codeassist.swift

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import org.json.JSONArray
import org.json.JSONObject

/**
 * One rendered preview, as the engine's display list (DisplayList.swift) describes it, ready to paint: parsed once per
 * frame, painted on every draw. Coordinates are points on the device screen.
 */
class SwiftPreviewFrame(
    val name: String,
    val width: Float,
    val height: Float,
    val background: Int,
    val ops: List<Op>,
) {
    sealed interface Op
    class Shape(val path: Path, val color: Int?, val gradient: Gradient?, val strokeWidth: Float?, val dash: FloatArray?) : Op
    class Text(val left: Float, val top: Float, val width: Float, val height: Float, val spans: List<SwiftTextEngine.Span>,
               val align: String, val maxLines: Int?, val lineAdvance: Float?) : Op
    class Symbol(val rect: RectF, val name: String, val size: Float, val weight: Int, val color: Int) : Op
    class Clip(val path: Path) : Op
    class Alpha(val alpha: Float) : Op
    class Shadow(val color: Int, val radius: Float, val dx: Float, val dy: Float) : Op
    object Pop : Op
    class Hit(val rect: RectF, val id: Int) : Op
    class Field(val rect: RectF, val id: Int, val text: String, val secure: Boolean) : Op

    class Gradient(val kind: String, val points: FloatArray, val colors: IntArray, val stops: FloatArray)

    /** The topmost tap target or text field under [x],[y] (points), if any. */
    fun hitAt(x: Float, y: Float): Op? = ops.lastOrNull {
        (it is Hit && it.rect.contains(x, y)) || (it is Field && it.rect.contains(x, y))
    }

    companion object {
        fun parse(json: JSONObject): SwiftPreviewFrame {
            val ops = json.getJSONArray("ops")
            return SwiftPreviewFrame(
                name = json.optString("name"),
                width = json.getDouble("w").toFloat(),
                height = json.getDouble("h").toFloat(),
                background = color(json.optString("bg", "#FFFFFFFF")),
                ops = (0 until ops.length()).mapNotNull { op(ops.getJSONObject(it)) },
            )
        }

        /** `#RRGGBBAA` (the engine's form) to Android's ARGB. */
        fun color(hex: String): Int {
            val v = hex.removePrefix("#").toLong(16)
            return if (hex.length == 9) (((v and 0xFF) shl 24) or (v ushr 8)).toInt() else (0xFF000000 or v).toInt()
        }

        private fun rect(a: JSONArray) = RectF(a.f(0), a.f(1), a.f(0) + a.f(2), a.f(1) + a.f(3))
        private fun JSONArray.f(i: Int) = getDouble(i).toFloat()

        private fun op(o: JSONObject): Op? = when (o.getString("o")) {
            "path" -> Shape(
                path(o.getJSONArray("d")),
                o.optString("c").takeIf { it.isNotEmpty() }?.let(::color),
                o.optJSONObject("g")?.let(::gradient),
                if (o.has("sw")) o.getDouble("sw").toFloat() else null,
                o.optJSONArray("dash")?.let { d -> FloatArray(d.length()) { d.f(it) } },
            )
            "text" -> {
                val r = o.getJSONArray("r")
                Text(r.f(0), r.f(1), r.f(2), r.f(3), spans(o.getJSONArray("sp")), o.optString("a", "leading"),
                    if (o.has("n")) o.getInt("n") else null, if (o.has("lh")) o.getDouble("lh").toFloat() else null)
            }
            "symbol" -> Symbol(rect(o.getJSONArray("r")), o.getString("n"), o.getDouble("s").toFloat(), o.optInt("w", 400), color(o.getString("c")))
            "clip" -> Clip(path(o.getJSONArray("d")))
            "alpha" -> Alpha(o.getDouble("a").toFloat())
            "shadow" -> Shadow(color(o.getString("c")), o.getDouble("r").toFloat(), o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
            "pop" -> Pop
            "hit" -> Hit(rect(o.getJSONArray("r")), o.getInt("id"))
            "field" -> Field(rect(o.getJSONArray("r")), o.getInt("id"), o.optString("text"), o.optInt("secure") == 1)
            else -> null
        }

        fun spans(a: JSONArray): List<SwiftTextEngine.Span> = (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            SwiftTextEngine.Span(
                text = s.getString("t"),
                size = s.getDouble("s").toFloat(),
                weight = s.getInt("w"),
                design = s.optString("d", "default"),
                italic = s.optInt("i") == 1,
                color = color(s.getString("c")),
                underline = s.optString("u").takeIf { it.isNotEmpty() }?.let(::color),
                strikethrough = s.optString("st").takeIf { it.isNotEmpty() }?.let(::color),
                kerning = s.optDouble("k", 0.0).toFloat(),
                baselineOffset = s.optDouble("b", 0.0).toFloat(),
                symbol = s.optString("sym").takeIf { it.isNotEmpty() },
            )
        }

        private fun gradient(g: JSONObject): Gradient {
            val p = g.getJSONArray("p")
            val s = g.getJSONArray("s")
            return Gradient(
                g.getString("k"),
                FloatArray(p.length()) { p.f(it) },
                IntArray(s.length()) { color(s.getJSONArray(it).getString(0)) },
                FloatArray(s.length()) { s.getJSONArray(it).f(1) },
            )
        }

        fun path(elements: JSONArray): Path {
            val p = Path()
            for (i in 0 until elements.length()) {
                val e = elements.getJSONArray(i)
                when (e.getString(0)) {
                    "M" -> p.moveTo(e.f(1), e.f(2))
                    "L" -> p.lineTo(e.f(1), e.f(2))
                    "Q" -> p.quadTo(e.f(3), e.f(4), e.f(1), e.f(2))
                    "C" -> p.cubicTo(e.f(3), e.f(4), e.f(5), e.f(6), e.f(1), e.f(2))
                    "Z" -> p.close()
                    "R" -> p.addRect(e.f(1), e.f(2), e.f(1) + e.f(3), e.f(2) + e.f(4), Path.Direction.CW)
                    "E" -> p.addOval(e.f(1), e.f(2), e.f(1) + e.f(3), e.f(2) + e.f(4), Path.Direction.CW)
                    "RR" -> {
                        val r = RectF(e.f(1), e.f(2), e.f(1) + e.f(3), e.f(2) + e.f(4))
                        if (e.getInt(7) == 1) continuousRect(p, r, e.f(5))
                        else p.addRoundRect(r, minOf(e.f(5), r.width() / 2), minOf(e.f(6), r.height() / 2), Path.Direction.CW)
                    }
                }
            }
            return p
        }

        /**
         * iOS's continuous ("squircle") corner, as UIBezierPath(roundedRect:cornerRadius:) draws it: each corner runs
         * 1.528665 x the radius along the edges, in three cubic segments. Same constants as the parity harness.
         */
        fun continuousRect(p: Path, r: RectF, radius: Float) {
            val c = minOf(radius, minOf(r.width(), r.height()) / 2f / 1.52866483f)
            val (l, t, rt, b) = listOf(r.left, r.top, r.right, r.bottom)
            p.moveTo(l + 1.52866483f * c, t)
            p.lineTo(rt - 1.52866483f * c, t)
            p.cubicTo(rt - 1.08849299f * c, t, rt - 0.86840701f * c, t, rt - 0.63149399f * c, t + 0.07491100f * c)
            p.cubicTo(rt - 0.37282401f * c, t + 0.16905900f * c, rt - 0.16905900f * c, t + 0.37282401f * c, rt - 0.07491100f * c, t + 0.63149399f * c)
            p.cubicTo(rt, t + 0.86840701f * c, rt, t + 1.08849299f * c, rt, t + 1.52866483f * c)
            p.lineTo(rt, b - 1.52866483f * c)
            p.cubicTo(rt, b - 1.08849299f * c, rt, b - 0.86840701f * c, rt - 0.07491100f * c, b - 0.63149399f * c)
            p.cubicTo(rt - 0.16905900f * c, b - 0.37282401f * c, rt - 0.37282401f * c, b - 0.16905900f * c, rt - 0.63149399f * c, b - 0.07491100f * c)
            p.cubicTo(rt - 0.86840701f * c, b, rt - 1.08849299f * c, b, rt - 1.52866483f * c, b)
            p.lineTo(l + 1.52866483f * c, b)
            p.cubicTo(l + 1.08849299f * c, b, l + 0.86840701f * c, b, l + 0.63149399f * c, b - 0.07491100f * c)
            p.cubicTo(l + 0.37282401f * c, b - 0.16905900f * c, l + 0.16905900f * c, b - 0.37282401f * c, l + 0.07491100f * c, b - 0.63149399f * c)
            p.cubicTo(l, b - 0.86840701f * c, l, b - 1.08849299f * c, l, b - 1.52866483f * c)
            p.lineTo(l, t + 1.52866483f * c)
            p.cubicTo(l, t + 1.08849299f * c, l, t + 0.86840701f * c, l + 0.07491100f * c, t + 0.63149399f * c)
            p.cubicTo(l + 0.16905900f * c, t + 0.37282401f * c, l + 0.37282401f * c, t + 0.16905900f * c, l + 0.63149399f * c, t + 0.07491100f * c)
            p.cubicTo(l + 0.86840701f * c, t, l + 1.08849299f * c, t, l + 1.52866483f * c, t)
            p.close()
        }
    }
}

/** Paints a [SwiftPreviewFrame] on an Android canvas that is already scaled to points. */
class SwiftPreviewPainter(private val text: SwiftTextEngine, private val symbols: SwiftSymbols) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun paint(canvas: Canvas, frame: SwiftPreviewFrame) {
        canvas.drawColor(frame.background)
        // Each push (clip, alpha, shadow) pops to the matching restore; shadows apply to what is drawn while pushed.
        val stack = ArrayList<SwiftPreviewFrame.Op>()
        var shadow: SwiftPreviewFrame.Shadow? = null
        for (op in frame.ops) {
            when (op) {
                is SwiftPreviewFrame.Shape -> drawShape(canvas, op, shadow)
                is SwiftPreviewFrame.Text -> text.draw(canvas, op.spans, op.left, op.top, op.width, op.maxLines, op.lineAdvance, op.align)
                is SwiftPreviewFrame.Symbol -> drawSymbol(canvas, op)
                is SwiftPreviewFrame.Clip -> { canvas.save(); canvas.clipPath(op.path); stack += op }
                is SwiftPreviewFrame.Alpha -> { canvas.saveLayerAlpha(null, (op.alpha * 255).toInt().coerceIn(0, 255)); stack += op }
                is SwiftPreviewFrame.Shadow -> { shadow = op; stack += op }
                SwiftPreviewFrame.Pop -> when (stack.removeLastOrNull()) {
                    is SwiftPreviewFrame.Shadow -> shadow = stack.lastOrNull { it is SwiftPreviewFrame.Shadow } as SwiftPreviewFrame.Shadow?
                    null -> Unit
                    else -> canvas.restore()
                }
                is SwiftPreviewFrame.Hit, is SwiftPreviewFrame.Field -> Unit
            }
        }
        repeat(stack.count { it !is SwiftPreviewFrame.Shadow }) { canvas.restore() }
        if (frame.width == 402f && frame.height == 874f) drawStatusBar(canvas, frame.background)
    }

    /**
     * The device's status bar and Dynamic Island, as Xcode's canvas shows them over a preview: 9:41, full signal,
     * Wi-Fi and battery, drawn as plain shapes at the places measured on the iPhone 17 Pro Simulator (simctl io
     * screenshot with the status bar overridden). Dark glyphs on a light background, light on a dark one.
     */
    private fun drawStatusBar(canvas: Canvas, background: Int) {
        val luminance = (0.299 * android.graphics.Color.red(background) + 0.587 * android.graphics.Color.green(background) +
            0.114 * android.graphics.Color.blue(background)) / 255
        val ink = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val faint = (ink and 0x00FFFFFF) or (0x5C shl 24)
        fill.shader = null
        fill.clearShadowLayer()
        // Time: semibold, its ink centred on x 72.67, on baseline 39.
        val time = listOf(SwiftTextEngine.Span("9:41", 17f, 600, "default", false, ink, null, null, 0f, 0f, null))
        val m = text.measure(time, null, 1, null, "leading")
        text.draw(canvas, time, 72.67f - m.width / 2f, 39f - m.firstBaseline, m.width + 1f, 1, null, "leading")
        // Signal: four bars, 3.33 wide, 2 apart, bottoms at 38.67.
        fill.color = ink
        val heights = floatArrayOf(4.67f, 7f, 9.67f, 12.33f)
        for (i in 0 until 4) {
            val left = 288.33f + i * 5.33f
            canvas.drawRoundRect(left, 38.67f - heights[i], left + 3.33f, 38.67f, 1f, 1f, fill)
        }
        // Wi-Fi: a wedge and two arcs fanning up from (323.5, 38.6).
        val cx = 323.5f
        val cy = 38.6f
        stroke.color = ink
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = 2.3f
        for (r in floatArrayOf(10.6f, 6.4f)) {
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -135f, 90f, false, stroke)
        }
        canvas.drawArc(cx - 3.6f, cy - 3.6f, cx + 3.6f, cy + 3.6f, -135f, 90f, true, fill)
        stroke.strokeCap = Paint.Cap.BUTT
        // Battery: an outlined body, full, with its cap.
        stroke.color = faint
        stroke.strokeWidth = 1f
        canvas.drawRoundRect(339.9f, 26.7f, 363.3f, 38.2f, 3.8f, 3.8f, stroke)
        canvas.drawRoundRect(341.4f, 28.1f, 361.8f, 36.8f, 2.6f, 2.6f, fill)
        fill.color = faint
        canvas.drawArc(362.4f, 30.4f, 366.7f, 34.6f, -90f, 180f, true, fill)
        // The Dynamic Island.
        fill.color = android.graphics.Color.BLACK
        canvas.drawRoundRect(138.5f, 11.33f, 263.5f, 48.33f, 18.5f, 18.5f, fill)
    }

    private fun drawShape(canvas: Canvas, op: SwiftPreviewFrame.Shape, shadow: SwiftPreviewFrame.Shadow?) {
        val paint = if (op.strokeWidth != null) stroke.apply {
            strokeWidth = op.strokeWidth
            pathEffect = op.dash?.let { android.graphics.DashPathEffect(it, 0f) }
        } else fill
        paint.shader = op.gradient?.let(::shader)
        paint.color = op.color ?: android.graphics.Color.BLACK
        // iOS's shadow radius is a blur radius; Android's shadow layer takes about half that as its own.
        if (shadow != null) paint.setShadowLayer(shadow.radius / 2f, shadow.dx, shadow.dy, shadow.color) else paint.clearShadowLayer()
        canvas.drawPath(op.path, paint)
        paint.shader = null
    }

    private fun shader(g: SwiftPreviewFrame.Gradient): Shader = when (g.kind) {
        "radial" -> RadialGradient(g.points[0], g.points[1], maxOf(1f, g.points[3]), g.colors, g.stops, Shader.TileMode.CLAMP)
        "angular" -> SweepGradient(g.points[0], g.points[1], g.colors, g.stops)
        else -> LinearGradient(g.points[0], g.points[1], g.points[2], g.points[3], g.colors, g.stops, Shader.TileMode.CLAMP)
    }

    /** An SF Symbol, drawn with its open stand-in ([SwiftSymbols]); a name nothing stands for gets a quiet outline. */
    private fun drawSymbol(canvas: Canvas, op: SwiftPreviewFrame.Symbol) {
        if (symbols.draw(canvas, op.name, op.rect, op.size, op.weight, op.color)) return
        stroke.color = op.color
        stroke.strokeWidth = maxOf(1f, op.size / 12f)
        stroke.pathEffect = null
        stroke.shader = null
        val inset = op.rect.height() * 0.18f
        canvas.drawRoundRect(op.rect.left + inset, op.rect.top + inset, op.rect.right - inset, op.rect.bottom - inset,
            inset, inset, stroke)
    }
}
