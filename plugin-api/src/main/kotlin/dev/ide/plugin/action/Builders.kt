package dev.ide.plugin.action

/**
 * A concise [IdeAction] backed by lambdas, for hosts and plugins that don't need a dedicated class per
 * action. The visibility/enablement predicates default to always-on; [perform] is the only required body.
 */
class SimpleAction(
    override val id: String,
    override val text: String,
    override val places: Set<ActionPlace>,
    override val iconId: String? = null,
    override val order: Int = 1000,
    private val visible: (ActionContext) -> Boolean = { true },
    private val enabled: (ActionContext) -> Boolean = { true },
    private val onPerform: suspend (ActionContext) -> ActionResult,
) : IdeAction {
    override fun isVisible(ctx: ActionContext): Boolean = visible(ctx)
    override fun isEnabled(ctx: ActionContext): Boolean = enabled(ctx)
    override suspend fun perform(ctx: ActionContext): ActionResult = onPerform(ctx)
}

/** A concise [ActionGroup] backed by a static (or context-derived) child-id list. */
class SimpleGroup(
    override val id: String,
    override val text: String,
    override val places: Set<ActionPlace>,
    override val iconId: String? = null,
    override val order: Int = 1000,
    private val childrenOf: (ActionContext) -> List<String>,
) : ActionGroup {
    constructor(
        id: String, text: String, places: Set<ActionPlace>, iconId: String? = null, order: Int = 1000,
        children: List<String>,
    ) : this(id, text, places, iconId, order, { children })

    override fun children(ctx: ActionContext): List<String> = childrenOf(ctx)
}
