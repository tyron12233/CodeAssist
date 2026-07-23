package dev.ide.ui.components

import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin

/**
 * Contributes the AI agent's chat panel as a RIGHT-anchored tool window (see docs/agentic-coding.md). The
 * chat UI is plugin-based like the rest of the IDE's extensible surfaces: the host renders
 * `ToolWindowRegistry.forAnchor(RIGHT)`, so any plugin can add or replace a right-edge panel the same way.
 * Registered with `UiPluginHost` from CodeAssistApp; the engine-side `AgentPlugin` owns the settings page and
 * the `AgentService` wiring.
 */
object AgentUiPlugin : UiPlugin {
    override val id: String = "agent-ui"

    override fun contributeUi(scope: UiContributionScope) {
        scope.toolWindow(
            ToolWindowContribution(
                id = "agent.chat",
                title = "AI",
                iconId = "sparkle",
                anchor = ToolWindowAnchor.RIGHT,
                content = { ctx -> ChatDrawer(ctx.backend) },
            ),
        )
    }
}
