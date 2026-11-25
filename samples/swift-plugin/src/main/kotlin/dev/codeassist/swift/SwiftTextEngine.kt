package dev.codeassist.swift

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Lays out and draws the text of a SwiftUI preview, as iOS would within what an open font allows.
 *
 * The font is Inter (SIL Open Font License): SF Pro and SF Symbols may not be shipped or used off Apple's devices.
 * What layout depends on is iOS's, not Inter's: line height 1.1934 x the point size, baseline at 0.9521 x it (SF's
 * hhea metrics, which match every size measured in the Simulator), a Dynamic Type style's line advance when the engine
 * gives one, greedy line breaking as CoreText does, and a width that counts a wrapped line's trailing space as SwiftUI
 * does. Inter is a little wider than SF at text sizes; a per-size letter spacing, measured against SF on a Mac
 * (CoreText, a 4-sentence sample), brings its average advance back to SF's so lines break where they break on iOS.
 *
 * Works in points, at [PX] pixels per point internally, which is how an iPhone renders (@3x).
 */
class SwiftTextEngine(private val fontDir: Path, private val symbols: SwiftSymbols? = null) {

    data class Span(
        val text: String,
        val size: Float,
        val weight: Int,
        val design: String,
        val italic: Boolean,
        val color: Int,
        val underline: Int?,
        val strikethrough: Int?,
        val kerning: Float,
        val baselineOffset: Float,
        val symbol: String?,
        /** An inline symbol's frame at this size (iOS's, from the engine): width, height, and depth below the baseline. */
        val symbolWidth: Float? = null,
        val symbolHeight: Float? = null,
        val symbolBaseline: Float? = null,
    )

    data class Metrics(val width: Float, val height: Float, val firstBaseline: Float, val lastBaseline: Float)

    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val layouts = object : LinkedHashMap<String, Layout>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Layout>?) = size > 256
    }

    private val regular: File by lazy { fontFile("InterVariable.ttf") }
    private val italic: File by lazy { fontFile("InterVariable-Italic.ttf") }

    private fun fontFile(name: String): File {
        val target = fontDir.resolve(name)
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(fontDir)
            val stream = javaClass.classLoader?.getResourceAsStream("assets/fonts/$name")
                ?: error("assets/fonts/$name is not packaged in this plugin")
            stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }
        return target.toFile()
    }

    /** Inter at [weight] with its optical size set to the point size, as SF switches between Text and Display. */
    fun typeface(weight: Int, size: Float, italic: Boolean, design: String): Typeface {
        if (design == "monospaced") return Typeface.MONOSPACE
        if (design == "serif") return Typeface.create(Typeface.SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        val opsz = size.coerceIn(14f, 32f).toInt()
        val key = "$weight/$opsz/$italic"
        return typefaces.getOrPut(key) {
            Typeface.Builder(if (italic) this.italic else regular)
                .setFontVariationSettings("'wght' $weight, 'opsz' $opsz")
                .build()
        }
    }

    private inner class StyleSpan(val span: Span) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) = apply(paint)
        override fun updateDrawState(paint: TextPaint) = apply(paint)
        private fun apply(paint: TextPaint) {
            paint.typeface = typeface(span.weight, span.size, span.italic, span.design)
            paint.textSize = span.size * PX
            paint.letterSpacing = tracking(span.size) + if (span.size > 0) span.kerning / span.size else 0f
            paint.baselineShift = (-span.baselineOffset * PX).toInt()
            paint.isSubpixelText = true
            paint.isLinearText = true
        }
    }

    /**
     * A symbol inline in text: as wide as iOS's frame for it at the span's size, drawn from the open symbol set sitting
     * on the baseline as iOS sets it. Leaves the line's height alone, as iOS does for symbols at text size.
     */
    private inner class SymbolSpan(val span: Span) : ReplacementSpan() {
        private val width = (span.symbolWidth ?: span.size) * PX
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            ceil(width).toInt()
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val height = (span.symbolHeight ?: span.size) * PX
            val depth = (span.symbolBaseline ?: 0f) * PX
            val frame = RectF(x, y + depth - height, x + width, y + depth)
            symbols?.draw(canvas, span.symbol ?: return, frame, span.size, span.weight, span.color)
        }
    }

    private fun text(spans: List<Span>): CharSequence {
        val sb = SpannableStringBuilder()
        for (s in spans) {
            val start = sb.length
            if (s.symbol != null) {
                sb.append("\uFFFC")
                sb.setSpan(StyleSpan(s), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(SymbolSpan(s), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            sb.append(s.text)
            val end = sb.length
            if (end == start) continue
            sb.setSpan(StyleSpan(s), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(s.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.underline != null) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.strikethrough != null) sb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private val basePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    private fun layout(spans: List<Span>, widthPt: Float?, maxLines: Int?, align: String): Layout {
        val key = "${spans.hashCode()}|$widthPt|$maxLines|$align"
        synchronized(layouts) { layouts[key]?.let { return it } }
        val text = text(spans)
        val widthPx = if (widthPt != null) max(1, ceil(widthPt * PX + 0.5f).toInt())
        else max(1, ceil(Layout.getDesiredWidth(text, basePaint)).toInt() + 2)
        val alignment = when (align) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "trailing" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, basePaint, widthPx)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (maxLines != null) builder.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        val built = builder.build()
        synchronized(layouts) { layouts[key] = built }
        return built
    }

    private fun maxSize(spans: List<Span>) = spans.maxOfOrNull { it.size } ?: 17f

    fun measure(spans: List<Span>, widthPt: Float?, maxLines: Int?, lineAdvance: Float?, align: String): Metrics {
        val layout = layout(spans, widthPt, maxLines, align)
        val text = layout.text
        var widest = 0f
        for (i in 0 until layout.lineCount) {
            // The line as SwiftUI counts it: with the space it wrapped after.
            val w = Layout.getDesiredWidth(text, layout.getLineStart(i), layout.getLineEnd(i), basePaint) / PX
            widest = max(widest, w)
        }
        if (widthPt != null) widest = min(widest, widthPt)
        val size = maxSize(spans)
        val lineHeight = LINE_HEIGHT * size
        val advance = lineAdvance ?: lineHeight
        val lines = max(1, layout.lineCount)
        val ascent = ASCENT * size
        return Metrics(widest, lineHeight + (lines - 1) * advance, ascent, (lines - 1) * advance + ascent)
    }

    /** Draw [spans] in [frame] (points) on [canvas], which is in points too. */
    fun draw(canvas: Canvas, spans: List<Span>, left: Float, top: Float, width: Float, maxLines: Int?, lineAdvance: Float?, align: String) {
        val layout = layout(spans, width, maxLines, align)
        val size = maxSize(spans)
        val advance = lineAdvance ?: (LINE_HEIGHT * size)
        val ascent = ASCENT * size
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(1f / PX, 1f / PX)
        for (i in 0 until layout.lineCount) {
            val target = (ascent + i * advance) * PX
            // Move line i's baseline to iOS's, then draw only that line.
            canvas.save()
            canvas.translate(0f, target - layout.getLineBaseline(i))
            canvas.clipRect(0f, layout.getLineTop(i).toFloat(), layout.width.toFloat(), layout.getLineBottom(i).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    companion object {
        const val PX = 3f
        const val LINE_HEIGHT = 1.1934f
        const val ASCENT = 0.9521f

        /** Letter spacing (em) that brings Inter's average advance to SF's, by point size (measured; see class doc). */
        private val TRACKING = listOf(
            11f to 0.0037f, 13f to -0.0080f, 15f to -0.0155f, 17f to -0.0206f, 20f to -0.0246f, 28f to -0.0147f,
            34f to -0.0078f, 48f to 0f,
        )

        fun tracking(size: Float): Float {
            if (size <= TRACKING.first().first) return TRACKING.first().second
            for (i in 1 until TRACKING.size) {
                val (s1, t1) = TRACKING[i]
                if (size <= s1) {
                    val (s0, t0) = TRACKING[i - 1]
                    return t0 + (t1 - t0) * (size - s0) / (s1 - s0)
                }
            }
            return TRACKING.last().second
        }
    }
}
