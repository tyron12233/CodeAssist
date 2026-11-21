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
 * CodeAssist Free AI: the project's `ai-proxy` (a Cloudflare Worker, with a Supabase edge function as fallback),
 * paid for out of the user's points wallet instead of their own API key.
 *
 * The proxy speaks the Anthropic Messages API whatever model serves a tier (it translates for models that don't),
 * so the wire, the stream decoder and prompt caching are [AnthropicProvider]'s, unchanged. What differs is the
 * credential: the `x-api-key` header carries the user's Supabase access token, fetched per request from
 * [accessToken] because it lasts only an hour. The [ProviderConfig.apiKey] the agent passes in is a placeholder
 * and is ignored. The tiers and their names come from the proxy ([listModels]).
 *
 * The proxy enforces every limit itself (models, output size, effort, context size, the wallet). Nothing
 * here is a limit; the client is open source and a limit in it would be advice.
 */
class CodeAssistFreeProvider(
    private val transport: LlmTransport,
    /** The proxies to use, in order: the first is primary, the rest are tried when it fails before answering. */
    private val baseUrls: List<String>,
    /** A current access token, or null when signed out. May block on a refresh; called off the main thread. */
    private val accessToken: () -> String?,
) : LlmProvider {
    constructor(transport: LlmTransport, baseUrl: String, accessToken: () -> String?) :
        this(transport, listOf(baseUrl), accessToken)

    private val anthropic = AnthropicProvider(transport, mapOf(MODEL_NAMES_HEADER to "1"))

    override val id: String = ID
    override val displayName: String = "CodeAssist Free AI"

    /**
     * Shown only until the proxy's list has been read (signed out, or offline). The ids are the proxy's TIER ids,
     * stable across the models that serve them; the names are deliberately not model names, because which model
     * serves a tier is the server's to change. See [listModels].
     */
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo(HAIKU, "Free AI", supportsThinking = true),
        LlmModelInfo(SONNET, "Free AI (premium)", supportsThinking = true),
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

    /**
     * The tiers the proxy serves and their current names, from its `GET /v1/models`, so the server can change which
     * model serves a tier (and add or retire tiers) without an app update. The built-in [models] when signed out or
     * when no proxy answers.
     */
    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> {
        val token = withContext(Dispatchers.IO) { accessToken() }
        if (token.isNullOrBlank()) return models
        for (base in baseUrls) {
            val listed = runCatching { fetchModels(base, token) }.getOrNull()
            if (!listed.isNullOrEmpty()) return listed
        }
        return models
    }

    private suspend fun fetchModels(base: String, token: String): List<LlmModelInfo> {
        val body = transport.get(
            "${base.trimEnd('/')}/v1/models",
            mapOf("x-api-key" to token, "anthropic-version" to AnthropicProvider.ANTHROPIC_VERSION, MODEL_NAMES_HEADER to "1"),
        )
        val data = AgentJson.parseToJsonElement(body).asObj()?.get("data").asArr() ?: return emptyList()
        return data.mapNotNull { it.asObj() }.mapNotNull { m ->
            val id = m["id"].asStr()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LlmModelInfo(id, m["display_name"].asStr()?.takeIf { it.isNotBlank() } ?: id, supportsThinking = true)
        }
    }

    companion object {
        const val ID = "codeassist"
        /** The default tier's id (named for the model that first served it; see [models]). */
        const val HAIKU = "claude-haiku-5-5"
        /** The premium tier's id. */
        const val SONNET = "claude-sonnet-5-5"

        /**
         * Tells the proxy this build shows the names it reads from `GET /v1/models`, so the proxy need not tell the
         * user in-chat which model now serves a tier (it does for builds that showed built-in names).
         */
        const val MODEL_NAMES_HEADER = "x-codeassist-model-names"
        const val SIGNED_OUT_MESSAGE = "Sign in to use CodeAssist Free AI (Settings > AI)."
    }
}
