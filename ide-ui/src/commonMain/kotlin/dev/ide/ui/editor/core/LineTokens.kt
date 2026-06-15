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
    // Kotlin extras (shared scanner)
    "fun", "val", "when", "is", "in", "object", "companion", "data", "override", "open", "internal",
    "lateinit", "by", "constructor", "init", "suspend", "vararg", "typealias", "as", "out", "reified",
)

private fun isPunct(c: Char) = c in "{}()[];,.<>=+-*\\/%&|!?:^~@"

fun styleLine(line: String, entryState: Int, language: CodeLanguage): StyledLine = when (language) {
    CodeLanguage.Plain -> StyledLine.EMPTY
    CodeLanguage.Xml -> styleXmlLine(line, entryState)
    CodeLanguage.Java, CodeLanguage.Kotlin -> styleCodeLine(line, entryState)
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
