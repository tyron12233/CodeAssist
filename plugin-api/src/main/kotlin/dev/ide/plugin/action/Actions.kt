package dev.ide.plugin.action

import dev.ide.platform.ExtensionPoint

/**
 * The lean, IntelliJ-style action model. An [IdeAction] is one invocable command (a toolbar button, a menu
 * row, a command-palette entry); an [ActionGroup] nests actions and other groups for menus; an [ActionPlace]
 * names where they appear. Actions and groups are contributed through [UI_ACTION_EP] / [ACTION_GROUP_EP], so
 * adding a button is a registration rather than a host edit, the same pattern the language backends, indexes,
 * and settings pages already use.
 *
 * This module knows nothing about Compose or the engine. The host (`ide-core`) registers built-ins and runs
 * the resolved actions; the UI renders them through neutral DTOs over the `IdeBackend` port. A third-party
 * plugin contributes to the same extension points the bundled actions use.
 */

/**
 * Where an action can appear. An open, string-backed set so a plugin (or a new UI surface) can introduce its
 * own place without a change here. The built-in places are in [ActionPlaces].
 */
@JvmInline
value class ActionPlace(val id: String)

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
}

/**
 * One invocable command. [id] is the stable registry key and the handle the UI round-trips on. [places] is
 * where it may appear; [order] sorts within a place (built-ins occupy 0..99, plugins default after them).
 * [isVisible] hides it for a context entirely; [isEnabled] greys it but keeps it listed.
 */
interface IdeAction {
    val id: String
    val text: String

    /** Icon id resolved by the UI's icon registry (e.g. `"refresh"`, `"run"`); null renders text-only. */
    val iconId: String? get() = null

    val places: Set<ActionPlace>
    val order: Int get() = 1000

    fun isVisible(ctx: ActionContext): Boolean = true
    fun isEnabled(ctx: ActionContext): Boolean = true

    /** Run the action. Returns an [ActionResult] carrying a status message and any UI effects to apply. */
    suspend fun perform(ctx: ActionContext): ActionResult
}

/**
 * A nesting container for menus. Its [children] are action ids and group ids in display order; the literal
 * id [SEPARATOR] inserts a divider. A group itself targets one or more [places]; the resolver expands it
 * into a submenu of its (recursively resolved) children.
 */
interface ActionGroup {
    val id: String
    val text: String
    val iconId: String? get() = null
    val places: Set<ActionPlace>
    val order: Int get() = 1000

    fun children(ctx: ActionContext): List<String>

    companion object {
        /** A child id that renders as a menu divider rather than an action. */
        const val SEPARATOR = "---"
    }
}

/**
 * The outcome of [IdeAction.perform]: an optional human-readable [message] (a toast/status line) and a list
 * of neutral [ActionEffect]s the UI applies. Keeping effects declarative lets an engine-side action ask the
 * UI to navigate or open a file without the action depending on the UI.
 */
data class ActionResult(
    val message: String? = null,
    val effects: List<ActionEffect> = emptyList(),
) {
    companion object {
        val NONE = ActionResult()
        fun message(text: String) = ActionResult(message = text)
        fun effect(vararg effects: ActionEffect) = ActionResult(effects = effects.toList())
    }
}

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

/** Plugins (and the host's built-ins) contribute invocable actions here. */
val UI_ACTION_EP = ExtensionPoint<IdeAction>("platform.uiAction")

/** Plugins (and the host's built-ins) contribute menu nesting here. */
val ACTION_GROUP_EP = ExtensionPoint<ActionGroup>("platform.actionGroup")
