package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.PathMeasure
import dev.ide.ui.backend.UiAnimatedValue
import dev.ide.ui.backend.UiAnimator
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiInterpolator
import dev.ide.ui.backend.UiVectorGroup
import dev.ide.ui.backend.UiVectorNode
import dev.ide.ui.backend.UiVectorPath
import dev.ide.ui.concurrent.UiLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Playback for animated drawables. [atTime] turns an `<animation-list>` or `<animated-vector>` (also when nested
 * in a layer-list or selector) into the static drawable it shows at a moment, which [drawUiDrawable] then draws
 * like any other; the preview pane drives the clock.
 */
object DrawableAnimation {

    /** Whether [d], or anything inside it, changes over time. */
    fun isAnimated(d: UiDrawable): Boolean = when (d) {
        is UiDrawable.Frames -> d.frames.size > 1
        is UiDrawable.AnimatedVector -> d.targets.isNotEmpty()
        is UiDrawable.Layers -> d.layers.any { isAnimated(it.drawable) }
        is UiDrawable.States -> d.defaultLayer?.let(::isAnimated) == true
        else -> false
    }

    /**
     * How long one play of [d] lasts, in ms, or null when it never ends (a looping animation-list, an animator
     * that repeats forever). A drawable that does not move lasts 0.
     */
    fun durationMs(d: UiDrawable): Long? = when (d) {
        is UiDrawable.Frames -> if (d.oneShot) d.frames.sumOf { it.durationMs.toLong() } else null
        is UiDrawable.AnimatedVector -> d.targets.fold(0L as Long?) { acc, t -> maxOrNull(acc, endMs(t.animator)) }
        is UiDrawable.Layers -> d.layers.fold(0L as Long?) { acc, l -> maxOrNull(acc, durationMs(l.drawable)) }
        is UiDrawable.States -> d.defaultLayer?.let(::durationMs) ?: 0L
        else -> 0L
    }

    /** What [d] shows [timeMs] after its animation started. */
    fun atTime(d: UiDrawable, timeMs: Long): UiDrawable = when (d) {
        is UiDrawable.Frames -> frameAt(d, timeMs)
        is UiDrawable.AnimatedVector -> vectorAt(d, timeMs)
        is UiDrawable.Layers -> d.copy(layers = d.layers.map { it.copy(drawable = atTime(it.drawable, timeMs)) })
        is UiDrawable.States -> d.copy(defaultLayer = d.defaultLayer?.let { atTime(it, timeMs) })
        else -> d
    }

    // --- animation-list ------------------------------------------------------------------------------------

    private fun frameAt(d: UiDrawable.Frames, timeMs: Long): UiDrawable {
        val frames = d.frames
        if (frames.isEmpty()) return UiDrawable.Unsupported("animation-list", "No frames")
        val total = frames.sumOf { it.durationMs.toLong() }
        if (total <= 0L) return frames.first().drawable
        // A one-shot list stops on its last frame; a looping one starts over.
        var t = if (d.oneShot) timeMs.coerceAtMost(total - 1) else floorMod(timeMs, total)
        for (frame in frames) {
            if (t < frame.durationMs) return frame.drawable
            t -= frame.durationMs
        }
        return frames.last().drawable
    }

    // --- animated-vector -----------------------------------------------------------------------------------

    /** One animator's write to a property, with the moment it started so later starters win. */
    private class Write(val start: Long, val target: String, val property: String, val value: UiAnimatedValue)

    private fun vectorAt(d: UiDrawable.AnimatedVector, timeMs: Long): UiDrawable.Vector {
        val base = d.vector
        val writes = ArrayList<Write>()
        for (target in d.targets) {
            collect(target.animator, 0L, timeMs, target.name, base, writes)
        }
        if (writes.isEmpty()) return base
        // Android leaves a property with the value of the animator that last took hold of it.
        writes.sortBy { it.start }
        val values = HashMap<String, HashMap<String, UiAnimatedValue>>()
        for (w in writes) values.getOrPut(w.target) { HashMap() }[w.property] = w.value
        return base.copy(
            rootAlpha = (base.name?.let { values[it] }?.get("alpha") as? UiAnimatedValue.Number)?.value ?: base.rootAlpha,
            nodes = base.nodes.map { apply(it, values) },
        )
    }

    private fun collect(
        a: UiAnimator, start: Long, timeMs: Long, target: String, base: UiDrawable.Vector, out: MutableList<Write>,
    ) {
        when (a) {
            is UiAnimator.Set -> {
                var childStart = start
                for (child in a.children) {
                    collect(child, childStart, timeMs, target, base, out)
                    if (a.sequential) childStart += endMs(child) ?: return
                }
            }
            is UiAnimator.Property -> {
                val begin = start + a.startOffsetMs
                val local = timeMs - begin
                if (local < 0) return // not started yet: the property keeps its own value
                val fraction = interpolate(a.interpolator, iterationFraction(a, local))
                val value = valueAt(a, fraction, baseValue(base, target, a.property)) ?: return
                out += Write(begin, target, a.property, value)
            }
        }
    }

    /** When [a] finishes, measured from its own start; null when it repeats forever. */
    private fun endMs(a: UiAnimator): Long? = when (a) {
        is UiAnimator.Set -> if (a.sequential) {
            a.children.fold(0L as Long?) { acc, c -> acc?.let { s -> endMs(c)?.let { s + it } } }
        } else {
            a.children.fold(0L as Long?) { acc, c -> maxOrNull(acc, endMs(c)) }
        }
        is UiAnimator.Property ->
            if (a.repeatCount < 0) null else a.startOffsetMs + a.durationMs * (a.repeatCount + 1)
    }

    /** The 0..1 progress through the current repeat, already reversed for an odd repeat of a reversing animator. */
    private fun iterationFraction(a: UiAnimator.Property, local: Long): Float {
        if (a.durationMs <= 0L) return 1f
        var iteration = local / a.durationMs
        var fraction = (local % a.durationMs).toFloat() / a.durationMs
        if (a.repeatCount >= 0 && iteration > a.repeatCount) {
            iteration = a.repeatCount.toLong()
            fraction = 1f
        }
        return if (a.reverse && iteration % 2L == 1L) 1f - fraction else fraction
    }

    /** The value at [fraction] (interpolated, possibly beyond 0..1) between the surrounding keyframes. */
    private fun valueAt(a: UiAnimator.Property, fraction: Float, base: UiAnimatedValue?): UiAnimatedValue? {
        val kfs = a.keyframes
        if (kfs.isEmpty()) return null
        if (kfs.size == 1) return kfs[0].value ?: base
        var i = 1
        while (i < kfs.size - 1 && fraction > kfs[i].fraction) i++
        val from = kfs[i - 1]
        val to = kfs[i]
        val span = to.fraction - from.fraction
        var t = if (span <= 0f) 1f else (fraction - from.fraction) / span
        to.interpolator?.let { t = interpolate(it, t) }
        return lerp(from.value ?: base ?: return to.value, to.value ?: base ?: return from.value, t)
    }

    private fun lerp(a: UiAnimatedValue, b: UiAnimatedValue, t: Float): UiAnimatedValue = when {
        a is UiAnimatedValue.Number && b is UiAnimatedValue.Number -> UiAnimatedValue.Number(a.value + (b.value - a.value) * t)
        a is UiAnimatedValue.Color && b is UiAnimatedValue.Color -> UiAnimatedValue.Color(lerpArgb(a.argb, b.argb, t))
        a is UiAnimatedValue.PathData && b is UiAnimatedValue.PathData ->
            UiAnimatedValue.PathData(PathMorph.lerp(a.data, b.data, t) ?: if (t >= 1f) b.data else a.data)
        else -> if (t >= 1f) b else a
    }

    private fun lerpArgb(a: Long, b: Long, t: Float): Long {
        fun ch(v: Long, shift: Int) = ((v shr shift) and 0xFF).toInt()
        fun mix(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).roundToInt().coerceIn(0, 255).toLong()
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    // --- the vector's own values ---------------------------------------------------------------------------

    private fun baseValue(v: UiDrawable.Vector, target: String, property: String): UiAnimatedValue? {
        if (target == v.name && property == "alpha") return UiAnimatedValue.Number(v.rootAlpha)
        val node = find(v.nodes, target) ?: return null
        return when (node) {
            is UiVectorGroup -> when (property) {
                "rotation" -> node.rotation
                "pivotX" -> node.pivotX
                "pivotY" -> node.pivotY
                "scaleX" -> node.scaleX
                "scaleY" -> node.scaleY
                "translateX" -> node.translateX
                "translateY" -> node.translateY
                else -> null
            }?.let { UiAnimatedValue.Number(it) }
            is UiVectorPath -> when (property) {
                "pathData" -> UiAnimatedValue.PathData(node.pathData)
                "fillColor" -> UiAnimatedValue.Color(node.fillColor ?: 0L)
                "strokeColor" -> UiAnimatedValue.Color(node.strokeColor ?: 0L)
                "strokeWidth" -> UiAnimatedValue.Number(node.strokeWidthVp)
                "fillAlpha" -> UiAnimatedValue.Number(node.fillAlpha)
                "strokeAlpha" -> UiAnimatedValue.Number(node.strokeAlpha)
                "trimPathStart" -> UiAnimatedValue.Number(node.trimPathStart)
                "trimPathEnd" -> UiAnimatedValue.Number(node.trimPathEnd)
                "trimPathOffset" -> UiAnimatedValue.Number(node.trimPathOffset)
                else -> null
            }
        }
    }

    private fun find(nodes: List<UiVectorNode>, name: String): UiVectorNode? {
        for (n in nodes) {
            when (n) {
                is UiVectorPath -> if (n.name == name) return n
                is UiVectorGroup -> {
                    if (n.name == name) return n
                    find(n.children, name)?.let { return it }
                }
            }
        }
        return null
    }

    private fun apply(node: UiVectorNode, values: Map<String, Map<String, UiAnimatedValue>>): UiVectorNode = when (node) {
        is UiVectorGroup -> {
            val v = node.name?.let(values::get)
            fun num(p: String, d: Float) = (v?.get(p) as? UiAnimatedValue.Number)?.value ?: d
            node.copy(
                children = node.children.map { apply(it, values) },
                rotation = num("rotation", node.rotation),
                pivotX = num("pivotX", node.pivotX),
                pivotY = num("pivotY", node.pivotY),
                scaleX = num("scaleX", node.scaleX),
                scaleY = num("scaleY", node.scaleY),
                translateX = num("translateX", node.translateX),
                translateY = num("translateY", node.translateY),
            )
        }
        is UiVectorPath -> {
            val v = node.name?.let(values::get)
            if (v == null) node else {
                fun num(p: String, d: Float) = (v[p] as? UiAnimatedValue.Number)?.value ?: d
                fun color(p: String, d: Long?) = (v[p] as? UiAnimatedValue.Color)?.argb ?: d
                node.copy(
                    pathData = (v["pathData"] as? UiAnimatedValue.PathData)?.data ?: node.pathData,
                    fillColor = color("fillColor", node.fillColor),
                    strokeColor = color("strokeColor", node.strokeColor),
                    strokeWidthVp = num("strokeWidth", node.strokeWidthVp),
                    fillAlpha = num("fillAlpha", node.fillAlpha),
                    strokeAlpha = num("strokeAlpha", node.strokeAlpha),
                    trimPathStart = num("trimPathStart", node.trimPathStart),
                    trimPathEnd = num("trimPathEnd", node.trimPathEnd),
                    trimPathOffset = num("trimPathOffset", node.trimPathOffset),
                )
            }
        }
    }

    // --- interpolators -------------------------------------------------------------------------------------

    /** The framework interpolators' curves, by the same formulas `android.view.animation` uses. */
    fun interpolate(i: UiInterpolator, t: Float): Float = when (i.kind) {
        "linear" -> t
        "accelerate" -> if (i.factor == 1f) t * t else t.pow(2f * i.factor)
        "decelerate" -> if (i.factor == 1f) 1f - (1f - t) * (1f - t) else 1f - (1f - t).pow(2f * i.factor)
        "anticipate" -> t * t * ((i.factor + 1f) * t - i.factor)
        "overshoot" -> (t - 1f).let { u -> u * u * ((i.factor + 1f) * u + i.factor) + 1f }
        "anticipate_overshoot" -> {
            val s = i.factor * i.extraTension
            if (t < 0.5f) 0.5f * anticipate(t * 2f, s) else 0.5f * (overshoot(t * 2f - 2f, s) + 2f)
        }
        "bounce" -> bounce(t)
        "cycle" -> sin(2f * PI.toFloat() * i.factor * t)
        "cubic" -> if (i.points.size == 4) cubicBezier(i.points[0], i.points[1], i.points[2], i.points[3], t) else t
        "path" -> i.pathData?.let { pathCurve(it, t) } ?: t
        else -> (cos((t + 1f) * PI.toFloat()) / 2f) + 0.5f // accelerate_decelerate
    }

    private fun anticipate(t: Float, s: Float) = t * t * ((s + 1f) * t - s)
    private fun overshoot(t: Float, s: Float) = t * t * ((s + 1f) * t + s)

    private fun bounce(input: Float): Float {
        fun b(t: Float) = t * t * 8f
        val t = input * 1.1226f
        return when {
            t < 0.3535f -> b(t)
            t < 0.7408f -> b(t - 0.54719f) + 0.7f
            t < 0.9644f -> b(t - 0.8526f) + 0.9f
            else -> b(t - 1.0435f) + 0.95f
        }
    }

    /** y on the curve (0,0) (x1,y1) (x2,y2) (1,1) where x = [t], found by bisection on the curve parameter. */
    private fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, t: Float): Float {
        if (t <= 0f || t >= 1f) return t.coerceIn(0f, 1f)
        fun at(a: Float, b: Float, s: Float): Float {
            val u = 1f - s
            return 3f * u * u * s * a + 3f * u * s * s * b + s * s * s
        }
        var lo = 0f
        var hi = 1f
        var s = t
        repeat(24) {
            s = (lo + hi) / 2f
            if (at(x1, x2, s) < t) lo = s else hi = s
        }
        return at(y1, y2, s)
    }

    private val curveLock = UiLock()
    private val curves = HashMap<String, FloatArray>()

    /** y at x = [t] along a `<pathInterpolator android:pathData>` curve, sampled once into an x,y table. */
    private fun pathCurve(pathData: String, t: Float): Float {
        val table = curveLock.withLock { curves[pathData] } ?: sample(pathData).also { s ->
            curveLock.withLock { if (curves.size < 64) curves[pathData] = s }
        }
        val n = table.size / 2
        if (n < 2) return t
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (table[mid * 2] < t) lo = mid else hi = mid
        }
        val x0 = table[lo * 2]; val y0 = table[lo * 2 + 1]
        val x1 = table[hi * 2]; val y1 = table[hi * 2 + 1]
        return if (x1 <= x0) y1 else y0 + (y1 - y0) * ((t - x0) / (x1 - x0))
    }

    private fun sample(pathData: String): FloatArray {
        val measure = PathMeasure()
        measure.setPath(AndroidPathParser.parse(pathData), false)
        val length = measure.length
        if (length <= 0f) return FloatArray(0)
        val steps = 200
        val out = FloatArray((steps + 1) * 2)
        for (k in 0..steps) {
            val p = measure.getPosition(length * k / steps)
            out[k * 2] = p.x
            out[k * 2 + 1] = p.y
        }
        return out
    }

    private fun floorMod(a: Long, b: Long): Long = ((a % b) + b) % b

    private fun maxOrNull(a: Long?, b: Long?): Long? = if (a == null || b == null) null else maxOf(a, b)
}

/**
 * Morphs between two path-data strings the way an animated vector's `pathType` animator does: the two must
 * have the same commands with the same number of arguments, and every number moves from one to the other.
 */
internal object PathMorph {

    /** The path at [t] between [from] and [to], or null when the two are not morphable into each other. */
    fun lerp(from: String, to: String, t: Float): String? {
        val a = tokens(from)
        val b = tokens(to)
        if (a.size != b.size) return null
        val sb = StringBuilder()
        for (k in a.indices) {
            val x = a[k]
            val y = b[k]
            if (x is Char || y is Char) {
                if (x != y) return null
                sb.append(x)
            } else {
                val v = (x as Float) + ((y as Float) - x) * t
                if (sb.isNotEmpty() && sb.last() != ' ' && !sb.last().isLetter()) sb.append(' ')
                sb.append(format(v))
            }
        }
        return sb.toString()
    }

    /** Commands as [Char]s and arguments as [Float]s, accepting every separator form path data allows. */
    private fun tokens(data: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        while (i < data.length) {
            val c = data[i]
            when {
                c.isLetter() && c != 'e' && c != 'E' -> { out += c; i++ }
                c == '-' || c == '+' || c == '.' || c.isDigit() -> {
                    val start = i
                    var seenDot = false
                    var seenExp = false
                    if (c == '-' || c == '+') i++
                    while (i < data.length) {
                        val d = data[i]
                        when {
                            d.isDigit() -> i++
                            d == '.' && !seenDot && !seenExp -> { seenDot = true; i++ }
                            (d == 'e' || d == 'E') && !seenExp -> {
                                seenExp = true; i++
                                if (i < data.length && (data[i] == '-' || data[i] == '+')) i++
                            }
                            else -> break
                        }
                    }
                    out += data.substring(start, i).toFloatOrNull() ?: return emptyList()
                }
                else -> i++
            }
        }
        return out
    }

    /** A plain decimal (never exponent notation, which the path parser would read as a command letter). */
    private fun format(v: Float): String {
        val r = (v * 1000f).roundToInt()
        if (r == 0) return "0"
        val sign = if (r < 0) "-" else ""
        val whole = abs(r) / 1000
        val frac = abs(r) % 1000
        return if (frac == 0) "$sign$whole" else sign + whole + "." + frac.toString().padStart(3, '0').trimEnd('0')
    }
}
