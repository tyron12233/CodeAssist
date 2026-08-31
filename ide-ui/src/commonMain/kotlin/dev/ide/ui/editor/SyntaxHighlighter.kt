package dev.ide.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import dev.ide.ui.editor.core.AIDL_KEYWORDS
import dev.ide.ui.editor.core.JAVA_KEYWORDS
import dev.ide.ui.editor.core.KOTLIN_KEYWORDS
import dev.ide.ui.ext.EditorLanguageProfile
import dev.ide.ui.ext.EditorLanguageRegistry
import dev.ide.ui.ext.SyntaxFamily
import dev.ide.ui.theme.SyntaxColors

/**
 * The editor's view of a file's language: everything the text layer needs (which scanner colors it, its
 * keywords, how a comment is written), carried as one [EditorLanguageProfile].
 *
 * This was a closed enum, which made the editor the one part of the platform a language could not extend:
 * a plugin could register a `LanguageBackend`, an analyzer, indexes and a build task, and its files still
 * opened uncolored with no comment toggle. It is now a value over a profile, so the built-ins below are just
 * the profiles the shell ships and [BUILT_IN_EDITOR_LANGUAGES] registers, and a plugin's profile resolves
 * through exactly the same path.
 *
 * The built-in names are kept as companion values (`CodeLanguage.Java`, …) so they read and compare as they
 * always did; identity is the profile's [EditorLanguageProfile.id].
 */
class CodeLanguage(val profile: EditorLanguageProfile) {
    val id: String get() = profile.id
    val syntax: SyntaxFamily get() = profile.syntax

    override fun equals(other: Any?): Boolean = other is CodeLanguage && other.profile.id == profile.id
    override fun hashCode(): Int = profile.id.hashCode()
    override fun toString(): String = profile.id

    companion object {
        val Java = CodeLanguage(
            EditorLanguageProfile(
                id = "java", suffixes = listOf(".java"), syntax = SyntaxFamily.C_FAMILY,
                keywords = JAVA_KEYWORDS, lineComment = "//",
                blockCommentOpen = "/*", blockCommentClose = "*/",
            )
        )
        val Kotlin = CodeLanguage(
            EditorLanguageProfile(
                id = "kotlin", suffixes = listOf(".kt", ".kts"), syntax = SyntaxFamily.C_FAMILY,
                keywords = KOTLIN_KEYWORDS, lineComment = "//",
                blockCommentOpen = "/*", blockCommentClose = "*/",
            )
        )
        val Xml = CodeLanguage(
            EditorLanguageProfile(
                id = "xml", suffixes = listOf(".xml"), syntax = SyntaxFamily.XML,
                blockCommentOpen = "<!--", blockCommentClose = "-->",
            )
        )
        // AIDL: a C-family brace language, styled by the shared code scanner over its own keyword set.
        val Aidl = CodeLanguage(
            EditorLanguageProfile(
                id = "aidl", suffixes = listOf(".aidl"), syntax = SyntaxFamily.C_FAMILY,
                keywords = AIDL_KEYWORDS, lineComment = "//",
                blockCommentOpen = "/*", blockCommentClose = "*/",
            )
        )
        // ProGuard/R8 keep-rule files: `proguard-rules.pro`, `consumer-rules.pro`, any `*.pro`.
        val Proguard = CodeLanguage(
            EditorLanguageProfile(
                id = "proguard", suffixes = listOf(".pro"), syntax = SyntaxFamily.HASH_COMMENT,
                lineComment = "#",
            )
        )
        val Markdown = CodeLanguage(
            EditorLanguageProfile(
                id = "markdown", suffixes = listOf(".md", ".markdown"), syntax = SyntaxFamily.MARKDOWN,
                blockCommentOpen = "<!--", blockCommentClose = "-->",
            )
        )
        val Plain = CodeLanguage(
            EditorLanguageProfile(id = "text", suffixes = emptyList(), syntax = SyntaxFamily.PLAIN)
        )
    }
}

/** The profiles the shell ships. Registered once into [EditorLanguageRegistry] by [ensureEditorLanguages]. */
private val BUILT_IN_EDITOR_LANGUAGES: List<CodeLanguage> =
    listOf(CodeLanguage.Java, CodeLanguage.Kotlin, CodeLanguage.Xml, CodeLanguage.Aidl,
        CodeLanguage.Proguard, CodeLanguage.Markdown)

private var builtInsRegistered = false

/** Put the built-in profiles in the registry once, so [languageFor] resolves them by the same lookup a
 *  plugin's profile goes through (rather than a hardcoded branch the plugin path can drift from). */
private fun ensureEditorLanguages() {
    if (builtInsRegistered) return
    builtInsRegistered = true
    BUILT_IN_EDITOR_LANGUAGES.forEach { EditorLanguageRegistry.register(it.profile) }
}

/**
 * The editor language for [fileName], resolved through [EditorLanguageRegistry]: the built-in profiles plus
 * whatever plugins contributed. An unclaimed name is [CodeLanguage.Plain] (edited as text), never guessed
 * into another language.
 */
fun languageFor(fileName: String): CodeLanguage {
    ensureEditorLanguages()
    val profile = EditorLanguageRegistry.forFile(fileName) ?: return CodeLanguage.Plain
    // Reuse the built-in instance when the registry resolved a built-in, so identity comparisons in the
    // editor keep matching the constants.
    return BUILT_IN_EDITOR_LANGUAGES.firstOrNull { it.profile === profile } ?: CodeLanguage(profile)
}

/** Keywords for the legacy whole-document [highlight] scanner, which colors Java and Kotlin with one
 *  pass and so unions both sets. The per-line lexer uses the language's own set from its profile. */
private val LEGACY_SCANNER_KEYWORDS = setOf(
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
    if (language == CodeLanguage.Proguard) return highlightProguard(text, syntax)
    // Markdown has no whole-document scanner (the active editor uses the incremental styleMarkdownLine); the
    // legacy scanner just renders it uncolored rather than mis-tokenizing prose as Java.
    if (language == CodeLanguage.Markdown) return AnnotatedString(text)
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
                        word in LEGACY_SCANNER_KEYWORDS -> syntax.keyword
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

/**
 * ProGuard/R8 keep-rule files: `#` line comments, `-directives` (keyword), `@`-annotations, quoted
 * strings, and capitalised class names as types. Line-based and tolerant — no real grammar needed.
 */
private fun highlightProguard(text: String, syntax: SyntaxColors): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(SpanStyle(color = syntax.default), 0, text.length)
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            c == '#' -> {
                val start = i
                while (i < n && text[i] != '\n') i++
                addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
            }
            // A directive like `-keep`, `-dontwarn`, `-keepclassmembers`.
            c == '-' && (i == 0 || text[i - 1] == '\n' || text[i - 1] == ' ' || text[i - 1] == '\t') -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                addStyle(SpanStyle(color = syntax.keyword), start, i)
            }
            c == '@' -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                addStyle(SpanStyle(color = syntax.annotation), start, i)
            }
            c == '"' || c == '\'' -> {
                val quote = c; val start = i; i++
                while (i < n && text[i] != quote && text[i] != '\n') i++
                if (i < n && text[i] == quote) i++
                addStyle(SpanStyle(color = syntax.string), start, i)
            }
            c.isLetter() || c == '_' -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.' || text[i] == '$')) i++
                // Class-name patterns read as types; keep-rule member keywords stay default.
                if (text[start].isUpperCase()) addStyle(SpanStyle(color = syntax.type), start, i)
            }
            c in "{}()[];,*" -> { addStyle(SpanStyle(color = syntax.punctuation), i, i + 1); i++ }
            else -> i++
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
