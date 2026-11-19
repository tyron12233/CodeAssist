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

/**
 * A whole editor color scheme, listed in Settings → Editor Colors beside the IDE's presets.
 *
 * [json] is the format the IDE's own scheme editor exports (Editor Colors → Export), so the way to make one is
 * to design it in the IDE, export it, and ship the file in the plugin (a raw resource or an asset, read at
 * `contribute` time). The document is sparse: it names only what it overrides, and every entry it leaves out,
 * including any the IDE adds later, falls back the way it does in a user's scheme. Both the dark and the light
 * variant may be given; a scheme that sets only one leaves the other at the defaults.
 *
 * A contributed scheme is read-only, as the presets are: picking it applies it, and editing it makes the user
 * a copy of their own. It is listed only while the plugin is loaded, so turning the plugin off or uninstalling
 * it removes it, and a user who had it active goes back to the default scheme.
 *
 * Since SPI 3.4.0.
 */
class ColorScheme(
    /** Stable within this plugin (`"midnight"`): what a user's choice is remembered by. Changing it un-picks it. */
    val id: String,

    /** The name shown in the picker. Blank takes the name the [json] carries. */
    val name: String,

    /** The scheme document, as exported. A document the IDE cannot read is skipped and logged, not thrown. */
    val json: String,
)

/**
 * A whole app theme: the Material color roles of the IDE's interface, dark and light, offered under
 * Settings → Appearance → Accent after the IDE's own accents.
 *
 * [json] is Material Theme Builder's export (`material-theme.json`), so the way to make one is to design it
 * there (m3.material.io/theme-builder), export "Material Theme (JSON)", and ship the file. The builder writes
 * every role for `light` and `dark` under `schemes`, plus the `seed` it started from; its contrast variants
 * are ignored. A theme written by hand may name only some roles, or only a `seed`: what it leaves out is
 * generated from the seed, or taken from the IDE's default theme without one.
 *
 * The editor keeps its own color scheme (see [ColorScheme] and Settings → Editor Colors); this themes
 * everything around it. Applied only when the user picks it, and only while the plugin is loaded: turned off
 * or uninstalled, the IDE falls back to its default accent and picks the theme up again if it comes back.
 *
 * Since SPI 3.4.0.
 */
class AppTheme(
    /** Stable within this plugin: what the user's choice is remembered by. */
    val id: String,

    /** The name shown in the Accent list. Blank takes the document's `name`, else [id]. */
    val name: String,

    /** The theme document. One the IDE cannot read is skipped and logged, not thrown. */
    val json: String,
)
