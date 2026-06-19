package dev.ide.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.SemSpan
import dev.ide.ui.theme.SyntaxColors

/**
 * Maps the backend-neutral semantic tokens to editor [SpanStyle]s and bins them per line. The kind id is an
 * OPEN set (the highlight SPI lets a backend invent kinds): a recognized kind picks a base color, an unknown
 * one returns null so the token is dropped and the line's lexical coloring shows through unchanged. The four
 * Kotlin distinctions the user asked for (extension / @Composable / suspend / mutable `var`) carry BOTH a
 * distinct color and a font style, layered on top of the base.
 */

/** Base color for a semantic [kind] id, or null for a kind the UI doesn't recognize (defer to lexical). */
private fun baseColor(kind: String, s: SyntaxColors): Color? = when (kind) {
    "class", "interface", "enum", "annotation", "object", "typeParameter" -> s.type
    "method", "function", "constructor" -> s.func
    "property", "field" -> s.property
    "enumConstant" -> s.constant
    "parameter", "localVariable" -> s.variable
    "namespace" -> s.default
    else -> null
}

/** Resolve a token's (kind, modifiers) to a [SpanStyle], or null to leave the run to the lexical layer. */
fun semanticSpanStyle(kind: String, mods: Set<UiHighlightModifier>, syntax: SyntaxColors): SpanStyle? {
    var color = baseColor(kind, syntax) ?: return null
    var italic = false
    var bold = false
    var underline = false
    var strike = false
    // Distinct color + font style for each Kotlin distinction (applied last so the strong signal wins).
    if (UiHighlightModifier.Extension in mods) { color = syntax.extension; italic = true }
    if (UiHighlightModifier.Composable in mods) { color = syntax.composable; bold = true }
    if (UiHighlightModifier.Suspend in mods) { color = syntax.suspendFn; italic = true }
    if (UiHighlightModifier.Mutable in mods) { color = syntax.mutableVar; underline = true }
    if (UiHighlightModifier.Static in mods) italic = true
    if (UiHighlightModifier.Deprecated in mods) strike = true
    val deco = when {
        underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        underline -> TextDecoration.Underline
        strike -> TextDecoration.LineThrough
        else -> null
    }
    return SpanStyle(
        color = color,
        fontStyle = if (italic) FontStyle.Italic else null,
        fontWeight = if (bold) FontWeight.Medium else null,
        textDecoration = deco,
    )
}

/**
 * Bin [tokens] (document offsets) into per-line [SemSpan]s (line-local columns), resolving each to a style
 * once via a small cache. Identifier tokens never cross a line, so a token is clamped to its start line.
 * Returns a document-line-keyed map the render cache overlays.
 */
fun perLineSemanticSpans(
    tokens: List<UiSemanticToken>,
    doc: EditorDocument,
    syntax: SyntaxColors,
): Map<Int, List<SemSpan>> {
    if (tokens.isEmpty()) return emptyMap()
    val styleCache = HashMap<String, SpanStyle?>()
    val out = HashMap<Int, MutableList<SemSpan>>()
    val len = doc.length
    for (t in tokens) {
        val start = t.startOffset.coerceIn(0, len)
        if (start >= len && t.startOffset >= len) continue
        val key = t.kind + ":" + t.modifiers.joinToString(",") { it.ordinal.toString() }
        val style = styleCache.getOrPut(key) { semanticSpanStyle(t.kind, t.modifiers, syntax) } ?: continue
        val line = doc.lineForOffset(start)
        val ls = doc.lineStart(line)
        val lineEnd = doc.lineEnd(line)
        val end = t.endOffset.coerceIn(start, lineEnd)
        if (end <= start) continue
        out.getOrPut(line) { ArrayList() }.add(SemSpan(start - ls, end - ls, style))
    }
    return out
}
