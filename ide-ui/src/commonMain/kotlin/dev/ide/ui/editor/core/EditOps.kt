package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage

/**
 * Smart-editing rules as **range edits** — the same behaviors `applySmartEdit` (EditorEdits.kt)
 * produced by rewriting whole strings, reshaped so the session can splice the document incrementally:
 * auto-close brackets/quotes, skip over an auto-inserted closer, smart Enter (indent continuation +
 * empty-pair expansion), and pair-aware backspace. Pure functions over the raw text; compose-free.
 */
class RangeEdit(
    val start: Int,
    val end: Int,
    val text: String,
    /** Caret offset after the edit, in post-edit coordinates. */
    val caret: Int,
)

/**
 * A single contiguous text change in pre-edit coordinates: the [removed] chars at [start] became [added]
 * chars. The [EditorSession] emits one per mutation so consumers (diagnostic re-mapping) can shift offsets
 * in O(items) — no whole-string diff, the editor already knows exactly what changed.
 */
class EditSpan(val start: Int, val removed: Int, val added: Int) {
    val delta: Int get() = added - removed
    val isNoOp: Boolean get() = removed == 0 && added == 0
}

private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')
private val CLOSE_TO_OPEN = OPEN_TO_CLOSE.entries.associate { (k, v) -> v to k }
private val QUOTES = setOf('"', '\'')
internal const val INDENT_UNIT = "    " // 4 spaces

/** Bounded random access for the rope-backed buffer — `null` outside `[0, length)`, never materializes. */
private fun CharSequence.charOrNull(index: Int): Char? = if (index in 0 until length) this[index] else null

private fun shouldAutoClose(nextChar: Char?): Boolean =
    nextChar == null || nextChar.isWhitespace() || nextChar in ")]},;"

private fun isIdentChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')

/** Cap on the balance scan so an unmatched bracket in a huge file can't cost O(N) per keystroke. */
private const val BRACKET_SCAN_LIMIT = 50_000

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
 * The edit for typing [ch] with the caret/selection at `[selStart, selEnd)`. A selection is replaced
 * plainly (parity with the old path, where smart rules only applied to collapsed single-char inserts).
 *
 * Takes a [CharSequence] (the rope) and touches only a handful of chars around the caret, so the per-key
 * smart-edit decision is O(log N) and never copies the whole document.
 */
fun smartInsert(text: CharSequence, selStart: Int, selEnd: Int, ch: Char, language: CodeLanguage): RangeEdit {
    if (selStart != selEnd) return RangeEdit(selStart, selEnd, ch.toString(), selStart + 1)
    val pos = selStart
    val nextChar = text.charOrNull(pos)

    // 1. Skip-over: typing the closing char that's already there just moves past it.
    if ((ch in CLOSE_TO_OPEN || ch in QUOTES) && nextChar == ch) {
        return RangeEdit(pos, pos, "", pos + 1)
    }
    // 2. Auto-close brackets — but only when a matching closer doesn't already exist further on.
    if (ch in OPEN_TO_CLOSE && shouldAutoClose(nextChar)) {
        return if (shouldInsertClosingBracket(text, ch)) {
            RangeEdit(pos, pos, "$ch${OPEN_TO_CLOSE.getValue(ch)}", pos + 1)
        } else {
            RangeEdit(pos, pos, ch.toString(), pos + 1)
        }
    }
    // 3. Auto-close quotes (not when finishing an identifier, not in plain text files).
    if (ch in QUOTES && language != CodeLanguage.Plain && shouldAutoClose(nextChar) && !isIdentChar(text.charOrNull(pos - 1))) {
        return RangeEdit(pos, pos, "$ch$ch", pos + 1)
    }
    // 4. Smart Enter — per-language (indent continuation, deeper after openers, comment continuation, …).
    if (ch == '\n') return newlineHandlerFor(language).onEnter(text, pos)

    return RangeEdit(pos, pos, ch.toString(), pos + 1)
}

/**
 * The edit for Backspace: deletes the selection; on a collapsed caret deletes the char before it —
 * taking an auto-inserted partner with it when the caret sits inside an empty pair. Null at offset 0.
 */
fun smartBackspace(text: CharSequence, selStart: Int, selEnd: Int): RangeEdit? {
    if (selStart != selEnd) return RangeEdit(selStart, selEnd, "", selStart)
    val pos = selStart
    if (pos <= 0) return null
    val deleted = text[pos - 1]
    val next = text.charOrNull(pos)
    val emptyPair = (deleted in OPEN_TO_CLOSE && next == OPEN_TO_CLOSE[deleted]) ||
        (deleted in QUOTES && next == deleted)
    if (emptyPair) return RangeEdit(pos - 1, pos + 1, "", pos - 1)
    // Smart-indent backspace (IntelliJ-style): on a blank, whitespace-only line, a single Backspace
    // removes the whole line (all of its whitespace, including any after the caret) together with the
    // preceding line break, hopping the caret to the end of the previous line — rather than peeling off
    // one space/tab at a time. Deleting through the trailing whitespace (e.g. the caret sitting amid the
    // tabs of an auto-inserted `{\n    \n}` block) leaves no trailing run, so the caret lands cleanly at
    // the end of the joined line.
    if ((deleted == ' ' || deleted == '\t') && pos > 1) {
        val lineStart = lineStartOf(text, pos)
        if (lineStart > 0 && isBlankLine(text, lineStart)) {
            return RangeEdit(lineStart - 1, lineEndOf(text, lineStart), "", lineStart - 1)
        }
    }
    // don't split a surrogate pair (emoji in string literals)
    val start = if (deleted.isLowSurrogate() && pos >= 2 && text[pos - 2].isHighSurrogate()) pos - 2 else pos - 1
    return RangeEdit(start, pos, "", start)
}

/** Offset of the start of the line containing [pos] (just after the previous '\n', or 0). */
private fun lineStartOf(text: CharSequence, pos: Int): Int {
    var i = pos
    while (i > 0 && text[i - 1] != '\n') i--
    return i
}

/** Offset of the end of the line beginning at [lineStart] (the next '\n', or EOF). */
private fun lineEndOf(text: CharSequence, lineStart: Int): Int {
    var i = lineStart
    while (i < text.length && text[i] != '\n') i++
    return i
}

/** Whether the line beginning at [lineStart] holds only whitespace up to its end ('\n' or EOF). */
private fun isBlankLine(text: CharSequence, lineStart: Int): Boolean {
    var i = lineStart
    while (i < text.length && text[i] != '\n') {
        if (!text[i].isWhitespace()) return false
        i++
    }
    return true
}

// ---- word boundaries (caret navigation / word delete / double-tap selection) ----

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

/** Offset of the previous word boundary left of [pos]: skip whitespace, then one homogeneous run. */
fun wordBoundaryLeft(text: CharSequence, pos: Int): Int {
    var i = pos.coerceIn(0, text.length)
    while (i > 0 && text[i - 1] != '\n' && text[i - 1].isWhitespace()) i--
    if (i > 0 && text[i - 1] == '\n') return i - 1
    if (i == 0) return 0
    val word = isWordChar(text[i - 1])
    while (i > 0 && text[i - 1] != '\n' && !text[i - 1].isWhitespace() && isWordChar(text[i - 1]) == word) i--
    return i
}

/** Offset of the next word boundary right of [pos]. */
fun wordBoundaryRight(text: CharSequence, pos: Int): Int {
    var i = pos.coerceIn(0, text.length)
    if (i < text.length && text[i] == '\n') return i + 1
    while (i < text.length && text[i] != '\n' && text[i].isWhitespace()) i++
    if (i >= text.length) return text.length
    if (text[i] == '\n') return i
    val word = isWordChar(text[i])
    while (i < text.length && text[i] != '\n' && !text[i].isWhitespace() && isWordChar(text[i]) == word) i++
    return i
}

/** The identifier-ish run containing [pos] (used by double-tap / long-press selection). */
fun wordRangeAt(text: CharSequence, pos: Int): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    var p = pos.coerceIn(0, text.length)
    if (p == text.length || (!isWordChar(text[p]) && p > 0 && isWordChar(text[p - 1]))) p--
    if (p < 0 || p >= text.length) return IntRange.EMPTY
    if (!isWordChar(text[p])) return p..p // a single symbol char
    var s = p
    while (s > 0 && isWordChar(text[s - 1])) s--
    var e = p + 1
    while (e < text.length && isWordChar(text[e])) e++
    return s until e
}
