package dev.ide.ui.editor

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A literal under the caret that the literal-tweak chip can adjust in place: a number to step or scrub, a
 * `Color(0xAARRGGBB)` to pick, or a boolean to flip. [start]/[end] is the source range the chip rewrites (for a
 * negative number it includes the unary `-`).
 *
 * Every adjustment is an ordinary text edit of exactly this range, so in a Compose preview file it lands as a
 * live-literal edit and the preview follows it without a re-lower.
 */
sealed interface TweakableLiteral {
    val start: Int
    val end: Int

    /** An integer or floating-point literal. [decimals] is how many fraction digits the source writes (the scrub
     *  step is one unit in the last place); [suffix] is the type suffix kept on rewrite (`f`, `L`, `u`, `uL`). */
    data class Number(
        override val start: Int,
        override val end: Int,
        val value: Double,
        val decimals: Int,
        val floating: Boolean,
        val suffix: String,
    ) : TweakableLiteral {
        /** The value one scrub step moves. */
        val step: Double get() = if (decimals == 0) 1.0 else 10.0.pow(-decimals)

        /** Source text for [newValue] in this literal's own format (same decimals and suffix). */
        fun format(newValue: Double): String {
            if (!floating) return newValue.roundToLong().toString() + suffix
            return formatFixed(newValue, decimals) + suffix
        }
    }

    /** The `0xAARRGGBB` argument of a `Color(...)` call. [lowercase] keeps the source's hex digit case. */
    data class ColorHex(
        override val start: Int,
        override val end: Int,
        val argb: Long,
        val prefix: String,
        val lowercase: Boolean,
    ) : TweakableLiteral {
        fun format(newArgb: Long): String {
            val hex = (newArgb and 0xFFFFFFFFL).toString(16).padStart(8, '0')
            return prefix + if (lowercase) hex else hex.uppercase()
        }
    }

    data class Bool(override val start: Int, override val end: Int, val value: Boolean) : TweakableLiteral {
        fun toggled(): String = (!value).toString()
    }
}

// A numeric or boolean token not glued to an identifier or a member access on its left (`x16`, `a.5`).
private val LITERAL_TOKEN = Regex(
    """(?<![\w.])(?:0[xX][0-9a-fA-F_]+(?:[uU]?L|[uU])?|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][+-]?\d+)?(?:[fF]|[uU]?L|[uU])?)(?![\w])|\b(?:true|false)\b"""
)

/**
 * The tweakable literal at [caret] in [text] (the caret may sit anywhere inside it or right after it), or null.
 * Lexical and line-local: a literal inside a string or after `//` on its line is ignored. A literal inside a block
 * comment can slip through, which only means the chip offers to edit commented-out code.
 */
fun tweakableLiteralAt(text: CharSequence, caret: Int): TweakableLiteral? {
    if (caret < 0 || caret > text.length) return null
    var lineStart = caret
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
    var lineEnd = caret
    while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
    val line = text.subSequence(lineStart, lineEnd).toString()
    val col = caret - lineStart
    val codeEnd = codePrefixEnd(line)
    for (m in LITERAL_TOKEN.findAll(line)) {
        val s = m.range.first
        val e = m.range.last + 1
        if (col < s || col > e) continue
        if (e > codeEnd || inString(line, s)) return null
        return classify(line, m.value, s, e)?.let { shift(it, lineStart) }
    }
    return null
}

private fun classify(line: String, token: String, s: Int, e: Int): TweakableLiteral? {
    if (token == "true" || token == "false") return TweakableLiteral.Bool(s, e, token == "true")
    val clean = token.replace("_", "")
    if (clean.startsWith("0x") || clean.startsWith("0X")) {
        // Only a full 8-digit ARGB inside `Color(` is a color; other hex has no meaningful scrub.
        val digits = clean.substring(2)
        if (digits.length != 8 || !digits.all { it.isHexDigit() }) return null
        if (!line.substring(0, s).trimEnd().endsWith("Color(")) return null
        val argb = digits.toLongOrNull(16) ?: return null
        return TweakableLiteral.ColorHex(s, e, argb, token.substring(0, 2), digits.any { it in 'a'..'f' })
    }
    if ('e' in clean || 'E' in clean) return null // exponent form: no natural step
    val suffix = Regex("""(?:[fF]|[uU]?L|[uU])$""").find(clean)?.value ?: ""
    val body = clean.dropLast(suffix.length)
    val floating = '.' in body || suffix.equals("f", ignoreCase = true)
    val decimals = if ('.' in body) body.length - body.indexOf('.') - 1 else 0
    var value = body.toDoubleOrNull() ?: return null
    // A unary minus directly in front (not a binary `a - 1`) belongs to the value, so scrubbing crosses zero.
    var start = s
    if (s > 0 && line[s - 1] == '-' && isUnaryMinus(line, s - 1)) { start = s - 1; value = -value }
    if (suffix.startsWith("u") || suffix.startsWith("U")) if (value < 0) return null
    return TweakableLiteral.Number(start, e, value, decimals, floating, suffix)
}

private fun isUnaryMinus(line: String, minus: Int): Boolean {
    var i = minus - 1
    while (i >= 0 && line[i] == ' ') i--
    if (i < 0) return true
    val c = line[i]
    return !(c.isLetterOrDigit() || c == '_' || c == ')' || c == ']' || c == '"' || c == '\'')
}

/** Where code ends on [line]: the start of a `//` comment outside a string, else the line length. */
private fun codePrefixEnd(line: String): Int {
    var inStr = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (inStr) {
            if (c == '\\') i++ else if (c == '"') inStr = false
        } else if (c == '"') {
            inStr = true
        } else if (c == '/' && i + 1 < line.length && line[i + 1] == '/') {
            return i
        }
        i++
    }
    return line.length
}

/** Whether [at] on [line] falls inside a `"…"` string (single-line strings only). */
private fun inString(line: String, at: Int): Boolean {
    var inStr = false
    var i = 0
    while (i < at) {
        val c = line[i]
        if (inStr && c == '\\') i++ else if (c == '"') inStr = !inStr
        i++
    }
    return inStr
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun shift(l: TweakableLiteral, by: Int): TweakableLiteral = when (l) {
    is TweakableLiteral.Number -> l.copy(start = l.start + by, end = l.end + by)
    is TweakableLiteral.ColorHex -> l.copy(start = l.start + by, end = l.end + by)
    is TweakableLiteral.Bool -> l.copy(start = l.start + by, end = l.end + by)
}

/** [value] written with exactly [decimals] fraction digits (no exponent, no locale), e.g. `1.50`, `-0.3`. */
internal fun formatFixed(value: Double, decimals: Int): String {
    val scale = 10.0.pow(decimals)
    val scaled = (abs(value) * scale).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return sign + scaled
    val unit = scale.roundToLong()
    val frac = (scaled % unit).toString().padStart(decimals, '0')
    return "$sign${scaled / unit}.$frac"
}
