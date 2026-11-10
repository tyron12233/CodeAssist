// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.ui

import androidx.compose.runtime.Composable

/** Where a [ToolWindow] docks: the left activity rail, the right rail, or the console region at the bottom. */
enum class ToolWindowAnchor { LEFT, RIGHT, BOTTOM }

/**
 * A dockable panel, shown when the user selects it in its [anchor]'s rail.
 *
 * [iconId] names an icon in the IDE's own registry (a plugin has no `Context` for its own package, so it
 * cannot ship drawables); an unknown id falls back to a generic one. [order] sorts the panel among the
 * built-ins, low first.
 *
 * [content] is composed while the panel is open, and only then.
 */
class ToolWindow(
    val id: String,
    val title: String,
    val iconId: String,
    val anchor: ToolWindowAnchor,
    val order: Int = 1000,
    val content: @Composable (UiContext) -> Unit,
)

/**
 * A full screen, shown by [UiContext.openScreen] or by an engine-side action returning
 * `ActionEffect.Navigate(id)`, which is how a plugin's command opens its own screen.
 *
 * [title] is what the screen's own chrome shows. [content] gets a [ScreenUiContext], so it can navigate back.
 */
class Screen(
    val id: String,
    val title: String,
    val content: @Composable (ScreenUiContext) -> Unit,
)

/**
 * A floating layer composed above every screen, for something that must be able to appear regardless of
 * where the user is (a prompt the plugin needs answered).
 *
 * [content] is composed at all times, so it decides its own visibility: it observes the plugin's state and
 * renders nothing until there is something to show.
 */
class Overlay(
    val id: String,
    val content: @Composable (UiContext) -> Unit,
)

/**
 * A preview pane for the editor: the right-hand half of the split, or the whole surface in Preview mode, for
 * files this plugin can show something better than text for.
 *
 * The IDE has three of these built in (Compose `@Preview`, drawable/color resources, Markdown); the Android
 * layout editor is itself a plugin built on this, so a pane can be a whole design surface and not only a
 * picture: with [EditorPreviewContext.applyEdits] it edits the buffer it shows. It exists because a framework's
 * preview is not a variation on any of the built-ins: a game's scene, a shader, a diagram, a state machine.
 * Combined with the interpreter (`:interp-api`), a plugin can render what the user's own code actually
 * produces rather than a picture of what it says.
 *
 * [appliesTo] is asked for each open file's path and should be cheap; it is called during composition. Return
 * false for anything the plugin cannot show, since claiming a file hides the pane the user expected. The
 * built-in panes are consulted first, so a plugin cannot take a drawable or a Markdown file away from them.
 *
 * [content] is composed while the pane is visible, and re-composed as the user types (the context carries the
 * live buffer). [title] labels the pane.
 */
class EditorPreview(
    val id: String,
    val title: String,
    val appliesTo: (path: String) -> Boolean,
    /**
     * Whether the pane wants the whole screen when it is the editor's only surface (Preview mode, not Split): the
     * host then hides its phone bottom bar (Search, Source, AI, More) so a canvas the user works in, a design
     * surface or a scene editor, gets that room. The editor's own top bar and toggle stay, so leaving is one tap.
     * Since SPI 3.5.0.
     */
    val fullScreen: Boolean,
    val content: @Composable (EditorPreviewContext) -> Unit,
) {
    /** The pre-3.5.0 shape, kept so a plugin compiled against it still links. */
    constructor(
        id: String,
        title: String,
        appliesTo: (path: String) -> Boolean,
        content: @Composable (EditorPreviewContext) -> Unit,
    ) : this(id, title, appliesTo, false, content)
}

/**
 * What an [EditorPreview] body is handed: the file being previewed, its live text, and the way to report what
 * went wrong.
 *
 * [text] is the editor's buffer, not the file on disk, which is the whole point of a live preview: it changes
 * on every keystroke and recomposes this body. Debounce if rendering is expensive.
 */
interface EditorPreviewContext : UiContext {

    /** Absolute path of the file being previewed. Never null here, unlike [UiContext.activeFilePath]. */
    val path: String

    /** The editor's live buffer for [path]. */
    val text: String

    /** Whether the preview surface is showing its dark scheme, so a rendered scene can match it. */
    val dark: Boolean

    /**
     * Report what is wrong with this preview, or an empty list once it is clean. The host shows them in the
     * same problem chip the built-in previews use, above the pane rather than over the rendered content.
     *
     * Call it on every pass, including the clean ones: a stale problem nobody cleared is worse than none.
     */
    fun reportProblems(problems: List<String>)

    // ---- Editing (since SPI 3.5.0) ----------------------------------------------------------------------
    // A preview can be a design surface, not only a picture: the same shared buffer the code editor edits,
    // written through the editor so an edit made in the pane is undoable, analysed and marks the tab dirty
    // exactly as typing does. On a host older than 3.5.0 these throw `AbstractMethodError`; the defaults
    // below are what a host with no editor answers (a headless render, a test).

    /** The caret in the shared buffer (the selection's start when there is one). Since SPI 3.5.0. */
    val caretOffset: Int get() = 0

    /** The selection's end; equal to [caretOffset] when nothing is selected. Since SPI 3.5.0. */
    val selectionEnd: Int get() = caretOffset

    /**
     * Whether the pane is the half of a Split view, beside the code editor, rather than the whole surface.
     * A pane that edits structurally can then keep its selection and the caret in step. Since SPI 3.5.0.
     */
    val isSplit: Boolean get() = false

    /**
     * Whether edits are possible at all. False on a host with no editor behind the pane, where [applyEdits]
     * does nothing; a design surface then shows itself read-only. Since SPI 3.5.0.
     */
    val canEdit: Boolean get() = false

    /**
     * Apply [edits] to the shared buffer as ONE undo step. Ranges are in the buffer as it is now (the [text]
     * this context carries), must not overlap, and may come in any order. Since SPI 3.5.0.
     */
    fun applyEdits(edits: List<TextReplacement>) {}

    /** Move the code editor's selection to `[start, end)`; a caret when they are equal. Since SPI 3.5.0. */
    fun select(start: Int, end: Int = start) {}

    /**
     * Say whether the pane is still working on what it will show (a render in flight, a debounce pending).
     * A pane that renders asynchronously reports `true` while it does and `false` once it has drawn; a pane that
     * never reports is taken to be settled as soon as it composes. The host uses it to know when a frame is
     * worth taking, as the IDE's agent does when it looks at a preview the user has not opened. Since SPI 3.5.0.
     */
    fun reportRendering(rendering: Boolean) {}
}

/** Replace `[start, end)` of a buffer with [newText]. Since SPI 3.5.0. */
class TextReplacement(val start: Int, val end: Int, val newText: String)
