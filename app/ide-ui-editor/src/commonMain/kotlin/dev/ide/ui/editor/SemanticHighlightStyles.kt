package dev.ide.ui.editor

import androidx.compose.ui.text.SpanStyle
import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.SemSpan
import dev.ide.ui.theme.colors.ResolvedColorScheme
import dev.ide.ui.theme.colors.semanticStyle
import dev.ide.ui.theme.colors.toSpanStyle

/**
 * Maps the backend-neutral semantic tokens to editor [SpanStyle]s and bins them per line.
 *
 * Both halves of the mapping are the color scheme's now: which attribute a kind resolves to, and which
 * attributes a token's modifiers layer over it, live in `SchemeKeyMapping` so the lexical and semantic
 * layers cannot disagree about what a construct is. The kind id is an OPEN set (the highlight SPI lets a
 * backend invent kinds), and an unrecognized one still returns null so the token is dropped and the line's
 * lexical coloring shows through unchanged.
 */

/** Resolve a token's (kind, modifiers) to a [SpanStyle], or null to leave the run to the lexical layer. */
fun semanticSpanStyle(kind: String, mods: Set<UiHighlightModifier>, colors: ResolvedColorScheme): SpanStyle? =
    colors.semanticStyle(kind, mods)?.toSpanStyle()

/**
 * Bin [tokens] (document offsets) into per-line [SemSpan]s (line-local columns), resolving each to a style
 * once via a small cache. Identifier tokens never cross a line, so a token is clamped to its start line.
 * Returns a document-line-keyed map the render cache overlays.
 */
fun perLineSemanticSpans(
    tokens: List<UiSemanticToken>,
    doc: EditorDocument,
    colors: ResolvedColorScheme,
): Map<Int, List<SemSpan>> {
    if (tokens.isEmpty()) return emptyMap()
    val styleCache = HashMap<String, SpanStyle?>()
    val out = HashMap<Int, MutableList<SemSpan>>()
    val len = doc.length
    for (t in tokens) {
        val start = t.startOffset.coerceIn(0, len)
        if (start >= len && t.startOffset >= len) continue
        val key = t.kind + ":" + t.modifiers.joinToString(",") { it.ordinal.toString() }
        val style = styleCache.getOrPut(key) { semanticSpanStyle(t.kind, t.modifiers, colors) } ?: continue
        val line = doc.lineForOffset(start)
        val ls = doc.lineStart(line)
        val lineEnd = doc.lineEnd(line)
        val end = t.endOffset.coerceIn(start, lineEnd)
        if (end <= start) continue
        out.getOrPut(line) { ArrayList() }.add(SemSpan(start - ls, end - ls, style))
    }
    return out
}
