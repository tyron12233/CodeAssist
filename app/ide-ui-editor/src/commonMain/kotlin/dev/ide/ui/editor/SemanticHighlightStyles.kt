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
 * Resolved styles per (kind, modifiers), so binning a file's tokens resolves each distinct combination once
 * instead of building a key string per token. The modifier set is folded into a bit mask (the modifiers are
 * a small enum), which with the kind identifies the combination exactly. Valid for one color scheme.
 */
class SemanticStyleCache(private val colors: ResolvedColorScheme) {
    private val byKind = HashMap<String, HashMap<Int, Any>>()

    fun styleFor(kind: String, mods: Set<UiHighlightModifier>): SpanStyle? {
        var mask = 0
        for (m in mods) mask = mask or (1 shl m.ordinal)
        val perMask = byKind.getOrPut(kind) { HashMap(4) }
        val hit = perMask[mask]
        if (hit != null) return hit as? SpanStyle
        val style = semanticSpanStyle(kind, mods, colors)
        perMask[mask] = style ?: NO_STYLE
        return style
    }

    private companion object {
        /** Marks a combination that resolves to no style, so it isn't resolved again. */
        val NO_STYLE = Any()
    }
}

/**
 * Bin [tokens] (document offsets) into per-line [SemSpan]s (line-local columns), resolving each to a style
 * once via a small cache. Identifier tokens never cross a line, so a token is clamped to its start line.
 * Returns a document-line-keyed map the render cache overlays.
 */
fun perLineSemanticSpans(
    tokens: List<UiSemanticToken>,
    doc: EditorDocument,
    colors: ResolvedColorScheme,
): Map<Int, List<SemSpan>> = perLineSemanticSpans(tokens, doc, SemanticStyleCache(colors))

/** [perLineSemanticSpans] with a caller-held [styles] cache, kept across passes for one color scheme. */
fun perLineSemanticSpans(
    tokens: List<UiSemanticToken>,
    doc: EditorDocument,
    styles: SemanticStyleCache,
): Map<Int, List<SemSpan>> = semanticSpansInLines(tokens, doc, styles, 0, doc.lineCount)

/**
 * The [perLineSemanticSpans] bins for document lines `[fromLine, toLine)` only: the per-edit re-bin. Each
 * token is placed exactly as the whole-file bin places it; tokens starting outside the lines are skipped with
 * two int compares, without the line lookup.
 */
fun semanticSpansInLines(
    tokens: List<UiSemanticToken>,
    doc: EditorDocument,
    styles: SemanticStyleCache,
    fromLine: Int,
    toLine: Int,
): Map<Int, List<SemSpan>> {
    if (tokens.isEmpty() || fromLine >= toLine) return emptyMap()
    val out = HashMap<Int, MutableList<SemSpan>>()
    val len = doc.length
    val rangeStart = doc.lineStart(fromLine)
    val rangeEnd = if (toLine >= doc.lineCount) Int.MAX_VALUE else doc.lineStart(toLine)
    for (t in tokens) {
        val start = t.startOffset.coerceIn(0, len)
        if (start >= len && t.startOffset >= len) continue
        if (start < rangeStart || start >= rangeEnd) continue
        val style = styles.styleFor(t.kind, t.modifiers) ?: continue
        val line = doc.lineForOffset(start)
        val ls = doc.lineStart(line)
        val lineEnd = doc.lineEnd(line)
        val end = t.endOffset.coerceIn(start, lineEnd)
        if (end <= start) continue
        out.getOrPut(line) { ArrayList() }.add(SemSpan(start - ls, end - ls, style))
    }
    return out
}
