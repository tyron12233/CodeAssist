package dev.ide.plugin.impl

import dev.ide.platform.ExtensionRegistry
import dev.ide.plugin.action.ACTION_GROUP_EP
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionGroup
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.IdeAction
import dev.ide.plugin.action.UI_ACTION_EP

/**
 * Resolves and dispatches the actions contributed to [UI_ACTION_EP] / [ACTION_GROUP_EP]. The single consumer
 * of those extension points: the host builds one over its [ExtensionRegistry] and exposes it across the UI
 * boundary (toolbars/palette via [actionsFor], context menus via [menuFor], invocation via [invoke]).
 *
 * The registry is queried live on every call, so a plugin loaded or unloaded at runtime is reflected without
 * rebuilding this manager (contributions are cheap snapshots; resolution is over a handful of items).
 */
class ActionManager(private val registry: ExtensionRegistry) {

    private fun actions(): List<IdeAction> = registry.extensions(UI_ACTION_EP)
    private fun groups(): List<ActionGroup> = registry.extensions(ACTION_GROUP_EP)

    /**
     * The visible actions for [ctx]'s place, ordered by ([IdeAction.order], then [IdeAction.text]). A flat
     * list with no group expansion, for toolbars and the command palette. Enablement is left to the caller
     * (the UI greys a disabled action) via [IdeAction.isEnabled].
     */
    fun actionsFor(ctx: ActionContext): List<IdeAction> =
        actions()
            .filter { ctx.place in it.places && it.isVisible(ctx) }
            .sortedWith(compareBy({ it.order }, { it.text }))

    /** The action with [id], or null if none is registered. */
    fun find(id: String): IdeAction? = actions().firstOrNull { it.id == id }

    /**
     * Run the action [id] for [ctx]. Returns [ActionResult.NONE] when the action is disabled and a message
     * result when [id] is unknown, so a stale UI round-trip degrades gracefully rather than throwing.
     */
    suspend fun invoke(id: String, ctx: ActionContext): ActionResult {
        val action = find(id) ?: return ActionResult.message("Unknown action: $id")
        if (!action.isEnabled(ctx)) return ActionResult.NONE
        return action.perform(ctx)
    }

    /**
     * Expand [ctx]'s place into a menu tree. Top-level items are the actions and groups placed here that are
     * not nested inside another placed group; each group becomes a [ResolvedMenuItem.Submenu] of its
     * (recursively resolved) children. Invisible actions are dropped; a child id that resolves to nothing is
     * skipped. Leading/trailing and doubled separators are collapsed.
     */
    fun menuFor(ctx: ActionContext): List<ResolvedMenuItem> {
        val actionsById = actions().associateBy { it.id }
        val groupsById = groups().associateBy { it.id }

        val placedActions = actions().filter { ctx.place in it.places }
        val placedGroups = groups().filter { ctx.place in it.places }

        // A child of any placed group is nested, so it must not also appear at the top level.
        val nested = placedGroups.flatMapTo(HashSet()) { it.children(ctx) }

        data class Top(val order: Int, val text: String, val item: Any)
        val top = buildList {
            placedActions.filter { it.id !in nested }.forEach { add(Top(it.order, it.text, it)) }
            placedGroups.filter { it.id !in nested }.forEach { add(Top(it.order, it.text, it)) }
        }.sortedWith(compareBy({ it.order }, { it.text }))

        val resolved = top.mapNotNull { resolve(it.item, ctx, actionsById, groupsById, HashSet()) }
        return collapseSeparators(resolved)
    }

    private fun resolve(
        item: Any,
        ctx: ActionContext,
        actionsById: Map<String, IdeAction>,
        groupsById: Map<String, ActionGroup>,
        visiting: MutableSet<String>,
    ): ResolvedMenuItem? = when (item) {
        is IdeAction -> if (item.isVisible(ctx)) ResolvedMenuItem.Action(item, item.isEnabled(ctx)) else null
        is ActionGroup -> {
            if (!visiting.add(item.id)) null // cycle guard
            else {
                val children = item.children(ctx).mapNotNull { childId ->
                    when {
                        childId == ActionGroup.SEPARATOR -> ResolvedMenuItem.Separator
                        else -> {
                            val child = actionsById[childId] ?: groupsById[childId]
                            child?.let { resolve(it, ctx, actionsById, groupsById, visiting) }
                        }
                    }
                }
                visiting.remove(item.id)
                val cleaned = collapseSeparators(children)
                if (cleaned.isEmpty()) null
                else ResolvedMenuItem.Submenu(item.id, item.text, item.iconId, cleaned)
            }
        }
        else -> null
    }

    private fun collapseSeparators(items: List<ResolvedMenuItem>): List<ResolvedMenuItem> {
        val out = ArrayList<ResolvedMenuItem>(items.size)
        for (it in items) {
            if (it is ResolvedMenuItem.Separator) {
                if (out.isEmpty() || out.last() is ResolvedMenuItem.Separator) continue
            }
            out.add(it)
        }
        while (out.isNotEmpty() && out.last() is ResolvedMenuItem.Separator) out.removeAt(out.size - 1)
        return out
    }
}

/** A node in a resolved menu tree. Enablement is pre-evaluated on [Action]; the UI renders it greyed. */
sealed interface ResolvedMenuItem {
    data class Action(val action: IdeAction, val enabled: Boolean) : ResolvedMenuItem
    data class Submenu(
        val id: String,
        val text: String,
        val iconId: String?,
        val items: List<ResolvedMenuItem>,
    ) : ResolvedMenuItem
    data object Separator : ResolvedMenuItem
}
