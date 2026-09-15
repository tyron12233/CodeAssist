package dev.ide.ui.ext

import dev.ide.ui.concurrent.UiLock

/**
 * How the editor treats a language as *text*, independent of whether any `LanguageBackend` parses it.
 *
 * The editor needs answers to a handful of questions for every open file: which words are keywords, how a
 * comment is written, whether Enter should indent like a brace language, whether typing `<` should close a
 * tag. Those used to be a closed `when` over an enum in the editor, so a language the IDE did not ship could
 * not be colored or commented no matter what it registered. A profile makes them data, so the answers arrive
 * with the language.
 *
 * A profile is deliberately NOT a parser. It is the cheap, synchronous layer that runs per line while typing;
 * a language wanting real structure contributes a `dev.ide.lang.LanguageBackend`, whose semantic highlighting
 * and folding then layer on top of this.
 */
class EditorLanguageProfile(
    /** Matches the `dev.ide.lang.LanguageId` the engine routes this language by, e.g. `"mylang"`. */
    val id: String,

    /** File-name suffixes this profile claims, e.g. `listOf(".mylang")`. Matched with `endsWith`. */
    val suffixes: List<String>,

    /** Which shared scanner colors this language, and which typing behaviour it gets. */
    val syntax: SyntaxFamily = SyntaxFamily.C_FAMILY,

    /** Words colored as keywords. Only consulted by [SyntaxFamily.C_FAMILY]; capitalized words are already
     *  colored as types by the shared scanner, so type names do not belong here. */
    val keywords: Set<String> = emptySet(),

    /** Line-comment marker, or null when the language has none (XML). Drives Toggle Comment. */
    val lineComment: String? = null,

    /** Block-comment delimiters, or null when the language has none. Both must be set to be used. */
    val blockCommentOpen: String? = null,
    val blockCommentClose: String? = null,

    /**
     * Marks a line that is a preprocessor directive rather than code (`"#"` for C and C++), or null for a
     * language that has no such notion, which is every language the IDE itself ships.
     *
     * Only consulted by [SyntaxFamily.C_FAMILY]. A line whose first non-blank text starts with this is
     * colored directive-first: the marker and the word after it read as a keyword, and a `<…>` following an
     * include-shaped directive reads as a string, which is what makes `#include <stdio.h>` look like the
     * import line it is instead of a run of operators. The rest of the line is scanned as ordinary code, so a
     * macro body still colors.
     */
    val directivePrefix: String? = null,

    /** Lowest wins when two profiles claim the same suffix, matching `FileTypeMapping.order`. */
    val order: Int = 1000,
) {
    fun matches(fileName: String): Boolean = suffixes.any { fileName.endsWith(it) }
}

/**
 * The shared scanners the editor implements. A language picks the one whose lexical shape it has; the family
 * decides coloring, auto-closing, and the Enter handler together, because those follow from the same shape
 * (a brace language wants smart indent AND `{}` auto-close AND `//` comments).
 */
enum class SyntaxFamily {
    /** Braces, `//` and block comments, quoted strings, numbers, `@annotations`. Java, Kotlin, AIDL. */
    C_FAMILY,

    /** Tags, attributes, entities; `<!-- -->` comments; tag auto-closing. */
    XML,

    /** Whole-line `#` comments, no strings, no nesting. ProGuard keep rules. */
    HASH_COMMENT,

    /** Headings, fences, lists, inline code. */
    MARKDOWN,

    /** No coloring and no typing assistance: the file is text. */
    PLAIN,
}

/**
 * The process-global registry of [EditorLanguageProfile]s, resolved by file name.
 *
 * Built-in profiles are registered by the shell for the languages it ships; a plugin adds its own through
 * `UiContributionScope.editorLanguage`. A later registration for the same suffix wins on a lower
 * [EditorLanguageProfile.order], so a plugin can also override how a built-in language is colored.
 */
object EditorLanguageRegistry {
    private val profiles = ArrayList<EditorLanguageProfile>()
    private val lock = UiLock()

    fun register(profile: EditorLanguageProfile): Registration {
        lock.withLock {
            profiles.add(profile)
            profiles.sortBy { it.order }
        }
        return Registration { lock.withLock { profiles.remove(profile) } }
    }

    /** The profile claiming [fileName] (lowest order first), or null when nothing claims it. */
    fun forFile(fileName: String): EditorLanguageProfile? =
        lock.withLock { profiles.firstOrNull { it.matches(fileName) } }

    /** The profile with [id], or null. */
    fun forId(id: String): EditorLanguageProfile? =
        lock.withLock { profiles.firstOrNull { it.id == id } }

    /** Every registered profile, in resolution order. */
    fun all(): List<EditorLanguageProfile> = lock.withLock { profiles.toList() }
}
