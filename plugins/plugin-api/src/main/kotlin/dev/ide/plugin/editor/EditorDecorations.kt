// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.editor

import dev.ide.platform.ExtensionPoint

/**
 * Marks a plugin puts on the text of an open file: a tinted range, a glyph in the gutter, a hint inlined
 * between two characters.
 *
 * This is the layer for a plugin that decorates a file without owning its language. Semantic highlighting,
 * folding and type hints already arrive from a `dev.ide.lang.LanguageBackend`, which means a plugin gets
 * them only by implementing a whole language; a coverage tint, a version-control change bar, a bookmark, a
 * "this test passed" glyph or a TODO marker belong to none of them and apply to files in every language.
 *
 * A provider is pulled, not pushed. The editor's highlighting daemon calls it on the same debounced pass run
 * as the language passes, so a provider needs no channel into the UI, is cancelled when the user types
 * again, and cannot hold the editor open. It also means a provider must be cheap and must not block: it runs
 * on the engine's worker alongside analysis.
 *
 * The whole contribution is data. Colors are named roles the host resolves against the active theme rather
 * than literal values, because a literal that reads well on the dark theme usually does not on the light
 * one, and a plugin has no way to know which is active. When a plugin genuinely owns its colors (a blame
 * heatmap, a coverage gradient) the answer is the UI tier's painter, not a color on this type.
 */
interface EditorDecorationProvider {
    /** Identity for attribution and for the host to key its per-provider caches on. */
    val id: String

    /**
     * Whether this provider has anything to say about [ctx]'s file. Checked before [decorate], so a provider
     * scoped to one language or one directory costs nothing on every other file. A provider that throws here
     * is skipped rather than allowed to break the pass.
     */
    fun appliesTo(ctx: EditorDecorationContext): Boolean = true

    /**
     * The marks for [ctx]'s file, as of the text it carries.
     *
     * Suspending so the pass can cancel it: the caller abandons the result the moment a newer edit arrives,
     * and a provider that reaches the index or the file system should be interruptible at those points.
     * Offsets are against [EditorDecorationContext.text], not against what is on disk.
     */
    suspend fun decorate(ctx: EditorDecorationContext): EditorDecorations
}

/** The file a provider is being asked about: its path, its live buffer, and the language routing it. */
interface EditorDecorationContext {
    /** Workspace-relative or absolute path of the open file, as the editor knows it. */
    val path: String

    /** The live buffer, which may differ from disk. Offsets in the result are against this text. */
    val text: String

    /** The `dev.ide.lang.LanguageId` the file routes to, or null when nothing claims it. */
    val languageId: String?
}

/**
 * One provider's answer for one file. Empty lists are the normal case: a provider with nothing to say for a
 * file returns [EMPTY] rather than being unregistered.
 */
class EditorDecorations(
    val ranges: List<TextDecoration> = emptyList(),
    val gutter: List<GutterMark> = emptyList(),
    val inlays: List<EditorInlay> = emptyList(),
) {
    val isEmpty: Boolean get() = ranges.isEmpty() && gutter.isEmpty() && inlays.isEmpty()

    companion object {
        val EMPTY = EditorDecorations()
    }
}

/**
 * A tinted span of text. `[startOffset, endOffset)` is a half-open document range; a range that crosses a
 * line is applied to each line it covers, and one that falls outside the buffer is dropped rather than
 * clamped (a stale offset is a bug in the provider, and a mark on the wrong text is worse than none).
 */
class TextDecoration(
    val startOffset: Int,
    val endOffset: Int,
    val style: DecorationStyle,
    val tint: DecorationTint,

    /** Shown when the user hovers or long-presses the range. Null draws the mark without one. */
    val tooltip: String? = null,

    /**
     * Draw order among decorations covering the same text, lowest first, so the highest wins where they
     * conflict. Marks from different providers share one space, so this is advisory between plugins and
     * decisive only within one.
     */
    val order: Int = 0,
)

/** How a [TextDecoration] marks its range. */
enum class DecorationStyle {
    /** Fills the range's background, behind the text and behind the selection. For a coverage or diff tint. */
    Background,

    /** A straight line under the range. */
    Underline,

    /** A wavy line under the range, the shape the editor already uses for diagnostics. */
    WavyUnderline,

    /** A dotted line under the range, for something weaker than a diagnostic (a spelling hint). */
    DottedUnderline,

    /** A line through the range, for text that is dead or deprecated. */
    Strikethrough,

    /** Recolors the text itself. Layered over lexical and semantic coloring, so it wins over both. */
    Foreground,

    /** A one-pixel outline around the range, for something identified rather than diagnosed. */
    Box,
}

/**
 * The color roles a decoration can ask for. The host resolves each against the active theme, so a mark stays
 * legible in both, and a theme a plugin has never heard of colors it correctly.
 *
 * The set is closed on purpose. An open string (the shape [dev.ide.plugin.action.IdeAction] icons and
 * semantic-highlight kinds use) would let a plugin name a role no theme defines, and the honest fallback for
 * an unknown color role is to draw nothing, which reads as the feature being broken.
 */
enum class DecorationTint {
    /** The theme's accent. For something the plugin wants looked at without implying a problem. */
    Accent,

    /** Informational, the tint the editor gives an Info diagnostic. */
    Info,

    /** Something passing or covered. */
    Success,

    /** Something suspicious, the tint of a Warning diagnostic. */
    Warning,

    /** Something wrong, the tint of an Error diagnostic. */
    Error,

    /** Low-contrast, for a mark that should recede until looked for. */
    Muted,

    /** Version-control roles, so a change bar matches the colors the rest of the IDE uses for a diff. */
    Added,
    Removed,
    Modified,
}

/**
 * A glyph on one line of the gutter, beside the line number.
 *
 * [line] is a zero-based document line. The gutter is narrow and host-drawn, so a mark is an icon id from
 * the IDE's own registry rather than an image: a plugin has no `Context` of its own to load a drawable from.
 * Several marks on one line are collapsed to the highest [order]; the rest stay reachable through the
 * tooltip, which lists them.
 */
class GutterMark(
    val line: Int,
    val iconId: String,
    val tint: DecorationTint = DecorationTint.Muted,
    val tooltip: String? = null,

    /**
     * The `dev.ide.plugin.action.IdeAction` id to invoke when the mark is tapped, dispatched through the
     * normal action path so its effects (edits, navigation) work as they do anywhere else. Null makes the
     * mark a passive indicator.
     */
    val actionId: String? = null,

    /** Which mark shows when more than one lands on a line; highest wins. */
    val order: Int = 0,
)

/**
 * Text the editor renders inside a line without it being in the document: a type after a name, a parameter
 * name before an argument.
 *
 * This is the same surface a language backend's `InlayHintService` fills, opened to a plugin that is not a
 * language. The text is inserted for layout, so the caret steps over it and a selection never includes it.
 */
class EditorInlay(
    /** Document offset the hint is anchored before. */
    val offset: Int,
    val text: String,
    val kind: InlayKind = InlayKind.Other,

    /** A document offset to jump to when the hint is tapped, or null for a passive hint. */
    val navOffset: Int? = null,
)

/** What an inlay conveys, which drives how the editor tints it. */
enum class InlayKind { Type, Parameter, Chaining, Other }

/**
 * Plugins contribute [EditorDecorationProvider]s here.
 *
 * Every registered provider is asked about every open file (after its own [EditorDecorationProvider.appliesTo]
 * gate), and the results are merged in registration order. Contributing through a plugin's
 * `PluginRegistration` attributes the provider and removes it on unload:
 *
 * ```
 * override fun register(reg: PluginRegistration) {
 *     reg.register(EDITOR_DECORATION_EP, CoverageDecorations)
 * }
 * ```
 */
val EDITOR_DECORATION_EP = ExtensionPoint<EditorDecorationProvider>("platform.editorDecoration")
