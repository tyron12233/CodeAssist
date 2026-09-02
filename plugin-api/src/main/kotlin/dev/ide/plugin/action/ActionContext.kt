package dev.ide.plugin.action

/**
 * A read-only snapshot of what an action acts on, passed to [IdeAction.isEnabled] / [IdeAction.isVisible] /
 * [IdeAction.perform]. Everything is neutral data so the snapshot can cross the UI boundary as a DTO and be
 * reconstructed host-side.
 *
 * Built-in actions (registered in `ide-core`) capture whatever host capability they need at construction.
 * A future revision adds a permission-gated host facade here for third-party plugins (the trust model in
 * `docs/ui-extensibility-and-plugin-api.md`); Phase A does not need it.
 */
interface ActionContext {
    /** The place the action is being resolved/invoked for. */
    val place: ActionPlace

    /** The open workspace root, or null when none is open. */
    val projectRoot: String?

    /** The file open in the active editor, or null. */
    val activeFilePath: String?

    /** The active editor selection `[selectionStart, selectionEnd)`, both null when there is no editor. */
    val selectionStart: Int?
    val selectionEnd: Int?

    /**
     * The tree/tab node the action was invoked on, when the place is a context menu (e.g. the file-tree row
     * for [ActionPlaces.FILE_CONTEXT]). Null for global places like the toolbar or palette.
     */
    val contextPath: String?

    /**
     * What the caret is on, for the places that resolve against editor content ([ActionPlaces.EDITOR], and
     * the command palette when an editor is focused). Null when there is no editor, when the file has not
     * been parsed yet, or for a place that carries no caret (the file tree).
     *
     * Defaulted so an action written before editor places existed still compiles, and so a host that has no
     * editor concept can implement [ActionContext] without one.
     */
    val caret: CaretContext? get() = null
}