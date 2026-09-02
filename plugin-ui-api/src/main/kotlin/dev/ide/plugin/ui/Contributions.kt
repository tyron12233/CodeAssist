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
