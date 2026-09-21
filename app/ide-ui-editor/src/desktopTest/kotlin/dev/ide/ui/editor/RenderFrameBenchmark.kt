package dev.ide.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.LineRenderCache
import dev.ide.ui.editor.core.MEASURER_CACHE_ENTRIES
import dev.ide.ui.editor.core.SemSpan
import dev.ide.ui.editor.folding.FoldModel
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test

/**
 * Frame-cost profile of the editor's canvas renderer: it drives [drawEditor] itself, over a realistic session
 * (4000 lines with semantic spans, inlay hints and diagnostics live), and reports what one frame costs in each
 * of the states the editor is actually in — repainting with every layout cached, scrolling a line at a time,
 * flinging, and arriving cold on a viewport nothing has shaped yet.
 *
 * It measures a frame **twice**, which is the point of the rig. Rasterizing onto a Skia surface is what this
 * host can do; recording into a `Picture` is what Android does, where the raster happens on the GPU and the
 * per-frame CPU cost is the traversal plus the display list. The two differ by roughly 4x, so a number taken
 * the first way would badly misdescribe the device.
 *
 * Nothing is asserted — the printed tables are the output, so it cannot fail on a slow or busy machine. Run it
 * with `./gradlew :ide-ui-editor:desktopTest --tests '*RenderFrameBenchmark*' --info`. Its on-device
 * counterpart is `EditorRenderBenchmarkTest` in `:ide-android`, which is the one to trust for absolute costs:
 * this host's JVM is several times faster than ART at exactly the object-graph work that dominates here.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RenderFrameBenchmark {

    private val width = 1200
    private val height = 1800
    private val density = Density(2f)

    @Test
    fun measure() {
        val lines = 4000
        val text = kotlinDoc(lines)
        val session = EditorSession(text, CodeLanguage.Kotlin, TextRange(text.length / 2))

        // Realistic analysis payload: semantic tokens on ~1/3 of identifiers, inlays every ~12 lines,
        // a handful of diagnostics.
        val semTokens = syntheticSemanticTokens(session)
        val inlays = syntheticInlays(session)
        val diagnostics = syntheticDiagnostics(session)

        var captured: Captured? = null
        val scene = ImageComposeScene(width, height, density) {
            CodeAssistTheme(dark = true) {
                Capture(session, semTokens, inlays) { captured = it }
            }
        }
        scene.render()
        val cap = captured ?: error("render state not captured")

        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas.asComposeCanvas()
        val drawScope = CanvasDrawScope()
        val size = Size(width.toFloat(), height.toFloat())
        val recorder = PictureRecorder()

        val vlayout = plainVLayout(session)
        val diagByLine = mapDiagnosticsToLines(diagnostics, session.doc)

        fun paint(scope: DrawScope, vOff: Float, withDiags: Boolean) {
            with(scope) {
                drawEditor(
                    session = session,
                    metrics = cap.render.metrics,
                    gutterWidth = cap.render.gutterWidthPx,
                    vOff = vOff,
                    hOff = 0f,
                    layoutFor = cap.render::layoutFor,
                    compositeLayoutFor = cap.render::compositeLayoutFor,
                    rawToVisual = cap.render.renderCache::rawToVisual,
                    foldModel = session.foldModel,
                    vlayout = vlayout,
                    wrap = false,
                    foldableStartLines = emptySet(),
                    foldStripWidth = cap.render.foldStripPx,
                    hoveredLine = -1,
                    numberLayout = cap.render::numberLayout,
                    diagByLine = if (withDiags) diagByLine else emptyMap(),
                    bracketPair = null,
                    findMatches = emptyList(),
                    currentMatch = -1,
                    occurrences = emptyList(),
                    templateFields = emptyList(),
                    decoByLine = emptyMap(),
                    filePath = "Bench.kt",
                    painters = EditorPainters.NONE,
                    indentColsFor = cap.render::indentColsFor,
                    stickyHeadersFor = { emptyList() },
                    colors = cap.colors,
                    caretVisible = true,
                    caretContent = Offset(100f, 100f),
                    handlesVisible = false,
                    handleColor = Color.Cyan,
                )
            }
        }

        fun frame(vOff: Float, withDiags: Boolean, withInlays: Boolean, withSem: Boolean) {
            drawScope.draw(density, LayoutDirection.Ltr, canvas, size) { paint(this, vOff, withDiags) }
        }

        /** Record into a Skia Picture instead of rasterizing -- the closest desktop proxy for what Android
         *  actually pays per frame (our traversal + display-list recording; the raster happens on the GPU). */
        fun record(vOff: Float) {
            val rec = recorder.beginRecording(Rect.makeWH(width.toFloat(), height.toFloat()))
            drawScope.draw(density, LayoutDirection.Ltr, rec.asComposeCanvas(), size) { paint(this, vOff, true) }
            recorder.finishRecordingAsPicture().close()
        }

        val lineH = cap.render.metrics.lineHeight
        val visible = (height / lineH).toInt()
        println("\n=== rig: $lines lines, viewport ${visible} rows, lineHeight=$lineH gutter=${cap.render.gutterWidthPx} ===")

        // ---- A. steady-state repaint (caret blink / cursor move): every layout cached, same scroll ----
        cap.render.renderCache.setInlays(emptyMap(), cap.inlayStyle)
        cap.render.renderCache.setSemanticSpans(emptyMap())
        val vMid = lineH * 1500
        repeat(40) { frame(vMid, true, false, false) }
        report("repaint, cached layouts, diags on ") { frame(vMid, true, false, false) }
        report("repaint, cached layouts, no diags ") { frame(vMid, false, false, false) }

        // ---- B. with inlays + semantic spans pushed (the real editor state) ----
        cap.render.renderCache.setInlays(cap.perLineInlays, cap.inlayStyle)
        cap.render.renderCache.setSemanticSpans(cap.perLineSem)
        repeat(40) { frame(vMid, true, true, true) }
        report("repaint, inlays+semantic      ") { frame(vMid, true, true, true) }

        // ---- C. scrolling: each frame moves by one line, so ~1 line newly shaped per frame ----
        var v = lineH * 100
        repeat(40) { v += lineH; frame(v, true, true, true) }
        report("scroll 1 line/frame           ") { v += lineH; frame(v, true, true, true) }

        // ---- D. fling: 12 lines per frame (many newly-shaped lines) ----
        var f = lineH * 100
        repeat(20) { f += lineH * 12; frame(f, true, true, true) }
        report("fling 12 lines/frame          ") { f += lineH * 12; frame(f, true, true, true) }

        // ---- E. a viewport re-shaped from scratch. Two cases, and the difference between them is the
        // measurer's content cache: re-shaping text it has already seen (a theme or zoom change, which drops
        // the line cache but revisits the same lines) against arriving on lines nothing has ever shaped (open
        // a file, jump to a search hit). The second is the one that costs. ----
        report("re-shape a SEEN viewport      ", runs = 12) {
            cap.render.renderCache.clear()
            cap.render.renderCache.setInlays(cap.perLineInlays, cap.inlayStyle)
            cap.render.renderCache.setSemanticSpans(cap.perLineSem)
            frame(vMid, true, true, true)
        }
        run {
            var jump = 0f
            report("arrive on an UNSEEN viewport  ", runs = 12) {
                cap.render.renderCache.clear()
                cap.render.renderCache.setInlays(cap.perLineInlays, cap.inlayStyle)
                cap.render.renderCache.setSemanticSpans(cap.perLineSem)
                jump += lineH * 120 // past anything the measurer has cached
                frame(jump, true, true, true)
            }
        }

        // ---- F. fling split: shaping the newly-visible lines vs drawing an all-cached frame ----
        run {
            val rc = cap.render.renderCache
            // shape-only: clear, then ask for 12 fresh lines (what a 12-line fling step adds)
            var base = 2000
            reportRaw("shape 12 fresh lines (measure)", runs = 40) {
                base += 12
                var bh = 0L
                for (l in base until base + 12) bh += cap.render.layoutFor(l).size.width.toLong()
                sink += bh
            }
            rc.clear()
            rc.setInlays(cap.perLineInlays, cap.inlayStyle)
            rc.setSemanticSpans(cap.perLineSem)
            repeat(3) { frame(vMid, true, true, true) }
            reportRaw("draw frame, all 56 cached     ", runs = 60) { frame(vMid, true, true, true) }
        }

        // ---- G. cache maintenance ----
        run {
            val rc = cap.render.renderCache
            // fill past MAX_ENTRIES so evict() fires
            for (l in 0 until 700) cap.render.layoutFor(l)
            reportRaw("evict pass (512-entry sort)   ", runs = 30) {
                for (l in 700 until 840) cap.render.layoutFor(l) // crosses the 512 cap -> evict
            }
            reportRaw("shiftKeys (Enter, warm cache) ", runs = 200) { rc.shiftKeys(1500, 1) }
        }

        // ---- H. per-recomposition overlay push (what rememberEditorRenderState does every recomposition) ----
        run {
            val rc = cap.render.renderCache
            val merged = mergeSpanLayers(cap.perLineSem, emptyMap())
            rc.setSemanticSpans(merged)
            reportRaw("setSemanticSpans (same map)   ", runs = 500) { rc.setSemanticSpans(merged) }
            reportRaw("setSemanticSpans (new equal)  ", runs = 200) {
                rc.setSemanticSpans(HashMap(cap.perLineSem))
            }
        }

        // ---- I. display-list recording only (Android proxy: no rasterization) ----
        run {
            repeat(20) { record(vMid) }
            reportRaw("RECORD frame, all cached      ", runs = 60) { record(vMid) }
        }

        // ---- J. the Enter keystroke's cache shift, broken down ----
        run {
            val rc = cap.render.renderCache
            rc.clear()
            rc.setInlays(cap.perLineInlays, cap.inlayStyle)
            rc.setSemanticSpans(cap.perLineSem)
            repeat(6) { frame(vMid, true, true, true) } // ~56 cached layouts, like a real editor
            println("  (inlay lines=${cap.perLineInlays.size}, semantic lines=${cap.perLineSem.size})")
            reportRaw("shiftKeys full (Enter)        ", runs = 300) { rc.shiftKeys(1500, 1) }
            val layoutOnly = HashMap<Int, Int>()
            for (l in 1480..1540) layoutOnly[l] = l
            reportRaw("  shiftIntKeyed(56-entry map) ", runs = 300) {
                // same body as LineRenderCache's internal shiftIntKeyed (not visible across the module line)
                val moved = layoutOnly.entries.filter { it.key >= 1500 }.map { it.key to it.value }
                for ((k, _) in moved) layoutOnly.remove(k)
                for ((k, v) in moved) { val nk = k + 1; if (nk >= 0) layoutOnly[nk] = v }
            }
            var sem: Map<Int, List<SemSpan>> = cap.perLineSem
            reportRaw("  semantic map rebuild (mapKeys)", runs = 100) {
                sem = sem.mapKeys { (k, _) -> if (k >= 1500) k + 1 else k }.filterKeys { it >= 0 }
            }
            sink += sem.size.toLong()
        }

        // ---- K0. END-TO-END: the same fling with a CONTENT-CACHED measurer (cacheSize=128) ----
        run {
            val style = cap.render.codeStyle
            for (cacheSize in intArrayOf(0, 128)) {
                val m = TextMeasurer(cap.fontResolver, density, LayoutDirection.Ltr, cacheSize)
                val rc2 = LineRenderCache(m, style, cap.render.palette)
                rc2.setInlays(cap.perLineInlays, cap.inlayStyle)
                rc2.setSemanticSpans(cap.perLineSem)
                var l = 300
                repeat(12) { rc2.layoutFor(l++, session.doc, session.styles) } // warm JIT
                var bh = 0L
                val alloc0 = allocated()
                val t0 = System.nanoTime()
                val steps = 40
                repeat(steps) { repeat(12) { bh += rc2.layoutFor(l++, session.doc, session.styles).size.width.toLong() } }
                val dt = System.nanoTime() - t0
                val alloc = (allocated() - alloc0) / steps
                sink += bh
                println(
                    "shape 12 lines, measurer cacheSize=%-3d | %6.3f ms/step  alloc %7.1f KB/step".format(
                        cacheSize, dt / 1e6 / steps, alloc / 1024.0,
                    ),
                )
            }
        }

        // ---- K. what one line's shaping actually costs, by complexity ----
        run {
            // An uncached measurer on purpose: the editor's own is content-cached, so measuring the same line
            // through it would report a cache hit under a label that claims to be the cost of shaping.
            val m = TextMeasurer(cap.fontResolver, density, LayoutDirection.Ltr, cacheSize = 0)
            val style = cap.render.codeStyle
            val plainText = session.doc.lineText(widestLine(session))
            val short = "    }"
            val blank = ""
            val ann = AnnotatedString(plainText)
            microReport("measure plain line (${plainText.length}ch)", 3_000) {
                m.measure(ann, style = style, softWrap = false, maxLines = 1).size.width.toLong()
            }
            microReport("measure '    }' (5ch)   ", 8_000) {
                m.measure(AnnotatedString(short), style = style, softWrap = false, maxLines = 1)
                    .size.width.toLong()
            }
            microReport("measure '' (blank line) ", 8_000) {
                m.measure(AnnotatedString(blank), style = style, softWrap = false, maxLines = 1)
                    .size.width.toLong()
            }
            // the same text through the editor's content-cached measurer: what a duplicate line costs instead
            val cached = TextMeasurer(
                cap.fontResolver, density, LayoutDirection.Ltr, MEASURER_CACHE_ENTRIES,
            )
            repeat(50) { cached.measure(AnnotatedString(short), style = style, softWrap = false, maxLines = 1) }
            microReport("measure '    }' cached      ", 20_000) {
                cached.measure(AnnotatedString(short), style = style, softWrap = false, maxLines = 1)
                    .size.width.toLong()
            }
            // AnnotatedString construction alone (the span mapping the render cache does before measuring)
            val line = widestLine(session)
            val spans = session.styles.spansFor(line)
            val sem = cap.perLineSem[line].orEmpty()
            microReport("build AnnotatedString   ", 100_000) {
                val ranges = spans.mapNotNull { sp ->
                    cap.render.palette[sp.type.ordinal]?.let {
                        AnnotatedString.Range(it, sp.start, sp.end)
                    }
                } + sem.map { AnnotatedString.Range(it.style, it.start, it.end) }
                AnnotatedString(plainText, spanStyles = ranges).length.toLong()
            }
            println("  (line $line: ${plainText.length} chars, ${spans.size} lexical spans, ${sem.size} semantic spans)")
        }

        // ---- micro: the helpers the draw loop calls per visible line ----
        val rc = cap.render.renderCache
        val plain = 1501            // a line with no inlay
        val withInlay = cap.perLineInlays.keys.first { it in 1400..1700 }
        microReport("BASELINE (empty body)   ", 300_000) { 1L }
        microReport("rawToVisual (no inlay)  ", 300_000) { rc.rawToVisual(plain, 20).toLong() }
        microReport("rawToVisual (has inlay) ", 300_000) { rc.rawToVisual(withInlay, 20).toLong() }
        microReport("layoutFor  (cache hit)  ", 300_000) { cap.render.layoutFor(plain).size.width.toLong() }
        microReport("styles.revOf            ", 300_000) { session.styles.revOf(plain).toLong() }
        microReport("numberLayout (cache hit)", 300_000) { cap.render.numberLayout(plain).size.width.toLong() }
        microReport("indentColsFor (cache hit)", 300_000) { cap.render.indentColsFor(plain).toLong() }
        microReport("doc.lineStart           ", 300_000) { session.doc.lineStart(plain).toLong() }
        microReport("measure() one fresh line", 4_000) {
            cap.render.renderCache.clear()
            cap.render.layoutFor(plain).size.width.toLong()
        }

        scene.close()
        surface.close()
        recorder.close()
    }

    // ---- harness ----

    private inline fun report(label: String, runs: Int = 60, body: () -> Unit) {
        repeat(10) { body() }
        val alloc0 = allocated()
        var best = Long.MAX_VALUE
        var total = 0L
        repeat(runs) {
            val t0 = System.nanoTime()
            body()
            val dt = System.nanoTime() - t0
            total += dt
            if (dt < best) best = dt
        }
        val allocPerFrame = (allocated() - alloc0) / runs
        println(
            "%s | best %6.2f ms  mean %6.2f ms  alloc %7.1f KB/frame".format(
                label, best / 1e6, total / runs / 1e6, allocPerFrame / 1024.0,
            ),
        )
    }

    private var sink = 0L
    private inline fun microReport(label: String, ops: Int, body: () -> Long) {
        var bh = 0L
        repeat(ops / 10) { bh += body() }
        val alloc0 = allocated()
        val t0 = System.nanoTime()
        repeat(ops) { bh += body() }
        val dt = System.nanoTime() - t0
        val alloc = (allocated() - alloc0).toDouble() / ops
        sink += bh
        println("  micro %s | %8.1f ns/op  %6.1f B/op".format(label, dt.toDouble() / ops, alloc))
    }

    /** Like [report] but without the extra warm-up pass (callers that mutate state do their own). */
    private inline fun reportRaw(label: String, runs: Int, body: () -> Unit) {
        val alloc0 = allocated()
        var best = Long.MAX_VALUE
        var total = 0L
        repeat(runs) {
            val t0 = System.nanoTime()
            body()
            val dt = System.nanoTime() - t0
            total += dt
            if (dt < best) best = dt
        }
        val allocPer = (allocated() - alloc0) / runs
        println(
            "%s | best %6.3f ms  mean %6.3f ms  alloc %7.1f KB/op".format(
                label, best / 1e6, total / runs / 1e6, allocPer / 1024.0,
            ),
        )
    }

    private fun allocated(): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        return (bean as com.sun.management.ThreadMXBean).getThreadAllocatedBytes(Thread.currentThread().id)
    }

    // ---- fixtures ----

    private class Captured(
        val render: EditorRenderState,
        val measurer: androidx.compose.ui.text.TextMeasurer,
        val fontResolver: FontFamily.Resolver,
        val colors: EditorDrawColors,
        val inlayStyle: SpanStyle,
        val perLineInlays: Map<Int, List<InlayPiece>>,
        val perLineSem: Map<Int, List<SemSpan>>,
    )

    @Composable
    private fun Capture(
        session: EditorSession,
        semTokens: List<UiSemanticToken>,
        inlays: List<UiInlayHint>,
        onReady: (Captured) -> Unit,
    ) {
        val measurer = rememberTextMeasurer(cacheSize = MEASURER_CACHE_ENTRIES)
        val density = LocalDensity.current
        val colors = Ca.colors
        val editorColors = Ide.editorColors
        val typography = Ca.type
        val render =
            rememberEditorRenderState(session, measurer, density, colors, editorColors, typography, 1f, true)
        Box(Modifier.fillMaxSize())
        val draw = EditorDrawColors(
            background = colors.editorBg, currentLine = colors.currentLine, caret = colors.accent,
            selection = colors.accent.copy(alpha = 0.30f), gutterText = colors.gutterText,
            gutterCurrent = colors.textSecondary, gutterBorder = colors.separator, error = colors.error,
            warning = colors.warning, info = colors.info, muted = colors.textTertiary,
            composing = colors.textSecondary, indentGuide = colors.hairline,
            findMatch = colors.warning.copy(alpha = 0.28f), findCurrent = colors.accent.copy(alpha = 0.5f),
            occurrence = colors.textSecondary.copy(alpha = 0.18f), templateField = colors.accent.copy(alpha = 0.16f),
        )
        val inlayStyle = SpanStyle(color = colors.textTertiary)
        val perLineInlays = buildMap<Int, MutableList<InlayPiece>> {
            val d = session.doc
            for (h in inlays) {
                val off = h.offset.coerceIn(0, d.length)
                val line = d.lineForOffset(off)
                getOrPut(line) { ArrayList() }.add(
                    InlayPiece(off - d.lineStart(line), " " + h.text),
                )
            }
        }
        val perLineSem = perLineSemanticSpans(semTokens, session.doc, editorColors)
        onReady(
            Captured(
                render, measurer, LocalFontFamilyResolver.current,
                draw, inlayStyle, perLineInlays, perLineSem,
            ),
        )
    }

    /** The widest line in the fixture's first stretch — a representative line to price, not a blank one. */
    private fun widestLine(session: EditorSession): Int {
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

    private fun plainVLayout(session: EditorSession) = object : VLayout {
        private val fold: FoldModel get() = session.foldModel
        override val totalRows: Int get() = fold.visualLineCount
        override fun topRow(line: Int) = fold.visualForDocLine(line)
        override fun rowsOf(line: Int) = if (fold.isHidden(line)) 0 else 1
        override fun docLineForRow(row: Int) = fold.docLineForVisual(row)
        override fun correctRange(first: Int, last: Int) = Unit
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

    private fun syntheticSemanticTokens(session: EditorSession): List<UiSemanticToken> {
        val doc = session.doc
        val out = ArrayList<UiSemanticToken>()
        var line = 0
        while (line < doc.lineCount) {
            val s = doc.lineStart(line)
            val len = doc.lineLength(line)
            if (len > 12) {
                out.add(UiSemanticToken(s + 4, s + 10, "function", setOf(UiHighlightModifier.Declaration)))
                if (len > 24) out.add(UiSemanticToken(s + 14, s + 20, "property"))
            }
            line++
        }
        return out
    }

    private fun syntheticInlays(session: EditorSession): List<UiInlayHint> {
        val doc = session.doc
        val out = ArrayList<UiInlayHint>()
        var line = 0
        while (line < doc.lineCount) {
            val len = doc.lineLength(line)
            if (len > 16) {
                out.add(
                    UiInlayHint(
                        offset = doc.lineStart(line) + 8,
                        parts = listOf(UiInlayPart(": Int")),
                        kind = UiInlayKind.Type,
                    ),
                )
            }
            line += 12
        }
        return out
    }

    private fun syntheticDiagnostics(session: EditorSession): List<UiDiagnostic> {
        val doc = session.doc
        val out = ArrayList<UiDiagnostic>()
        var line = 3
        while (line < doc.lineCount) {
            val len = doc.lineLength(line)
            if (len > 20) {
                val s = doc.lineStart(line) + 4
                out.add(UiDiagnostic(UiSeverity.Warning, line, 4, "unused", s, s + 8, unused = true))
            }
            line += 9
        }
        return out
    }
}
