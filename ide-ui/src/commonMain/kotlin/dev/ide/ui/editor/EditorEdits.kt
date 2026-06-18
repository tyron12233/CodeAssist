package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ide.ui.editor.core.newlineHandlerFor

private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')
private val CLOSE_TO_OPEN = OPEN_TO_CLOSE.entries.associate { (k, v) -> v to k }
private val QUOTES = setOf('"', '\'')

/**
 * A single-character insertion, located by the *caret* rather than by prefix-diffing the strings. The
 * caret is authoritative: when the typed char equals the one right after it (typing `)` in front of an
 * auto-inserted `)`, or Enter before an existing newline) a prefix-diff would place the insertion one
 * slot too late, breaking skip-over and indent. Returns (position, char) or null.
 */
private fun singleInsert(old: TextFieldValue, new: TextFieldValue): Pair<Int, Char>? {
    if (new.text.length != old.text.length + 1 || !new.selection.collapsed) return null
    val pos = new.selection.start - 1
    if (pos < 0 || pos >= new.text.length) return null
    // The char at [pos] must be exactly what's extra: removing it reproduces the old text.
    if (new.text.substring(0, pos) + new.text.substring(pos + 1) != old.text) return null
    return pos to new.text[pos]
}

/**
 * A single-character deletion, located by the caret. For both Backspace and forward-Delete the removed
 * index in [old] equals the new caret position. Returns (deletedIndexInOld, deletedChar) or null.
 */
private fun singleDelete(old: TextFieldValue, new: TextFieldValue): Pair<Int, Char>? {
    if (old.text.length != new.text.length + 1 || !new.selection.collapsed) return null
    val pos = new.selection.start
    if (pos < 0 || pos >= old.text.length) return null
    if (old.text.substring(0, pos) + old.text.substring(pos + 1) != new.text) return null
    return pos to old.text[pos]
}

private fun shouldAutoClose(nextChar: Char?): Boolean =
    nextChar == null || nextChar.isWhitespace() || nextChar in ")]},;"

private fun isIdentChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')

/**
 * Whether typing [open] should also insert its closing partner, given the surrounding [text]. Counts
 * the document-wide balance of that bracket type (bounded by [BRACKET_SCAN_LIMIT]): when the closers
 * already outnumber the openers, the just-typed opener will be matched by one of those stray closers
 * (e.g. typing `{` on `fun main() <caret>\n  …\n}` — the trailing `}` is its partner), so inserting
 * another closer would leave a dangling one. Over the limit we fall back to always auto-closing.
 */
private fun shouldInsertClosingBracket(text: CharSequence, open: Char): Boolean {
    val close = OPEN_TO_CLOSE.getValue(open)
    val n = text.length
    if (n > BRACKET_SCAN_LIMIT) return true
    var opens = 0
    var closes = 0
    var i = 0
    while (i < n) {
        val c = text[i]
        if (c == open) opens++ else if (c == close) closes++
        i++
    }
    return opens >= closes
}

/**
 * IDE smart editing applied to a just-produced [new] value relative to [old]: auto-close brackets and
 * quotes, skip over an auto-inserted closing char when the user types it, and smart Enter (continue the
 * current indent, and expand a `{}` / `()` / `[]` pair the caret sits inside onto three lines). Returns
 * the value the editor should adopt — equal to [new] when nothing applies (paste, multi-char edits, …).
 */
fun applySmartEdit(old: TextFieldValue, new: TextFieldValue, language: CodeLanguage): TextFieldValue {
    // Deleting the opener of an empty pair removes its auto-inserted partner too: `(|)` + Backspace -> ``.
    singleDelete(old, new)?.let { (pos, deleted) ->
        val nextInNew = new.text.getOrNull(pos)
        val isEmptyPair = (deleted in OPEN_TO_CLOSE && nextInNew == OPEN_TO_CLOSE[deleted]) ||
            (deleted in QUOTES && nextInNew == deleted)
        return if (isEmptyPair) TextFieldValue(new.text.removeRange(pos, pos + 1), TextRange(pos)) else new
    }

    val (pos, ch) = singleInsert(old, new) ?: return new
    val text = old.text
    val nextChar = text.getOrNull(pos) // char that sat at the caret before the insertion

    // 1. Skip-over: typing the closing char that's already there just moves past it.
    if ((ch in CLOSE_TO_OPEN || ch in QUOTES) && nextChar == ch) {
        return TextFieldValue(text, TextRange(pos + 1))
    }

    // 2. Auto-close brackets — but only when a matching closer doesn't already exist further on.
    if (ch in OPEN_TO_CLOSE && shouldAutoClose(nextChar) && shouldInsertClosingBracket(text, ch)) {
        val close = OPEN_TO_CLOSE.getValue(ch)
        val merged = text.substring(0, pos) + ch + close + text.substring(pos)
        return TextFieldValue(merged, TextRange(pos + 1))
    }

    // 3. Auto-close quotes (not when finishing an identifier like an apostrophe, not in plain text files).
    if (ch in QUOTES && language != CodeLanguage.Plain && shouldAutoClose(nextChar) && !isIdentChar(text.getOrNull(pos - 1))) {
        val merged = text.substring(0, pos) + ch + ch + text.substring(pos)
        return TextFieldValue(merged, TextRange(pos + 1))
    }

    // 4. Smart Enter — delegate to the shared per-language handler (same logic as the canvas editor's path).
    if (ch == '\n') {
        val e = newlineHandlerFor(language).onEnter(text, pos)
        val merged = text.substring(0, e.start) + e.text + text.substring(e.end)
        return TextFieldValue(merged, TextRange(e.caret))
    }

    return new
}

/** Cap on the bracket-match scan so an unmatched bracket in a huge file can't cost O(N) per keystroke. */
private const val BRACKET_SCAN_LIMIT = 50_000

/**
 * The matching bracket for the bracket immediately before or at [caret], as (openIndex, closeIndex), or
 * null. Naive depth scan (ignores strings/comments) — fine for highlighting. Takes a [CharSequence] so it
 * reads off the rope directly, and the scan is bounded by [BRACKET_SCAN_LIMIT] (an unmatched bracket just
 * yields no highlight rather than walking the whole document).
 */
fun matchingBracket(text: CharSequence, caret: Int): Pair<Int, Int>? {
    for (probe in intArrayOf(caret - 1, caret)) {
        if (probe < 0 || probe >= text.length) continue
        val ch = text[probe]
        if (ch in OPEN_TO_CLOSE) {
            val close = OPEN_TO_CLOSE.getValue(ch)
            var depth = 0
            var i = probe
            val limit = minOf(text.length, probe + BRACKET_SCAN_LIMIT)
            while (i < limit) {
                val c = text[i]
                if (c == ch) depth++ else if (c == close) { depth--; if (depth == 0) return probe to i }
                i++
            }
        } else if (ch in CLOSE_TO_OPEN) {
            val open = CLOSE_TO_OPEN.getValue(ch)
            var depth = 0
            var i = probe
            val limit = maxOf(0, probe - BRACKET_SCAN_LIMIT)
            while (i >= limit) {
                val c = text[i]
                if (c == ch) depth++ else if (c == open) { depth--; if (depth == 0) return i to probe }
                i--
            }
        }
    }
    return null
}
