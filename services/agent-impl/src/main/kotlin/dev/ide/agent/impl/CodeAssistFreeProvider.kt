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
    /** The proxies to use, in order: the first is primary, the rest are tried when it fails before answering. */
    private val baseUrls: List<String>,
    /** A current access token, or null when signed out. May block on a refresh; called off the main thread. */
    private val accessToken: () -> String?,
) : LlmProvider {
    constructor(transport: LlmTransport, baseUrl: String, accessToken: () -> String?) :
        this(transport, listOf(baseUrl), accessToken)

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
            for ((n, base) in baseUrls.withIndex()) {
                val hasNext = n < baseUrls.lastIndex
                var answered = false
                var fallBack = false
                anthropic.client(ProviderConfig(token, base, null)).chat(request).collect { event ->
                    // A proxy that fails before saying anything (unreachable, or the platform refused to run it)
                    // is skipped for the next one. Once anything has streamed, the reply is this proxy's.
                    if (!answered && hasNext && event is LlmStreamEvent.Failed && proxyUnavailable(event)) {
                        fallBack = true
                    } else if (!fallBack) {
                        answered = true
                        emit(event)
                    }
                }
                if (!fallBack) return@flow
            }
        }
    }

    /**
     * A failure that is about the proxy rather than the request: no response at all, or a 5xx. The proxies'
     * own refusals (out of points, signed out) are 400s and go straight to the user.
     */
    private fun proxyUnavailable(event: LlmStreamEvent.Failed): Boolean {
        val http = event.cause as? LlmHttpException ?: return event.cause != null
        val status = http.statusCode ?: return true
        return status >= 500
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
