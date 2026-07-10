package dev.ide.plugin.action

/** A neutral instruction an action returns for the UI to carry out. Open set; the UI ignores ones it cannot
 *  honor. */
sealed interface ActionEffect {
    /** Open [path] in the editor, optionally moving the caret to [offset]. */
    data class OpenFile(val path: String, val offset: Int? = null) : ActionEffect

    /** Navigate to a named UI destination (a screen/tool-window id). Forward-compatible with the screen and
     *  tool-window registries (Phase B). */
    data class Navigate(val target: String) : ActionEffect

    /** Re-read the file tree (a file/dir was created/removed). */
    data object RefreshTree : ActionEffect

    /** Re-read the active editor's content from disk (a file the editor shows changed underneath it). */
    data class ReloadFile(val path: String) : ActionEffect
}