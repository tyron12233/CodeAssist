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

/**
 * Smart Enter / "Complete Statement" (Shift+Enter): the edit that finishes the line containing [caret] and
 * opens an indented new line. A control-flow / loop header missing its body gets a `{ … }` block with the
 * caret inside it; a Java statement missing its `;` gets one appended; otherwise it falls back to dropping a
 * smartly-indented line at the line's end. Pure over the buffer, like the [NewlineHandler]s.
 */
fun smartEnter(text: CharSequence, caret: Int, language: CodeLanguage): RangeEdit {
    val c = caret.coerceIn(0, text.length)
    val lineStart = lineStartOf(text, c)
    val lineEnd = lineEndFrom(text, c)
    val lineText = text.subSequence(lineStart, lineEnd).toString()
    val indent = lineText.takeWhile { it == ' ' || it == '\t' }
    val unit = detectIndentUnit(text)
    val code = lineText.trim()

    if (controlFlowNeedsBraces(code)) {
        val open = if (lineText.isEmpty() || lineText.endsWith(" ")) "{" else " {"
        val body = "\n" + indent + unit
        val insert = "$open$body\n$indent}"
        return RangeEdit(lineEnd, lineEnd, insert, lineEnd + open.length + body.length)
    }
    val lastCh = code.lastOrNull()
    val endsOpen = lastCh != null && lastCh in "+-*/%=&|<>.,(?:"
    val needsSemi = language == CodeLanguage.Java && code.isNotEmpty() && !endsOpen &&
        !code.endsWith(";") && !code.endsWith("{") && !code.endsWith("}") &&
        !code.startsWith("//") && !code.startsWith("*") && !code.startsWith("@")
    val insert = (if (needsSemi) ";" else "") + "\n" + indent
    return RangeEdit(lineEnd, lineEnd, insert, lineEnd + insert.length)
}

/**
 * Whether [code] (a trimmed line) is a control-flow / loop header whose body is missing — so Smart Enter
 * completes it with a `{ }` block. `else` / `try` / `do` / `finally` qualify on their own; the rest (`if`,
 * `for`, `while`, `switch`, `when`, `catch`) qualify when they end on a closed `)`.
 */
private fun controlFlowNeedsBraces(code: String): Boolean {
    if (code.endsWith("{") || code.endsWith("}")) return false
    val c = code.removePrefix("}").trim() // tolerate `} else …`
    if (c == "else" || c == "try" || c == "do" || c == "finally") return true
    if (!c.endsWith(")")) return false
    val first = c.takeWhile { it.isLetter() }
    return first in setOf("if", "for", "while", "switch", "when", "catch", "else")
}

private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')
private val CLOSE_TO_OPEN = OPEN_TO_CLOSE.entries.associate { (k, v) -> v to k }

private fun CharSequence.charOrNull(i: Int): Char? = if (i in indices) this[i] else null

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
    private val rawStrings: Boolean,
) : NewlineHandler {

    override fun onEnter(text: CharSequence, pos: Int): RangeEdit {
        val lineStart = lineStartOf(text, pos)
        val indent = leadingIndent(text, lineStart, pos)
        val unit = detectIndentUnit(text) // respect the document's own indent style (tabs / 2-space / 4-space)

        if (insideBlockComment(text, pos)) return continueBlockComment(text, pos, lineStart, indent)

        // Inside a raw string (Kotlin `"""…"""`, Java text block) the content is literal — keep the current
        // line's indent so `.trimIndent()` margins line up, and apply none of the code rules below.
        if (rawStrings && insideRawString(text, pos)) {
            return RangeEdit(pos, pos, "\n" + indent, pos + 1 + indent.length)
        }

        // Split a string literal across two lines, joined with `+`: `"foo|bar"` → `"foo" +` / `"bar"`.
        if (stringSplits && insideStringLiteral(text, lineStart, pos)) {
            val insert = "\" +\n$indent$unit\""
            return RangeEdit(pos, pos, insert, pos + insert.length)
        }

        // A full-line `//` comment continues with a fresh `// ` on the next line — carrying a list bullet
        // (`// - `, `// 1. `, …) when the comment is a bulleted list.
        val lineComment = lineCommentStart(text, lineStart, pos)
        if (lineComment >= 0) {
            var c = lineComment + 2
            while (c < pos && (text[c] == ' ' || text[c] == '\t')) c++
            val prefix = "// " + (listMarker(text, c, pos)?.next ?: "")
            return RangeEdit(pos, pos, "\n$indent$prefix", pos + 1 + indent.length + prefix.length)
        }

        // Caret inside an empty pair `{|}` / `(|)` / `[|]` → open onto three lines, the closer de-dented.
        val before = text.charOrNull(pos - 1)
        val after = text.charOrNull(pos)
        if (before != null && before in OPEN_TO_CLOSE && after == OPEN_TO_CLOSE[before]) {
            val mid = "\n" + indent + unit
            return RangeEdit(pos, pos, mid + "\n" + indent, pos + mid.length)
        }

        val deeper = shouldIndentDeeper(text, lineStart, pos)
        if (deeper) {
            // The line opens a block (a trailing `{`, a Kotlin `->`, …) but its closer already sits to the
            // right of the caret — e.g. `listOf().filter { it -> |}`. Drop the closer onto its own line at
            // the base indent and land the caret on the deeper body line, swallowing the blanks between.
            val closer = nextNonBlankOnLine(text, pos)
            if (closer >= 0 && text[closer] in CLOSE_TO_OPEN) {
                val deeperIndent = indent + unit
                val mid = "\n" + deeperIndent
                // Swallow blanks on both sides of the caret so the broken line keeps no trailing space.
                var start = pos
                while (start > lineStart && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
                return RangeEdit(start, closer, mid + "\n" + indent, start + mid.length)
            }
        } else {
            // Breaking right before a closer that pairs with an opener earlier in the buffer (the multi-line
            // list/call form): drop the closer onto its own line at the base indent and land the caret one
            // level deeper. `Column(\n    a = 1,|)` → the `)` falls under `Column`, the caret at the item indent.
            val closer = nextNonBlankOnLine(text, pos)
            if (closer >= 0 && text[closer] in CLOSE_TO_OPEN) {
                // Opener on THIS line → the line is the base; opener on an earlier line → this line is already
                // a body/item line, so the closer dedents one level below it.
                val base = if (openBracketBalance(text, lineStart, pos) > 0) indent else dropIndentLevel(indent, unit)
                val mid = "\n" + base + unit
                var start = pos
                while (start > lineStart && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
                return RangeEdit(start, closer, mid + "\n" + base, start + mid.length)
            }
        }

        val code = text.subSequence(lineStart, codeEnd(text, lineStart, pos)).toString().trim()

        // A line of only annotations / modifiers (`@Composable`, `private`, `@Preview(...)`) is not a statement
        // that wraps — the declaration it precedes stays at the same indent.
        if (!deeper && isAnnotationOrModifierOnly(code)) {
            return RangeEdit(pos, pos, "\n" + indent, pos + 1 + indent.length)
        }

        // Wrap BEFORE a leading binary operator: `val x = a |+ b`, breaking before `.bar()` in a call chain →
        // the wrapped tail drops one level deeper (the mirror of the trailing-operator rule). Only the first
        // wrap indents — a line that already starts with an operator is mid-chain and stays put. Swallows the
        // blanks before the caret so the broken line keeps no trailing space.
        if (!deeper && startsWithLeadingOp(text, pos) && !startsWithLeadingOp(text, lineStart)) {
            var start = pos
            while (start > lineStart && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
            val nl = "\n" + indent + unit
            return RangeEdit(start, pos, nl, start + nl.length)
        }

        // Continuation indent: a line that ends on a dangling operator (`+`, `=`, `.`, `&&`, …) or a chained
        // `.` indents its wrapped tail one level — but only the FIRST wrap, so a run of continued lines stays
        // at one level instead of marching right. A trailing `,` is special: inside an already-open bracket
        // list its lines are siblings (same indent), so it wraps deeper ONLY when the line itself leaves an
        // opener unclosed — `listOf(1, 2,` → next line deeper, but `modifier = foo(),` (balanced) inside an
        // earlier `Column(` → next line stays aligned with it.
        val extra = when {
            deeper -> unit
            code.endsWith(",") -> if (openBracketBalance(text, lineStart, pos) > 0) unit else ""
            isContinuation(text, lineStart, pos) -> unit
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

private val JavaNewlineHandler = BraceNewlineHandler(arrowIndents = false, caseIndents = true, stringSplits = true, rawStrings = true)
private val KotlinNewlineHandler = BraceNewlineHandler(arrowIndents = true, caseIndents = false, stringSplits = false, rawStrings = true)

/**
 * The fallback for plain/markup files: continue the current indent, step one deeper after an opening
 * bracket, and expand an empty pair. No comment or control-flow awareness.
 */
private object DefaultNewlineHandler : NewlineHandler {
    override fun onEnter(text: CharSequence, pos: Int): RangeEdit {
        val lineStart = lineStartOf(text, pos)
        val indent = leadingIndent(text, lineStart, pos)
        val unit = detectIndentUnit(text)

        // Markdown / plain-text list continuation: `- item` / `* item` / `1. item` → the next line repeats the
        // bullet (ordered markers auto-increment). Pressing Enter on an EMPTY item (only the marker) ends the
        // list: the marker is cleared and a plain blank line is dropped.
        val marker = listMarker(text, lineStart + indent.length, pos)
        if (marker != null) {
            val lineEnd = lineEndFrom(text, pos)
            if (isBlankRange(text, marker.end, lineEnd)) {
                return RangeEdit(lineStart + indent.length, pos, "\n" + indent, lineStart + indent.length + 1 + indent.length)
            }
            val cont = "\n" + indent + marker.next
            return RangeEdit(pos, pos, cont, pos + cont.length)
        }

        val before = text.charOrNull(pos - 1)
        val after = text.charOrNull(pos)
        if (before != null && before in OPEN_TO_CLOSE && after == OPEN_TO_CLOSE[before]) {
            val mid = "\n" + indent + unit
            return RangeEdit(pos, pos, mid + "\n" + indent, pos + mid.length)
        }
        val newIndent = if (before != null && before in OPEN_TO_CLOSE) indent + unit else indent
        return RangeEdit(pos, pos, "\n" + newIndent, pos + 1 + newIndent.length)
    }
}

/**
 * The XML/layout Enter handler (Android manifests, layouts, resources). The new line's indent is
 * **structural** - derived from the element nesting at the caret ([xmlIndentAt]) rather than from the
 * previous line's whitespace - so it is correct regardless of how the surrounding lines happen to be
 * indented: a sibling after a close tag (`</TextView>|` → the TextView's level), a child after an open tag
 * (`<LinearLayout>|` → one deeper), and a sibling after a self-closing tag (`<CardView/>|` → the same level).
 * Two special cases ride on top: a tag pair is expanded onto three lines (`<Foo>|</Foo>` → body deeper, close
 * de-dented), and inside an unclosed start tag the wrapped attribute aligns under the first attribute.
 */
private object XmlNewlineHandler : NewlineHandler {
    override fun onEnter(text: CharSequence, pos: Int): RangeEdit {
        val lineStart = lineStartOf(text, pos)
        val indent = leadingIndent(text, lineStart, pos)
        val unit = detectIndentUnit(text)

        // Inside an unclosed start tag → we're in its attribute list; align the wrapped attribute.
        val tagOpen = enclosingStartTag(text, pos)
        if (tagOpen >= 0) {
            // Tag opener on an earlier line → this line is already a wrapped attribute; just keep its indent.
            val pad = if (lineStartOf(text, tagOpen) == lineStart) " ".repeat(attributeAlignColumn(text, tagOpen, unit.length)) else indent
            return RangeEdit(pos, pos, "\n" + pad, pos + 1 + pad.length)
        }

        // The indent a new line at the caret should get, from the open-element nesting (child of the innermost
        // open element; "" at the root). The body of a tag pair sits here; its close tag one level shallower.
        val base = xmlIndentAt(text, pos, unit)

        // Tag-pair expansion: `<Foo …>|</Foo>` → body on a deeper line, the close tag de-dented under <Foo>.
        val gt = prevNonBlankOnLine(text, pos)
        val closeLt = nextNonBlankOnLine(text, pos)
        if (gt >= 0 && text[gt] == '>' && text.charOrNull(gt - 1) != '/' &&
            closeLt >= 0 && text[closeLt] == '<' && text.charOrNull(closeLt + 1) == '/'
        ) {
            val mid = "\n" + base
            var start = pos
            while (start > lineStart && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
            return RangeEdit(start, closeLt, mid + "\n" + dropIndentLevel(base, unit), start + mid.length)
        }

        return RangeEdit(pos, pos, "\n" + base, pos + 1 + base.length)
    }
}

private const val XML_INDENT_SCAN_LIMIT = 200_000

/**
 * The structural indent for a new line at [pos]: one [unit] deeper than the innermost still-open element's
 * opening line, or "" when no element is open (the root level). A forward scan maintains a stack of the
 * opening-line indents of currently-open elements (open tags push, close tags pop, self-closing tags don't),
 * skipping comments / CDATA / processing instructions / doctype, so the result reflects the document's actual
 * nesting and base indentation rather than the previous line's whitespace. Bounded: past
 * [XML_INDENT_SCAN_LIMIT] it falls back to the current line's leading indent (huge files only).
 */
private fun xmlIndentAt(text: CharSequence, pos: Int, unit: String): String {
    if (pos > XML_INDENT_SCAN_LIMIT) return leadingIndent(text, lineStartOf(text, pos), pos)
    val stack = ArrayList<String>()
    var i = 0
    while (i < pos) {
        if (text[i] != '<') { i++; continue }
        i = when {
            text.startsWith("<!--", i) -> indexAfter(text, "-->", i + 4, pos)
            text.startsWith("<![CDATA[", i) -> indexAfter(text, "]]>", i + 9, pos)
            text.charOrNull(i + 1) == '?' -> indexAfter(text, "?>", i + 2, pos)
            text.charOrNull(i + 1) == '!' -> indexAfter(text, ">", i + 2, pos)
            text.charOrNull(i + 1) == '/' -> { // close tag → pop the innermost open element
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                indexAfter(text, ">", i + 2, pos)
            }
            text.charOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> { // open or self-closing tag
                val gt = findTagEnd(text, i + 1, pos)
                if (gt < 0) pos // tag unterminated before the caret (we're inside it) - stop
                else {
                    if (text.charOrNull(gt - 1) != '/') stack.add(leadingIndent(text, lineStartOf(text, i), i))
                    gt + 1
                }
            }
            else -> i + 1
        }
    }
    val parent = stack.lastOrNull() ?: return ""
    return parent + unit
}

/** Index of the `>` ending a tag whose name starts at [from], honoring quoted attribute values, or -1. */
private fun findTagEnd(text: CharSequence, from: Int, limit: Int): Int {
    var i = from
    var quote = ' '
    var inQuote = false
    while (i < limit) {
        val c = text[i]
        when {
            inQuote -> if (c == quote) inQuote = false
            c == '"' || c == '\'' -> { inQuote = true; quote = c }
            c == '>' -> return i
        }
        i++
    }
    return -1
}

/** Index just past [needle]'s first occurrence in `[from, limit)`, or [limit] when it isn't found in range. */
private fun indexAfter(text: CharSequence, needle: String, from: Int, limit: Int): Int {
    val idx = indexBounded(text, needle, from)
    return if (idx in 0 until limit) idx + needle.length else limit
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
private fun attributeAlignColumn(text: CharSequence, tagOpen: Int, unitLen: Int): Int {
    val tagLineStart = lineStartOf(text, tagOpen)
    var i = tagOpen + 1 // past '<'
    while (i < text.length && isTagNameChar(text[i])) i++
    var j = i
    while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
    // First attribute on the tag line → align under it; otherwise fall back to one level deeper than the tag.
    val attrPresent = j < text.length && text[j] != '\n' && text[j] != '>' && text[j] != '/'
    return if (attrPresent) j - tagLineStart else (tagOpen - tagLineStart) + unitLen
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


/**
 * True when [pos] sits inside an unterminated block comment. A forward scan tracks lexer state so a
 * block-comment open/close delimiter inside a string, char, or raw-string literal — or inside a `//` line
 * comment — is NOT mistaken for a real delimiter. (A bare last-open-vs-last-close index comparison was fooled
 * by an opener sitting inside a string literal, leaving the editor convinced every line was a block comment
 * and prepending a stray star on each Enter.) Bounded like the other comment scans: past the limit the scan
 * starts mid-buffer and is best-effort.
 */
private fun insideBlockComment(text: CharSequence, pos: Int): Boolean {
    var i = if (pos > COMMENT_SCAN_LIMIT) pos - COMMENT_SCAN_LIMIT else 0
    var inBlock = false
    var inLine = false
    var inStr = false
    var inChar = false
    var inRaw = false
    while (i < pos) {
        val c = text[i]
        when {
            inLine -> if (c == '\n') inLine = false
            inBlock -> if (c == '*' && text.charOrNull(i + 1) == '/') { inBlock = false; i++ }
            // A raw string (`"""…"""`) is literal — only a closing triple-quote ends it.
            inRaw -> if (c == '"' && text.charOrNull(i + 1) == '"' && text.charOrNull(i + 2) == '"') { inRaw = false; i += 2 }
            // A normal string/char literal ends at its closing quote (escapes skip the next char) or at EOL —
            // an unterminated one can't swallow the rest of the buffer.
            inStr -> if (c == '\\') i++ else if (c == '"' || c == '\n') inStr = false
            inChar -> if (c == '\\') i++ else if (c == '\'' || c == '\n') inChar = false
            c == '/' && text.charOrNull(i + 1) == '*' -> { inBlock = true; i++ }
            c == '/' && text.charOrNull(i + 1) == '/' -> { inLine = true; i++ }
            c == '"' && text.charOrNull(i + 1) == '"' && text.charOrNull(i + 2) == '"' -> { inRaw = true; i += 2 }
            c == '"' -> inStr = true
            c == '\'' -> inChar = true
        }
        i++
    }
    return inBlock
}

/** Continue (or auto-close) a block / doc comment: line up a leading `*`, and finish a freshly-opened comment. */
private fun continueBlockComment(text: CharSequence, pos: Int, lineStart: Int, indent: String): RangeEdit {
    val firstNonWs = firstNonWsIndex(text, lineStart, pos)
    val firstCh = text.charOrNull(firstNonWs)
    // Opening line (`/* …` or `/** …`) that isn't closed yet → drop in a `*` line and the closing `*/`.
    if (firstCh == '/' && indexBounded(text, "*/", pos) < 0) {
        val mid = "\n$indent * "
        return RangeEdit(pos, pos, "$mid\n$indent */", pos + mid.length)
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

/**
 * Net bracket balance of `[lineStart, pos)` — opens minus closes — ignoring brackets inside string/char
 * literals and stopping at a `//` line comment. A positive result means the line leaves an opener unclosed,
 * so its wrapped tail belongs one level deeper; zero (balanced) means a trailing `,` is just separating
 * siblings already at the right indent.
 */
private fun openBracketBalance(text: CharSequence, lineStart: Int, pos: Int): Int {
    var depth = 0
    var inStr = false
    var inChar = false
    var i = lineStart
    while (i < pos) {
        val c = text[i]
        when {
            inStr -> if (c == '\\') i++ else if (c == '"') inStr = false
            inChar -> if (c == '\\') i++ else if (c == '\'') inChar = false
            c == '"' -> inStr = true
            c == '\'' -> inChar = true
            c == '/' && text.charOrNull(i + 1) == '/' -> return depth
            c == '(' || c == '[' || c == '{' -> depth++
            c == ')' || c == ']' || c == '}' -> depth--
        }
        i++
    }
    return depth
}

/**
 * Whether the first non-blank char at/after [from] begins a binary operator that, at the START of a wrapped
 * line, marks it a continuation (`.`, `+`, `*`, `/`, `%`, `&&`, `||`, elvis `?:`, safe-call `?.`, binary `-`).
 * Excludes `--`/`->` and the comparison/assignment operators (`<`, `>`, `=`) whose start is ambiguous.
 */
private fun startsWithLeadingOp(text: CharSequence, from: Int): Boolean {
    var i = from
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    if (i >= text.length || text[i] == '\n') return false
    val c = text[i]
    val c2 = text.charOrNull(i + 1)
    return when (c) {
        '.', '+', '*', '/', '%' -> true
        '?' -> c2 == ':' || c2 == '.'
        '&' -> c2 == '&'
        '|' -> c2 == '|'
        '-' -> c2 != '-' && c2 != '>'
        else -> false
    }
}

/** A list marker found at the line's content start: where it ends (past the trailing space) + the marker to
 *  put on the continuation line (ordered numbers auto-incremented). */
private class ListMarker(val end: Int, val next: String)

/** Detects a `- ` / `* ` / `+ ` bullet or an ordered `N. ` / `N) ` marker beginning at [from] (before [pos]). */
private fun listMarker(text: CharSequence, from: Int, pos: Int): ListMarker? {
    if (from !in 0..<pos) return null
    val c = text[from]
    if ((c == '-' || c == '*' || c == '+') && from + 1 < text.length && text[from + 1] == ' ') {
        return ListMarker(from + 2, "$c ")
    }
    var j = from
    while (j < text.length && text[j].isDigit()) j++
    if (j > from && j < text.length && (text[j] == '.' || text[j] == ')') && j + 1 < text.length && text[j + 1] == ' ') {
        val n = text.subSequence(from, j).toString().toIntOrNull() ?: return null
        return ListMarker(j + 2, "${n + 1}${text[j]} ")
    }
    return null
}

/** True when `[start, end)` is empty or only whitespace. */
private fun isBlankRange(text: CharSequence, start: Int, end: Int): Boolean {
    var i = start
    while (i < end) { if (!text[i].isWhitespace()) return false; i++ }
    return true
}

/** Offset of the end of the line containing [pos] (the next '\n', or EOF). */
private fun lineEndFrom(text: CharSequence, pos: Int): Int {
    var i = pos
    while (i < text.length && text[i] != '\n') i++
    return i
}

private const val INDENT_SCAN_LIMIT = 10_000

/**
 * The document's indentation unit, inferred from its existing lines: a tab when leading tabs dominate the
 * indented code lines, otherwise the most common positive indent *increment* between consecutive code lines
 * (the increment histogram — robust against stray alignment lines that a min/GCD would be fooled by),
 * clamped to {2, 4, 8} spaces with a four-space fallback. Bounded scan so it stays cheap on every Enter.
 */
internal fun detectIndentUnit(text: CharSequence): String {
    val limit = minOf(text.length, INDENT_SCAN_LIMIT)
    var tabLines = 0
    var spaceLines = 0
    val diffCounts = HashMap<Int, Int>() // positive space-indent increments → frequency
    var prevWidth = 0
    var i = 0
    while (i < limit) {
        var j = i
        var sawTab = false
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) { if (text[j] == '\t') sawTab = true; j++ }
        val lineChar = if (j < text.length) text[j] else '\n'
        if (lineChar != '\n' && lineChar != '*') { // a real code line (skip blanks + javadoc `*` continuations)
            if (sawTab) {
                tabLines++
            } else {
                spaceLines++
                val d = (j - i) - prevWidth
                if (d in 1..8) diffCounts[d] = (diffCounts[d] ?: 0) + 1
                prevWidth = j - i
            }
        }
        i = j
        while (i < text.length && text[i] != '\n') i++
        i++
    }
    // Require corroborating evidence (≥2 lines) before overriding the four-space default, so a single
    // artificially-indented line can't mis-set the unit for the whole document.
    if (tabLines > spaceLines && tabLines >= 2) return "\t"
    val best = diffCounts.maxByOrNull { it.value }
    val unit = if (best != null && best.value >= 2) best.key else 4
    return " ".repeat(if (unit == 2 || unit == 4 || unit == 8) unit else 4)
}

/**
 * Whether the non-blank line `[lineStart, lineEnd)` opens a block that a following line indents one level
 * deeper into. Shared by smart Enter (via [BraceNewlineHandler]) and smart-backspace's blank-line collapse
 * ([smartBackspace]). Brace languages: a trailing opener `{`/`(`/`[` (ignoring a trailing `//` comment), a
 * Kotlin `->`, or a brace-less control-flow header. XML: the line ends with the `>` of an open
 * (non-self-closing) start tag.
 */
internal fun lineOpensBlock(text: CharSequence, lineStart: Int, lineEnd: Int, language: CodeLanguage): Boolean {
    if (language == CodeLanguage.Xml) {
        var gt = lineEnd - 1
        while (gt >= lineStart && (text[gt] == ' ' || text[gt] == '\t')) gt--
        return gt >= lineStart && text[gt] == '>' && text.charOrNull(gt - 1) != '/' && isOpenStartTagEnd(text, gt)
    }
    val end = codeEnd(text, lineStart, lineEnd)
    val last = lastNonWs(text, lineStart, end) ?: return false
    if (last == '{' || last == '(' || last == '[') return true
    val code = text.subSequence(lineStart, end).toString().trim()
    if (language == CodeLanguage.Kotlin && code.endsWith("->")) return true
    return bracelessControlFlow(code)
}

/** Remove one indentation level ([unit]) from the end of [indent], or whatever leading width is there. */
private fun dropIndentLevel(indent: String, unit: String): String = when {
    indent.endsWith(unit) -> indent.substring(0, indent.length - unit.length)
    else -> indent.substring(0, (indent.length - unit.length).coerceAtLeast(0))
}

/**
 * Whether [pos] sits inside a raw string / text block (`"""…"""`), by counting whole `"""` triples before it
 * (odd ⇒ inside). Bounded from the start of the buffer; gives up (returns false) past the scan limit so a
 * huge file stays cheap — raw strings far down a long file are rare.
 */
private fun insideRawString(text: CharSequence, pos: Int): Boolean {
    if (pos > COMMENT_SCAN_LIMIT) return false
    var i = 0
    var count = 0
    while (i + 2 < pos) {
        if (text[i] == '"' && text[i + 1] == '"' && text[i + 2] == '"') { count++; i += 3 } else i++
    }
    return count % 2 == 1
}

private val MODIFIER_KEYWORDS = setOf(
    "public", "private", "protected", "internal", "abstract", "final", "open", "sealed", "override",
    "inline", "infix", "operator", "suspend", "lateinit", "const", "vararg", "tailrec", "external",
    "data", "enum", "annotation", "inner", "companion", "expect", "actual", "crossinline", "noinline",
    "reified", "static", "default", "native", "strictfp", "synchronized", "transient", "volatile",
)

/**
 * True when [code] is only annotation(s) and/or declaration modifiers — `@Composable`, `private`,
 * `@Preview(showBackground = true)`, `@JvmStatic private` — i.e. nothing that wraps onto the next line. Such
 * a line precedes a declaration that stays at the same indent. An `@`-token swallows a balanced `(…)`; bare
 * words must all be modifier keywords. Anything else (a name, `fun`, `val`, an expression) returns false.
 */
private fun isAnnotationOrModifierOnly(code: String): Boolean {
    if (code.isEmpty()) return false
    val n = code.length
    var i = 0
    while (i < n) {
        while (i < n && code[i].isWhitespace()) i++
        if (i >= n) break
        when {
            code[i] == '@' -> {
                i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_' || code[i] == ':')) i++
                if (i < n && code[i] == '(') { // consume a balanced argument list
                    var depth = 0
                    while (i < n) {
                        if (code[i] == '(') depth++ else if (code[i] == ')') { depth--; if (depth == 0) { i++; break } }
                        i++
                    }
                    if (depth != 0) return false // unbalanced → not a complete annotation
                }
            }
            code[i].isLetter() -> {
                val start = i
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                if (code.substring(start, i) !in MODIFIER_KEYWORDS) return false
            }
            else -> return false
        }
    }
    return true
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

/** [needle]'s first start index at or after [from], bounded so a delimiter-free suffix can't cost O(N). */
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
