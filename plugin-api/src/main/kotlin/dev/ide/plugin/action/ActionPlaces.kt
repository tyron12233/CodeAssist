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
}