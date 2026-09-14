package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.ProviderConfig

/**
 * OpenRouter as a first-class provider. OpenRouter speaks the OpenAI Chat Completions dialect, so the request
 * and streaming wire is reused from [OpenAiProvider] verbatim — this adds only the fixed base URL, OpenRouter's
 * `vendor/model` model ids, and a live model list from its `/models` endpoint (which, unlike OpenAI's, is not
 * filtered to `gpt*`). A user still brings their own key; a custom [ProviderConfig.baseUrl] (e.g. a proxy) wins
 * over the default.
 *
 * Explicit prompt caching is switched on because OpenRouter's Anthropic models cache nothing on their own: the
 * OpenAI dialect has no automatic prefix cache to inherit, so without `cache_control` markers a Claude model
 * reached this way re-bills the whole system prompt and tool set on every step of a turn.
 */
class OpenRouterProvider(private val transport: LlmTransport) : LlmProvider {
    private val openai = OpenAiProvider(transport, explicitPromptCaching = true)

    override val id: String = "openrouter"
    override val displayName: String = "OpenRouter"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("anthropic/claude-opus-5", "Claude Opus 5"),
        LlmModelInfo("anthropic/claude-sonnet-5", "Claude Sonnet 5"),
        LlmModelInfo("openai/gpt-5", "GPT-5"),
        LlmModelInfo("google/gemini-2.5-flash", "Gemini 2.5 Flash"),
    )
    override val defaultModel: String = "anthropic/claude-opus-5"

    override fun client(config: ProviderConfig): LlmClient =
        openai.client(config.copy(baseUrl = config.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE))

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = config.baseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: DEFAULT_BASE
        val body = transport.get("$base/v1/models", mapOf("Authorization" to "Bearer ${config.apiKey}"), config.caCertificatePem)
        val data = AgentJson.parseToJsonElement(body).asObj()?.get("data").asArr() ?: return@runCatching models
        data.mapNotNull { it.asObj() }
            .mapNotNull { m ->
                val modelId = m["id"].asStr() ?: return@mapNotNull null
                LlmModelInfo(modelId, m["name"].asStr() ?: modelId)
            }
            .ifEmpty { models }
    }.getOrDefault(models)

    companion object {
        /** OpenRouter's OpenAI-compatible root; [OpenAiProvider] appends `/v1/chat/completions`. */
        const val DEFAULT_BASE = "https://openrouter.ai/api"
    }
}
