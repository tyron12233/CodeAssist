package dev.ide.ui.editor.core

import dev.ide.ui.concurrent.UiLock
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.ext.EditorLanguageProfile

/*
 * SyntaxFamily.INDENT_BLOCK: languages whose blocks are made of indentation rather than braces (Python,
 * GDScript, Starlark, Nim, CoffeeScript).
 *
 * Everything here reads its language from the profile, with Python's answers standing in for whatever a
 * profile leaves empty, so a plugin that only says "this is an indentation language with these keywords" gets
 * a working editor, and one that differs (another comment marker, another set of string prefixes) says only
 * what differs.
 *
 * Like the other families this is the cheap synchronous layer: per line, no tree. What needs a parse (which
 * `else` belongs to which `if` beyond the indentation, which name is a class) is a LanguageBackend's job.
 */

/** Python 3's keywords, used when an INDENT_BLOCK profile names none. */
internal val PYTHON_KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
    "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
    "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "match", "case",
    "self", "cls",
)

/** The indentation family's own branch-and-jump words; the brace family's set has no `elif` or `except`. */
private val INDENT_CONTROL_WORDS = setOf(
    "if", "elif", "else", "for", "while", "break", "continue", "return", "try", "except", "finally", "raise",
    "yield", "pass", "match", "case", "with", "await",
)

private val INDENT_MODIFIER_WORDS = setOf("async", "global", "nonlocal")

private val DEFAULT_MULTILINE = listOf("\"\"\"", "'''")
private val DEFAULT_PREFIXES = setOf("r", "u", "f", "b", "br", "rb", "fr", "rf")
private val DEFAULT_INTERPOLATING = setOf("f", "fr", "rf")
private val DEFAULT_OPENERS = listOf(":")
private val DEFAULT_EXIT_WORDS = setOf("return", "pass", "raise", "break", "continue")
private val DEFAULT_DEDENT_WORDS = setOf("else", "elif", "except", "finally")

/** Which earlier block headers each of Python's continuation words may line up with. */
private val DEDENT_PARTNERS = mapOf(
    "else" to setOf("if", "elif", "for", "while", "try", "except"),
    "elif" to setOf("if", "elif"),
    "except" to setOf("try", "except"),
    "finally" to setOf("try", "except", "else"),
)

/** Lines whose header Shift+Enter completes with the block opener. */
private val HEADER_WORDS = setOf(
    "if", "elif", "else", "for", "while", "def", "class", "with", "try", "except", "finally", "async", "match",
    "case",
)

/**
 * A profile with the family's defaults filled in, built once per profile and cached, because the scanner asks
 * for these on every line it styles.
 */
internal class IndentSyntax private constructor(val profile: EditorLanguageProfile) {
    val keywords: Set<String> = profile.keywords.ifEmpty { PYTHON_KEYWORDS }
    val lineComment: String = profile.lineComment ?: "#"
    /** Longest first, so `'''` is tried before `'`; the index into this list is part of the carried state. */
    val multiline: List<String> = profile.multilineStrings.ifEmpty { DEFAULT_MULTILINE }.sortedByDescending { it.length }
    val prefixes: Set<String> = profile.stringPrefixes.ifEmpty { DEFAULT_PREFIXES }.map { it.lowercase() }.toSet()
    val interpolating: Set<String> =
        profile.interpolatingPrefixes.ifEmpty { DEFAULT_INTERPOLATING }.map { it.lowercase() }.toSet()
    val openers: List<String> = profile.blockOpeners.ifEmpty { DEFAULT_OPENERS }
    val exitWords: Set<String> = profile.blockExitWords.ifEmpty { DEFAULT_EXIT_WORDS }
    val dedentWords: Set<String> = profile.dedentWords.ifEmpty { DEFAULT_DEDENT_WORDS }
    val maxPrefix: Int = prefixes.maxOfOrNull { it.length } ?: 0

    companion object {
        // Keyed by identity (a profile has no equals): one entry per registered language, so it stays small.
        private val cache = HashMap<EditorLanguageProfile, IndentSyntax>()
        private val lock = UiLock()

        fun of(profile: EditorLanguageProfile): IndentSyntax =
            lock.withLock { cache.getOrPut(profile) { IndentSyntax(profile) } }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Lexer state. A multi-line string open at the end of a line is carried as one int: which delimiter, whether
// it interpolates, and whether it is a docstring. The base is well above LexState's own constants.
// ---------------------------------------------------------------------------------------------------------

internal const val MULTI_BASE = 64

internal fun multiState(index: Int, interpolating: Boolean, doc: Boolean): Int =
    MULTI_BASE + index * 4 + (if (interpolating) 1 else 0) + (if (doc) 2 else 0)

internal fun isMultiState(state: Int) = state >= MULTI_BASE

/** Style one line of an INDENT_BLOCK language, given the state the previous line ended in. */
internal fun styleIndentLine(line: String, entryState: Int, profile: EditorLanguageProfile): StyledLine {
    val syntax = IndentSyntax.of(profile)
    val spans = ArrayList<LineSpan>(8)
    var i = 0
    if (isMultiState(entryState)) {
        val k = entryState - MULTI_BASE
        val delimiter = syntax.multiline.getOrNull(k / 4) ?: return StyledLine(spans, LexState.CODE)
        val doc = (k and 2) != 0
        val end = scanStringBody(
            line, 0, delimiter, interpolating = (k and 1) != 0,
            kind = if (doc) TokenType.DOC_COMMENT else TokenType.RAW_STRING, spans = spans, syntax = syntax,
        )
        if (end < 0) return StyledLine(spans, entryState)
        i = end
    }
    return StyledLine(spans, scanIndentCode(line, i, line.length, spans, syntax, topLevel = true))
}

/**
 * Scan code in `line[from, to)`. Returns the exit state, which is only ever not CODE when a multi-line string
 * is left open at the end of a top-level scan; inside an interpolation ([topLevel] false) strings stay on the
 * line.
 */
private fun scanIndentCode(
    line: String,
    from: Int,
    to: Int,
    spans: MutableList<LineSpan>,
    syntax: IndentSyntax,
    topLevel: Boolean,
): Int {
    val firstNonBlank = line.indexOfFirst { it != ' ' && it != '\t' }
    var i = from
    while (i < to) {
        val c = line[i]
        when {
            line.startsWith(syntax.lineComment, i) -> {
                spans.add(LineSpan(i, to, TokenType.COMMENT))
                return LexState.CODE
            }
            c == '"' || c == '\'' -> {
                val r = scanString(line, i, i, to, "", spans, syntax, topLevel, statementStart = i == firstNonBlank)
                if (r < 0) return -r
                i = r
            }
            c == '@' && i == firstNonBlank && topLevel -> {
                val start = i
                i++
                while (i < to && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '.')) i++
                spans.add(LineSpan(start, i, TokenType.ANNOTATION))
            }
            c.isDigit() || (c == '.' && i + 1 < to && line[i + 1].isDigit()) -> {
                val start = i
                val hex = c == '0' && i + 1 < to && line[i + 1] in "xXoObB"
                i++
                while (i < to) {
                    val d = line[i]
                    val exponentSign = !hex && (d == '+' || d == '-') && line[i - 1] in "eE"
                    if (d.isLetterOrDigit() || d == '_' || d == '.' || exponentSign) i++ else break
                }
                spans.add(LineSpan(start, i, TokenType.NUMBER))
            }
            c.isLetter() || c == '_' -> {
                val start = i
                i++
                while (i < to && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                // A string prefix belongs to the string it sits in front of: `f"…"`, `rb'…'`.
                if (i < to && (line[i] == '"' || line[i] == '\'') && i - start <= syntax.maxPrefix &&
                    line.substring(start, i).lowercase() in syntax.prefixes
                ) {
                    val prefix = line.substring(start, i).lowercase()
                    val r = scanString(line, start, i, to, prefix, spans, syntax, topLevel, start == firstNonBlank)
                    if (r < 0) return -r
                    i = r
                    continue
                }
                val word = line.substring(start, i)
                val type = when {
                    word in syntax.keywords -> when (word) {
                        in INDENT_CONTROL_WORDS -> TokenType.KEYWORD_CONTROL
                        in INDENT_MODIFIER_WORDS -> TokenType.KEYWORD_MODIFIER
                        else -> TokenType.KEYWORD
                    }
                    else -> {
                        var j = i
                        while (j < to && (line[j] == ' ' || line[j] == '\t')) j++
                        when {
                            j < to && line[j] == '(' -> TokenType.FUNC
                            word[0].isUpperCase() -> TokenType.TYPE
                            else -> null
                        }
                    }
                }
                if (type != null) spans.add(LineSpan(start, i, type))
            }
            c in "{}()[];,.<>=+-*/%&|!?:^~@" -> {
                val type = when (c) {
                    '{', '}', '(', ')', '[', ']' -> TokenType.BRACKET
                    ';', ',', '.' -> TokenType.SEPARATOR
                    else -> TokenType.OPERATOR
                }
                spans.add(LineSpan(i, i + 1, type))
                i++
            }
            else -> i++
        }
    }
    return LexState.CODE
}

/**
 * Scan a string whose prefix starts at [start] and whose quote is at [quote]. Returns the index after it, or,
 * when a multi-line string is left open at the end of the line, the NEGATED carried state (always below -1,
 * because the state is at least [MULTI_BASE]).
 */
private fun scanString(
    line: String,
    start: Int,
    quote: Int,
    to: Int,
    prefix: String,
    spans: MutableList<LineSpan>,
    syntax: IndentSyntax,
    topLevel: Boolean,
    statementStart: Boolean,
): Int {
    val interpolating = prefix in syntax.interpolating
    val multiIndex = if (topLevel) syntax.multiline.indexOfFirst { line.startsWith(it, quote) } else -1
    val delimiter = if (multiIndex >= 0) syntax.multiline[multiIndex] else line[quote].toString()
    // A triple-quoted string standing as a statement of its own is documentation, not a value.
    val doc = multiIndex >= 0 && statementStart
    val kind = when {
        doc -> TokenType.DOC_COMMENT
        multiIndex >= 0 -> TokenType.RAW_STRING
        else -> TokenType.STRING
    }
    if (quote > start) spans.add(LineSpan(start, quote, kind))
    spans.add(LineSpan(quote, quote + delimiter.length, kind))
    val end = scanStringBody(line, quote + delimiter.length, delimiter, interpolating, kind, spans, syntax, to)
    if (end >= 0) return end
    // Unterminated. A single-quoted string ends with its line; a multi-line one carries on.
    return if (multiIndex >= 0) -multiState(multiIndex, interpolating, doc) else to
}

/**
 * Scan a string's body from [from] up to and including [delimiter]. Returns the index after the delimiter, or
 * -1 when the line ends first (everything to the end is then colored [kind]). Inside an interpolating string,
 * `{expression}` is scanned as code and `{{`/`}}` stay literal.
 */
private fun scanStringBody(
    line: String,
    from: Int,
    delimiter: String,
    interpolating: Boolean,
    kind: TokenType,
    spans: MutableList<LineSpan>,
    syntax: IndentSyntax,
    to: Int = line.length,
): Int {
    var runStart = from
    var i = from
    fun flush(end: Int) {
        if (end > runStart) spans.add(LineSpan(runStart, end, kind))
    }
    while (i < to) {
        val c = line[i]
        when {
            c == '\\' -> i += 2
            line.startsWith(delimiter, i) -> {
                i += delimiter.length
                flush(i)
                return i
            }
            interpolating && c == '{' && i + 1 < to && line[i + 1] == '{' -> i += 2
            interpolating && c == '{' -> {
                val close = interpolationEnd(line, i + 1, to, delimiter)
                if (close < 0) {
                    i++
                } else {
                    flush(i)
                    // The braces stay uncolored, as Kotlin's `${…}` does, so the expression reads as code.
                    val exprEnd = formatSpecStart(line, i + 1, close)
                    scanIndentCode(line, i + 1, exprEnd, spans, syntax, topLevel = false)
                    if (exprEnd < close) spans.add(LineSpan(exprEnd, close, kind))
                    i = close + 1
                    runStart = i
                }
            }
            else -> i++
        }
    }
    flush(to.coerceAtMost(line.length))
    return -1
}

/** Index of the `}` closing an interpolation that starts at [from], or -1 when it does not close on this line
 *  before the string's own [delimiter]. Brackets and quoted strings inside it are skipped. */
private fun interpolationEnd(line: String, from: Int, to: Int, delimiter: String): Int {
    var depth = 0
    var i = from
    while (i < to) {
        val c = line[i]
        when {
            depth == 0 && line.startsWith(delimiter, i) -> return -1
            c == '\'' || c == '"' -> {
                val q = c
                i++
                while (i < to && line[i] != q) { if (line[i] == '\\') i++; i++ }
            }
            c == '(' || c == '[' || c == '{' -> depth++
            c == ')' || c == ']' -> depth--
            c == '}' -> if (depth == 0) return i else depth--
        }
        i++
    }
    return -1
}

/** Where an interpolation's `:format` or `!conversion` begins, or [end] when it has none. Only at bracket
 *  depth 0, and a `:` of a `!=`/`:=`/lambda is not one. */
private fun formatSpecStart(line: String, from: Int, end: Int): Int {
    var depth = 0
    var i = from
    while (i < end) {
        val c = line[i]
        when (c) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            '\'', '"' -> {
                i++
                while (i < end && line[i] != c) { if (line[i] == '\\') i++; i++ }
            }
            '!' -> if (depth == 0 && line.getOrNull(i + 1) != '=') return i
            ':' -> if (depth == 0 && line.getOrNull(i + 1) != '=' && !line.substring(from, i).contains("lambda")) return i
        }
        i++
    }
    return end
}

// ---------------------------------------------------------------------------------------------------------
// Editing
// ---------------------------------------------------------------------------------------------------------

/**
 * Where the code of [line] ends: the start of its comment, or its length. Quoted strings are skipped so a `#`
 * inside one is not a comment. Multi-line strings are not tracked, which is fine for the questions this
 * answers (does a line end with `:`, which brackets does it open).
 */
private fun codeEnd(line: CharSequence, syntax: IndentSyntax): Int {
    var i = 0
    val n = line.length
    while (i < n) {
        val c = line[i]
        when {
            c == '"' || c == '\'' -> {
                i++
                while (i < n && line[i] != c) { if (line[i] == '\\') i++; i++ }
                i++
            }
            line.startsWith(syntax.lineComment, i) -> return i
            else -> i++
        }
    }
    return n
}

/** The brackets [code] leaves open, as (offset of the opener) in order; a stray closer pops nothing. */
private fun openBrackets(code: CharSequence): Pair<List<Int>, Int> {
    val open = ArrayList<Int>()
    var unmatchedClosers = 0
    var i = 0
    while (i < code.length) {
        val c = code[i]
        when (c) {
            '"', '\'' -> {
                i++
                while (i < code.length && code[i] != c) { if (code[i] == '\\') i++; i++ }
            }
            '(', '[', '{' -> open.add(i)
            ')', ']', '}' -> if (open.isNotEmpty()) open.removeAt(open.lastIndex) else unmatchedClosers++
        }
        i++
    }
    return open to unmatchedClosers
}

private fun leadingWhitespace(s: CharSequence): String {
    var i = 0
    while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
    return s.subSequence(0, i).toString()
}

private fun firstWord(s: String): String {
    val t = s.trimStart()
    var i = 0
    while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '_')) i++
    return t.substring(0, i)
}

private fun dedentOnce(indent: String, unit: String): String = when {
    indent.endsWith(unit) -> indent.dropLast(unit.length)
    indent.endsWith("\t") -> indent.dropLast(1)
    else -> indent.dropLast(minOf(unit.length, indent.length))
}

private fun lineAt(text: CharSequence, start: Int): String {
    var e = start
    while (e < text.length && text[e] != '\n') e++
    return text.subSequence(start, e).toString()
}

/**
 * The indentation of the logical line that [lineStart]'s line belongs to: when that line closes brackets
 * opened on earlier lines (`    b):` finishing `def f(a,`), the statement started up there, and so did its
 * indentation.
 */
private fun logicalIndent(text: CharSequence, lineStart: Int, code: CharSequence, syntax: IndentSyntax): String {
    var (_, owed) = openBrackets(code)
    var start = lineStart
    var guard = 0
    while (owed > 0 && start > 0 && guard++ < 50) {
        val prevStart = text.lastIndexOf('\n', start - 2) + 1
        val prev = lineAt(text, prevStart)
        val (open, closers) = openBrackets(prev.substring(0, codeEnd(prev, syntax)))
        owed += closers - open.size
        start = prevStart
    }
    return leadingWhitespace(lineAt(text, start))
}

/** Enter for an INDENT_BLOCK language. */
internal class IndentBlockNewlineHandler(profile: EditorLanguageProfile) : NewlineHandler {
    private val syntax = IndentSyntax.of(profile)

    override fun onEnter(text: CharSequence, pos: Int): RangeEdit {
        val indent = newLineIndent(text, pos)
        return RangeEdit(pos, pos, "\n" + indent, pos + 1 + indent.length)
    }

    /** The indentation a line opened at [pos] should start with. */
    fun newLineIndent(text: CharSequence, pos: Int): String {
        val lineStart = text.lastIndexOf('\n', pos - 1) + 1
        val before = text.subSequence(lineStart, pos).toString()
        val unit = detectIndentUnit(text)
        val code = before.substring(0, codeEnd(before, syntax)).trimEnd()
        val indent = leadingWhitespace(before)

        val (open, _) = openBrackets(code)
        if (open.isNotEmpty()) {
            val opener = open.last()
            // `foo(` then Enter: one level in. `foo(a,` then Enter: line up under `a`, as PEP 8 asks.
            val after = code.substring(opener + 1)
            return if (after.isBlank()) indent + unit else " ".repeat(columnsOf(before, opener + 1 + leadingWhitespace(after).length))
        }
        val base = logicalIndent(text, lineStart, code, syntax)
        return when {
            syntax.openers.any { code.endsWith(it) } -> base + unit
            firstWord(code) in syntax.exitWords -> dedentOnce(base, unit)
            else -> base
        }
    }

    /** Display columns of `line[0, end)`, with a tab advancing to the next multiple of four. */
    private fun columnsOf(line: String, end: Int): Int {
        var col = 0
        for (k in 0 until end.coerceAtMost(line.length)) col = if (line[k] == '\t') (col / 4 + 1) * 4 else col + 1
        return col
    }
}

/**
 * Typing the last character of a block opener at the end of a continuation line (`else:`, `except E:`) moves
 * the line back to the level of the header it continues, or returns null when nothing should move.
 */
internal fun indentBlockDedentOnType(text: CharSequence, pos: Int, ch: Char, profile: EditorLanguageProfile): RangeEdit? {
    val syntax = IndentSyntax.of(profile)
    val opener = syntax.openers.firstOrNull { it.last() == ch } ?: return null
    val lineStart = text.lastIndexOf('\n', pos - 1) + 1
    val before = text.subSequence(lineStart, pos).toString()
    if (!(before + ch).trimEnd().endsWith(opener)) return null
    var after = pos
    while (after < text.length && text[after] != '\n') {
        if (text[after] != ' ' && text[after] != '\t') return null
        after++
    }
    if (codeEnd(before, syntax) != before.length) return null
    val word = firstWord(before)
    if (word !in syntax.dedentWords) return null

    val indent = leadingWhitespace(before)
    val current = indent.length
    val partners = DEDENT_PARTNERS[word]
    // Walk up through the lines that set a new, shallower-or-equal level; the first header that may partner
    // this word decides the level. A deeper line belongs to a block already closed and says nothing.
    var minSeen = Int.MAX_VALUE
    var start = lineStart
    var target: String? = null
    while (start > 0) {
        val prevStart = text.lastIndexOf('\n', start - 2) + 1
        val prev = lineAt(text, prevStart)
        start = prevStart
        val prevCode = prev.substring(0, codeEnd(prev, syntax)).trimEnd()
        if (prevCode.isBlank()) continue
        val prevIndent = leadingWhitespace(prev)
        if (prevIndent.length > current || prevIndent.length > minSeen) continue
        minSeen = prevIndent.length
        val header = syntax.openers.any { prevCode.endsWith(it) }
        val prevWord = firstWord(prevCode)
        if (header && (partners == null || prevWord in partners)) { target = prevIndent; break }
        if (prevIndent.isEmpty()) break
    }
    val level = target ?: return null
    if (level.length >= current) return null
    val rest = before.substring(indent.length)
    val replacement = level + rest + ch
    return RangeEdit(lineStart, pos, replacement, lineStart + replacement.length)
}

/**
 * Shift+Enter for an INDENT_BLOCK language: a block header missing its opener (`if ready`) gets one, then a
 * deeper line opens; any other line ends where it is and a line opens at the level Enter would give.
 */
internal fun indentBlockSmartEnter(text: CharSequence, caret: Int, profile: EditorLanguageProfile): RangeEdit {
    val syntax = IndentSyntax.of(profile)
    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
    var lineEnd = caret
    while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
    val line = text.subSequence(lineStart, lineEnd).toString()
    val codeLen = codeEnd(line, syntax)
    val code = line.substring(0, codeLen).trimEnd()
    val word = firstWord(code)
    val needsOpener = code.isNotEmpty() && word in HEADER_WORDS && word in syntax.keywords &&
        syntax.openers.none { code.endsWith(it) } && openBrackets(code).first.isEmpty()
    val handler = IndentBlockNewlineHandler(profile)
    if (needsOpener) {
        val at = lineStart + code.length
        val opener = syntax.openers.first()
        val patched = text.subSequence(0, at).toString() + opener + text.subSequence(at, lineEnd)
        val indent = handler.newLineIndent(patched, patched.length)
        val insert = opener + text.subSequence(at, lineEnd) + "\n" + indent
        return RangeEdit(at, lineEnd, insert, at + insert.length)
    }
    val indent = handler.newLineIndent(text, lineEnd)
    return RangeEdit(lineEnd, lineEnd, "\n" + indent, lineEnd + 1 + indent.length)
}

/** Whether typing the third quote at [pos] opens one of the profile's multi-line strings. */
internal fun indentBlockOpensTripleQuote(text: CharSequence, pos: Int, ch: Char, profile: EditorLanguageProfile): Boolean {
    val syntax = IndentSyntax.of(profile)
    val triple = "$ch$ch$ch"
    if (triple !in syntax.multiline) return false
    if (pos >= 3 && text[pos - 3] == ch) return false
    // Opening, not closing: the scanner says the line before the two quotes is not inside such a string.
    val lineStart = text.lastIndexOf('\n', pos - 1) + 1
    var state = LexState.CODE
    var s = 0
    while (s < lineStart) {
        var e = s
        while (e < text.length && text[e] != '\n') e++
        state = styleIndentLine(text.subSequence(s, e).toString(), state, syntax.profile).exitState
        s = e + 1
    }
    if (isMultiState(state)) return false
    val head = text.subSequence(lineStart, pos - 2).toString()
    return styleIndentLine(head, state, syntax.profile).exitState == LexState.CODE
}

// ---------------------------------------------------------------------------------------------------------
// Folding
// ---------------------------------------------------------------------------------------------------------

/**
 * Indentation folds: every block header (a line ending in a block opener) folds the lines indented under it.
 *
 * Used when no LanguageBackend supplies folds. Lines inside a multi-line string and lines continuing an open
 * bracket belong to the statement above them, so a docstring at column 0 or a wrapped argument list never
 * ends a block early.
 */
fun indentBlockFolds(text: CharSequence, profile: EditorLanguageProfile): List<FoldRegion> {
    val syntax = IndentSyntax.of(profile)
    class Line(val start: Int, val end: Int, val indent: Int, val blank: Boolean, val header: Boolean, val counts: Boolean)

    val lines = ArrayList<Line>()
    var state = LexState.CODE
    var depth = 0
    var s = 0
    while (s <= text.length) {
        var e = s
        while (e < text.length && text[e] != '\n') e++
        val raw = text.subSequence(s, e).toString()
        val inString = isMultiState(state)
        val continuation = depth > 0
        state = styleIndentLine(raw, state, profile).exitState
        val code = if (inString) "" else raw.substring(0, codeEnd(raw, syntax)).trimEnd()
        val (open, closers) = openBrackets(code)
        depth = (depth + open.size - closers).coerceAtLeast(0)
        val indent = leadingWhitespace(raw).fold(0) { col, c -> if (c == '\t') (col / 4 + 1) * 4 else col + 1 }
        lines.add(
            Line(
                start = s,
                end = e,
                indent = indent,
                blank = raw.isBlank(),
                header = !inString && depth == 0 && syntax.openers.any { code.endsWith(it) },
                counts = !inString && !continuation,
            )
        )
        if (e >= text.length) break
        s = e + 1
    }

    val out = ArrayList<FoldRegion>()
    for (h in lines.indices) {
        val header = lines[h]
        if (!header.header) continue
        // The header's level is its statement's: a multi-line `def f(\n a,\n):` starts where `def` does.
        var top = h
        while (top > 0 && !lines[top].counts) top--
        val level = lines[top].indent
        var last = -1
        var j = h + 1
        while (j < lines.size) {
            val l = lines[j]
            if (!l.blank && l.counts && l.indent <= level) break
            if (!l.blank) last = j
            j++
        }
        if (last > h) out.add(FoldRegion(header.end, lines[last].end, "...", "block", collapsed = false))
    }
    return out
}
