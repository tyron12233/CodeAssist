package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage

/**
 * Per-language Enter handling: given the raw buffer and the collapsed caret [pos] where a newline is being
 * inserted, returns the [RangeEdit] to splice (its text always begins with `'\n'`) and the resulting caret
 * offset. Pure over a [CharSequence] so it reads straight off the rope and stays O(local) — the same
 * contract as [smartInsert]'s other branches.
 *
 * This is the editor's "smart Enter" seam: a language picks how a new line is indented (continuation,
 * one level deeper after an opener, comment continuation, …) so typed code lands aligned the way it would
 * in IntelliJ. Adding a language is a [newlineHandlerFor] case, not an edit to the edit engine.
 */
fun interface NewlineHandler {
    fun onEnter(text: CharSequence, pos: Int): RangeEdit
}

/** The Enter handler for [language]. Brace languages (Java/Kotlin) get the IntelliJ-style smart indent. */
fun newlineHandlerFor(language: CodeLanguage): NewlineHandler = when (language) {
    CodeLanguage.Java -> JavaNewlineHandler
    CodeLanguage.Kotlin -> KotlinNewlineHandler
    CodeLanguage.Xml -> XmlNewlineHandler
    else -> DefaultNewlineHandler
}

private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')
private val CLOSE_TO_OPEN = OPEN_TO_CLOSE.entries.associate { (k, v) -> v to k }

private fun CharSequence.charOrNull(i: Int): Char? = if (i in 0 until length) this[i] else null

/** Index of the next non-blank char at or after [pos] on the same line (stops at a newline), or -1. */
private fun nextNonBlankOnLine(text: CharSequence, pos: Int): Int {
    var i = pos
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    return if (i < text.length && text[i] != '\n') i else -1
}

/** Start offset of the line containing [pos]. */
private fun lineStartOf(text: CharSequence, pos: Int): Int = text.lastIndexOf('\n', pos - 1) + 1

/** The leading run of spaces/tabs of the line starting at [lineStart], up to (but not past) [pos]. */
private fun leadingIndent(text: CharSequence, lineStart: Int, pos: Int): String = buildString {
    var i = lineStart
    while (i < pos && (text[i] == ' ' || text[i] == '\t')) { append(text[i]); i++ }
}

/**
 * Backbone shared by Java and Kotlin: continue the current indent, go one level deeper after an opener (or
 * a brace-less control-flow header), expand an empty bracket pair onto three lines, and continue block /
 * doc comments. The few language differences are flags ([arrowIndents] for Kotlin's `->`).
 */
private open class BraceNewlineHandler(
    private val arrowIndents: Boolean,
    private val caseIndents: Boolean,
    private val stringSplits: Boolean,
) : NewlineHandler {

    override fun onEnter(text: CharSequence, pos: Int): RangeEdit {
        val lineStart = lineStartOf(text, pos)
        val indent = leadingIndent(text, lineStart, pos)

        if (insideBlockComment(text, pos)) return continueBlockComment(text, pos, lineStart, indent)

        // Split a string literal across two lines, joined with `+`: `"foo|bar"` → `"foo" +` / `"bar"`.
        if (stringSplits && insideStringLiteral(text, lineStart, pos)) {
            val insert = "\" +\n" + indent + INDENT_UNIT + "\""
            return RangeEdit(pos, pos, insert, pos + insert.length)
        }

        // A full-line `//` comment continues with a fresh `// ` on the next line.
        val lineComment = lineCommentStart(text, lineStart, pos)
        if (lineComment >= 0) return RangeEdit(pos, pos, "\n$indent// ", pos + 1 + indent.length + 3)

        // Caret inside an empty pair `{|}` / `(|)` / `[|]` → open onto three lines, the closer de-dented.
        val before = text.charOrNull(pos - 1)
        val after = text.charOrNull(pos)
        if (before != null && before in OPEN_TO_CLOSE && after == OPEN_TO_CLOSE[before]) {
            val mid = "\n" + indent + INDENT_UNIT
            return RangeEdit(pos, pos, mid + "\n" + indent, pos + mid.length)
        }

        val deeper = shouldIndentDeeper(text, lineStart, pos)
        if (deeper) {
            // The line opens a block (a trailing `{`, a Kotlin `->`, …) but its closer already sits to the
            // right of the caret — e.g. `listOf().filter { it -> |}`. Drop the closer onto its own line at
            // the base indent and land the caret on the deeper body line, swallowing the blanks between.
            val closer = nextNonBlankOnLine(text, pos)
            if (closer >= 0 && text[closer] in CLOSE_TO_OPEN) {
                val deeperIndent = indent + INDENT_UNIT
                val mid = "\n" + deeperIndent
                // Swallow blanks on both sides of the caret so the broken line keeps no trailing space.
                var start = pos
                while (start > lineStart && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
                return RangeEdit(start, closer, mid + "\n" + indent, start + mid.length)
            }
        }
        // Continuation indent: a line that ends on a dangling operator (`+`, `=`, `.`, `,`, `&&`, …) or a
        // chained `.` indents its wrapped tail one level — but only the FIRST wrap, so a run of continued
        // lines stays at one level instead of marching right.
        val extra = when {
            deeper -> INDENT_UNIT
            isContinuation(text, lineStart, pos) -> INDENT_UNIT
            else -> ""
        }
        return RangeEdit(pos, pos, "\n" + indent + extra, pos + 1 + indent.length + extra.length)
    }

    /** One level deeper when the line's code ends with an opener, a Kotlin `->`, or a brace-less header. */
    private fun shouldIndentDeeper(text: CharSequence, lineStart: Int, pos: Int): Boolean {
        val codeEnd = codeEnd(text, lineStart, pos)
        val last = lastNonWs(text, lineStart, codeEnd) ?: return false
        if (last in OPEN_TO_CLOSE) return true
        val code = text.subSequence(lineStart, codeEnd).toString().trim()
        if (arrowIndents && code.endsWith("->")) return true
        if (caseIndents && isCaseLabel(code)) return true
        return bracelessControlFlow(code)
    }
}

private val JavaNewlineHandler = BraceNewlineHandler(arrowIndents = false, caseIndents = true, stringSplits = true)
private val KotlinNewlineHandler = BraceNewlineHandler(arrowIndents = true, caseIndents = false, stringSplits = false)

/**
 * The fallback for plain/markup files: continue the current indent, step one deeper after an opening
 * bracket, and expand an empty pair. No comment or control-flow awareness.
 */
private object DefaultNewlineHandler : NewlineHandler {
    override fun onEnter(text: CharSequence, pos: Int): RangeEdit {
        val lineStart = lineStartOf(text, pos)
        val indent = leadingIndent(text, lineStart, pos)
        val before = text.charOrNull(pos - 1)
        val after = text.charOrNull(pos)
        if (before != null && before in OPEN_TO_CLOSE && after == OPEN_TO_CLOSE[before]) {
            val mid = "\n" + indent + INDENT_UNIT
            return RangeEdit(pos, pos, mid + "\n" + indent, pos + mid.length)
        }
        val newIndent = if (before != null && before in OPEN_TO_CLOSE) indent + INDENT_UNIT else indent
        return RangeEdit(pos, pos, "\n" + newIndent, pos + 1 + newIndent.length)
    }
}

/**
 * The XML/layout Enter handler (Android manifests, layouts, resources). Expands a tag pair onto three
 * lines, indents one level deeper after an opening tag, and — inside a start tag's attribute list — aligns
 * the next line under the first attribute. Falls back to plain indent continuation.
 */
private object XmlNewlineHandler : NewlineHandler {
    override fun onEnter(text: CharSequence, pos: Int): RangeEdit {
        val lineStart = lineStartOf(text, pos)
        val indent = leadingIndent(text, lineStart, pos)

        // Inside an unclosed start tag → we're in its attribute list; align the wrapped attribute.
        val tagOpen = enclosingStartTag(text, pos)
        if (tagOpen >= 0) {
            // Tag opener on an earlier line → this line is already a wrapped attribute; just keep its indent.
            val pad = if (lineStartOf(text, tagOpen) == lineStart) " ".repeat(attributeAlignColumn(text, tagOpen)) else indent
            return RangeEdit(pos, pos, "\n" + pad, pos + 1 + pad.length)
        }

        // Tag-pair expansion: `<Foo …>|</Foo>` → body on a deeper line, the close tag de-dented.
        val gt = prevNonBlankOnLine(text, pos)
        val closeLt = nextNonBlankOnLine(text, pos)
        if (gt >= 0 && text[gt] == '>' && text.charOrNull(gt - 1) != '/' &&
            closeLt >= 0 && text[closeLt] == '<' && text.charOrNull(closeLt + 1) == '/'
        ) {
            val mid = "\n" + indent + INDENT_UNIT
            var start = pos
            while (start > lineStart && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
            return RangeEdit(start, closeLt, mid + "\n" + indent, start + mid.length)
        }

        // After an opening start tag (close elsewhere / none) → one level deeper.
        if (gt >= 0 && text[gt] == '>' && isOpenStartTagEnd(text, gt)) {
            val deeperIndent = indent + INDENT_UNIT
            return RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length)
        }

        return RangeEdit(pos, pos, "\n" + indent, pos + 1 + indent.length)
    }
}

// ---- helpers shared by the brace handler ----

/** Index of the previous non-blank char before [pos] on the same line (stops at a newline), or -1. */
private fun prevNonBlankOnLine(text: CharSequence, pos: Int): Int {
    var i = pos - 1
    while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
    return if (i >= 0 && text[i] != '\n') i else -1
}

/**
 * Start offset of a full-line `//` comment the caret sits in (the `//` is the line's first non-blank, and
 * the caret is at/after it), or -1. Trailing comments after code don't continue — matching IntelliJ.
 */
private fun lineCommentStart(text: CharSequence, lineStart: Int, pos: Int): Int {
    val first = firstNonWsIndex(text, lineStart, pos)
    return if (text.charOrNull(first) == '/' && text.charOrNull(first + 1) == '/' && pos >= first + 2) first else -1
}

/** True when the line being broken ends on a dangling operator and isn't itself already a continuation line. */
private fun isContinuation(text: CharSequence, lineStart: Int, pos: Int): Boolean {
    val code = text.subSequence(lineStart, codeEnd(text, lineStart, pos)).toString().trim()
    if (!endsWithContinuationOp(code)) return false
    if (lineStart == 0) return true
    // The previous line already ending on an operator means we're mid-continuation → don't add another level.
    val prevStart = lineStartOf(text, lineStart - 1)
    val prevCode = text.subSequence(prevStart, codeEnd(text, prevStart, lineStart - 1)).toString().trim()
    return !endsWithContinuationOp(prevCode)
}

/** High-signal "this statement continues on the next line" operators (excludes `++`/`->`, handled elsewhere). */
private fun endsWithContinuationOp(code: String): Boolean {
    if (code.isEmpty()) return false
    if (code.endsWith("++") || code.endsWith("->")) return false
    return when (code.last()) {
        '+', '=', '.', ',', '?' -> true
        else -> code.endsWith("&&") || code.endsWith("||")
    }
}

/** Index of the `<` of a start tag the caret is inside (no `>` between it and [pos]), or -1. */
private fun enclosingStartTag(text: CharSequence, pos: Int): Int {
    var i = pos - 1
    val limit = maxOf(0, pos - COMMENT_SCAN_LIMIT)
    while (i >= limit) {
        when (text[i]) {
            '>' -> return -1
            '<' -> {
                val nx = text.charOrNull(i + 1)
                return if (nx != null && (nx.isLetter() || nx == '_')) i else -1
            }
        }
        i--
    }
    return -1
}

/** Spaces to align a wrapped attribute under the first attribute of the start tag opening at [tagOpen]. */
private fun attributeAlignColumn(text: CharSequence, tagOpen: Int): Int {
    val tagLineStart = lineStartOf(text, tagOpen)
    var i = tagOpen + 1 // past '<'
    while (i < text.length && isTagNameChar(text[i])) i++
    var j = i
    while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
    // First attribute on the tag line → align under it; otherwise fall back to one level deeper than the tag.
    val attrPresent = j < text.length && text[j] != '\n' && text[j] != '>' && text[j] != '/'
    return if (attrPresent) j - tagLineStart else (tagOpen - tagLineStart) + INDENT_UNIT.length
}

private fun isTagNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'

/** True when the `>` at [gt] closes an opening start tag (not `</…>`, `<?…?>`, `<!…>`, or a self-closing `/>`). */
private fun isOpenStartTagEnd(text: CharSequence, gt: Int): Boolean {
    if (text.charOrNull(gt - 1) == '/') return false // self-closing
    var i = gt - 1
    val limit = maxOf(0, gt - COMMENT_SCAN_LIMIT)
    while (i >= limit && text[i] != '<' && text[i] != '>') i--
    if (i < 0 || text[i] != '<') return false
    val nx = text.charOrNull(i + 1) ?: return false
    return nx.isLetter() || nx == '_'
}


/** True when [pos] sits inside an unterminated block comment — its opener is the nearest comment delimiter before it. */
private fun insideBlockComment(text: CharSequence, pos: Int): Boolean {
    val open = lastIndexBounded(text, "/*", pos - 1)
    if (open < 0) return false
    val close = lastIndexBounded(text, "*/", pos - 1)
    return open > close
}

/** Continue (or auto-close) a block / doc comment: line up a leading `*`, and finish a freshly-opened comment. */
private fun continueBlockComment(text: CharSequence, pos: Int, lineStart: Int, indent: String): RangeEdit {
    val firstNonWs = firstNonWsIndex(text, lineStart, pos)
    val firstCh = text.charOrNull(firstNonWs)
    // Opening line (`/* …` or `/** …`) that isn't closed yet → drop in a `*` line and the closing `*/`.
    if (firstCh == '/' && indexBounded(text, "*/", pos) < 0) {
        val mid = "\n$indent * "
        return RangeEdit(pos, pos, mid + "\n$indent */", pos + mid.length)
    }
    if (firstCh != '*') {
        // Opener line without a `*` (rare `/* text`) → align the new star one past the `/`.
        val prefix = "$indent * "
        return RangeEdit(pos, pos, "\n$prefix", pos + 1 + prefix.length)
    }
    // `*` lines: align the continuation under the line's content — and under the *description* of a
    // `@param name`/`@throws name`/`@return`/… doc tag, so wrapped tag text lines up (IntelliJ/KDoc style).
    val col = docContinuationColumn(text, lineStart, firstNonWs, pos)
    val prefix = indent + "*" + " ".repeat((col - indent.length - 1).coerceAtLeast(1))
    return RangeEdit(pos, pos, "\n$prefix", pos + 1 + prefix.length)
}

/** Column the next doc-comment line should start at: under the line's content, or a tag's description. */
private fun docContinuationColumn(text: CharSequence, lineStart: Int, starIndex: Int, pos: Int): Int {
    var i = starIndex + 1
    while (i < pos && (text[i] == ' ' || text[i] == '\t')) i++
    val contentCol = i - lineStart
    if (text.charOrNull(i) != '@') return contentCol
    var j = i + 1
    while (j < pos && text[j].isLetter()) j++
    val tag = text.subSequence(i, j).toString()
    while (j < pos && (text[j] == ' ' || text[j] == '\t')) j++
    // `@param`/`@throws`/`@exception` carry a name before the description; skip it too.
    if (tag == "@param" || tag == "@throws" || tag == "@exception") {
        while (j < pos && !text[j].isWhitespace()) j++
        while (j < pos && (text[j] == ' ' || text[j] == '\t')) j++
    }
    return if (j < pos) j - lineStart else contentCol
}

/** A Java `case …:` / `default:` label whose statement body indents one level deeper. */
private fun isCaseLabel(code: String): Boolean =
    code == "default:" || (code.startsWith("case ") && code.endsWith(":"))

/**
 * True when [pos] sits inside a `"…"` string literal on its line that also closes on the line — i.e. a
 * split point. Tracks escapes, char literals, and bails at a `//` line comment.
 */
private fun insideStringLiteral(text: CharSequence, lineStart: Int, pos: Int): Boolean {
    var i = lineStart
    var inStr = false
    var inChar = false
    while (i < pos) {
        val c = text[i]
        when {
            inStr -> if (c == '\\') i++ else if (c == '"') inStr = false
            inChar -> if (c == '\\') i++ else if (c == '\'') inChar = false
            c == '"' -> inStr = true
            c == '\'' -> inChar = true
            c == '/' && text.charOrNull(i + 1) == '/' -> return false
        }
        i++
    }
    if (!inStr) return false
    // Require a closing quote later on the same line, so we never split an unterminated string at EOL.
    var j = pos
    while (j < text.length && text[j] != '\n') {
        if (text[j] == '\\') j++ else if (text[j] == '"') return true
        j++
    }
    return false
}

/** A brace-less `if`/`for`/`while`/`else`/`do` header (no trailing `{`/`;`) — its single statement indents one deeper. */
private fun bracelessControlFlow(code: String): Boolean {
    if (code == "do" || code == "else") return true
    if (!code.endsWith(")")) return false
    val head = code.removePrefix("}").trim() // tolerate `} else if (…)`
    val tokens = head.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
    val first = tokens.firstOrNull() ?: return false
    return first in setOf("if", "for", "while") || (first == "else" && tokens.getOrNull(1) == "if")
}

/** Offset where the line's code ends — i.e. before a trailing `// …` line comment (or [pos] when there is none). */
private fun codeEnd(text: CharSequence, lineStart: Int, pos: Int): Int {
    var i = lineStart
    while (i < pos - 1) {
        if (text[i] == '/' && text[i + 1] == '/') return i
        i++
    }
    return pos
}

/** The last non-whitespace char in `[lineStart, end)`, or null when the line is blank. */
private fun lastNonWs(text: CharSequence, lineStart: Int, end: Int): Char? {
    var i = end - 1
    while (i >= lineStart) {
        val c = text[i]
        if (c != ' ' && c != '\t') return c
        i--
    }
    return null
}

/** Index of the first non-whitespace char in `[lineStart, pos)`, or [pos] when the line is blank. */
private fun firstNonWsIndex(text: CharSequence, lineStart: Int, pos: Int): Int {
    var i = lineStart
    while (i < pos && (text[i] == ' ' || text[i] == '\t')) i++
    return i
}

private const val COMMENT_SCAN_LIMIT = 100_000

/** [needle]'s last start index at or before [from], bounded so a delimiter-free prefix can't cost O(N). */
private fun lastIndexBounded(text: CharSequence, needle: String, from: Int): Int {
    val limit = maxOf(0, from - COMMENT_SCAN_LIMIT)
    var i = minOf(from, text.length - needle.length)
    while (i >= limit) {
        var k = 0
        while (k < needle.length && text[i + k] == needle[k]) k++
        if (k == needle.length) return i
        i--
    }
    return -1
}

/** [needle]'s first start index at or after [from], bounded for the same reason as [lastIndexBounded]. */
private fun indexBounded(text: CharSequence, needle: String, from: Int): Int {
    val limit = minOf(text.length - needle.length, from + COMMENT_SCAN_LIMIT)
    var i = maxOf(0, from)
    while (i <= limit) {
        var k = 0
        while (k < needle.length && text[i + k] == needle[k]) k++
        if (k == needle.length) return i
        i++
    }
    return -1
}
