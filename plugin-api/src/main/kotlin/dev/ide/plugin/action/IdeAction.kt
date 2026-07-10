package dev.ide.plugin.action

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