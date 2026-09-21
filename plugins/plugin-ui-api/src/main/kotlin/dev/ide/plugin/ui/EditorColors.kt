// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.ui

/**
 * A colorable thing in the editor: one entry in the user's color scheme.
 *
 * The editor's colors are a user-editable scheme rather than a fixed palette, and the set of things a
 * scheme can color is open. Registering one of these puts the construct in Settings → Editor Colors, under
 * this plugin's own group, with a color and a font style the user can set and a scheme can carry.
 *
 * It matters because the alternative is borrowing. A language contributed by a plugin is colored by one of
 * the [SyntaxStyle] families, whose token types are named for a brace language, so a directive, a pragma or
 * a shader qualifier has to arrive as somebody else's "keyword" — indistinguishable from an actual keyword
 * and impossible for the user to recolor on its own.
 *
 * ```
 * ui.colorAttribute(
 *     ColorAttribute(
 *         key = "glsl.qualifier",
 *         title = "Storage qualifier",
 *         group = "GLSL",
 *         parent = ColorAttributeKeys.KEYWORD,
 *         darkColor = 0xFF4EC9B0, lightColor = 0xFF267F6E, bold = true,
 *     )
 * )
 * ui.editorLanguage(
 *     EditorLanguage(
 *         id = "glsl", suffixes = listOf(".glsl"), syntax = SyntaxStyle.C_FAMILY,
 *         tokenColorKeys = mapOf("ANNOTATION" to "glsl.qualifier"),
 *     )
 * )
 * ```
 *
 * ### Why it still looks right in a scheme that never heard of it
 *
 * Two things, and both are why the defaults below are worth filling in rather than leaving to the host.
 *
 * [parent] is the entry this one falls back to, field by field, for anything it and the active scheme both
 * leave unset. So a user who recolors `keyword` in their own scheme moves a qualifier that declares
 * `parent = KEYWORD` along with it, without having heard of GLSL.
 *
 * [darkColor]/[lightColor] and the font flags are this attribute's own opinion, used when the scheme has
 * none. A scheme records only what the user overrode, so the schemes that already exist say nothing about
 * an attribute added later — and it is these defaults, not a fallback to grey, that they render with.
 */
class ColorAttribute(
    /**
     * A stable id, namespaced to the language or plugin: `"glsl.qualifier"`, not `"qualifier"`. It is what
     * a scheme records an override against and what [EditorLanguage.tokenColorKeys] points at, so changing
     * it later silently drops every user's customization of this entry.
     *
     * It is also the name a `dev.ide.lang.LanguageBackend`'s semantic highlighting can emit as its
     * highlight kind. That is the way to colour a construct the shared scanners do not distinguish at all
     * — a C preprocessor directive is the same token type as any other keyword, so no token mapping can
     * separate them, and the analyzer naming it is what can.
     */
    val key: String,

    /** The name shown in Settings, in sentence case: `"Storage qualifier"`. */
    val title: String,

    /**
     * The section it appears under in Settings, shown as written. Use the language's name (`"GLSL"`).
     *
     * Matched by exact string, and a name nothing else uses gets a section of its own, after the IDE's.
     * Two attributes naming the same group share a section, which is the way to keep a language's entries
     * together.
     */
    val group: String,

    /**
     * The key this falls back to — see the note above. Null means no fallback, which is right only for an
     * attribute that is genuinely unlike everything else; anything nameable as a kind of keyword, type,
     * function, string or comment should say so. [ColorAttributeKeys] lists the built-ins worth pointing at.
     */
    val parent: String? = null,

    /** `0xAARRGGBB` in dark mode, or null to take [parent]'s color. */
    val darkColor: Long? = null,

    /** `0xAARRGGBB` in light mode, or null to take [parent]'s color. A dark-only default reads as a bug on
     *  a light theme, so set both or neither. */
    val lightColor: Long? = null,

    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
)

/**
 * The attribute keys the IDE itself registers, for use as an [ColorAttribute.parent].
 *
 * Only the ones a contributed language plausibly falls back to are listed. The full set (the editor's own
 * chrome, the per-language groups) is an implementation detail of the host's color model and is not part of
 * this SPI: naming one of those here would freeze it.
 */
object ColorAttributeKeys {
    /** Default text. The root: everything falls back here in the end. */
    const val TEXT: String = "text"
    const val KEYWORD: String = "keyword"
    const val STRING: String = "string"
    const val STRING_ESCAPE: String = "string.escape"
    const val NUMBER: String = "number"
    const val COMMENT: String = "comment"
    const val ANNOTATION: String = "annotation"
    const val FUNCTION: String = "function"
    const val TYPE: String = "type"
    const val PROPERTY: String = "property"
    const val VARIABLE: String = "variable"
    const val CONSTANT: String = "constant"
    const val LABEL: String = "label"
    const val PUNCTUATION: String = "punctuation"
    const val NAMESPACE: String = "namespace"
}
