package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Parses an Android `<path android:pathData>` string (the SVG path-data grammar) into a Compose [Path] in
 * the vector's viewport coordinate space — the renderer scales viewport → bounds. Supports the full command
 * set: M/L/H/V/C/S/Q/T/A (and relative variants) + Z, including reflected control points for smooth
 * curves and an arc → cubic conversion. Tolerant: a malformed segment ends parsing rather than throwing.
 */
object AndroidPathParser {

    fun parse(pathData: String, fillEvenOdd: Boolean = false): Path {
        val path = Path()
        if (fillEvenOdd) path.fillType = PathFillType.EvenOdd
        runCatching { build(pathData, path) }
        return path
    }

    private class Cursor(var x: Float = 0f, var y: Float = 0f)

    private fun build(data: String, path: Path) {
        val tokens = tokenize(data)
        var i = 0
        val cur = Cursor()
        val start = Cursor()
        var prevCmd = ' '
        var lastCtrlX = 0f
        var lastCtrlY = 0f

        fun num(): Float = tokens[i++].toFloat()

        while (i < tokens.size) {
            val cmd = if (tokens[i].length == 1 && tokens[i][0].isLetter()) tokens[i++][0] else prevCmd
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    var x = num(); var y = num()
                    if (rel) { x += cur.x; y += cur.y }
                    path.moveTo(x, y); cur.x = x; cur.y = y; start.x = x; start.y = y
                    // Subsequent implicit pairs after M are treated as L.
                    while (hasNumber(tokens, i)) {
                        var lx = num(); var ly = num()
                        if (rel) { lx += cur.x; ly += cur.y }
                        path.lineTo(lx, ly); cur.x = lx; cur.y = ly
                    }
                }
                'L' -> while (hasNumber(tokens, i)) {
                    var x = num(); var y = num()
                    if (rel) { x += cur.x; y += cur.y }
                    path.lineTo(x, y); cur.x = x; cur.y = y
                }
                'H' -> while (hasNumber(tokens, i)) {
                    var x = num(); if (rel) x += cur.x
                    path.lineTo(x, cur.y); cur.x = x
                }
                'V' -> while (hasNumber(tokens, i)) {
                    var y = num(); if (rel) y += cur.y
                    path.lineTo(cur.x, y); cur.y = y
                }
                'C' -> while (hasNumber(tokens, i)) {
                    var x1 = num(); var y1 = num(); var x2 = num(); var y2 = num(); var x = num(); var y = num()
                    if (rel) { x1 += cur.x; y1 += cur.y; x2 += cur.x; y2 += cur.y; x += cur.x; y += cur.y }
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastCtrlX = x2; lastCtrlY = y2; cur.x = x; cur.y = y
                }
                'S' -> while (hasNumber(tokens, i)) {
                    var x2 = num(); var y2 = num(); var x = num(); var y = num()
                    if (rel) { x2 += cur.x; y2 += cur.y; x += cur.x; y += cur.y }
                    val (rx, ry) = reflect(prevCmd, cur, lastCtrlX, lastCtrlY)
                    path.cubicTo(rx, ry, x2, y2, x, y)
                    lastCtrlX = x2; lastCtrlY = y2; cur.x = x; cur.y = y
                }
                'Q' -> while (hasNumber(tokens, i)) {
                    var x1 = num(); var y1 = num(); var x = num(); var y = num()
                    if (rel) { x1 += cur.x; y1 += cur.y; x += cur.x; y += cur.y }
                    path.quadraticBezierTo(x1, y1, x, y)
                    lastCtrlX = x1; lastCtrlY = y1; cur.x = x; cur.y = y
                }
                'T' -> while (hasNumber(tokens, i)) {
                    var x = num(); var y = num()
                    if (rel) { x += cur.x; y += cur.y }
                    val (rx, ry) = reflect(prevCmd, cur, lastCtrlX, lastCtrlY)
                    path.quadraticBezierTo(rx, ry, x, y)
                    lastCtrlX = rx; lastCtrlY = ry; cur.x = x; cur.y = y
                }
                'A' -> while (hasNumber(tokens, i)) {
                    val rx = num(); val ry = num(); val rot = num()
                    val large = num() != 0f; val sweep = num() != 0f
                    var x = num(); var y = num()
                    if (rel) { x += cur.x; y += cur.y }
                    arcTo(path, cur.x, cur.y, rx, ry, rot, large, sweep, x, y)
                    cur.x = x; cur.y = y
                }
                'Z' -> { path.close(); cur.x = start.x; cur.y = start.y }
                else -> return // unknown command — stop
            }
            prevCmd = cmd
        }
    }

    /** Reflected control point for S/T smooth curves: mirror the last control point about the current point. */
    private fun reflect(prevCmd: Char, cur: Cursor, lastCtrlX: Float, lastCtrlY: Float): Pair<Float, Float> {
        val smooth = prevCmd.uppercaseChar() in setOf('C', 'S', 'Q', 'T')
        return if (smooth) (2 * cur.x - lastCtrlX) to (2 * cur.y - lastCtrlY) else cur.x to cur.y
    }

    /** Endpoint-parameterised elliptical arc → cubic bezier segments (SVG implementation notes F.6). */
    private fun arcTo(
        path: Path, x0: Float, y0: Float, rxIn: Float, ryIn: Float, rotDeg: Float,
        large: Boolean, sweep: Boolean, x: Float, y: Float,
    ) {
        if (rxIn == 0f || ryIn == 0f) { path.lineTo(x, y); return }
        var rx = abs(rxIn); var ry = abs(ryIn)
        val phi = rotDeg * PI.toFloat() / 180f
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x0 - x) / 2f; val dy = (y0 - y) / 2f
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        var lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1f) { val s = sqrt(lambda); rx *= s; ry *= s }
        val sign = if (large != sweep) 1f else -1f
        val num = (rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p).coerceAtLeast(0f)
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val co = if (den == 0f) 0f else sign * sqrt(num / den)
        val cxp = co * (rx * y1p / ry)
        val cyp = co * -(ry * x1p / rx)
        val cx = cosP * cxp - sinP * cyp + (x0 + x) / 2f
        val cy = sinP * cxp + cosP * cyp + (y0 + y) / 2f
        val theta1 = angle(1f, 0f, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dTheta > 0) dTheta -= 2f * PI.toFloat()
        if (sweep && dTheta < 0) dTheta += 2f * PI.toFloat()

        val segments = ceil(abs(dTheta) / (PI.toFloat() / 2f)).toInt().coerceAtLeast(1)
        val delta = dTheta / segments
        val t = 4f / 3f * sin(delta / 2f) / (1f + cos(delta / 2f))
        var th = theta1
        for (seg in 0 until segments) {
            val cosTh = cos(th); val sinTh = sin(th)
            val cosTh2 = cos(th + delta); val sinTh2 = sin(th + delta)
            val e1x = cx + rx * cosP * cosTh - ry * sinP * sinTh
            val e1y = cy + rx * sinP * cosTh + ry * cosP * sinTh
            val e2x = cx + rx * cosP * cosTh2 - ry * sinP * sinTh2
            val e2y = cy + rx * sinP * cosTh2 + ry * cosP * sinTh2
            val d1x = -rx * cosP * sinTh - ry * sinP * cosTh
            val d1y = -rx * sinP * sinTh + ry * cosP * cosTh
            val d2x = -rx * cosP * sinTh2 - ry * sinP * cosTh2
            val d2y = -rx * sinP * sinTh2 + ry * cosP * cosTh2
            path.cubicTo(e1x + t * d1x, e1y + t * d1y, e2x - t * d2x, e2y - t * d2y, e2x, e2y)
            th += delta
        }
    }

    private fun angle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
        val dot = ux * vx + uy * vy
        val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        var a = kotlin.math.acos((dot / len).coerceIn(-1f, 1f))
        if (ux * vy - uy * vx < 0) a = -a
        return a
    }

    private fun hasNumber(tokens: List<String>, i: Int): Boolean =
        i < tokens.size && !(tokens[i].length == 1 && tokens[i][0].isLetter())

    /** Split path data into command letters and numbers (handles `-`/`.`/`e` and comma/space separators). */
    private fun tokenize(data: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
        var idx = 0
        while (idx < data.length) {
            val c = data[idx]
            when {
                c.isLetter() -> { flush(); out += c.toString() }
                c == ',' || c.isWhitespace() -> flush()
                c == '-' || c == '+' -> {
                    // A sign starts a new number unless it's an exponent sign (e/E just before).
                    if (sb.isNotEmpty() && (sb.last() == 'e' || sb.last() == 'E')) sb.append(c)
                    else { flush(); sb.append(c) }
                }
                c == '.' -> {
                    // A second '.' in a token starts a new number (e.g. "1.5.5" → "1.5", ".5").
                    if (sb.contains('.')) { flush(); sb.append(c) } else sb.append(c)
                }
                else -> sb.append(c)
            }
            idx++
        }
        flush()
        return out
    }
}
