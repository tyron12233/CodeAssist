package dev.codeassist.ndk

/**
 * Finds a C or C++ file's preamble: the run of `#include`s and other directives at its top, before the first
 * line of real code.
 *
 * That run is where nearly all of a parse goes. `#include <vector>` alone is tens of thousands of lines of
 * libc++, and every completion and every error check used to read all of them again for a file whose own
 * code is a few hundred lines. Compiled once into a precompiled header, the preamble is loaded instead of
 * re-parsed, and clang is told to skip those bytes of the buffer (`-preamble-bytes`). On the emulator that
 * took completion on a file including six standard headers from ~190 ms to ~21 ms, and an error check from
 * ~210 ms to ~50 ms. clangd works the same way; this is the same idea driven from the command line.
 *
 * The cut has to be one clang can resume after, so it is only ever made:
 *  - at the start of a line, right after a directive's newline, because that is what `-preamble-bytes=N,1`
 *    promises clang;
 *  - with every `#if` closed. A precompiled header cannot end inside a conditional, so a file guarded by
 *    `#ifndef FOO_H` gets the directives BEFORE the guard (often none) rather than a broken cut.
 *
 * A preamble with no `#include` in it is reported as none: precompiling a handful of `#define`s costs more
 * than it saves.
 */
internal object ClangPreamble {

    /**
     * The offset (in chars) where the preamble of [text] ends, or 0 when it has none worth precompiling.
     * Always the start of a line.
     */
    fun end(text: CharSequence): Int {
        var i = 0
        var depth = 0
        var sawInclude = false
        var safeEnd = 0
        var safeHasInclude = false

        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000C' || c == '\u000B' -> i++
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> i = lineCommentEnd(text, i)
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val close = indexOf(text, "*/", i + 2)
                    if (close < 0) break // an unterminated comment: nothing after it is safe
                    i = close + 2
                }
                c == '#' -> {
                    val name = directiveName(text, i + 1)
                    when (name) {
                        "if", "ifdef", "ifndef" -> depth++
                        "endif" -> if (--depth < 0) break
                        "include", "include_next", "import" -> sawInclude = true
                    }
                    i = directiveEnd(text, i + 1)
                    // Only a directive that ended in a newline is a place to cut: one running to the end of
                    // the file would leave the cut mid-line, which is not what -preamble-bytes=N,1 says.
                    if (i <= text.length && i > 0 && text[i - 1] == '\n' && depth == 0) {
                        safeEnd = i
                        safeHasInclude = sawInclude
                    }
                }
                else -> break // the first token of real code
            }
        }
        return if (safeHasInclude) safeEnd else 0
    }

    /** How many UTF-8 bytes the first [chars] chars of [text] take, which is the unit `-preamble-bytes` counts. */
    fun utf8Length(text: CharSequence, chars: Int): Int {
        var bytes = 0
        var i = 0
        while (i < chars) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < chars && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /** The directive's name after the `#`: spaces and tabs may sit between them. Empty for a null directive. */
    private fun directiveName(text: CharSequence, from: Int): String {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        val start = i
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return text.substring(start, i)
    }

    /**
     * Just past the newline that ends the directive starting at [from], or the end of the text.
     *
     * A backslash before the newline continues the directive, a block comment may run across lines inside
     * it, and a string or character literal may contain comment markers that are not comments
     * (`#define URL "http://x"`).
     */
    private fun directiveEnd(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] == '\n' -> i += 2
                c == '\\' && i + 2 < text.length && text[i + 1] == '\r' && text[i + 2] == '\n' -> i += 3
                c == '\n' -> return i + 1
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> return lineCommentEnd(text, i)
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val close = indexOf(text, "*/", i + 2)
                    if (close < 0) return text.length
                    i = close + 2
                }
                c == '"' || c == '\'' -> i = literalEnd(text, i)
                else -> i++
            }
        }
        return text.length
    }

    /** Just past the newline ending the `//` comment at [from], honouring a backslash continuation. */
    private fun lineCommentEnd(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] == '\n' -> i += 2
                c == '\\' && i + 2 < text.length && text[i + 1] == '\r' && text[i + 2] == '\n' -> i += 3
                c == '\n' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    /** Just past the literal opened by the quote at [from]; an unterminated one ends at its line's newline. */
    private fun literalEnd(text: CharSequence, from: Int): Int {
        val quote = text[from]
        var i = from + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> i += 2
                c == quote -> return i + 1
                c == '\n' -> return i
                else -> i++
            }
        }
        return text.length
    }

    private fun indexOf(text: CharSequence, needle: String, from: Int): Int {
        var i = from
        outer@ while (i + needle.length <= text.length) {
            for (k in needle.indices) if (text[i + k] != needle[k]) { i++; continue@outer }
            return i
        }
        return -1
    }
}
