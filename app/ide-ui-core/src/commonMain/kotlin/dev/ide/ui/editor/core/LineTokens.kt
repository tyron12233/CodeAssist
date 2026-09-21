package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.ext.SyntaxFamily

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

/**
 * Token classes — resolved to theme colors only at render time, so a theme swap re-styles nothing.
 *
 * These are lexical shapes rather than language concepts, because one set serves every scanner: what a
 * `TYPE` MEANS is decided per [SyntaxFamily] when it is mapped to a color attribute (`tokenColorKey`), so a
 * `TYPE` is a class name in a brace language and a tag name in XML without either scanner knowing about
 * color schemes.
 *
 * The first nine are the original set. The rest are constructs the scanners were already walking past and
 * lumping in with a neighbour — a doc comment inside `COMMENT`, a char literal inside `STRING`, a brace
 * inside `PUNCT` — which meant no scheme could color them apart no matter what it said. Emitting them
 * separately is what makes them themeable; they all fall back to what they used to be, so nothing changes
 * appearance until a scheme says otherwise.
 */
enum class TokenType {
    KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, FUNC, TYPE, PUNCT, PROPERTY,

    /** `if`, `else`, `for`, `while`, `when`, `return`, `try` — the words that branch or jump. */
    KEYWORD_CONTROL,

    /** `private`, `open`, `override`, `suspend`, `static`, `final` — the words that qualify a declaration. */
    KEYWORD_MODIFIER,

    /** A doc comment (KDoc, Javadoc), as distinct from an ordinary block comment. */
    DOC_COMMENT,

    /** A character literal (`'c'`), which is not a string however much it looks like one. */
    CHAR,

    /** A raw/multi-line string: Kotlin's `"""…"""`, a Java text block. */
    RAW_STRING,

    /** `+ - * / % & | ! ? : ^ ~ < > =` — the symbols that compute. */
    OPERATOR,

    /** `{ } ( ) [ ]` — the symbols that nest. */
    BRACKET,

    /** `; , .` — the symbols that separate. */
    SEPARATOR,

    /** The `<`, `</`, `>`, `/>` of a markup tag, as distinct from the tag's name. */
    TAG_DELIMITER,

    /** A namespace prefix: the `android` of `android:id`, without the colon. */
    NAMESPACE,

    /** A markup entity reference: `&amp;`, `&#65;`. */
    ENTITY,

    /** A markup prolog or declaration: `<?xml … ?>`, `<!DOCTYPE …>`. */
    PROLOG,

    /** A `<![CDATA[ … ]]>` section, delimiters and content alike. */
    CDATA,

    /** Markdown emphasis: `**bold**`, `*italic*`, `_italic_`, markers included. */
    EMPHASIS,
}

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
    /** Inside a Markdown fenced code block (``` ``` ``` / `~~~`), which spans lines. */
    const val MD_FENCE = 4
    /** Inside a doc comment (KDoc, Javadoc). Distinct from [BLOCK_COMMENT] so the carried state remembers
     *  which of the two a continuation line belongs to; they close on the same delimiter. */
    const val DOC_COMMENT = 5
    /** Inside a `<![CDATA[ … ]]>` section, which spans lines. */
    const val XML_CDATA = 6
}

class StyledLine(val spans: List<LineSpan>, val exitState: Int) {
    companion object {
        val EMPTY = StyledLine(emptyList(), LexState.CODE)
    }
}

internal val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
    "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
    "volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield",
)

internal val KOTLIN_KEYWORDS = setOf(
    // hard keywords
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
    // common soft keywords (rarely used as plain identifiers)
    "by", "catch", "constructor", "finally", "import", "init", "where",
    // modifier keywords ("data" is handled contextually below, like "value")
    "abstract", "actual", "annotation", "companion", "const", "crossinline", "enum", "expect",
    "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open",
    "operator", "out", "override", "private", "protected", "public", "reified", "sealed", "suspend",
    "tailrec", "vararg",
)

/**
 * AIDL keywords. The type names it also predefines (`String`, `List`, `IBinder`, …) are deliberately left
 * out: they start with a capital, so the shared scanner already colors them as types, the same way `String`
 * reads in a Java file.
 */
internal val AIDL_KEYWORDS = setOf(
    "package", "import", "interface", "parcelable", "enum", "union", "oneway", "const",
    "in", "out", "inout", "cpp_header",
    "void", "boolean", "byte", "char", "int", "long", "float", "double", "true", "false",
)

private fun isPunct(c: Char) = c in "{}()[];,.<>=+-*\\/%&|!?:^~@"

private fun isBracket(c: Char) = c in "{}()[]"

private fun isSeparator(c: Char) = c in ";,."

/** Which of the three punctuation classes [c] belongs to; `isPunct` is the gate, this is the split. */
private fun punctType(c: Char): TokenType = when {
    isBracket(c) -> TokenType.BRACKET
    isSeparator(c) -> TokenType.SEPARATOR
    else -> TokenType.OPERATOR
}

/**
 * The brace-language words that branch or jump, and the ones that qualify a declaration.
 *
 * One pair of sets for the whole [SyntaxFamily.C_FAMILY] rather than per language, and applied only to
 * words a profile already calls keywords: `return` means the same thing in Java, Kotlin, C++ and any
 * language a plugin contributes with that family, so a contributed language gets the distinction for free.
 * A language that disagrees points `KEYWORD_CONTROL` back at the plain keyword attribute through its
 * profile's `tokenColorKeys`.
 *
 * Named `_WORDS` rather than `_KEYWORDS` because the smart-indent logic in this package already owns a
 * `MODIFIER_KEYWORDS` of its own, for a different question (what may precede a declaration on a new line).
 */
internal val CONTROL_WORDS = setOf(
    "break", "case", "catch", "continue", "default", "do", "else", "finally", "for", "goto", "if", "return",
    "switch", "throw", "throws", "try", "when", "while", "yield",
)

internal val MODIFIER_WORDS = setOf(
    "abstract", "actual", "companion", "const", "crossinline", "expect", "external", "final", "infix",
    "inline", "inner", "internal", "lateinit", "native", "noinline", "open", "operator", "out", "override",
    "private", "protected", "public", "reified", "sealed", "static", "strictfp", "suspend", "synchronized",
    "tailrec", "transient", "vararg", "volatile",
)

/** A keyword's finer class, or [TokenType.KEYWORD] when it is neither a control word nor a modifier. */
private fun keywordType(word: String): TokenType = when (word) {
    in CONTROL_WORDS -> TokenType.KEYWORD_CONTROL
    in MODIFIER_WORDS -> TokenType.KEYWORD_MODIFIER
    else -> TokenType.KEYWORD
}

/**
 * Style one line of [language]. Dispatch is on the language's [SyntaxFamily], not on a fixed set of
 * languages, so a plugin-contributed profile is colored by the same scanners the built-ins use: a C-family
 * language differs from Java only in which words are keywords, which the profile carries.
 *
 * Kotlin keeps its own scanner rather than the shared C-family one because it colors constructs the shared
 * scanner has no notion of (raw strings spanning lines, contextual soft keywords).
 */
fun styleLine(line: String, entryState: Int, language: CodeLanguage): StyledLine =
    when (language.profile.syntax) {
        SyntaxFamily.PLAIN -> StyledLine.EMPTY
        SyntaxFamily.XML -> styleXmlLine(line, entryState)
        SyntaxFamily.HASH_COMMENT -> styleProguardLine(line)
        SyntaxFamily.MARKDOWN -> styleMarkdownLine(line, entryState)
        SyntaxFamily.C_FAMILY ->
            if (language == CodeLanguage.Kotlin) styleKotlinLine(line, entryState)
            else styleCodeLine(
                line, entryState,
                language.profile.keywords.ifEmpty { JAVA_KEYWORDS },
                language.profile.directivePrefix,
            )
    }

/**
 * Markdown line styling: headings, fenced code (``` / ~~~, carried across lines via [LexState.MD_FENCE]),
 * block quotes, thematic breaks, list markers, and inline code spans / links. Constructs map onto the shared
 * [TokenType] palette (heading → TYPE, list marker → KEYWORD, quote → COMMENT, code → STRING). Emphasis
 * (`**bold**`, `*italic*`) is left uncolored — the Preview view shows the real formatting; a color can't.
 */
private fun styleMarkdownLine(line: String, entryState: Int): StyledLine {
    val n = line.length
    val spans = ArrayList<LineSpan>(8)
    val indent = line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) n else it }
    val trimmed = line.substring(indent)

    // Inside a fenced code block: the whole line is code; a closing fence ends the block.
    if (entryState == LexState.MD_FENCE) {
        if (n > 0) spans.add(LineSpan(0, n, TokenType.STRING))
        val exit = if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) LexState.CODE else LexState.MD_FENCE
        return StyledLine(spans, exit)
    }
    // Opening fence.
    if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
        if (n > 0) spans.add(LineSpan(0, n, TokenType.STRING))
        return StyledLine(spans, LexState.MD_FENCE)
    }
    // Thematic break (three or more of the same -, *, _), checked before list markers.
    if (isMdThematicBreak(trimmed)) {
        spans.add(LineSpan(0, n, TokenType.PUNCT))
        return StyledLine(spans, LexState.CODE)
    }
    // ATX heading — the whole line reads as a heading.
    if (mdHeadingLevel(trimmed) > 0) {
        spans.add(LineSpan(0, n, TokenType.TYPE))
        return StyledLine(spans, LexState.CODE)
    }
    // Block quote.
    if (trimmed.startsWith(">")) {
        spans.add(LineSpan(0, n, TokenType.COMMENT))
        return StyledLine(spans, LexState.CODE)
    }
    // List marker at the start of the line.
    var i = indent
    val marker = mdListMarkerEnd(line, indent)
    if (marker > indent) {
        spans.add(LineSpan(indent, marker, TokenType.KEYWORD))
        i = marker
    }
    // Inline: code spans, links, emphasis.
    while (i < n) {
        when (line[i]) {
            '`' -> {
                val end = line.indexOf('`', i + 1)
                if (end < 0) i++ else { spans.add(LineSpan(i, end + 1, TokenType.STRING)); i = end + 1 }
            }
            // `**bold**`, `*italic*`, `_italic_`. Left uncolored before on the reasoning that Preview shows
            // the real formatting; but the marker pairs are syntax, and a scheme can now dim them or set
            // the run bold, which is the thing Preview cannot do while you are editing the source.
            '*', '_' -> {
                val end = mdEmphasisEnd(line, i)
                if (end < 0) i++ else { spans.add(LineSpan(i, end, TokenType.EMPHASIS)); i = end }
            }
            '[' -> {
                val close = line.indexOf(']', i + 1)
                if (close in (i + 1) until n && close + 1 < n && line[close + 1] == '(') {
                    val paren = line.indexOf(')', close + 2)
                    if (paren > 0) {
                        spans.add(LineSpan(i, close + 1, TokenType.FUNC))            // [text]
                        spans.add(LineSpan(close + 1, paren + 1, TokenType.PROPERTY)) // (url)
                        i = paren + 1
                    } else i++
                } else i++
            }
            else -> i++
        }
    }
    return StyledLine(spans, LexState.CODE)
}

/**
 * End (exclusive) of an emphasis run starting at [from], or -1 when the marker does not close on this line.
 *
 * A doubled marker is bold and a single one italic, and both have to close with the same marker, which is
 * what keeps a lone `*` in prose from swallowing the rest of the line.
 *
 * `_` carries CommonMark's extra rule: it emphasizes only at a word boundary, because `snake_case_name` is
 * one identifier and not an italic `case`. `*` has no such rule, in Markdown or here.
 */
private fun mdEmphasisEnd(line: String, from: Int): Int {
    val marker = line[from]
    val intraword = marker == '_' && from > 0 && (line[from - 1].isLetterOrDigit() || line[from - 1] == '_')
    if (intraword) return -1
    val double = from + 1 < line.length && line[from + 1] == marker
    val open = if (double) from + 2 else from + 1
    if (open >= line.length || line[open].isWhitespace()) return -1
    val close = line.indexOf(if (double) "$marker$marker" else "$marker", open)
    if (close < 0) return -1
    val end = close + (if (double) 2 else 1)
    if (marker == '_' && end < line.length && line[end].isLetterOrDigit()) return -1
    return end
}

/** A thematic break: three or more `-`, `*`, or `_` with only spaces between (e.g. `---`, `***`, `_ _ _`). */
private fun isMdThematicBreak(trimmed: String): Boolean {
    val bare = trimmed.replace(" ", "")
    if (bare.length < 3) return false
    val c = bare[0]
    return (c == '-' || c == '*' || c == '_') && bare.all { it == c }
}

/** ATX heading level 1..6, or 0 if [trimmed] is not a heading. */
private fun mdHeadingLevel(trimmed: String): Int {
    var h = 0
    while (h < trimmed.length && trimmed[h] == '#') h++
    return if (h in 1..6 && (h == trimmed.length || trimmed[h] == ' ')) h else 0
}

/** End (exclusive) of a list marker starting at [from] (`- `, `* `, `1. `), or -1 if the line isn't a list
 *  item. The trailing space is excluded so only the marker glyph is colored. */
private fun mdListMarkerEnd(line: String, from: Int): Int {
    val n = line.length
    if (from + 1 < n && line[from] in "-*+" && line[from + 1] == ' ') return from + 1
    var j = from
    while (j < n && line[j].isDigit()) j++
    if (j > from && j < n && (line[j] == '.' || line[j] == ')') && j + 1 < n && line[j + 1] == ' ') return j + 1
    return -1
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

/**
 * Color a leading preprocessor directive (`#include <stdio.h>`, `#define FOO 1`) and answer where ordinary
 * code scanning should resume. Answers 0 when the line is not a directive, leaving the whole line to the
 * caller's main loop.
 *
 * The marker and the word after it are one keyword span, so `# include` (legal, and rare) colors like
 * `#include`. An angle-bracket header name is colored as a string because that is what it is: a quoted path
 * in the language's other spelling. Everything after it is ordinary code, which is what makes a macro body
 * color like the code it becomes.
 */
private fun scanDirective(line: String, prefix: String, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = 0
    while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
    if (!line.startsWith(prefix, i)) return 0
    val start = i
    i += prefix.length
    while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
    val wordStart = i
    while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
    // A bare marker with no directive after it: color the marker alone rather than swallowing the line.
    spans.add(LineSpan(start, if (i > wordStart) i else start + prefix.length, TokenType.KEYWORD))
    val directive = line.substring(wordStart, i)
    if (directive != "include" && directive != "include_next" && directive != "import") return i
    var j = i
    while (j < n && (line[j] == ' ' || line[j] == '\t')) j++
    if (j >= n || line[j] != '<') return i
    val close = line.indexOf('>', startIndex = j + 1)
    // An unclosed `<` is a header name still being typed: color to end of line rather than nothing, so it
    // does not flicker between operator-colored and string-colored on every keystroke.
    val end = if (close < 0) n else close + 1
    spans.add(LineSpan(j, end, TokenType.STRING))
    return end
}

private fun styleCodeLine(
    line: String,
    entryState: Int,
    keywords: Set<String> = JAVA_KEYWORDS,
    directivePrefix: String? = null,
): StyledLine {
    val n = line.length
    val spans = ArrayList<LineSpan>(8)
    var i = 0
    if (entryState == LexState.BLOCK_COMMENT || entryState == LexState.DOC_COMMENT) {
        // Both close on the same delimiter; the carried state is what remembers which one opened.
        val kind = if (entryState == LexState.DOC_COMMENT) TokenType.DOC_COMMENT else TokenType.COMMENT
        val close = line.indexOf("*/")
        if (close < 0) {
            if (n > 0) spans.add(LineSpan(0, n, kind))
            return StyledLine(spans, entryState)
        }
        spans.add(LineSpan(0, close + 2, kind))
        i = close + 2
    } else if (directivePrefix != null) {
        // Only at the head of the line, and only outside a carried block comment: `#` anywhere else in a
        // C-family language is an operator or lives inside a string, and both are the main loop's business.
        i = scanDirective(line, directivePrefix, spans)
    }
    while (i < n) {
        val c = line[i]
        when {
            c == '/' && i + 1 < n && line[i + 1] == '/' -> {
                spans.add(LineSpan(i, n, TokenType.COMMENT))
                return StyledLine(spans, LexState.CODE)
            }
            c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                // `/**` opens a doc comment, but `/**/` is an empty ordinary one, not an unterminated doc.
                val doc = i + 2 < n && line[i + 2] == '*' && !(i + 3 < n && line[i + 3] == '/')
                val kind = if (doc) TokenType.DOC_COMMENT else TokenType.COMMENT
                val close = line.indexOf("*/", startIndex = i + 2)
                if (close < 0) {
                    spans.add(LineSpan(i, n, kind))
                    return StyledLine(spans, if (doc) LexState.DOC_COMMENT else LexState.BLOCK_COMMENT)
                }
                spans.add(LineSpan(i, close + 2, kind))
                i = close + 2
            }
            c == '"' || c == '\'' -> {
                val start = i; i++
                while (i < n && line[i] != c) { if (line[i] == '\\') i++; i++ }
                if (i < n) i++
                val kind = if (c == '\'') TokenType.CHAR else TokenType.STRING
                spans.add(LineSpan(start, i.coerceAtMost(n), kind))
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
                    word in keywords -> keywordType(word)
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
            isPunct(c) -> { spans.add(LineSpan(i, i + 1, punctType(c))); i++ }
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
        LexState.BLOCK_COMMENT, LexState.DOC_COMMENT -> {
            val kind = if (entryState == LexState.DOC_COMMENT) TokenType.DOC_COMMENT else TokenType.COMMENT
            val close = line.indexOf("*/")
            if (close < 0) {
                if (n > 0) spans.add(LineSpan(0, n, kind))
                return StyledLine(spans, entryState)
            }
            spans.add(LineSpan(0, close + 2, kind))
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
                // KDoc opens on `/**`, except for `/**/`, which is an empty ordinary comment.
                val doc = i + 2 < n && line[i + 2] == '*' && !(i + 3 < n && line[i + 3] == '/')
                val kind = if (doc) TokenType.DOC_COMMENT else TokenType.COMMENT
                val close = line.indexOf("*/", startIndex = i + 2)
                if (close < 0) {
                    spans.add(LineSpan(i, n, kind))
                    return if (doc) LexState.DOC_COMMENT else LexState.BLOCK_COMMENT
                }
                spans.add(LineSpan(i, close + 2, kind)); i = close + 2
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
            isPunct(c) -> { spans.add(LineSpan(i, i + 1, punctType(c))); i++ }
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

/** Scan a raw-string (`"""…"""`) body from [from], with the current literal run starting at [litStart].
 *  Handles `${…}` / `$name` interpolation; raw strings have no escapes. Returns the index past the closing
 *  `"""`, or -1 if the raw string does not close on this line (the whole tail is string). */
private fun scanRawStringBody(line: String, from: Int, litStart: Int, spans: MutableList<LineSpan>): Int {
    val n = line.length
    var i = from
    var lit = litStart
    while (i < n) {
        val c = line[i]
        when {
            c == '"' && i + 2 < n && line[i + 1] == '"' && line[i + 2] == '"' -> {
                i += 3; spans.add(LineSpan(lit, i, TokenType.RAW_STRING)); return i
            }
            c == '$' && i + 1 < n && line[i + 1] == '{' -> {
                if (i > lit) spans.add(LineSpan(lit, i, TokenType.RAW_STRING))
                i = scanInterpolation(line, i, spans)
                lit = i
            }
            c == '$' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_') -> {
                if (i > lit) spans.add(LineSpan(lit, i, TokenType.RAW_STRING))
                i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                lit = i
            }
            else -> i++
        }
    }
    if (n > lit) spans.add(LineSpan(lit, n, TokenType.RAW_STRING))
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
        word in KOTLIN_KEYWORDS -> keywordType(word)
        // `value` is a keyword only in `value class` (a soft keyword) — as a plain identifier (`val value`,
        // `it.value`) it is far too common to color everywhere, so gate it on the following `class`.
        word == "value" && nextWordIs(line, i, "class") -> TokenType.KEYWORD_MODIFIER
        // `data` is a keyword only in `data class` / `data object` — as a plain identifier or a package
        // segment (`import com.example.data.Foo`, `val data`) it must NOT be colored, so gate it likewise.
        word == "data" && (nextWordIs(line, i, "class") || nextWordIs(line, i, "object")) ->
            TokenType.KEYWORD_MODIFIER
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

/** Whether the next word on [line] after [from] (skipping spaces/tabs) is exactly [word] — used to color a
 *  context-sensitive soft keyword (`value class`) as a keyword only in its keyword position. */
private fun nextWordIs(line: String, from: Int, word: String): Boolean {
    val n = line.length
    var j = from
    while (j < n && (line[j] == ' ' || line[j] == '\t')) j++
    if (j + word.length > n || line.regionMatches(j, word, 0, word.length).not()) return false
    val after = j + word.length
    return after >= n || !(line[after].isLetterOrDigit() || line[after] == '_')
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
    spans.add(LineSpan(start, end, TokenType.CHAR))
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
        LexState.XML_CDATA -> {
            val close = line.indexOf("]]>")
            if (close < 0) {
                if (n > 0) spans.add(LineSpan(0, n, TokenType.CDATA))
                return StyledLine(spans, LexState.XML_CDATA)
            }
            spans.add(LineSpan(0, close + 3, TokenType.CDATA))
            i = close + 3
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
            // `<![CDATA[ … ]]>`: delimiters and content alike, and it may run past the end of the line.
            c == '<' && line.startsWith("<![CDATA[", i) -> {
                val close = line.indexOf("]]>", startIndex = i + 9)
                if (close < 0) {
                    spans.add(LineSpan(i, n, TokenType.CDATA))
                    return StyledLine(spans, LexState.XML_CDATA)
                }
                spans.add(LineSpan(i, close + 3, TokenType.CDATA))
                i = close + 3
            }
            // A prolog or a declaration: `<?xml … ?>`, `<!DOCTYPE …>`. One run, because the inside of one
            // is not the element grammar and pretending otherwise colors it wrongly.
            c == '<' && i + 1 < n && (line[i + 1] == '?' || line[i + 1] == '!') -> {
                val start = i
                val close = if (line[i + 1] == '?') line.indexOf("?>", i + 2) else line.indexOf('>', i + 2)
                i = if (close < 0) n else close + (if (line[start + 1] == '?') 2 else 1)
                spans.add(LineSpan(start, i, TokenType.PROLOG))
            }
            c == '<' -> {
                // The delimiter and the name are separate runs: `<` is punctuation that happens to open a
                // tag, and a scheme that colors markup structure apart from its names needs both.
                val open = i; i++
                if (i < n && line[i] == '/') i++
                spans.add(LineSpan(open, i, TokenType.TAG_DELIMITER))
                i = scanXmlName(line, i, spans, TokenType.TYPE)
            }
            // `>` and `/>` close what the run above opened, and were left uncolored entirely.
            c == '>' -> { spans.add(LineSpan(i, i + 1, TokenType.TAG_DELIMITER)); i++ }
            c == '/' && i + 1 < n && line[i + 1] == '>' -> {
                spans.add(LineSpan(i, i + 2, TokenType.TAG_DELIMITER)); i += 2
            }
            // `&amp;` / `&#65;` — markup, not content, and invisible until now.
            c == '&' -> {
                val semi = line.indexOf(';', i + 1)
                val end = if (semi in (i + 1)..(i + 12)) semi + 1 else -1
                if (end < 0) i++ else { spans.add(LineSpan(i, end, TokenType.ENTITY)); i = end }
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
                val start = i
                var end = i
                while (end < n && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '-' || line[end] == ':')) end++
                var j = end
                while (j < n && line[j] == ' ') j++
                // Only a name followed by `=` is an attribute; anything else here is element content.
                if (j < n && line[j] == '=') scanXmlName(line, start, spans, TokenType.PROPERTY)
                i = end
            }
            else -> i++
        }
    }
    return StyledLine(spans, LexState.CODE)
}

/**
 * Emit a markup name from [start] as an optional namespace prefix plus a local name, and return the index
 * past it.
 *
 * `android:id` was one run, so the prefix could not be colored apart from the name it qualifies — which is
 * the distinction that makes an Android layout readable, since the prefix is the same on every line and the
 * name is the part being read. The colon goes with the delimiters: it is punctuation between two names.
 */
private fun scanXmlName(line: String, start: Int, spans: MutableList<LineSpan>, nameType: TokenType): Int {
    val n = line.length
    var i = start
    while (i < n && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '-' || line[i] == ':')) i++
    if (i == start) return i
    val colon = line.lastIndexOf(':', i - 1).takeIf { it >= start }
    if (colon == null || colon == start || colon == i - 1) {
        spans.add(LineSpan(start, i, nameType))
    } else {
        spans.add(LineSpan(start, colon, TokenType.NAMESPACE))
        spans.add(LineSpan(colon, colon + 1, TokenType.TAG_DELIMITER))
        spans.add(LineSpan(colon + 1, i, nameType))
    }
    return i
}
