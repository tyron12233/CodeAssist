// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The two Compose-bearing ways into the code editor itself, above `platform.editorDecoration`.
 *
 * Reach for the decoration provider first. It is engine-tier, it is data, it runs off the composition, its
 * failures are contained per provider, and it covers a tinted range, a gutter glyph and an inlay, which is
 * most of what marking up code amounts to. The two surfaces here exist for what data cannot express:
 *
 *  - [EditorLayer] places real composables at document positions: a hover card, an inline button, a code-lens
 *    row above a declaration. Touch targets, animation and Material components need composables.
 *  - [EditorPainter] draws into the editor's canvas. It is the escape hatch for a plugin whose colors are its
 *    own (a blame heatmap, a coverage gradient) rather than one of the theme's named roles, which is the one
 *    thing a decoration deliberately cannot do.
 *
 * Both are contributed by a UI facet, so both are Compose and both bind to the IDE's own copy of it at load.
 */

/** Where an [EditorWidget] sits. Resolved against the live document, so a document offset is enough. */
sealed interface EditorAnchor {
    /** At a document offset, on that offset's row and at its column. For a marker on a specific token. */
    class AtOffset(val offset: Int) : EditorAnchor

    /** After the end of [line]'s text, on the same row. The code-lens / inline-annotation position. */
    class AfterLine(val line: Int) : EditorAnchor

    /** On its own row above [line]. For a header over a declaration. */
    class AboveLine(val line: Int) : EditorAnchor
}

/**
 * One placed composable.
 *
 * [key] identifies the widget across recompositions, so its own remembered state survives scrolling and
 * re-placement. Two widgets in one layer's result must not share a key.
 */
class EditorWidget(
    val anchor: EditorAnchor,
    val key: Any,
    val content: @Composable () -> Unit,
)

/** The open file a layer places widgets over. Extends [UiContext], so a widget can also open a file. */
interface EditorLayerContext : UiContext {
    /** Path of the file being decorated, which is [UiContext.activeFilePath] for the focused tab. */
    val path: String

    /** The live buffer, which may differ from what is on disk. An anchor's offsets are against this. */
    val text: String

    /** Inclusive range of document lines on screen. Place only what is visible: a layer is asked again as
     *  the user scrolls, and a widget for line 4000 costs a composable for nothing. */
    val visibleLines: IntRange

    /** The caret offset, or the selection's start when text is selected. */
    val caretOffset: Int
}

/**
 * A producer of anchored composables for the open file.
 *
 * [widgets] runs inside the editor's composition, on every recomposition, which is what makes it reactive:
 * read a `StateFlow` collected as state or your own observable store and the layer re-places itself, with no
 * polling and no channel into the editor. In exchange it must only read state and decide. Do the work where
 * the state is produced, in this plugin's engine facet, and read the result here.
 *
 * It is also not isolated from the editor's composition, because a `@Composable` call cannot be wrapped in a
 * `try`: the composition is built from a slot table with no way to unwind half a composable. A producer that
 * throws takes the editor's composition with it. That asymmetry with the data tier (where one provider's
 * failure costs only that provider) is the strongest reason to prefer a decoration when a decoration will do.
 *
 * [appliesTo] is checked before anything is composed, and IS guarded, so it is the safe place to be
 * selective. [order] sorts layers, low first, and [id] breaks ties and identifies the layer in composition.
 */
class EditorLayer(
    val id: String,
    val order: Int = 1000,
    val appliesTo: (filePath: String) -> Boolean = { true },
    val widgets: @Composable (EditorLayerContext) -> List<EditorWidget>,
)

/** How deep in the editor's canvas an [EditorPainter] draws. */
enum class EditorPaintLayer {
    /** Under the text and under the selection. Where a fill belongs: the code stays readable over it. */
    BelowText,

    /** Over the text and over every decoration, under the caret. For a line or an outline that must not be
     *  hidden by the code. A filled shape here buries the text, which is the painter's problem. */
    AboveText,
}

/**
 * The editor's geometry for one frame, as a painter sees it.
 *
 * Everything is in the pixel space the [DrawScope] draws in: the editor's top-left is the origin, the gutter
 * occupies `[0, gutterWidth)`, and text starts at [textLeft]. The positions are functions rather than a table
 * of numbers because scrolling, soft wrap and collapsed folds all move a line: `line * lineHeight` is wrong
 * the moment any of the three is in play, and all three are normal.
 */
interface EditorPaintContext {
    /** Path of the file being drawn. */
    val path: String

    /** Inclusive range of document lines on screen. Drawing outside it is clipped away. */
    val visibleLines: IntRange

    /** Lines in the document. */
    val lineCount: Int

    /** Height of one unwrapped row, in px. */
    val lineHeight: Float

    /** Advance of one character in the editor's monospace font, in px. */
    val charWidth: Float

    /** Width of the gutter, in px: stay out of it unless you mean to draw in it. */
    val gutterWidth: Float

    /** X of the first text column, in px: past the gutter and the padding, minus the horizontal scroll. */
    val textLeft: Float

    /** Viewport Y of the top of [line]'s first row, in px. */
    fun lineTop(line: Int): Float

    /** Viewport X of a document [offset], in px. An offset that is not laid out answers [textLeft] rather
     *  than throwing, so a stale offset costs a misplaced mark for one frame and nothing worse. */
    fun xOf(offset: Int): Float

    /** The document line holding [offset]. */
    fun lineOf(offset: Int): Int

    /** `[start, end)` offsets of [line], excluding its line break. */
    fun lineRange(line: Int): IntRange

    /** Whether [line] is inside a collapsed fold, so nothing should be drawn for it. */
    fun isHidden(line: Int): Boolean
}

/**
 * A painter for the editor's canvas.
 *
 * [paint] runs in the draw phase, on every frame, including every frame of a fling on a phone. Two rules
 * follow, and they are not style advice:
 *
 *  - **Do no work and allocate nothing.** Anything computed here is computed sixty times a second. Compute in
 *    an engine-tier decoration provider or a layer and draw from the result.
 *  - **Do not throw.** A painter that throws is retired for the rest of the session rather than retried,
 *    because a draw that throws once throws every frame, and the alternative is an editor that neither draws
 *    nor recovers. The plugin loses its drawing until the IDE restarts.
 *
 * [appliesTo] is guarded and is checked outside the draw, so gate there rather than returning early in
 * [paint]. [order] sorts painters within a layer, low first.
 */
class EditorPainter(
    val id: String,
    val order: Int = 1000,
    val layer: EditorPaintLayer = EditorPaintLayer.BelowText,
    val appliesTo: (filePath: String) -> Boolean = { true },
    val paint: DrawScope.(EditorPaintContext) -> Unit,
)

/**
 * A surface for a tab, beside the IDE's own Code, Blocks, Preview and Split: a scene view, a data grid, a
 * form over a config file.
 *
 * A view mode is a view OF the tab's buffer, not a second copy of it. The code editor, the block editor and
 * this pane all edit the one document, which is why [EditorViewModeContext.replaceText] is undoable, is
 * analysed, and marks the tab dirty exactly as typing does, and why a user can switch to Code and see what
 * this pane just wrote.
 *
 * [appliesTo] decides which files offer the mode, so the toggle shows it on those files and nowhere else.
 * [iconId] is a glyph from the IDE's registry, since the toggle is icon-only and [label] rides along as the
 * accessibility description.
 */
class EditorViewMode(
    val id: String,
    val label: String,
    val iconId: String = "layers",
    val order: Int = 1000,
    val appliesTo: (filePath: String) -> Boolean = { true },

    /**
     * Whether a tab for [filePath] should OPEN in this mode rather than in the code editor.
     *
     * This is how a plugin comes to own a file kind: claim it and the file opens into your pane, the way an
     * image already opens into the IDE's bitmap preview. Code stays reachable from the toggle on purpose: a
     * pane can be wrong about a file, and a user who cannot see the text has no way to find out why.
     *
     * If you own a kind the text editor cannot represent (a binary), read the file yourself through this
     * plugin's engine facet. [EditorViewModeContext.text] is a text decode of the bytes, which for a binary
     * is garbage, and [EditorViewModeContext.replaceText] would write that garbage back.
     */
    val isDefault: (filePath: String) -> Boolean = { false },
    val content: @Composable (EditorViewModeContext) -> Unit,
)

/** What a view mode renders against. Extends [UiContext], so a pane can also open another file. */
interface EditorViewModeContext : UiContext {
    /** Path of the file this pane is showing. */
    val path: String

    /** The editor's live buffer, which may differ from disk. The pane recomposes as the user types. */
    val text: String

    /** The caret offset in the shared buffer, so a pane can follow the code editor's position. */
    val caretOffset: Int

    /**
     * Replace `[start, end)` of the shared buffer with [newText].
     *
     * Prefer the narrowest range that changes: replacing the whole text works, and costs the user their undo
     * granularity for the edit.
     */
    fun replaceText(start: Int, end: Int, newText: String)
}
