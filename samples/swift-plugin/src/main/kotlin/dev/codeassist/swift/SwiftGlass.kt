package dev.codeassist.swift

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Liquid Glass, computed from what lies behind it: the device port of tools/swiftui-parity/GlassModel.swift, with the
 * same parameters (assets/glass-params.json, fitted against the iOS Simulator by `Ours --fit-glass`).
 *
 * A lens samples the content further in near the rim (a measured table of displacement by distance from the edge), a
 * blur softens it (regular glass frosts a band along the rim: a radius by distance, between blurred copies), a colour
 * matrix lifts and saturates it (regular) or barely touches it (clear), a tint colours it, a bright rim edges it, and a
 * wide soft shadow sits outside it. Works on an ARGB bitmap at [scale] pixels per point.
 */
class SwiftGlass(params: JSONObject?) {
    private val p = params ?: JSONObject()
    private fun d(name: String, default: Double) = p.optDouble(name, default)
    private fun table(name: String): DoubleArray? = p.optJSONArray(name)?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }

    private val darkA = d("darkA", 0.30)
    private val darkB = d("darkB", 0.10)
    private val tintAmount = d("tintAmount", 0.8)
    private val blur = d("blur", 0.25)
    private val regularBlur = if (p.has("regularBlur")) d("regularBlur", blur) else null
    private val rimWidth = d("rimWidth", 1.0)
    private val rimAmount = d("rimAmount", 0.25)
    private val shadowAlpha = d("shadowAlpha", 0.07)
    private val shadowRadius = d("shadowRadius", 14.0)
    private val shadowY = d("shadowY", 4.0)
    private val regularMatrix = table("regularMatrix")
        ?: doubleArrayOf(0.6685, -0.3660, -0.0282, 0.7160, -0.0883, 0.4001, -0.0300, 0.7125, -0.1196, -0.4078, 0.7839, 0.7315)
    private val clearMatrix = table("clearMatrix")
        ?: doubleArrayOf(1.0324, 0.0112, 0.0005, 0.0723, 0.0057, 1.0385, 0.0024, 0.0711, 0.0003, 0.0097, 1.0283, 0.0754)
    private val lensTable = table("lensTable")
        ?: doubleArrayOf(30.0, 30.0, 32.0, 26.0, 22.8, 19.2, 16.2, 13.55, 11.3, 9.3, 7.55, 6.0, 4.65, 3.55, 2.55, 1.75, 1.08, 0.65, 0.27, 0.05, 0.0)
    private val regularLensTable = table("regularLensTable")
    private val regularBlurTable = table("regularBlurTable")
    /** Regular glass smears along the edge's normal: half-length in points by distance from the edge (GlassModel.swift). */
    private val regularNormalBlurTable = table("regularNormalBlurTable")

    /** Apply glass in [path] (points) with frame [bounds] (points) to [bitmap], drawn at [scale] pixels per point. */
    fun apply(bitmap: Bitmap, scale: Float, path: Path, bounds: RectF, variant: String, tint: Int?, dark: Boolean) {
        val s = scale.toDouble()
        val w = bitmap.width
        val h = bitmap.height
        val margin = ((shadowRadius * 2 + shadowY + 4) * s).toInt()
        val x0 = max(0, (bounds.left * s).toInt() - margin)
        val x1 = min(w, (bounds.right * s).toInt() + margin)
        val y0 = max(0, (bounds.top * s).toInt() - margin)
        val y1 = min(h, (bounds.bottom * s).toInt() + margin)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 3 || rh < 3) return

        val pixels = IntArray(rw * rh)
        bitmap.getPixels(pixels, 0, rw, x0, y0, rw, rh)
        val original = FloatArray(rw * rh * 3)
        for (i in 0 until rw * rh) {
            val c = pixels[i]
            original[i * 3] = Color.red(c) / 255f
            original[i * 3 + 1] = Color.green(c) / 255f
            original[i * 3 + 2] = Color.blue(c) / 255f
        }
        val regular = variant != "clear"
        val lensT = if (regular) regularLensTable ?: lensTable else lensTable
        val blurTable = if (regular) regularBlurTable else null
        fun blurred(radius: Double): FloatArray {
            val b = original.copyOf()
            val r = (radius * s).roundToInt()
            if (r > 0) repeat(3) { boxBlur3(b, rw, rh, r) }
            return b
        }
        // Blurred copies: one radius, or a ladder (doubling from one pixel) that a per-pixel radius interpolates in.
        val radii = ArrayList<Double>()
        val levels = ArrayList<FloatArray>()
        if (blurTable != null) {
            val maxR = blurTable.maxOrNull() ?: 0.0
            radii.add(0.0); levels.add(original)
            var radius = 1 / s
            while (true) {
                radii.add(radius); levels.add(blurred(radius))
                if (radius >= maxR) break
                radius *= 2
            }
        } else {
            val r = if (regular) regularBlur ?: blur else blur
            radii.add(r); levels.add(blurred(r))
        }

        // The shape's coverage in this region, the distance to its edge, and clean normals from a smoothed copy.
        val maskBitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ALPHA_8)
        Canvas(maskBitmap).apply {
            translate(-x0.toFloat(), -y0.toFloat())
            scale(scale, scale)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        val alpha = ByteArray(maskBitmap.rowBytes * rh)
        maskBitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(alpha))
        val rowBytes = maskBitmap.rowBytes
        maskBitmap.recycle()
        val mask = FloatArray(rw * rh) { (alpha[(it / rw) * rowBytes + it % rw].toInt() and 0xFF) / 255f }
        val dist = distanceInside(mask, rw, rh)
        val smooth = dist.copyOf()
        repeat(2) { boxBlur1(smooth, rw, rh, 2) }

        val shadow = mask.copyOf()
        val sr = max(1, (shadowRadius * s / 2).roundToInt())
        repeat(3) { boxBlur1(shadow, rw, rh, sr) }
        val dy = (shadowY * s).roundToInt()
        val tr = tint?.let { Color.red(it) / 255.0 }
        val tg = tint?.let { Color.green(it) / 255.0 }
        val tb = tint?.let { Color.blue(it) / 255.0 }
        val m = if (regular) regularMatrix else clearMatrix
        val rgb = DoubleArray(3)
        val other = DoubleArray(3)

        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val i = y * rw + x
                val cover = mask[i].toDouble()
                var or = original[i * 3].toDouble()
                var og = original[i * 3 + 1].toDouble()
                var ob = original[i * 3 + 2].toDouble()
                val sy = y - dy
                if (sy in 0 until rh) {
                    val sh = shadow[sy * rw + x] * shadowAlpha * (1 - cover)
                    or *= 1 - sh; og *= 1 - sh; ob *= 1 - sh
                }
                if (cover > 0) {
                    val dPt = dist[i] / s
                    val dxp = (smooth[y * rw + min(x + 1, rw - 1)] - smooth[y * rw + max(x - 1, 0)]).toDouble()
                    val dyp = (smooth[min(y + 1, rh - 1) * rw + x] - smooth[max(y - 1, 0) * rw + x]).toDouble()
                    val len = max(1e-6, sqrt(dxp * dxp + dyp * dyp))
                    val offset = lens(lensT, dPt) * s
                    val qx = x + dxp / len * offset
                    val qy = y + dyp / len * offset
                    if (blurTable != null) {
                        val want = lens(blurTable, dPt)
                        var k = 0
                        while (k < radii.size - 2 && radii[k + 1] < want) k++
                        val k1 = min(k + 1, radii.size - 1)
                        val t = if (radii[k1] > radii[k]) ((want - radii[k]) / (radii[k1] - radii[k])).coerceIn(0.0, 1.0) else 0.0
                        fun at(px: Double, py: Double, out: DoubleArray) {
                            sample(levels[k], rw, rh, px, py, out)
                            if (t > 0) {
                                sample(levels[k1], rw, rh, px, py, other)
                                for (c in 0..2) out[c] = out[c] * (1 - t) + other[c] * t
                            }
                        }
                        val along = if (regular && regularNormalBlurTable != null) lens(regularNormalBlurTable, dPt) * s else 0.0
                        if (along > 0.5) {
                            // The smear along the normal: an average of samples across [-along, along].
                            val nx = dxp / len
                            val ny = dyp / len
                            val n = (along / 1.5).toInt().coerceIn(2, 24)
                            var ar = 0.0; var ag = 0.0; var ab = 0.0
                            for (j in 0..n) {
                                val u = (j.toDouble() / n * 2 - 1) * along
                                at(qx + nx * u, qy + ny * u, rgb)
                                ar += rgb[0]; ag += rgb[1]; ab += rgb[2]
                            }
                            rgb[0] = ar / (n + 1); rgb[1] = ag / (n + 1); rgb[2] = ab / (n + 1)
                        } else {
                            at(qx, qy, rgb)
                        }
                    } else {
                        sample(levels[0], rw, rh, qx, qy, rgb)
                    }
                    var cr: Double
                    var cg: Double
                    var cb: Double
                    if (regular && dark) {
                        cr = rgb[0] * darkA + darkB; cg = rgb[1] * darkA + darkB; cb = rgb[2] * darkA + darkB
                    } else {
                        cr = (m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2] + m[3]).coerceIn(0.0, 1.0)
                        cg = (m[4] * rgb[0] + m[5] * rgb[1] + m[6] * rgb[2] + m[7]).coerceIn(0.0, 1.0)
                        cb = (m[8] * rgb[0] + m[9] * rgb[1] + m[10] * rgb[2] + m[11]).coerceIn(0.0, 1.0)
                    }
                    if (tr != null && tg != null && tb != null) {
                        cr = cr * (1 - tintAmount) + tr * tintAmount
                        cg = cg * (1 - tintAmount) + tg * tintAmount
                        cb = cb * (1 - tintAmount) + tb * tintAmount
                    }
                    // Measured: dark glass has no bright rim.
                    if (dPt < rimWidth && !(dark && variant != "clear")) {
                        val k = rimAmount * (1 - dPt / rimWidth)
                        cr += (1 - cr) * k; cg += (1 - cg) * k; cb += (1 - cb) * k
                    }
                    or = or * (1 - cover) + cr * cover
                    og = og * (1 - cover) + cg * cover
                    ob = ob * (1 - cover) + cb * cover
                }
                pixels[i] = Color.rgb(
                    (or * 255).roundToInt().coerceIn(0, 255),
                    (og * 255).roundToInt().coerceIn(0, 255),
                    (ob * 255).roundToInt().coerceIn(0, 255),
                )
            }
        }
        bitmap.setPixels(pixels, 0, rw, x0, y0, rw, rh)
    }

    /** A measured profile: linear between entries (one per point), the last entry beyond the end. */
    private fun lens(t: DoubleArray, d: Double): Double {
        if (d < 0 || t.isEmpty()) return 0.0
        val i = d.toInt()
        if (i >= t.size - 1) return t.last()
        val f = d - i
        return t[i] * (1 - f) + t[i + 1] * f
    }

    private fun sample(s: FloatArray, w: Int, h: Int, x: Double, y: Double, out: DoubleArray) {
        val fx = x.coerceIn(0.0, (w - 1).toDouble())
        val fy = y.coerceIn(0.0, (h - 1).toDouble())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        for (c in 0..2) {
            val a = s[(y0 * w + x0) * 3 + c]
            val b = s[(y0 * w + x1) * 3 + c]
            val d = s[(y1 * w + x0) * 3 + c]
            val e = s[(y1 * w + x1) * 3 + c]
            out[c] = (a * (1 - tx) + b * tx) * (1 - ty) + (d * (1 - tx) + e * tx) * ty
        }
    }

    private fun boxBlur3(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(a.size)
        val n = (2 * r + 1).toFloat()
        for (y in 0 until h) for (c in 0 until 3) {
            var acc = 0f
            for (k in -r..r) acc += a[(y * w + k.coerceIn(0, w - 1)) * 3 + c]
            for (x in 0 until w) {
                tmp[(y * w + x) * 3 + c] = acc / n
                acc += a[(y * w + min(x + r + 1, w - 1)) * 3 + c] - a[(y * w + max(x - r, 0)) * 3 + c]
            }
        }
        for (x in 0 until w) for (c in 0 until 3) {
            var acc = 0f
            for (k in -r..r) acc += tmp[(k.coerceIn(0, h - 1) * w + x) * 3 + c]
            for (y in 0 until h) {
                a[(y * w + x) * 3 + c] = acc / n
                acc += tmp[(min(y + r + 1, h - 1) * w + x) * 3 + c] - tmp[(max(y - r, 0) * w + x) * 3 + c]
            }
        }
    }

    private fun boxBlur1(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(a.size)
        val n = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            var acc = 0f
            for (k in -r..r) acc += a[y * w + k.coerceIn(0, w - 1)]
            for (x in 0 until w) { tmp[y * w + x] = acc / n; acc += a[y * w + min(x + r + 1, w - 1)] - a[y * w + max(x - r, 0)] }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) { a[y * w + x] = acc / n; acc += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x] }
        }
    }

    /** Distance (pixels) from each covered pixel to the nearest uncovered one: a two-pass chamfer on the mask. */
    private fun distanceInside(m: FloatArray, w: Int, h: Int): FloatArray {
        val big = 1e9f
        val d = FloatArray(m.size) { if (m[it] >= 0.5f) big else 0f }
        val diag = 1.4142f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0f) continue
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1)
            if (y > 0) v = min(v, d[i - w] + 1)
            if (x > 0 && y > 0) v = min(v, d[i - w - 1] + diag)
            if (x < w - 1 && y > 0) v = min(v, d[i - w + 1] + diag)
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0f) continue
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 1)
            if (y < h - 1) v = min(v, d[i + w] + 1)
            if (x < w - 1 && y < h - 1) v = min(v, d[i + w + 1] + diag)
            if (x > 0 && y < h - 1) v = min(v, d[i + w - 1] + diag)
            d[i] = v
        }
        for (i in d.indices) if (m[i] > 0f && m[i] < 0.5f) d[i] = 0.5f
        return d
    }
}
