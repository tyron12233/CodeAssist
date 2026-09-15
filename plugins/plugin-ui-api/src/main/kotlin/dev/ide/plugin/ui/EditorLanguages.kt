// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.ui

/**
 * The shared scanners the editor colors text with. A language picks the one whose lexical shape it has, and
 * the choice decides coloring, auto-closing and the Enter handler together, because those all follow from the
 * same shape: a brace language wants smart indent AND `{}` auto-close AND `//` comments.
 *
 * This is deliberately a small closed set rather than a way to plug in a lexer. It is the layer that runs
 * synchronously on every keystroke, on every visible line, so it is one of the few places in the editor a
 * plugin's own code must not be. A language that needs more structure than a family gives contributes a
 * `dev.ide.lang.LanguageBackend`, whose semantic highlighting then layers over this and can color anything.
 */
enum class SyntaxStyle {
    /** Braces, line and block comments, quoted strings, numbers, `@annotations`. Java, Kotlin, C, C++. */
    C_FAMILY,

    /** Tags, attributes, entities; `<!-- -->` comments; tag auto-closing. */
    XML,

    /** Whole-line `#` comments, no strings, no nesting. */
    HASH_COMMENT,

    /** Headings, fences, lists, inline code. */
    MARKDOWN,

    /** No coloring and no typing assistance: the file is text. */
    PLAIN,
}

/**
 * How the editor treats a language as *text*: which words are keywords, how a comment is written, whether
 * Enter indents like a brace language, whether typing a quote closes it.
 *
 * Registering one is what stops a plugin's files opening as grey text. It is independent of, and worth
 * contributing alongside, a `dev.ide.lang.LanguageBackend`: the backend's semantic highlighting is accurate
 * but asynchronous and debounced, so without a profile there is no coloring at all while the user types, and
 * no Toggle Comment, no auto-closing brackets and no smart indent at any time.
 *
 * A profile is NOT a parser and deliberately cannot be one. See [SyntaxStyle].
 *
 * ```
 * ui.editorLanguage(
 *     EditorLanguage(
 *         id = "cpp",
 *         suffixes = listOf(".cpp", ".cc", ".h", ".hpp"),
 *         syntax = SyntaxStyle.C_FAMILY,
 *         keywords = CPP_KEYWORDS,
 *         lineComment = "//",
 *         blockCommentOpen = SLASH_STAR, blockCommentClose = STAR_SLASH,
 *         directivePrefix = "#",
 *     )
 * )
 * ```
 */
class EditorLanguage(
    /**
     * Matches the `dev.ide.lang.LanguageId` the engine routes this language by, so one language is one id
     * across the editor and the engine. A plugin that also contributes a `platform.fileType` mapping must
     * name the same id here.
     */
    val id: String,

    /** File-name suffixes this profile claims, e.g. `listOf(".cpp", ".h")`. Matched with `endsWith`. */
    val suffixes: List<String>,

    /** Which shared scanner colors this language, and which typing behaviour it gets. */
    val syntax: SyntaxStyle = SyntaxStyle.C_FAMILY,

    /**
     * Words colored as keywords. Only consulted by [SyntaxStyle.C_FAMILY]. Capitalized words already color as
     * types and a word followed by `(` already colors as a call, so neither type names nor function names
     * belong here.
     */
    val keywords: Set<String> = emptySet(),

    /** Line-comment marker, or null when the language has none. Drives Toggle Comment. */
    val lineComment: String? = null,

    /** Block-comment delimiters, or null when the language has none. Both must be set to be used. */
    val blockCommentOpen: String? = null,
    val blockCommentClose: String? = null,

    /**
     * Marks a line that is a preprocessor directive rather than code (`"#"` for C and C++), or null for a
     * language with no such notion. Only consulted by [SyntaxStyle.C_FAMILY].
     *
     * A line whose first non-blank text starts with this colors directive-first: the marker and the word
     * after it read as a keyword, and a `<…>` after an include-shaped directive reads as a string, so
     * `#include <stdio.h>` looks like the import line it is. The rest of the line scans as ordinary code.
     */
    val directivePrefix: String? = null,

    /**
     * Lowest wins when two profiles claim the same suffix. The IDE's own profiles sit at the default, so a
     * plugin that means to take a built-in language's coloring over must pass something lower, and one that
     * merely adds a language need not think about it.
     */
    val order: Int = 1000,
)

/**
 * An icon for the file types a plugin adds, shown in the project tree, on editor tabs and in breadcrumbs.
 *
 * Registering one is what stops a plugin's files showing as the generic grey document. The IDE's icon
 * registry is keyed by an opaque id, and an id nothing has registered art for resolves to that fallback, so
 * naming an id is not enough on its own: this carries the art with it.
 *
 * ```
 * ui.fileIcon(FileIcon(id = "cpp", suffixes = listOf(".cpp", ".cc"), badge = "C++", color = 0xFF8ABEB7))
 * ```
 */
class FileIcon(
    /**
     * The registry id, which is also what a `dev.ide.model.FileIconProvider` returns for the project tree.
     * The tree asks the ENGINE for an icon and tabs resolve the name themselves, so a plugin that wants both
     * registers this and contributes that provider; the id is what ties the two together.
     */
    val id: String,

    /** File-name suffixes this icon claims, matched with `endsWith`. */
    val suffixes: List<String>,

    /**
     * One to three characters drawn as a colored badge, in the style the IDE uses for `J`, `K` and `R8`.
     * Longer text is drawn smaller and stops being legible, which is why this is a badge and not a label.
     */
    val badge: String,

    /** `0xAARRGGBB`. Opaque colors only; a transparent badge reads as a rendering fault. */
    val color: Long,
)
