package dev.ide.ui.editor

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import dev.ide.ui.editor.core.TokenType
import dev.ide.ui.theme.SyntaxColors

// Small pure helpers for the code editor (no Compose state): caret heuristics, the syntax→SpanStyle palette,
// the zoom clamp, and code-point encoding. Extracted from CodeEditor.kt to keep it focused on the surface.

/**
 * A cheap, bounded backward scan: is the caret inside an open `(...)` call argument list? Used only as a gate
 * so signature help hits the backend just when the caret is plausibly in a call (the backend does the real,
 * literal-aware resolution). It bails at a statement boundary (`;`/`{`) or an opening `[`/`{` at depth 0 so an
 * array index / lambda body doesn't read as a call. False positives only cost one backend call that returns null.
 */
internal fun caretInsideCall(chars: CharSequence, caret: Int): Boolean {
    var depth = 0
    var i = (caret - 1).coerceAtMost(chars.length - 1)
    var guard = 0
    while (i >= 0 && guard < 4000) {
        when (chars[i]) {
            ')', ']', '}' -> depth++
            '(' -> { if (depth == 0) return true; depth-- }
            '[' -> { if (depth == 0) return false; depth-- }
            '{', ';' -> if (depth == 0) return false
        }
        i--; guard++
    }
    return false
}

/**
 * Whether [caret] sits inside an XML attribute value — between an opening quote and its close, within a start
 * tag (`<Tag attr="|">`). Cheap local scan: find the enclosing `<` without crossing a `>` (else we're in
 * element content, not a tag), then track quote state up to the caret. Used to arm parameter hints inside `""`.
 */
internal fun caretInsideXmlAttributeValue(chars: CharSequence, caret: Int): Boolean {
    var i = (caret - 1).coerceAtMost(chars.length - 1)
    var lt = -1
    var guard = 0
    while (i >= 0 && guard < 4000) {
        val c = chars[i]
        if (c == '>') return false // element content, not a start tag
        if (c == '<') { lt = i; break }
        i--; guard++
    }
    if (lt < 0) return false
    var quote: Char? = null
    var j = lt
    while (j < caret && j < chars.length) {
        val c = chars[j]
        if (quote != null) { if (c == quote) quote = null } else if (c == '"' || c == '\'') quote = c
        j++
    }
    return quote != null
}

internal fun paletteFor(syntax: SyntaxColors): Array<SpanStyle?> {
    val palette = arrayOfNulls<SpanStyle>(TokenType.entries.size)
    palette[TokenType.KEYWORD.ordinal] = SpanStyle(color = syntax.keyword)
    palette[TokenType.STRING.ordinal] = SpanStyle(color = syntax.string)
    palette[TokenType.COMMENT.ordinal] = SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic)
    palette[TokenType.NUMBER.ordinal] = SpanStyle(color = syntax.number)
    palette[TokenType.ANNOTATION.ordinal] = SpanStyle(color = syntax.annotation)
    palette[TokenType.FUNC.ordinal] = SpanStyle(color = syntax.func)
    palette[TokenType.TYPE.ordinal] = SpanStyle(color = syntax.type)
    palette[TokenType.PUNCT.ordinal] = SpanStyle(color = syntax.punctuation)
    palette[TokenType.PROPERTY.ordinal] = SpanStyle(color = syntax.property)
    return palette
}

/** Editor zoom limits (× the theme code size) and the clamp the pinch/keyboard zoom both go through. */
internal const val MIN_FONT_SCALE = 0.6f
internal const val MAX_FONT_SCALE = 2.6f
internal fun clampFontScale(s: Float): Float = s.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

internal fun codePointToString(cp: Int): String = when {
    cp < 0x10000 -> cp.toChar().toString()
    else -> {
        val v = cp - 0x10000
        charArrayOf(((v ushr 10) + 0xD800).toChar(), ((v and 0x3FF) + 0xDC00).toChar()).concatToString()
    }
}
