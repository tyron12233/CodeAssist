package dev.ide.ui.editor

import androidx.compose.ui.text.SpanStyle
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.backend.UiTextDecoration
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.LineRenderCache
import dev.ide.ui.editor.core.SemSpan
import dev.ide.ui.theme.CodeAssistColors
import dev.ide.ui.theme.colors.ResolvedColorScheme

/** Where [OverlayBinner] writes its per-line overlays: the render cache, or a plain recorder in tests. */
internal interface OverlaySink {
    fun setSemanticSpans(spans: Map<Int, List<SemSpan>>)
    fun setSemanticLine(line: Int, spans: List<SemSpan>)
    fun setInlays(inlays: Map<Int, List<InlayPiece>>, style: SpanStyle)
    fun setInlayLine(line: Int, pieces: List<InlayPiece>)
}

private class RenderCacheSink(val cache: LineRenderCache) : OverlaySink {
    override fun setSemanticSpans(spans: Map<Int, List<SemSpan>>) = cache.setSemanticSpans(spans)
    override fun setSemanticLine(line: Int, spans: List<SemSpan>) = cache.setSemanticLine(line, spans)
    override fun setInlays(inlays: Map<Int, List<InlayPiece>>, style: SpanStyle) = cache.setInlays(inlays, style)
    override fun setInlayLine(line: Int, pieces: List<InlayPiece>) = cache.setInlayLine(line, pieces)
}

/**
 * Keeps the render cache's per-line overlays (semantic spans, inlay pieces, a plugin's text decorations) in
 * step with the session, at a cost proportional to what changed.
 *
 * A fresh daemon pass replaces an overlay list wholesale ([EditorSession.overlayGeneration] moves) and is
 * binned over the whole file, as it always was. A keystroke only SHIFTS those lists: every line the edit did
 * not touch keeps its spans, merely renumbered, and the render cache already renumbers its overlays on each
 * line splice. So an edit re-bins just the lines the session reports as touched
 * ([EditorSession.takeOverlayDirtyLines]) and writes them line by line, which bumps a line's stamp only when
 * its spans really changed. A plugin's text decorations may cross lines, so while any are present the whole
 * file is re-binned as before.
 */
internal class OverlayBinner {
    private var sinkKey: Any? = null
    private var schemeColors: ResolvedColorScheme? = null
    private var chromeColors: CodeAssistColors? = null
    private var inlayStyle: SpanStyle? = null
    private var generation = -1
    private var doc: EditorDocument? = null
    private var tokens: List<UiSemanticToken>? = null
    private var hints: List<UiInlayHint>? = null
    private var pluginHints: List<UiInlayHint>? = null
    private var decorations: List<UiTextDecoration>? = null
    private var styles: SemanticStyleCache? = null
    private var renderSink: RenderCacheSink? = null

    /** The plugin's geometric decorations binned per line for the canvas (empty without decorations). */
    var decoByLine: Map<Int, List<DecoSeg>> = emptyMap()
        private set

    fun sync(
        session: EditorSession,
        cache: LineRenderCache,
        editorColors: ResolvedColorScheme,
        colors: CodeAssistColors,
        inlayStyle: SpanStyle,
    ) {
        val sink = renderSink?.takeIf { it.cache === cache } ?: RenderCacheSink(cache).also { renderSink = it }
        syncInto(session, cache, sink, editorColors, colors, inlayStyle)
    }

    /** [sync] against any [sink]; [key] identifies it, and a new one is filled from scratch. */
    fun syncInto(
        session: EditorSession,
        key: Any,
        sink: OverlaySink,
        editorColors: ResolvedColorScheme,
        colors: CodeAssistColors,
        inlayStyle: SpanStyle,
    ) {
        val d = session.doc
        val t = session.semanticTokens
        val h = session.inlayHints
        val p = session.pluginInlays
        val decos = session.textDecorations
        val dirty = session.takeOverlayDirtyLines()
        val sameTarget = key === sinkKey && editorColors == schemeColors && colors == chromeColors &&
            inlayStyle == this.inlayStyle && session.overlayGeneration == generation
        if (sameTarget && d === doc && t === tokens && h === hints && p === pluginHints && decos === decorations) {
            return // nothing this reads has changed (a caret move, an unrelated recomposition)
        }
        val styleCache = styles.takeIf { editorColors == schemeColors } ?: SemanticStyleCache(editorColors)
        val perLine = sameTarget && d !== doc && dirty != null && decos.isEmpty() && decorations.isNullOrEmpty()
        if (perLine) {
            rebinLines(dirty!!, d, t, h, p, styleCache, sink)
        } else {
            val semantic = perLineSemanticSpans(t, d, styleCache)
            sink.setSemanticSpans(mergeSpanLayers(semantic, perLineDecorationSpans(decos, d, colors)))
            sink.setInlays(perLineInlayPieces(h, p, d, 0, d.lineCount), inlayStyle)
            decoByLine = perLineDecorationSegs(decos, d, colors)
        }
        sinkKey = key
        schemeColors = editorColors
        chromeColors = colors
        this.inlayStyle = inlayStyle
        generation = session.overlayGeneration
        doc = d
        tokens = t
        hints = h
        pluginHints = p
        decorations = decos
        styles = styleCache
    }

    private fun rebinLines(
        lines: IntRange,
        d: EditorDocument,
        t: List<UiSemanticToken>,
        h: List<UiInlayHint>,
        p: List<UiInlayHint>,
        styleCache: SemanticStyleCache,
        sink: OverlaySink,
    ) {
        if (lines.isEmpty()) return
        val from = lines.first
        val to = lines.last + 1
        // An overlay that was empty before and after has nothing on these lines to add or clear.
        if (t.isNotEmpty() || !tokens.isNullOrEmpty()) {
            val spans = semanticSpansInLines(t, d, styleCache, from, to)
            for (line in lines) sink.setSemanticLine(line, spans[line] ?: emptyList())
        }
        if (h.isNotEmpty() || p.isNotEmpty() || !hints.isNullOrEmpty() || !pluginHints.isNullOrEmpty()) {
            val pieces = perLineInlayPieces(h, p, d, from, to)
            for (line in lines) sink.setInlayLine(line, pieces[line] ?: emptyList())
        }
    }
}

/**
 * The language backend's [hints] then the plugin tier's [pluginHints] as phantom-text pieces per document
 * line, for lines `[fromLine, toLine)`: one run of text each, with the hint's padding folded in.
 */
internal fun perLineInlayPieces(
    hints: List<UiInlayHint>,
    pluginHints: List<UiInlayHint>,
    doc: EditorDocument,
    fromLine: Int,
    toLine: Int,
): Map<Int, List<InlayPiece>> {
    if ((hints.isEmpty() && pluginHints.isEmpty()) || fromLine >= toLine) return emptyMap()
    val out = HashMap<Int, MutableList<InlayPiece>>()
    val rangeStart = doc.lineStart(fromLine)
    val rangeEnd = if (toLine >= doc.lineCount) Int.MAX_VALUE else doc.lineStart(toLine)
    fun add(h: UiInlayHint) {
        val off = h.offset.coerceIn(0, doc.length)
        if (off < rangeStart || off >= rangeEnd) return
        val line = doc.lineForOffset(off)
        val col = off - doc.lineStart(line)
        val txt = (if (h.paddingLeft) " " else "") + h.text + (if (h.paddingRight) " " else "")
        out.getOrPut(line) { ArrayList() }.add(InlayPiece(col, txt))
    }
    for (h in hints) add(h)
    for (h in pluginHints) add(h)
    return out
}
