package dev.ide.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration as ComposeTextDecoration
import dev.ide.ui.backend.UiDecorationStyle
import dev.ide.ui.backend.UiDecorationTint
import dev.ide.ui.backend.UiTextDecoration
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.SemSpan
import dev.ide.ui.theme.CodeAssistColors

/**
 * Resolves a plugin's editor decorations against the active theme and bins them per line, the counterpart of
 * [perLineSemanticSpans] for the `platform.editorDecoration` tier.
 *
 * A decoration names a color ROLE, never a value, so this is where it becomes a color. That indirection is
 * the whole reason the tint set is closed: a plugin cannot know whether the light or the dark theme is active,
 * and the IDE's themes are generated (see `SeedScheme`), so there is no fixed value a plugin could have
 * hard-coded and still be legible.
 *
 * The styles split by how they are drawn, not by how they look. Recoloring text or striking it through is a
 * property of the shaped line, so those become [SemSpan]s the render cache folds into the line's layout, and
 * they get the per-line caching and the wrap handling for free. Everything else is geometry over the shaped
 * line, so it is drawn in the editor's canvas from a [DecoSeg].
 */

/** A decoration reduced to one line's columns and a resolved color: what the canvas layer draws. */
internal class DecoSeg(
    val startCol: Int,
    val endCol: Int,
    val style: UiDecorationStyle,
    val color: Color,
)

/** The theme color for a decoration [tint]. */
internal fun decorationColor(tint: UiDecorationTint, colors: CodeAssistColors): Color = when (tint) {
    UiDecorationTint.Accent -> colors.accent
    UiDecorationTint.Info -> colors.info
    UiDecorationTint.Success -> colors.success
    UiDecorationTint.Warning -> colors.warning
    UiDecorationTint.Error -> colors.error
    UiDecorationTint.Muted -> colors.textTertiary
    UiDecorationTint.Added -> colors.gitAdded
    UiDecorationTint.Removed -> colors.gitDeleted
    UiDecorationTint.Modified -> colors.gitModified
}

/**
 * Whether [style] recolors the shaped text (so it travels as a [SemSpan]) rather than being drawn over it.
 * The two sets are disjoint and together cover [UiDecorationStyle], so every decoration reaches exactly one
 * layer.
 */
private fun isTextStyle(style: UiDecorationStyle): Boolean =
    style == UiDecorationStyle.Foreground || style == UiDecorationStyle.Strikethrough

/**
 * The text-styling decorations as per-line [SemSpan]s, to be merged with the semantic-token spans.
 *
 * A background fill on a [SpanStyle] is deliberately NOT used for [UiDecorationStyle.Background]: Compose
 * paints a span background inside the text layout, which would sit above the selection and below nothing,
 * and a coverage tint has to read as being behind both. That one goes to the canvas layer instead.
 */
internal fun perLineDecorationSpans(
    decorations: List<UiTextDecoration>,
    doc: EditorDocument,
    colors: CodeAssistColors,
): Map<Int, List<SemSpan>> {
    if (decorations.isEmpty()) return emptyMap()
    val out = HashMap<Int, MutableList<SemSpan>>()
    val len = doc.length
    for (d in decorations) {
        if (!isTextStyle(d.style)) continue
        val start = d.startOffset.coerceIn(0, len)
        val color = decorationColor(d.tint, colors)
        val style = when (d.style) {
            UiDecorationStyle.Foreground -> SpanStyle(color = color)
            // Struck-through text keeps its own color: a plugin marking something dead is saying it is dead,
            // not asking for it to be recolored, and recoloring it too would lose the syntax coloring under it.
            else -> SpanStyle(textDecoration = ComposeTextDecoration.LineThrough)
        }
        // A styled run cannot cross a line in the shaped-line model, so clip each decoration to the lines it
        // covers and emit one span per line rather than dropping the tail (a background tint over a whole
        // method is the common case, and clipping to the start line would mark only its signature).
        var line = doc.lineForOffset(start)
        var offset = start
        val end = d.endOffset.coerceIn(start, len)
        while (offset < end) {
            val lineStart = doc.lineStart(line)
            val lineEnd = doc.lineEnd(line)
            val segEnd = minOf(end, lineEnd)
            if (segEnd > offset) {
                out.getOrPut(line) { ArrayList(2) }.add(SemSpan(offset - lineStart, segEnd - lineStart, style))
            }
            if (lineEnd >= len) break
            line++
            offset = doc.lineStart(line)
        }
    }
    return out
}

/**
 * The drawn decorations as per-line [DecoSeg]s, in the order they should be painted (ascending
 * [UiTextDecoration.order], which the collector already sorted them into).
 */
internal fun perLineDecorationSegs(
    decorations: List<UiTextDecoration>,
    doc: EditorDocument,
    colors: CodeAssistColors,
): Map<Int, List<DecoSeg>> {
    if (decorations.isEmpty()) return emptyMap()
    val out = HashMap<Int, MutableList<DecoSeg>>()
    val len = doc.length
    for (d in decorations) {
        if (isTextStyle(d.style)) continue
        val start = d.startOffset.coerceIn(0, len)
        val end = d.endOffset.coerceIn(start, len)
        val color = decorationColor(d.tint, colors)
        var line = doc.lineForOffset(start)
        var offset = start
        while (offset < end) {
            val lineStart = doc.lineStart(line)
            val lineEnd = doc.lineEnd(line)
            val segEnd = minOf(end, lineEnd)
            // An empty segment happens where a multi-line range crosses a blank line; a background still
            // wants to show there (it is what makes a covered block read as one block), so it is kept with a
            // zero width and the draw widens it to the line height.
            if (segEnd >= offset) {
                out.getOrPut(line) { ArrayList(2) }
                    .add(DecoSeg(offset - lineStart, segEnd - lineStart, d.style, color))
            }
            if (lineEnd >= len) break
            line++
            offset = doc.lineStart(line)
        }
    }
    return out
}

/**
 * Merge two per-line span maps, the plugin decorations laid over the semantic tokens.
 *
 * Order matters and this is the only place it is decided: the render cache applies spans in list order, so a
 * plugin's recolor has to come last to win over the type-aware coloring underneath it. A plugin marking a
 * range is making a claim about that text ("not covered", "changed on this branch") that is more specific
 * than what color its syntax happens to be.
 */
internal fun mergeSpanLayers(
    base: Map<Int, List<SemSpan>>,
    over: Map<Int, List<SemSpan>>,
): Map<Int, List<SemSpan>> = when {
    over.isEmpty() -> base
    base.isEmpty() -> over
    else -> {
        val out = HashMap<Int, List<SemSpan>>(base.size + over.size)
        out.putAll(base)
        for ((line, spans) in over) {
            val existing = out[line]
            out[line] = if (existing == null) spans else existing + spans
        }
        out
    }
}
