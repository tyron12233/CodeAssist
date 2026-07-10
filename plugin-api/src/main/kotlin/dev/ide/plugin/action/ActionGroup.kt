package dev.ide.plugin.action

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