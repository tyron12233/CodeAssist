package dev.ide.core.plugins

import dev.ide.agent.LlmEffort
import dev.ide.core.agent.AgentBackend
import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.UI_ACTION_EP
import dev.ide.ui.backend.UiAgentAttachment
import dev.ide.ui.backend.UiAgentAttachmentKind

/**
 * The AI coding agent, contributed as a built-in plugin (see docs/agentic-coding.md). Registers the "AI"
 * settings page (bring-your-own-key provider configuration). The chat service itself ([AgentBackend]) is a
 * concern backend wired by [IdeServicesBackend]; this plugin is non-essential so users can disable the
 * feature from the Plugins settings screen.
 */
internal class AgentPlugin : Plugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "AI Agent",
        description = "An AI coding assistant: chat, read and edit files, and run tools under a permission policy.",
    )

    override fun register(reg: PluginRegistration) {
        reg.register(SETTINGS_PAGE_EP, AgentSettingsPage)
        reg.register(UI_ACTION_EP, askAboutSelection)
    }

    /**
     * "Ask AI": attaches the editor selection (or, with nothing selected, the whole file) to the next chat
     * message and opens the chat beside the editor. The selection travels as an attachment rather than pasted
     * text, so the composer stays empty for the question and the model sees the path and line range.
     */
    private val askAboutSelection = SimpleAction(
        id = "agent.askAboutSelection",
        text = "Ask AI",
        places = setOf(ActionPlaces.EDITOR, ActionPlaces.COMMAND_PALETTE),
        iconId = "sparkle",
        order = 5,
        visible = { it.activeFilePath != null && it.documentText != null },
    ) { ctx -> askAbout(ctx) }

    private fun askAbout(ctx: ActionContext): ActionResult {
        val path = ctx.activeFilePath ?: return ActionResult.NONE
        val text = ctx.documentText ?: return ActionResult.NONE
        val agent = AgentBackend.forProject(ctx.projectRoot) ?: return ActionResult.message("The AI agent is not available.")
        val start = (ctx.selectionStart ?: 0).coerceIn(0, text.length)
        val end = (ctx.selectionEnd ?: 0).coerceIn(start, text.length)
        val name = path.substringAfterLast('/')
        val attachment = if (end > start) {
            val first = lineOf(text, start)
            val last = lineOf(text, if (end > start && text[end - 1] == '\n') end - 1 else end)
            UiAgentAttachment(
                UiAgentAttachmentKind.SELECTION,
                name = if (first == last) "$name:$first" else "$name:$first-$last",
                path = path,
                text = text.substring(start, end),
                startLine = first,
                endLine = last,
            )
        } else {
            UiAgentAttachment(UiAgentAttachmentKind.FILE, name = name, path = path)
        }
        agent.attach(attachment)
        return ActionResult.effect(ActionEffect.Navigate(CHAT_TOOL_WINDOW))
    }

    /** The 1-based line holding [offset]. */
    private fun lineOf(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    companion object {
        /** The plugin id (non-essential; disablable from Settings > Plugins). [IdeServicesBackend] gates the
         *  agent service on it, and the UI hides the chat surfaces when it's off. */
        const val ID = "agent"

        /** The chat's tool-window id, as `AgentUiPlugin` registers it. */
        const val CHAT_TOOL_WINDOW = "agent.chat"
    }
}

/** The "AI" settings page. Values persist under `settings.ai.*`; [AgentBackend] reads them at send time. */
internal object AgentSettingsPage : SettingsPage {
    override val id: String = AgentBackend.AI_PAGE
    override val title: String = "AI"
    override val iconId: String = "sparkle"
    override val scope: SettingsScope = SettingsScope.APPLICATION
    override val order: Int = 90

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Choice(
            key = "provider",
            title = "Provider",
            description = "Which AI provider the agent uses.",
            default = "anthropic",
            options = listOf(
                SettingControl.Choice.Option("anthropic", "Anthropic (Claude)"),
                SettingControl.Choice.Option("openai", "OpenAI"),
                SettingControl.Choice.Option("gemini", "Google Gemini"),
                SettingControl.Choice.Option("openrouter", "OpenRouter"),
                SettingControl.Choice.Option("gateway", "Custom gateway"),
            ),
        ),
        SettingControl.Text(
            key = "anthropicKey",
            title = "Anthropic API key",
            description = "Used when the provider is Anthropic.",
            placeholder = "sk-ant-...",
        ),
        SettingControl.Text(
            key = "openaiKey",
            title = "OpenAI API key",
            description = "Used when the provider is OpenAI (or an OpenAI-compatible gateway).",
            placeholder = "sk-...",
        ),
        SettingControl.Text(
            key = "geminiKey",
            title = "Gemini API key",
            description = "Used when the provider is Google Gemini.",
        ),
        SettingControl.Text(
            key = "openrouterKey",
            title = "OpenRouter API key",
            description = "Used when the provider is OpenRouter.",
            placeholder = "sk-or-...",
        ),
        SettingControl.Text(
            key = "model",
            title = "Model",
            description = "Optional. Leave blank to use the newest model that suits your key (on Gemini, the " +
                "current Flash, which the free tier supports). Picking a model in the chat overrides this for " +
                "that provider.",
        ),
        SettingControl.Choice(
            key = "reasoningEffort",
            title = "Reasoning effort",
            description = "How hard the model thinks, and the main cost lever after caching: routine work is " +
                "much cheaper at Low or Medium and rarely worse, while High and above earn their cost on real " +
                "coding tasks. Every provider honours it. Pick None for a newer OpenAI reasoning model, which " +
                "rejects function tools combined with reasoning and so cannot use the agent's tools otherwise. " +
                "Default sends nothing and leaves each provider's own default in place.",
            default = AgentBackend.REASONING_EFFORT_DEFAULT,
            options = listOf(
                SettingControl.Choice.Option(AgentBackend.REASONING_EFFORT_DEFAULT, "Default"),
                SettingControl.Choice.Option(LlmEffort.NONE, "None"),
                SettingControl.Choice.Option(LlmEffort.MINIMAL, "Minimal"),
                SettingControl.Choice.Option(LlmEffort.LOW, "Low"),
                SettingControl.Choice.Option(LlmEffort.MEDIUM, "Medium"),
                SettingControl.Choice.Option(LlmEffort.HIGH, "High"),
                SettingControl.Choice.Option(LlmEffort.XHIGH, "Very high"),
                SettingControl.Choice.Option(LlmEffort.MAX, "Maximum"),
            ),
        ),
        SettingControl.Toggle(
            key = "webSearch",
            title = "Web search",
            description = "Let the agent search the web when the provider supports it (Anthropic and Gemini). " +
                "The web_fetch and http_request tools work regardless.",
            default = true,
        ),
        SettingControl.Toggle(
            key = "mcpServer",
            title = "MCP server",
            description = "Expose the agent's tools over MCP on port " + AgentBackend.MCP_PORT +
                " (Streamable HTTP). Connect from a desktop client with \"adb forward tcp:" + AgentBackend.MCP_PORT +
                " tcp:" + AgentBackend.MCP_PORT + "\" then add a remote MCP server at \"http://127.0.0.1:" +
                AgentBackend.MCP_PORT + "/mcp\". Warning: any client that can reach the port can edit the open " +
                "project, without further permission prompts. Applies on the next launch.",
            default = false,
        ),
        SettingControl.IntSlider(
            key = "maxTokens",
            title = "Max response tokens",
            description = "Upper bound on the tokens the model may generate in a single response.",
            default = AgentBackend.DEFAULT_MAX_TOKENS,
            min = 1024,
            max = 32768,
            step = 1024,
            unit = "tok",
            advanced = true,
        ),
        SettingControl.IntSlider(
            key = "maxIterations",
            title = "Max tool iterations",
            description = "How many tool-call rounds the agent may take in one turn before it must stop.",
            default = AgentBackend.DEFAULT_MAX_ITERATIONS,
            min = 1,
            max = 100,
            step = 1,
            advanced = true,
        ),
        SettingControl.IntSlider(
            key = "rpm",
            title = "Requests per minute",
            description = "Pace the agent to stay under your provider's per-minute request limit, waiting " +
                "between steps instead of being rejected. 0 learns the limit from the provider's own errors, " +
                "which suits most free tiers.",
            default = 0,
            min = 0,
            max = 120,
            step = 1,
            unit = "/min",
            advanced = true,
        ),
        SettingControl.IntSlider(
            key = "tpmK",
            title = "Input tokens per minute",
            description = "The same pacing for a per-minute token limit, in thousands of tokens. 0 learns it.",
            default = 0,
            min = 0,
            max = 4000,
            step = 10,
            unit = "K",
            advanced = true,
        ),
        SettingControl.Text(
            key = "gatewayKey",
            title = "Gateway API key",
            description = "Used when the provider is Custom gateway.",
            advanced = true,
        ),
        SettingControl.Text(
            key = "gatewayBaseUrl",
            title = "Gateway base URL",
            description = "An OpenAI-compatible endpoint (OpenRouter, LiteLLM, or self-hosted).",
            advanced = true,
        ),
        SettingControl.Text(
            key = "gatewayModel",
            title = "Gateway model",
            description = "The model name to request from the custom gateway.",
            advanced = true,
        ),
    )
}
