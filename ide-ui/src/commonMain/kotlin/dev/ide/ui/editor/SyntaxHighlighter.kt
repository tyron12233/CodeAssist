package dev.ide.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import dev.ide.ui.theme.SyntaxColors

enum class CodeLanguage { Java, Kotlin, Xml, Plain }

fun languageFor(fileName: String): CodeLanguage = when {
    fileName.endsWith(".java") -> CodeLanguage.Java
    fileName.endsWith(".kt") || fileName.endsWith(".kts") -> CodeLanguage.Kotlin
    fileName.endsWith(".xml") -> CodeLanguage.Xml
    else -> CodeLanguage.Plain
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

private fun isPunct(c: Char) = c in "{}()[];,.<>=+-*/%&|!?:^~@"

/** Single-pass scanner → colored [AnnotatedString]. Backend-free; good enough for editor highlighting. */
fun highlight(text: String, language: CodeLanguage, syntax: SyntaxColors): AnnotatedString {
    if (language == CodeLanguage.Xml) return highlightXml(text, syntax)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = syntax.default), 0, text.length)
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val start = i; i += 2
                    while (i < n && text[i] != '\n') i++
                    addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val start = i; i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                    addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
                }
                c == '"' -> {
                    val start = i; i++
                    while (i < n && text[i] != '"' && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                    if (i < n && text[i] == '"') i++
                    addStyle(SpanStyle(color = syntax.string), start, i)
                }
                c == '\'' -> {
                    val start = i; i++
                    while (i < n && text[i] != '\'' && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                    if (i < n && text[i] == '\'') i++
                    addStyle(SpanStyle(color = syntax.string), start, i)
                }
                c.isDigit() -> {
                    val start = i; i++
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) i++
                    addStyle(SpanStyle(color = syntax.number), start, i)
                }
                c == '@' -> {
                    val start = i; i++
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    addStyle(SpanStyle(color = syntax.annotation), start, i)
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i; i++
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                    val word = text.substring(start, i)
                    val color = when {
                        word in JAVA_KEYWORDS -> syntax.keyword
                        else -> {
                            var j = i
                            while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
                            when {
                                j < n && text[j] == '(' -> syntax.func
                                word[0].isUpperCase() -> syntax.type
                                else -> null
                            }
                        }
                    }
                    if (color != null) addStyle(SpanStyle(color = color), start, i)
                }
                isPunct(c) -> { addStyle(SpanStyle(color = syntax.punctuation), i, i + 1); i++ }
                else -> i++
            }
        }
    }
}

private fun highlightXml(text: String, syntax: SyntaxColors): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(SpanStyle(color = syntax.default), 0, text.length)
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            c == '<' && i + 3 < n && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-' -> {
                val start = i; i += 4
                while (i < n && !(text[i] == '-' && i + 2 < n && text[i + 1] == '-' && text[i + 2] == '>')) i++
                i = (i + 3).coerceAtMost(n)
                addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
            }
            c == '<' -> {
                val start = i; i++
                if (i < n && (text[i] == '/' || text[i] == '?')) i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == ':')) i++
                addStyle(SpanStyle(color = syntax.type), start, i)
            }
            c == '"' -> {
                val start = i; i++
                while (i < n && text[i] != '"') i++
                if (i < n) i++
                addStyle(SpanStyle(color = syntax.string), start, i)
            }
            c.isLetter() -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == ':')) i++
                var j = i
                while (j < n && text[j] == ' ') j++
                if (j < n && text[j] == '=') addStyle(SpanStyle(color = syntax.property), start, i)
            }
            else -> i++
        }
    }
}
