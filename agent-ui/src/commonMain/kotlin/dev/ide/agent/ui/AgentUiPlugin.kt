package dev.ide.agent.ui

import dev.ide.ui.ext.OverlayContribution
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin

/**
 * The AI agent's Compose UI, as one self-contained plugin: the chat panel (a RIGHT-anchored tool window) plus
 * the write-permission prompt (an app-wide overlay). Co-declared with its engine facet `AgentPlugin` as one
 * `BuiltInPlugin` in ide-core's `BuiltInPlugins`, so a single unified registration drives both halves under one
 * enable/disable decision; the shell just registers whatever `IdeBackend.uiPlugins()` reports. `AgentPlugin`
 * owns the settings page + the `AgentService` wiring.
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
        // The write-permission prompt (ASK_EACH): observes backend.agent.permissionRequest and shows only while
        // a mutating tool call awaits approval. App-wide overlay so it appears over any screen.
        scope.overlay(
            OverlayContribution(
                id = "agent.permission",
                content = { ctx -> AgentPermissionDialog(ctx.backend) },
            ),
        )
    }
}
