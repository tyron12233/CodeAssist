package dev.ide.plugin.action

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