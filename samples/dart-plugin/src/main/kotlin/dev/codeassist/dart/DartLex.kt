package dev.codeassist.dart

/**
 * Just enough of Dart's lexical structure to scan source for declarations without a parser: where comments
 * and strings end, so a `test(` or an `@Preview` inside one is not taken for code, and matching brackets.
 */
internal object DartLex {
    /** Past a comment or string starting at [i], or [i] itself when neither starts there. */
    fun skipNonCode(text: String, i: Int): Int {
        if (text.startsWith("//", i)) return text.indexOf('\n', i).let { if (it < 0) text.length else it }
        if (text.startsWith("/" + "*", i)) {
            var depth = 0
            var j = i
            while (j < text.length) {
                if (text.startsWith("/" + "*", j)) { depth++; j += 2; continue }
                if (text.startsWith("*" + "/", j)) { depth--; j += 2; if (depth == 0) return j; continue }
                j++
            }
            return text.length
        }
        val raw = text[i] == 'r' && i + 1 < text.length && (text[i + 1] == '\'' || text[i + 1] == '"') &&
            (i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_'))
        val q = if (raw) i + 1 else i
        if (q >= text.length || (text[q] != '\'' && text[q] != '"')) return i
        return stringEnd(text, q, raw)
    }

    /** The index past the string literal whose opening quote is at [q]. */
    fun stringEnd(text: String, q: Int, raw: Boolean): Int {
        val quote = text[q]
        val triple = text.startsWith("$quote$quote$quote", q)
        val close = if (triple) "$quote$quote$quote" else "$quote"
        var j = q + close.length
        while (j < text.length) {
            if (!raw && text[j] == '\\') { j += 2; continue }
            if (text.startsWith(close, j)) return j + close.length
            if (!triple && text[j] == '\n') return j // unterminated: stop at the line's end
            j++
        }
        return text.length
    }

    /** The index of the `)` matching the `(` at [open], skipping strings and comments; the text's end if none. */
    fun matchParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            val skipped = skipNonCode(text, i)
            if (skipped != i) { i = skipped; continue }
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return text.length
    }

    /** The index of the `}` matching the `{` at [open], skipping strings and comments; the text's end if none. */
    fun matchBrace(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            val skipped = skipNonCode(text, i)
            if (skipped != i) { i = skipped; continue }
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return text.length
    }
}
