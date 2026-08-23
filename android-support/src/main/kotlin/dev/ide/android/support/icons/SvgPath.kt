package dev.ide.android.support.icons

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A 2x3 affine transform in SVG's `matrix(a b c d e f)` order:
 * `x' = a·x + c·y + e`, `y' = b·x + d·y + f`.
 *
 * Used to fold an SVG's nested `<g transform=…>` chain (and its `viewBox` mapping) into one matrix, which the
 * converter either expresses as a VectorDrawable `<group>` or bakes straight into the path coordinates.
 */
data class Affine(
    val a: Float, val b: Float,
    val c: Float, val d: Float,
    val e: Float, val f: Float,
) {

    /** This transform applied *after* [inner]: the matrix product `this · inner`. */
    operator fun times(inner: Affine) = Affine(
        a = a * inner.a + c * inner.b,
        b = b * inner.a + d * inner.b,
        c = a * inner.c + c * inner.d,
        d = b * inner.c + d * inner.d,
        e = a * inner.e + c * inner.f + e,
        f = b * inner.e + d * inner.f + f,
    )

    fun applyX(x: Float, y: Float): Float = a * x + c * y + e
    fun applyY(x: Float, y: Float): Float = b * x + d * y + f

    /** The linear part only, for transforming a tangent/derivative that no translation applies to. */
    fun deltaX(dx: Float, dy: Float): Float = a * dx + c * dy
    fun deltaY(dx: Float, dy: Float): Float = b * dx + d * dy

    val isIdentity: Boolean
        get() = near(a, 1f) && near(b, 0f) && near(c, 0f) && near(d, 1f) && near(e, 0f) && near(f, 0f)

    /**
     * True when this is a scale-then-translate with no rotation or skew, which is exactly what a
     * VectorDrawable `<group>` expresses losslessly (`scaleX`/`scaleY` + `translateX`/`translateY`).
     */
    val isAxisAligned: Boolean get() = near(b, 0f) && near(c, 0f)

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(tx: Float, ty: Float) = Affine(1f, 0f, 0f, 1f, tx, ty)
        fun scale(sx: Float, sy: Float) = Affine(sx, 0f, 0f, sy, 0f, 0f)

        fun rotate(degrees: Float, cx: Float = 0f, cy: Float = 0f): Affine {
            val r = degrees * PI_OVER_180
            val cs = cos(r)
            val sn = sin(r)
            val rot = Affine(cs, sn, -sn, cs, 0f, 0f)
            if (cx == 0f && cy == 0f) return rot
            return translate(cx, cy) * rot * translate(-cx, -cy)
        }

        fun skewX(degrees: Float) = Affine(1f, 0f, tan(degrees * PI_OVER_180), 1f, 0f, 0f)
        fun skewY(degrees: Float) = Affine(1f, tan(degrees * PI_OVER_180), 0f, 1f, 0f, 0f)

        /**
         * Parses an SVG `transform` attribute (`matrix`, `translate`, `scale`, `rotate`, `skewX`, `skewY`, in any
         * order, space- or comma-separated) into a single matrix. Unknown functions are skipped, so a
         * transform list this doesn't understand degrades to the part it does rather than throwing.
         */
        fun parse(raw: String?): Affine {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return IDENTITY
            var m = IDENTITY
            for (match in FUNCTION.findAll(s)) {
                val name = match.groupValues[1]
                val args = numbers(match.groupValues[2])
                val step = when (name) {
                    "matrix" -> if (args.size >= 6) Affine(args[0], args[1], args[2], args[3], args[4], args[5]) else null
                    "translate" -> if (args.isNotEmpty()) translate(args[0], args.getOrElse(1) { 0f }) else null
                    "scale" -> if (args.isNotEmpty()) scale(args[0], args.getOrElse(1) { args[0] }) else null
                    "rotate" -> if (args.isNotEmpty())
                        rotate(args[0], args.getOrElse(1) { 0f }, args.getOrElse(2) { 0f }) else null
                    "skewX" -> if (args.isNotEmpty()) skewX(args[0]) else null
                    "skewY" -> if (args.isNotEmpty()) skewY(args[0]) else null
                    else -> null
                }
                if (step != null) m *= step
            }
            return m
        }

        private val FUNCTION = Regex("""([a-zA-Z]+)\s*\(([^)]*)\)""")

        private const val PI_OVER_180 = (Math.PI / 180.0).toFloat()

        internal fun numbers(raw: String): List<Float> =
            NUMBER.findAll(raw).mapNotNull { it.value.toFloatOrNull() }.toList()

        internal val NUMBER = Regex("""[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""")

        private fun near(v: Float, target: Float) = abs(v - target) < 1e-4f
    }
}

/**
 * One absolute segment of a parsed path. Every relative command, `H`/`V` shorthand, `S`/`T` reflection and
 * `A` arc is normalised into these four cases, so applying an affine transform is just mapping the points
 * (an affine map takes a cubic/quadratic Bézier to a cubic/quadratic Bézier with the same degree).
 */
sealed interface PathSegment {
    data class MoveTo(val x: Float, val y: Float) : PathSegment
    data class LineTo(val x: Float, val y: Float) : PathSegment
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathSegment
    data class CubicTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x: Float, val y: Float,
    ) : PathSegment

    data object Close : PathSegment
}

/**
 * SVG/VectorDrawable path-data handling: parse `d` into absolute [PathSegment]s, transform them by an
 * [Affine], and print them back as a compact `pathData` string.
 *
 * This exists because a VectorDrawable `<group>` can only express scale/rotate/translate, so an SVG
 * `matrix(…)` or `skewX(…)` has to be *baked into the coordinates* instead. Arcs are converted to cubic
 * Béziers on the way in, since an arc under a skew is no longer an axis-aligned elliptical arc; every other
 * segment type survives an affine map exactly. Path data that needs no transform is never round-tripped
 * through here, so the common case keeps its original string byte for byte.
 */
object SvgPathData {

    /** Parse [d] into absolute segments. Malformed input yields whatever parsed cleanly before the problem. */
    fun parse(d: String): List<PathSegment> {
        val out = ArrayList<PathSegment>()
        val tokens = tokenize(d)
        var i = 0
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        // The reflected control point for an `S`/`T` shorthand. Only valid right after a curve of its kind.
        var lastCubicCtrlX = 0f
        var lastCubicCtrlY = 0f
        var lastQuadCtrlX = 0f
        var lastQuadCtrlY = 0f
        var prevWasCubic = false
        var prevWasQuad = false

        while (i < tokens.size) {
            val token = tokens[i]
            if (token !is Token.Cmd) { i++; continue }   // a stray number with no command, so resync
            val cmd = token.ch
            i++
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    var first = true
                    while (hasNums(tokens, i, 2)) {
                        val x = num(tokens, i) + if (rel) cx else 0f
                        val y = num(tokens, i + 1) + if (rel) cy else 0f
                        i += 2
                        if (first) {
                            out += PathSegment.MoveTo(x, y); startX = x; startY = y; first = false
                        } else {
                            out += PathSegment.LineTo(x, y) // extra pairs after M are implicit lineTos
                        }
                        cx = x; cy = y
                    }
                    prevWasCubic = false; prevWasQuad = false
                }

                'L' -> {
                    while (hasNums(tokens, i, 2)) {
                        val x = num(tokens, i) + if (rel) cx else 0f
                        val y = num(tokens, i + 1) + if (rel) cy else 0f
                        i += 2
                        out += PathSegment.LineTo(x, y); cx = x; cy = y
                    }
                    prevWasCubic = false; prevWasQuad = false
                }

                'H' -> {
                    while (hasNums(tokens, i, 1)) {
                        val x = num(tokens, i) + if (rel) cx else 0f
                        i += 1
                        out += PathSegment.LineTo(x, cy); cx = x
                    }
                    prevWasCubic = false; prevWasQuad = false
                }

                'V' -> {
                    while (hasNums(tokens, i, 1)) {
                        val y = num(tokens, i) + if (rel) cy else 0f
                        i += 1
                        out += PathSegment.LineTo(cx, y); cy = y
                    }
                    prevWasCubic = false; prevWasQuad = false
                }

                'C' -> {
                    while (hasNums(tokens, i, 6)) {
                        val ox = if (rel) cx else 0f
                        val oy = if (rel) cy else 0f
                        val x1 = num(tokens, i) + ox; val y1 = num(tokens, i + 1) + oy
                        val x2 = num(tokens, i + 2) + ox; val y2 = num(tokens, i + 3) + oy
                        val x = num(tokens, i + 4) + ox; val y = num(tokens, i + 5) + oy
                        i += 6
                        out += PathSegment.CubicTo(x1, y1, x2, y2, x, y)
                        lastCubicCtrlX = x2; lastCubicCtrlY = y2; cx = x; cy = y
                    }
                    prevWasCubic = true; prevWasQuad = false
                }

                'S' -> {
                    while (hasNums(tokens, i, 4)) {
                        val ox = if (rel) cx else 0f
                        val oy = if (rel) cy else 0f
                        val x1 = if (prevWasCubic) 2 * cx - lastCubicCtrlX else cx
                        val y1 = if (prevWasCubic) 2 * cy - lastCubicCtrlY else cy
                        val x2 = num(tokens, i) + ox; val y2 = num(tokens, i + 1) + oy
                        val x = num(tokens, i + 2) + ox; val y = num(tokens, i + 3) + oy
                        i += 4
                        out += PathSegment.CubicTo(x1, y1, x2, y2, x, y)
                        lastCubicCtrlX = x2; lastCubicCtrlY = y2; cx = x; cy = y
                        prevWasCubic = true
                    }
                    prevWasQuad = false
                }

                'Q' -> {
                    while (hasNums(tokens, i, 4)) {
                        val ox = if (rel) cx else 0f
                        val oy = if (rel) cy else 0f
                        val x1 = num(tokens, i) + ox; val y1 = num(tokens, i + 1) + oy
                        val x = num(tokens, i + 2) + ox; val y = num(tokens, i + 3) + oy
                        i += 4
                        out += PathSegment.QuadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1; lastQuadCtrlY = y1; cx = x; cy = y
                        prevWasQuad = true
                    }
                    prevWasCubic = false
                }

                'T' -> {
                    while (hasNums(tokens, i, 2)) {
                        val ox = if (rel) cx else 0f
                        val oy = if (rel) cy else 0f
                        val x1 = if (prevWasQuad) 2 * cx - lastQuadCtrlX else cx
                        val y1 = if (prevWasQuad) 2 * cy - lastQuadCtrlY else cy
                        val x = num(tokens, i) + ox; val y = num(tokens, i + 1) + oy
                        i += 2
                        out += PathSegment.QuadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1; lastQuadCtrlY = y1; cx = x; cy = y
                        prevWasQuad = true
                    }
                    prevWasCubic = false
                }

                'A' -> {
                    while (hasNums(tokens, i, 7)) {
                        val rx = num(tokens, i); val ry = num(tokens, i + 1)
                        val rot = num(tokens, i + 2)
                        val largeArc = num(tokens, i + 3) != 0f
                        val sweep = num(tokens, i + 4) != 0f
                        val x = num(tokens, i + 5) + if (rel) cx else 0f
                        val y = num(tokens, i + 6) + if (rel) cy else 0f
                        i += 7
                        out += arcToCubics(cx, cy, rx, ry, rot, largeArc, sweep, x, y)
                        cx = x; cy = y
                    }
                    prevWasCubic = false; prevWasQuad = false
                }

                'Z' -> {
                    out += PathSegment.Close
                    cx = startX; cy = startY
                    prevWasCubic = false; prevWasQuad = false
                }

                else -> { /* unknown command letter: skip it and resync on the next one */ }
            }
        }
        return out
    }

    /** [segments] with every point mapped through [m]. */
    fun transform(segments: List<PathSegment>, m: Affine): List<PathSegment> = segments.map { seg ->
        when (seg) {
            is PathSegment.MoveTo -> PathSegment.MoveTo(m.applyX(seg.x, seg.y), m.applyY(seg.x, seg.y))
            is PathSegment.LineTo -> PathSegment.LineTo(m.applyX(seg.x, seg.y), m.applyY(seg.x, seg.y))
            is PathSegment.QuadTo -> PathSegment.QuadTo(
                m.applyX(seg.x1, seg.y1), m.applyY(seg.x1, seg.y1),
                m.applyX(seg.x, seg.y), m.applyY(seg.x, seg.y),
            )

            is PathSegment.CubicTo -> PathSegment.CubicTo(
                m.applyX(seg.x1, seg.y1), m.applyY(seg.x1, seg.y1),
                m.applyX(seg.x2, seg.y2), m.applyY(seg.x2, seg.y2),
                m.applyX(seg.x, seg.y), m.applyY(seg.x, seg.y),
            )

            PathSegment.Close -> PathSegment.Close
        }
    }

    /** Print [segments] as absolute `pathData`, one command letter per segment, numbers trimmed. */
    fun toPathData(segments: List<PathSegment>): String {
        val sb = StringBuilder()
        for (seg in segments) {
            when (seg) {
                is PathSegment.MoveTo -> sb.append('M').append(n(seg.x)).append(',').append(n(seg.y))
                is PathSegment.LineTo -> sb.append('L').append(n(seg.x)).append(',').append(n(seg.y))
                is PathSegment.QuadTo -> sb.append('Q').append(n(seg.x1)).append(',').append(n(seg.y1))
                    .append(' ').append(n(seg.x)).append(',').append(n(seg.y))

                is PathSegment.CubicTo -> sb.append('C').append(n(seg.x1)).append(',').append(n(seg.y1))
                    .append(' ').append(n(seg.x2)).append(',').append(n(seg.y2))
                    .append(' ').append(n(seg.x)).append(',').append(n(seg.y))

                PathSegment.Close -> sb.append('Z')
            }
        }
        return sb.toString()
    }

    /** Parse, transform and reprint in one step: the "bake this matrix into the coordinates" path. */
    fun transformPathData(d: String, m: Affine): String =
        if (m.isIdentity) d else toPathData(transform(parse(d), m))

    // --- arc → cubic ---------------------------------------------------------------------------------

    /**
     * The SVG endpoint-parameterised arc `A rx ry rot largeArc sweep x y` from ([x0], [y0]), as cubic
     * Béziers, at most one per 90° of sweep, which keeps the approximation error well under a rasterised
     * pixel at icon sizes. Degenerate radii collapse to a straight line, as the SVG spec requires.
     */
    internal fun arcToCubics(
        x0: Float, y0: Float,
        rxIn: Float, ryIn: Float, rotDeg: Float,
        largeArc: Boolean, sweep: Boolean,
        x: Float, y: Float,
    ): List<PathSegment> {
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx < 1e-6f || ry < 1e-6f || (x0 == x && y0 == y)) return listOf(PathSegment.LineTo(x, y))

        val phi = (rotDeg * Math.PI / 180.0)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        // Endpoint → centre parameterisation (SVG implementation notes, F.6.5).
        val dx2 = (x0 - x) / 2.0
        val dy2 = (y0 - y) / 2.0
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        // Scale the radii up if they're too small to span the endpoints (F.6.6).
        val lambda = (x1p * x1p) / (rx * rx).toDouble() + (y1p * y1p) / (ry * ry).toDouble()
        if (lambda > 1.0) {
            val s = sqrt(lambda).toFloat()
            rx *= s
            ry *= s
        }
        val rxD = rx.toDouble()
        val ryD = ry.toDouble()

        val denom = rxD * rxD * y1p * y1p + ryD * ryD * x1p * x1p
        val numer = (rxD * rxD * ryD * ryD - rxD * rxD * y1p * y1p - ryD * ryD * x1p * x1p).coerceAtLeast(0.0)
        val coefMag = if (denom == 0.0) 0.0 else sqrt(numer / denom)
        val coef = if (largeArc != sweep) coefMag else -coefMag
        val cxp = coef * (rxD * y1p / ryD)
        val cyp = coef * (-ryD * x1p / rxD)
        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0

        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rxD, (y1p - cyp) / ryD)
        var delta = angle((x1p - cxp) / rxD, (y1p - cyp) / ryD, (-x1p - cxp) / rxD, (-y1p - cyp) / ryD)
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        if (sweep && delta < 0) delta += 2 * Math.PI

        val steps = ceil(abs(delta) / (Math.PI / 2) - 1e-9).toInt().coerceAtLeast(1)
        val step = delta / steps
        // The control-point scale that makes a cubic match an elliptical arc of `step` radians.
        val alpha = sin(step) * (sqrt(4.0 + 3.0 * tan(step / 2).let { it * it }) - 1.0) / 3.0

        val out = ArrayList<PathSegment>(steps)
        var t = theta1
        for (k in 0 until steps) {
            val t2 = t + step
            val (px, py) = ellipse(cx, cy, rxD, ryD, cosPhi, sinPhi, t)
            val (qx, qy) = ellipse(cx, cy, rxD, ryD, cosPhi, sinPhi, t2)
            val (dpx, dpy) = ellipseDeriv(rxD, ryD, cosPhi, sinPhi, t)
            val (dqx, dqy) = ellipseDeriv(rxD, ryD, cosPhi, sinPhi, t2)
            out += PathSegment.CubicTo(
                (px + alpha * dpx).toFloat(), (py + alpha * dpy).toFloat(),
                (qx - alpha * dqx).toFloat(), (qy - alpha * dqy).toFloat(),
                qx.toFloat(), qy.toFloat(),
            )
            t = t2
        }
        return out
    }

    private fun ellipse(
        cx: Double, cy: Double, rx: Double, ry: Double,
        cosPhi: Double, sinPhi: Double, t: Double,
    ): Pair<Double, Double> {
        val ct = cos(t)
        val st = sin(t)
        return (cx + rx * ct * cosPhi - ry * st * sinPhi) to (cy + rx * ct * sinPhi + ry * st * cosPhi)
    }

    private fun ellipseDeriv(
        rx: Double, ry: Double,
        cosPhi: Double, sinPhi: Double, t: Double,
    ): Pair<Double, Double> {
        val ct = cos(t)
        val st = sin(t)
        return (-rx * st * cosPhi - ry * ct * sinPhi) to (-rx * st * sinPhi + ry * ct * cosPhi)
    }

    /** The signed angle from vector (ux,uy) to (vx,vy), in radians. */
    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
        if (len == 0.0) return 0.0
        val acos = Math.acos((dot / len).coerceIn(-1.0, 1.0))
        return if (ux * vy - uy * vx < 0) -acos else acos
    }

    // --- tokenizing ----------------------------------------------------------------------------------

    private sealed interface Token {
        data class Cmd(val ch: Char) : Token
        data class Num(val v: Float) : Token
    }

    /**
     * Splits path data into command letters and numbers. Handles the compact forms real files use: no
     * separator before a `-` or a `.` (`M0,0L-1.5.5`), and exponents (`1e-5`).
     */
    private fun tokenize(d: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < d.length) {
            val ch = d[i]
            when {
                ch.isWhitespace() || ch == ',' -> i++
                ch.isLetter() -> { out += Token.Cmd(ch); i++ }
                else -> {
                    val m = Affine.NUMBER.matchAt(d, i)
                    if (m == null || m.value.isEmpty()) {
                        i++ // not a number and not a command, so skip it
                    } else {
                        m.value.toFloatOrNull()?.let { out += Token.Num(it) }
                        i += m.value.length
                    }
                }
            }
        }
        return out
    }

    private fun hasNums(tokens: List<Token>, from: Int, count: Int): Boolean {
        if (from + count > tokens.size) return false
        for (k in 0 until count) if (tokens[from + k] !is Token.Num) return false
        return true
    }

    private fun num(tokens: List<Token>, at: Int): Float = (tokens[at] as Token.Num).v

    /** Compact decimal: integers print without a fraction, others to at most three places. */
    internal fun n(v: Float): String {
        val rounded = Math.round(v * 1000f) / 1000f
        if (rounded == rounded.toLong().toFloat()) return rounded.toLong().toString()
        return rounded.toString().trimEnd('0').trimEnd('.')
    }
}
