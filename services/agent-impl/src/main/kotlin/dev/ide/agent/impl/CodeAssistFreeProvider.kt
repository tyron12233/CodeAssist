package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * CodeAssist Free AI: Claude through the project's `ai-proxy` edge function, paid for out of the user's
 * points wallet instead of their own API key.
 *
 * The proxy speaks the Anthropic Messages API, so the wire, the stream decoder and prompt caching are
 * [AnthropicProvider]'s, unchanged. What differs is the credential: the `x-api-key` header carries the
 * user's Supabase access token, fetched per request from [accessToken] because it lasts only an hour. The
 * [ProviderConfig.apiKey] the agent passes in is a placeholder and is ignored.
 *
 * The proxy enforces every limit itself (models, output size, effort, context size, the wallet). Nothing
 * here is a limit; the client is open source and a limit in it would be advice.
 */
class CodeAssistFreeProvider(
    transport: LlmTransport,
    private val baseUrl: String,
    /** A current access token, or null when signed out. May block on a refresh; called off the main thread. */
    private val accessToken: () -> String?,
) : LlmProvider {
    private val anthropic = AnthropicProvider(transport)

    override val id: String = ID
    override val displayName: String = "CodeAssist Free AI"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo(HAIKU, "Claude Haiku 5.5", supportsThinking = true),
        LlmModelInfo(SONNET, "Claude Sonnet 5.5 (premium)", supportsThinking = true),
    )
    override val defaultModel: String = HAIKU
    override val managesContext: Boolean = true

    override fun client(config: ProviderConfig): LlmClient = LlmClient { request ->
        flow {
            val token = withContext(Dispatchers.IO) { accessToken() }
            if (token.isNullOrBlank()) {
                emit(LlmStreamEvent.Failed(SIGNED_OUT_MESSAGE, null))
                return@flow
            }
            emitAll(anthropic.client(ProviderConfig(token, baseUrl, null)).chat(request))
        }
    }

    /** The proxy serves exactly these; there is nothing to discover. */
    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = models

    companion object {
        const val ID = "codeassist"
        const val HAIKU = "claude-haiku-5-5"
        const val SONNET = "claude-sonnet-5-5"
        const val SIGNED_OUT_MESSAGE = "Sign in to use CodeAssist Free AI (Settings > AI)."
    }
}
