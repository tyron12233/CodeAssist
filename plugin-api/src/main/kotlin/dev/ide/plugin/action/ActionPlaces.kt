// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.action

/** The places the bundled UI exposes. Plugins target these (or define their own). */
object ActionPlaces {
    /** The editor's main top bar. Plugin actions render in a dedicated slot beside the built-in chrome. */
    val MAIN_TOOLBAR = ActionPlace("mainToolbar")

    /** The collapse target the compact/mobile top bar folds overflow actions into. */
    val MAIN_OVERFLOW = ActionPlace("mainToolbar.overflow")

    /** The editor's "More" menu (the secondary-actions sheet). */
    val MORE_MENU = ActionPlace("moreMenu")

    /** The file-tree row context menu (long-press / right-click). [ActionContext.contextPath] is the node. */
    val FILE_CONTEXT = ActionPlace("fileContext")

    /** An open editor tab's context menu. [ActionContext.activeFilePath] is the tab's file. */
    val EDITOR_TAB = ActionPlace("editorTab")

    /** The command palette. Actions placed here are searchable commands. */
    val COMMAND_PALETTE = ActionPlace("commandPalette")

    /**
     * The editor content at the caret. One place, three surfaces: an action placed here is listed in the
     * Alt-Enter / lightbulb popup alongside the analysis quick-fixes and intentions, in the editor's
     * overflow context menu (where [ActionGroup]s nest it into submenus), and in the command palette while
     * an editor is focused.
     *
     * [ActionContext.caret] carries what the caret is on, so [IdeAction.isVisible] can keep the action out
     * of the list where it does not apply. An action that offers itself everywhere adds noise to a popup
     * the user opened to fix one specific thing.
     */
    val EDITOR = ActionPlace("editor")
}