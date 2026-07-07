package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage

///**
// * Per-line tokenization with carried lexer state — the incremental half of syntax highlighting.
// *
// * The old whole-document scanner (`highlight()` in SyntaxHighlighter.kt) is split at line boundaries:
// * [styleLine] tokenizes ONE line given the state the previous line ended in ([StyledLine.exitState]),
// * so an edit re-tokenizes only the edited line — and keeps walking forward only while a line's exit
// * state actually changes (a `\\/*` opened above ripples down; a normal keystroke stops after one line).
// * Token rules are identical to the old scanner; the only constructs that cross lines are block
// * comments (Java/Kotlin/XML) and XML attribute strings, which is what the state encodes.
// **/

/** Token classes — resolved to theme colors only at render time, so a theme swap re-styles nothing. */
enum class TokenType { KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, FUNC, TYPE, PUNCT, PROPERTY }

/** One colored run within a line; [start]/[end] are columns (char offsets within the line). */
class LineSpan(val start: Int, val end: Int, val type: TokenType)

/** Lexer state at a line boundary. */
object LexState {
    const val CODE = 0
    /** Inside a `/* ... */` (Java/Kotlin) or `<!-- ... -->` (XML) comment. */
    const val BLOCK_COMMENT = 1
    /** Inside a double-quoted XML attribute value (XML strings may span lines). */
    const val XML_STRING = 2
    /** Inside a Kotlin raw string (`"""…"""`), which spans lines. */
    const val KT_RAW_STRING = 3
}

class StyledLine(val spans: List<LineSpan>, val exitState: Int) {
    companion object {
        val EMPTY = StyledLine(emptyList(), LexState.CODE)
    }
}

private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
    "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
    "volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield",
)

private val KOTLIN_KEYWORDS = setOf(
    // hard keywords
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
    // common soft keywords (rarely used as plain identifiers)
    "by", "catch", "constructor", "finally", "import", "init", "where",
    // modifier keywords
    "abstract", "actual", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
    "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open",
    "operator", "out", "override", "private", "protected", "public", "reified", "sealed", "suspend",
    "tailrec", "vararg",
)

private fun isPunct(c: Char) = c in "{}()[];,.<>=+-*\\/%&|!?:^~@"

fun styleLine(line: String, entryState: Int, language: CodeLanguage): StyledLine = when (language) {
    CodeLanguage.Plain -> StyledLine.EMPTY
    CodeLanguage.Xml -> styleXmlLine(line, entryState)
    CodeLanguage.Proguard -> styleProguardLine(line)
    CodeLanguage.Kotlin -> styleKotlinLine(line, entryState)
    CodeLanguage.Java -> styleCodeLine(line, entryState)
}

/** ProGuard/R8 keep-rule line styling: `#` comments, `-directives` (keyword), `@`-annotations, quoted
 *  strings, and capitalised class names as types. No cross-line state (no block comments). */
private fun styleProguardLine(line: String): StyledLine {
    val n = line.length
    val spans = ArrayList<LineSpan>(8)
    var i = 0
    while (i < n) {
        val c = line[i]
        when {
            c == '#' -> { spans.add(LineSpan(i, n, TokenType.COMMENT)); return StyledLine(spans, LexState.CODE) }
            c == '-' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t') -> {
                val start = i; i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                spans.add(LineSpan(start, i, TokenType.KEYWORD))
            }
            c == '@' -> {
                val start = i; i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '.')) i++
                spans.add(LineSpan(start, i, TokenType.ANNOTATION))
            }
            c == '"' || c == '\'' -> {
                val start = i; i++
                while (i < n && line[i] != c) i++
                if (i < n) i++
                spans.add(LineSpan(start, i.coerceAtMost(n), TokenType.STRING))
            }
            c.isLetter() || c == '_' -> {
                val start = i; i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '.' || line[i] == '$')) i++
                if (line[start].isUpperCase()) spans.add(LineSpan(start, i, TokenType.TYPE))
            }
            c in "{}()[];,*" -> { spans.add(LineSpan(i, i + 1, TokenType.PUNCT)); i++ }
            else -> i++
        }
    }
    return StyledLine(spans, LexState.CODE)
}

private fun styleCodeLine(line: String, entryState: Int): StyledLine {
    val n = line.length
    val spans = ArrayList<LineSpan>(8)
    var i = 0
    if (entryState == LexState.BLOCK_COMMENT) {
        val close = line.indexOf("*/")
        if (close < 0) {
            if (n > 0) spans.add(LineSpan(0, n, TokenType.COMMENT))
            return StyledLine(spans, LexState.BLOCK_COMMENT)
        }
        spans.add(LineSpan(0, close + 2, TokenType.COMMENT))
        i = close + 2
    }
    while (i < n) {
        val c = line[i]
        when {
            c == '/' && i + 1 < n && line[i + 1] == '/' -> {
                spans.add(LineSpan(i, n, TokenType.COMMENT))
                return StyledLine(spans, LexState.CODE)
            }
            c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                val close = line.indexOf("*/", startIndex = i + 2)
                if (close < 0) {
                    spans.add(LineSpan(i, n, TokenType.COMMENT))
                    return StyledLine(spans, LexState.BLOCK_COMMENT)
                }
                spans.add(LineSpan(i, close + 2, TokenType.COMMENT))
                i = close + 2
            }
            c == '"' || c == '\'' -> {
                val start = i; i++
                while (i < n && line[i] != c) { if (line[i] == '\\') i++; i++ }
                if (i < n) i++
                spans.add(LineSpan(start, i.coerceAtMost(n), TokenType.STRING))
            }
            c.isDigit() -> {
                val start = i; i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '.' || line[i] == '_')) i++
                spans.add(LineSpan(start, i, TokenType.NUMBER))
            }
            c == '@' -> {
                val start = i; i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                spans.add(LineSpan(start, i, TokenType.ANNOTATION))
            }
            c.isLetter() || c == '_' || c == '$' -> {
                val start = i; i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '$')) i++
                val word = line.substring(start, i)
                val type = when {
                    word in JAVA_KEYWORDS -> TokenType.KEYWORD
                    else -> {
                        var j = i
                        while (j < n && (line[j] == ' ' || line[j] == '\t')) j++
                        when {
                            j < n && line[j] == '(' -> TokenType.FUNC
                            word[0].isUpperCase() -> TokenType.TYPE
                            else -> null
                        }
                    }
                }
                if (type != null) spans.add(LineSpan(start, i, type))
            }
            isPunct(c) -> { spans.add(LineSpan(i, i + 1, TokenType.PUNCT)); i++ }
            else -> i++
        }
    }
    return StyledLine(spans, LexState.CODE)
}

/**
 * Kotlin line styler. Unlike [styleCodeLine] (Java) it understands string **interpolation**: inside a
 * `"…"` / `"""…"""` string, the code in a `${…}` block (and the identifier of a `$name`) is styled as
 * code — keywords, nested strings, calls, numbers — instead of being swallowed into the string run, and a
 * `"…"` nested inside an interpolation is colored as its own string. Raw strings carry [LexState.KT_RAW_STRING]
 * across lines. Single-line interpolation nests to any depth (the string / interpolation scanners recurse into
 * each other); a `${…}` or raw string left open at end of line degrades gracefully (no crash, no stale span).
 * The `${`/`}` delimiters and a `$name` identifier are left uncolored so the semantic layer (which resolves the
 * interpolated variable/expression) shows through — the same division of labor the rest of the editor uses.
 */
private fun styleKotlinLine(line: String, entryState: Int): StyledLine {
    val n = line.length
    val spans = ArrayList<LineSpan>(8)
    var i = 0
    when (entryState) {
        LexState.BLOCK_COMMENT -> {
            val close = line.indexOf("*/")
            if (close < 0) {
                if (n > 0) spans.add(LineSpan(0, n, TokenType.COMMENT))
                return StyledLine(spans, LexState.BLOCK_COMMENT)
            }
            spans.add(LineSpan(0, close + 2, TokenType.COMMENT))
            i = close + 2
        }
        LexState.KT_RAW_STRING -> {
            val next = scanRawStringBody(line, 0, 0, spans)
            if (next < 0) return StyledLine(spans, LexState.KT_RAW_STRING)
            i = next
        }
    }
    return StyledLine(spans, scanKotlinCode(line, i, spans))
}

/** Scan Kotlin code from [start] to end of line; returns the exit [LexState] (a block comment or raw
 *  string left open at end of line crosses to the next line, exactly like [styleCodeLine]'s comments). */
private fun scanKotlinCode(line: String, start: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = start
    while (i < n) {
        val c = line[i]
        when {
            c == '/' && i + 1 < n && line[i + 1] == '/' -> {
                spans.add(LineSpan(i, n, TokenType.COMMENT)); return LexState.CODE
            }
            c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                val close = line.indexOf("*/", startIndex = i + 2)
                if (close < 0) { spans.add(LineSpan(i, n, TokenType.COMMENT)); return LexState.BLOCK_COMMENT }
                spans.add(LineSpan(i, close + 2, TokenType.COMMENT)); i = close + 2
            }
            c == '"' && i + 2 < n && line[i + 1] == '"' && line[i + 2] == '"' -> {
                val next = scanRawStringBody(line, i + 3, i, spans)
                if (next < 0) return LexState.KT_RAW_STRING
                i = next
            }
            c == '"' -> i = scanKotlinString(line, i, spans)
            c == '\'' -> i = scanCharLiteral(line, i, spans)
            c.isDigit() -> i = scanNumber(line, i, spans)
            c == '@' -> i = scanAnnotation(line, i, spans)
            c.isLetter() || c == '_' || c == '`' -> i = scanKotlinWord(line, i, spans)
            isPunct(c) -> { spans.add(LineSpan(i, i + 1, TokenType.PUNCT)); i++ }
            else -> i++
        }
    }
    return LexState.CODE
}

/** A regular `"…"` string from the opening quote at [start]; styles literal runs as STRING and hands each
 *  `${…}` / `$name` interpolation to code scanning. Returns the index past the closing quote (or the line
 *  length if the string is unterminated — a regular string can't span lines, so no cross-line state). */
private fun scanKotlinString(line: String, start: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = start + 1
    var litStart = start
    while (i < n) {
        val c = line[i]
        when {
            c == '\\' -> i += 2 // escape (stays in the literal run; the semantic layer recolors `\n`, `\uXXXX`, …)
            c == '"' -> { i++; spans.add(LineSpan(litStart, i, TokenType.STRING)); return i }
            c == '$' && i + 1 < n && line[i + 1] == '{' -> {
                if (i > litStart) spans.add(LineSpan(litStart, i, TokenType.STRING))
                i = scanInterpolation(line, i, spans)
                litStart = i
            }
            c == '$' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_') -> {
                if (i > litStart) spans.add(LineSpan(litStart, i, TokenType.STRING))
                i++ // the `$`
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                litStart = i
            }
            else -> i++
        }
    }
    if (n > litStart) spans.add(LineSpan(litStart, n, TokenType.STRING))
    return n
}

/** Scan a raw-string (`"""…"""`) body from [from], with the current STRING literal run starting at
 *  [litStart]. Handles `${…}` / `$name` interpolation; raw strings have no escapes. Returns the index past
 *  the closing `"""`, or -1 if the raw string does not close on this line (the whole tail is string). */
private fun scanRawStringBody(line: String, from: Int, litStart: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = from
    var lit = litStart
    while (i < n) {
        val c = line[i]
        when {
            c == '"' && i + 2 < n && line[i + 1] == '"' && line[i + 2] == '"' -> {
                i += 3; spans.add(LineSpan(lit, i, TokenType.STRING)); return i
            }
            c == '$' && i + 1 < n && line[i + 1] == '{' -> {
                if (i > lit) spans.add(LineSpan(lit, i, TokenType.STRING))
                i = scanInterpolation(line, i, spans)
                lit = i
            }
            c == '$' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_') -> {
                if (i > lit) spans.add(LineSpan(lit, i, TokenType.STRING))
                i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                lit = i
            }
            else -> i++
        }
    }
    if (n > lit) spans.add(LineSpan(lit, n, TokenType.STRING))
    return -1
}

/** Scan a `${…}` interpolation from the `$` at [start]; the inner code is styled as code (keywords, nested
 *  strings, calls). Returns the index past the matching `}` (or the line length if it's unclosed). The
 *  `${` and matching `}` are left uncolored so the semantic layer's `stringTemplateEntry` shows through. */
private fun scanInterpolation(line: String, start: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = start + 2 // past `${`
    var depth = 1
    while (i < n && depth > 0) {
        val c = line[i]
        when {
            c == '{' -> { spans.add(LineSpan(i, i + 1, TokenType.PUNCT)); depth++; i++ }
            c == '}' -> {
                depth--
                if (depth > 0) spans.add(LineSpan(i, i + 1, TokenType.PUNCT)) // an inner `}` (lambda close)
                i++
            }
            c == '"' && i + 2 < n && line[i + 1] == '"' && line[i + 2] == '"' -> {
                val next = scanRawStringBody(line, i + 3, i, spans)
                i = if (next < 0) n else next
            }
            c == '"' -> i = scanKotlinString(line, i, spans)
            c == '\'' -> i = scanCharLiteral(line, i, spans)
            c.isDigit() -> i = scanNumber(line, i, spans)
            c == '@' -> i = scanAnnotation(line, i, spans)
            c.isLetter() || c == '_' || c == '`' -> i = scanKotlinWord(line, i, spans)
            isPunct(c) -> { spans.add(LineSpan(i, i + 1, TokenType.PUNCT)); i++ }
            else -> i++
        }
    }
    return i
}

/** An identifier / keyword (or a backtick-quoted identifier). Colors keywords, a Capitalized name as a
 *  type, and a name directly before `(` as a call. Returns the index past the word. */
private fun scanKotlinWord(line: String, start: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    if (line[start] == '`') { // `backtick identifier` — left uncolored (the semantic layer may recolor it)
        var i = start + 1
        while (i < n && line[i] != '`') i++
        if (i < n) i++
        return i
    }
    var i = start + 1
    while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
    val word = line.substring(start, i)
    val type = when {
        word in KOTLIN_KEYWORDS -> TokenType.KEYWORD
        else -> {
            var j = i
            while (j < n && (line[j] == ' ' || line[j] == '\t')) j++
            when {
                j < n && line[j] == '(' -> TokenType.FUNC
                word[0].isUpperCase() -> TokenType.TYPE
                else -> null
            }
        }
    }
    if (type != null) spans.add(LineSpan(start, i, type))
    return i
}

private fun scanNumber(line: String, start: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = start + 1
    while (i < n && (line[i].isLetterOrDigit() || line[i] == '.' || line[i] == '_')) i++
    spans.add(LineSpan(start, i, TokenType.NUMBER))
    return i
}

private fun scanAnnotation(line: String, start: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = start + 1
    while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
    spans.add(LineSpan(start, i, TokenType.ANNOTATION))
    return i
}

private fun scanCharLiteral(line: String, start: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = start + 1
    while (i < n && line[i] != '\'') { if (line[i] == '\\') i++; i++ }
    if (i < n) i++
    val end = i.coerceAtMost(n)
    spans.add(LineSpan(start, end, TokenType.STRING))
    return end
}

private fun styleXmlLine(line: String, entryState: Int): StyledLine {
    val n = line.length
    val spans = ArrayList<LineSpan>(8)
    var i = 0
    when (entryState) {
        LexState.BLOCK_COMMENT -> {
            val close = line.indexOf("-->")
            if (close < 0) {
                if (n > 0) spans.add(LineSpan(0, n, TokenType.COMMENT))
                return StyledLine(spans, LexState.BLOCK_COMMENT)
            }
            spans.add(LineSpan(0, close + 3, TokenType.COMMENT))
            i = close + 3
        }
        LexState.XML_STRING -> {
            val close = line.indexOf('"')
            if (close < 0) {
                if (n > 0) spans.add(LineSpan(0, n, TokenType.STRING))
                return StyledLine(spans, LexState.XML_STRING)
            }
            spans.add(LineSpan(0, close + 1, TokenType.STRING))
            i = close + 1
        }
    }
    while (i < n) {
        val c = line[i]
        when {
            c == '<' && i + 3 < n && line[i + 1] == '!' && line[i + 2] == '-' && line[i + 3] == '-' -> {
                val close = line.indexOf("-->", startIndex = i + 4)
                if (close < 0) {
                    spans.add(LineSpan(i, n, TokenType.COMMENT))
                    return StyledLine(spans, LexState.BLOCK_COMMENT)
                }
                spans.add(LineSpan(i, close + 3, TokenType.COMMENT))
                i = close + 3
            }
            c == '<' -> {
                val start = i; i++
                if (i < n && (line[i] == '/' || line[i] == '?')) i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '-' || line[i] == ':')) i++
                spans.add(LineSpan(start, i, TokenType.TYPE))
            }
            c == '"' -> {
                val start = i; i++
                while (i < n && line[i] != '"') i++
                if (i < n) {
                    i++
                    spans.add(LineSpan(start, i, TokenType.STRING))
                } else {
                    spans.add(LineSpan(start, n, TokenType.STRING))
                    return StyledLine(spans, LexState.XML_STRING)
                }
            }
            c.isLetter() -> {
                val start = i; i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '-' || line[i] == ':')) i++
                var j = i
                while (j < n && line[j] == ' ') j++
                if (j < n && line[j] == '=') spans.add(LineSpan(start, i, TokenType.PROPERTY))
            }
            else -> i++
        }
    }
    return StyledLine(spans, LexState.CODE)
}
