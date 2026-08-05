package dev.ide.core

import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration

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
    }

    companion object {
        /** The plugin id (non-essential; disablable from Settings > Plugins). [IdeServicesBackend] gates the
         *  agent service on it, and the UI hides the chat surfaces when it's off. */
        const val ID = "agent"
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
            description = "Optional. Leave blank to use the provider's default model.",
        ),
        SettingControl.Choice(
            key = "reasoningEffort",
            title = "Reasoning effort",
            description = "For OpenAI (and OpenAI-compatible gateway/OpenRouter) reasoning models. Newer models " +
                "(e.g. GPT-5.x) reject function tools combined with reasoning on the chat-completions API, so " +
                "pick None to let the agent's tools work. Default sends nothing (correct for other providers).",
            default = AgentBackend.REASONING_EFFORT_DEFAULT,
            options = listOf(
                SettingControl.Choice.Option(AgentBackend.REASONING_EFFORT_DEFAULT, "Default"),
                SettingControl.Choice.Option("none", "None"),
                SettingControl.Choice.Option("minimal", "Minimal"),
                SettingControl.Choice.Option("low", "Low"),
                SettingControl.Choice.Option("medium", "Medium"),
                SettingControl.Choice.Option("high", "High"),
            ),
        ),
        SettingControl.Toggle(
            key = "webSearch",
            title = "Web search",
            description = "Let the agent search the web when the provider supports it (Anthropic and Gemini). " +
                "The web_fetch and http_request tools work regardless.",
            default = true,
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
