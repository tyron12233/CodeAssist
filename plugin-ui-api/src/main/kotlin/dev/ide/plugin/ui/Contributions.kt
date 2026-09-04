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
 * The IDE has four of these built in (Compose `@Preview`, Android XML layouts, resources, Markdown) and they
 * are what this is modelled on. It exists because a framework's preview is not a variation on any of them: a
 * game's scene, a shader, a diagram, a state machine. Combined with the interpreter (`:interp-api`), a plugin
 * can render what the user's own code actually produces rather than a picture of what it says.
 *
 * [appliesTo] is asked for each open file's path and should be cheap; it is called during composition. Return
 * false for anything the plugin cannot show, since claiming a file hides the pane the user expected. The
 * built-in panes are consulted first, so a plugin cannot take `.xml` away from the layout preview.
 *
 * [content] is composed while the pane is visible, and re-composed as the user types (the context carries the
 * live buffer). [title] labels the pane.
 */
class EditorPreview(
    val id: String,
    val title: String,
    val appliesTo: (path: String) -> Boolean,
    val content: @Composable (EditorPreviewContext) -> Unit,
)

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
}
