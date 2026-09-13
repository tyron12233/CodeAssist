package dev.ide.android.bench

import android.os.Debug
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.LineRenderCache
import dev.ide.ui.editor.core.MEASURER_CACHE_ENTRIES
import dev.ide.ui.editor.core.SemSpan
import dev.ide.ui.editor.core.TokenType
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (ART) profile of the editor's per-line render cost — the counterpart to the desktop
 * `RenderFrameBenchmark`, which can only measure the same work on a JVM with a raster Skia surface.
 *
 * What it isolates is the cost the desktop harness found to dominate a scroll frame: one call to
 * [TextMeasurer.measure], which builds a whole `Paragraph` object graph per line and whose price on the JVM is
 * the same for an empty string as for a full line of code. This tells us whether that holds on ART, where the
 * text stack underneath is `android.text` rather than Skia's own, and what it costs relative to the per-line
 * cache lookups around it.
 *
 * Results go to logcat under `editor-bench`; nothing is asserted, so it cannot fail on a slow device.
 * Run with `./gradlew :ide-android:connectedProfileAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.bench.EditorRenderBenchmarkTest`.
 */
@RunWith(AndroidJUnit4::class)
class EditorRenderBenchmarkTest {

    private val tag = "editor-bench"
    private var sink = 0L

    @Test
    fun editorRenderCosts() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val density = Density(ctx.resources.displayMetrics.density)
        val resolver = createFontFamilyResolver(ctx)
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White)

        val lines = 4000
        val text = kotlinDoc(lines)
        val session = EditorSession(text, CodeLanguage.Kotlin, TextRange(0))
        val doc = session.doc

        log("device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} density=${density.density}")
        log("doc: ${doc.lineCount} lines, ${doc.length} chars")

        // ---- 0. warm the text stack before any timing (first measure() pays class-load + JIT) ----
        run {
            val warm = TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize = 0)
            repeat(400) { i ->
                warm.measure(AnnotatedString("warm up line $i"), style = style, softWrap = false, maxLines = 1)
            }
        }

        // ---- 1. measure() fixed cost: does content matter, and what is a content cache worth? ----
        // cacheSize=128 runs FIRST so the uncached numbers cannot inherit its warm-up, and every figure is a
        // best-of (this emulator's mean/best spread is ~4x, so a mean says more about the host than the code).
        val longLine = doc.lineText(descriptiveLine(session))
        for (cacheSize in intArrayOf(128, 0, 128, 0)) {
            val m = TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize)
            bench("measure blank             cache=%3d".format(cacheSize), 2_000) {
                m.measure(AnnotatedString(""), style = style, softWrap = false, maxLines = 1).size.width.toLong()
            }
            bench("measure '    }'           cache=%3d".format(cacheSize), 2_000) {
                m.measure(AnnotatedString("    }"), style = style, softWrap = false, maxLines = 1).size.width.toLong()
            }
            bench("measure ${longLine.length}-char line     cache=%3d".format(cacheSize), 2_000) {
                m.measure(AnnotatedString(longLine), style = style, softWrap = false, maxLines = 1).size.width.toLong()
            }
        }

        // ---- 1b. DISTINCT lines, which is what a scroll actually shapes (a cache hit is not the fling case).
        // Sized here rather than guessed: the win comes from the duplicate lines real source is full of
        // (blank, `}`, `    }`, `    )`), so the question is how small a cache still catches them.
        for (cacheSize in intArrayOf(0, 8, 16, 32, 64, 128, 256)) {
            val m = TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize)
            var ln = 0
            repeat(120) { m.measure(AnnotatedString(doc.lineText(ln++ % doc.lineCount)), style = style, softWrap = false, maxLines = 1) }
            benchStep("measure 12 DISTINCT lines cache=%3d".format(cacheSize), 80) {
                var bh = 0L
                repeat(12) {
                    val t = doc.lineText(ln++ % doc.lineCount)
                    bh += m.measure(AnnotatedString(t), style = style, softWrap = false, maxLines = 1).size.width.toLong()
                }
                bh
            }
        }


        // ---- 1d. how far below Compose's measure() the floor actually is -----------------------------
        // Before rewriting the draw path to bypass Paragraph entirely, find out what the alternatives cost:
        // the Paragraph itself without the TextLayoutResult wrapper, and the raw platform text calls that a
        // monospace fast path would come down to.
        run {
            val line = doc.lineText(descriptiveLine(session))
            val ann = AnnotatedString(line)
            val m = TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize = 0)
            bench("floor: TextMeasurer.measure  ", 2_000) {
                m.measure(ann, style = style, softWrap = false, maxLines = 1).size.width.toLong()
            }
            bench("floor: Paragraph direct      ", 2_000) {
                androidx.compose.ui.text.Paragraph(
                    text = line,
                    style = style,
                    constraints = androidx.compose.ui.unit.Constraints(),
                    density = density,
                    fontFamilyResolver = resolver,
                    spanStyles = emptyList(),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                ).width.toLong()
            }
            val paint = android.graphics.Paint().apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f * density.density
            }
            bench("floor: Paint.measureText     ", 50_000) { paint.measureText(line).toLong() }
            val bmp = android.graphics.Bitmap.createBitmap(1200, 64, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            bench("floor: Canvas.drawText       ", 50_000) {
                c.drawText(line, 0, line.length, 0f, 20f, paint)
                1L
            }
            // A coloured line is several runs, not one call -- price the realistic shape of a fast path.
            bench("floor: drawText x6 runs      ", 20_000) {
                var at = 0
                repeat(6) {
                    val end = minOf(at + line.length / 6, line.length)
                    if (end > at) c.drawText(line, at, end, at * 8f, 20f, paint)
                    at = end
                }
                1L
            }
            // The other half of the trade. Today a line is shaped ONCE and the cached TextLayoutResult is
            // painted every frame; a raw path would shape never and pay drawText every frame. Which is cheaper
            // depends entirely on what painting a cached layout costs, so price that too.
            val cachedLayout = m.measure(ann, style = style, softWrap = false, maxLines = 1)
            val composeCanvas = androidx.compose.ui.graphics.Canvas(c)
            bench("floor: paint CACHED layout   ", 20_000) {
                androidx.compose.ui.text.TextPainter.paint(composeCanvas, cachedLayout)
                1L
            }
            bmp.recycle()
        }

        // ---- 2. LineRenderCache: a cold (shaped) line vs a warm hit ----
        val palette = arrayOfNulls<SpanStyle?>(TokenType.entries.size).also { p ->
            for (i in p.indices) p[i] = SpanStyle(color = Color(0xFF80CBC4))
        }
        val measurer = TextMeasurer(resolver, density, LayoutDirection.Ltr, MEASURER_CACHE_ENTRIES)
        val cache = LineRenderCache(measurer, style, palette)
        val perLineInlays = HashMap<Int, List<InlayPiece>>()
        val perLineSem = HashMap<Int, List<SemSpan>>()
        val semStyle = SpanStyle(color = Color(0xFFFFCC80))
        var l = 0
        while (l < doc.lineCount) {
            if (doc.lineLength(l) > 16) perLineSem[l] = listOf(SemSpan(4, 10, semStyle))
            if (l % 12 == 0 && doc.lineLength(l) > 16) perLineInlays[l] = listOf(InlayPiece(8, " : Int"))
            l++
        }
        cache.setInlays(perLineInlays, semStyle)
        cache.setSemanticSpans(perLineSem)
        log("overlay: ${perLineSem.size} semantic lines, ${perLineInlays.size} inlay lines")

        var cold = 600
        bench("layoutFor COLD (one fresh line)", 1_500) {
            cache.layoutFor(cold++, doc, session.styles).size.width.toLong()
        }
        // Where a cold line's time actually goes: lex, rope read, span mapping, then the measure itself.
        run {
            var t = 2_500
            bench("  cold: styles.revOf (lex)     ", 2_000) { session.styles.revOf(t++ % doc.lineCount).toLong() }
            var r = 0
            bench("  cold: doc.lineText (rope)    ", 20_000) { doc.lineText(r++ % doc.lineCount).length.toLong() }
            val spans = session.styles.spansFor(700)
            val sem = perLineSem[700].orEmpty()
            bench("  cold: build AnnotatedString  ", 20_000) {
                val ranges = spans.mapNotNull { sp ->
                    palette[sp.type.ordinal]?.let { AnnotatedString.Range(it, sp.start, sp.end) }
                } + sem.map { AnnotatedString.Range(it.style, it.start, it.end) }
                AnnotatedString(doc.lineText(700), spanStyles = ranges).length.toLong()
            }
        }
        bench("layoutFor WARM (cache hit)     ", 200_000) {
            cache.layoutFor(700, doc, session.styles).size.width.toLong()
        }
        bench("rawToVisual (line with inlay)  ", 200_000) {
            cache.rawToVisual(perLineInlays.keys.first(), 20).toLong()
        }

        // ---- 3. a fling step: 12 lines newly entering the viewport ----
        var fling = 1_200
        benchStep("fling step: shape 12 fresh lines", 60) {
            var bh = 0L
            repeat(12) { bh += cache.layoutFor(fling++, doc, session.styles).size.width.toLong() }
            bh
        }

        // ---- 4. the Enter keystroke: shifting the per-line caches by one line ----
        repeat(60) { cache.layoutFor(1500 + it, doc, session.styles) } // a viewport's worth cached
        benchStep("Enter: shiftKeys(1500, +1)      ", 300) {
            cache.shiftKeys(1500, 1)
            1L
        }
        log("sink=$sink")
    }

    // ---- harness ----

    /**
     * Best-of-[runs] batches, each [ops]/runs calls. A mean on this host is dominated by emulator scheduling
     * noise (a 4x best/mean spread), so the batch minimum is the figure that describes the code.
     */
    private inline fun bench(label: String, ops: Int, body: () -> Long) {
        var bh = 0L
        repeat(ops / 4) { bh += body() }
        val gc0 = gcCount()
        val runs = 8
        val per = (ops / runs).coerceAtLeast(1)
        var best = Long.MAX_VALUE
        var total = 0L
        repeat(runs) {
            val t0 = System.nanoTime()
            var i = 0
            while (i < per) { bh += body(); i++ }
            val dt = System.nanoTime() - t0
            total += dt
            if (dt < best) best = dt
        }
        sink += bh
        log(
            "%s | best %9.2f us/op  mean %9.2f us/op  gc+%d".format(
                label, best.toDouble() / per / 1000.0, total.toDouble() / runs / per / 1000.0, gcCount() - gc0,
            ),
        )
    }

    /** For an op whose state advances each call (no repeatable warm-up): report best and mean. */
    private inline fun benchStep(label: String, runs: Int, body: () -> Long) {
        var bh = 0L
        val gc0 = gcCount()
        var best = Long.MAX_VALUE
        var total = 0L
        repeat(runs) {
            val t0 = System.nanoTime()
            bh += body()
            val dt = System.nanoTime() - t0
            total += dt
            if (dt < best) best = dt
        }
        sink += bh
        log(
            "%s | best %7.3f ms  mean %7.3f ms  gc+%d".format(
                label, best / 1e6, total / runs / 1e6, gcCount() - gc0,
            ),
        )
    }

    private fun gcCount(): Long = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L

    private fun log(line: String) = Log.i(tag, line).let { }

    /** A line long enough to be representative of real code (the fixture's widest kind). */
    private fun descriptiveLine(session: EditorSession): Int {
        val doc = session.doc
        var best = 0
        var bestLen = 0
        var i = 0
        while (i < minOf(doc.lineCount, 400)) {
            val len = doc.lineLength(i)
            if (len > bestLen) { bestLen = len; best = i }
            i++
        }
        return best
    }

    private fun kotlinDoc(lines: Int): String = buildString {
        append("package dev.ide.bench\n\nimport kotlin.math.max\n\n")
        var i = 0
        while (i < lines) {
            append("class Generated$i(private val name: String, val count: Int) {\n")
            append("    fun compute(input: Int): Int {\n")
            append("        val scaled = max(input * count, ${i % 97})\n")
            append("        return scaled + name.length // note $i\n")
            append("    }\n")
            append("}\n\n")
            i += 7
        }
    }
}
