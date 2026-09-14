package dev.ide.agent

import kotlinx.coroutines.flow.Flow

/**
 * The provider-neutral LLM client model. A [LlmProvider] (Anthropic, OpenAI, Gemini, or a plugin-supplied
 * one) builds an [LlmClient] from a [ProviderConfig]; the client streams an [LlmRequest] as a flow of
 * normalized [LlmStreamEvent]s. Providers translate their own wire and streaming formats into this model,
 * so the agent loop and the chat UI never see a provider-specific shape.
 */

/**
 * The author of a message in the conversation. Tool results carry [LlmRole.TOOL]. A [LlmRole.SYSTEM] message
 * inside [LlmRequest.messages] is an operator instruction that applies from that point on — it belongs AFTER
 * the history, not in [LlmRequest.system], so that refreshing it leaves the cached prompt prefix intact.
 * Providers whose wire has no mid-conversation system role render it as a `<system-reminder>` block folded
 * into the preceding user turn, which caches the same way.
 */
enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }

/** A piece of message content. A single message may interleave several parts. */
sealed interface ContentPart {
    /** Plain assistant or user text. */
    data class Text(val text: String) : ContentPart

    /** Model reasoning, when the provider returns it (adaptive thinking). [signature] is opaque and, when
     *  present, must be echoed back unchanged on the same provider in later turns. */
    data class Thinking(val text: String, val signature: String? = null) : ContentPart

    /** A model request to call a tool. [arguments] is the raw JSON argument object as a string. [signature]
     *  is a provider-opaque token echoed back on the tool_use when continuing (Gemini's thought signature). */
    data class ToolUse(val id: String, val name: String, val arguments: String, val signature: String? = null) : ContentPart

    /** The result of a tool call, referenced back to its [ToolUse.id]. */
    data class ToolResultPart(val toolCallId: String, val content: String, val isError: Boolean = false) : ContentPart
}

/** One turn of the conversation. */
data class LlmMessage(val role: LlmRole, val content: List<ContentPart>) {
    companion object {
        fun user(text: String): LlmMessage = LlmMessage(LlmRole.USER, listOf(ContentPart.Text(text)))
        fun assistant(parts: List<ContentPart>): LlmMessage = LlmMessage(LlmRole.ASSISTANT, parts)
        fun toolResult(toolCallId: String, content: String, isError: Boolean = false): LlmMessage =
            LlmMessage(LlmRole.TOOL, listOf(ContentPart.ToolResultPart(toolCallId, content, isError)))
    }
}

/**
 * Token accounting reported by the provider. [inputTokens] is normalized across providers to the *uncached*
 * prompt remainder: providers that report a total prompt count including cache hits (OpenAI, Gemini) have the
 * cached share subtracted, so [promptTokens] is always the whole prompt and the three input figures never
 * double-count. A prompt-cache read bills at a fraction of the input rate and a write at a small premium, so
 * the split is the only way to tell whether caching is actually working.
 */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Prompt tokens served from the provider's cache this request. */
    val cacheReadTokens: Int = 0,
    /** Prompt tokens written to the provider's cache this request. */
    val cacheWriteTokens: Int = 0,
) {
    /** The whole prompt: the uncached remainder plus whatever was read from and written to the cache. */
    val promptTokens: Int get() = inputTokens + cacheReadTokens + cacheWriteTokens

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens + other.cacheWriteTokens,
    )
}

/** Why the model stopped generating a turn. */
enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE, REFUSAL, ERROR }

/** A single request to the model. The loop sets [tools] and [thinking]; the UI picks [model]. */
data class LlmRequest(
    val model: String,
    /** The STABLE system prefix. Anything that changes during a conversation (permission mode, live project
     *  context) belongs in a trailing [LlmRole.SYSTEM] message instead — editing this field re-renders the
     *  prompt ahead of the whole conversation and throws away every cached turn. */
    val system: String?,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 8192,
    /** Request adaptive reasoning when the model supports it. Providers ignore it on models that do not. */
    val thinking: Boolean = true,
    /** Optional cap on the provider's reasoning ("thinking") tokens; null leaves the model default. Only
     *  providers that expose a reasoning budget honor it (Gemini 2.5's `thinkingConfig`); others ignore it.
     *  A lower budget trims token spend, which matters on token-metered free tiers. */
    val thinkingBudget: Int? = null,
    /** Offer the provider's own server-side web search when it supports one (Anthropic's `web_search` tool,
     *  Gemini's `google_search` grounding). The provider runs the search itself and folds the results into the
     *  turn — it is not a client-executed [ToolSpec]. Providers without native search ignore the flag. */
    val webSearch: Boolean = false,
    /**
     * How hard the model should think, as one of [LlmEffort]; null leaves the provider default. This is the
     * main cost/quality lever after prompt caching: routine work is much cheaper at `low`/`medium` and rarely
     * worse, while `high` and above earn their cost on real coding tasks. Every provider honors it, each
     * mapping it onto its own control.
     *
     * Pin it for a whole conversation rather than varying it per request — an effort change invalidates the
     * messages cache on every provider.
     */
    val effort: String? = null,
)

/**
 * The neutral effort levels. [NONE] and [MINIMAL] exist for the OpenAI dialect, where newer reasoning models
 * reject function tools combined with reasoning on `/v1/chat/completions`, so `none` is what lets a tool-using
 * agent run against them at all; providers without that constraint treat them as the lowest real effort.
 */
object LlmEffort {
    const val NONE = "none"
    const val MINIMAL = "minimal"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val XHIGH = "xhigh"
    const val MAX = "max"

    /** The levels in increasing order of spend, for a settings menu. */
    val ALL = listOf(NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX)
}

/** A normalized streaming event. Providers emit these; the agent loop assembles them into a turn. */
sealed interface LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent
    data class ThinkingDelta(val text: String) : LlmStreamEvent
    /** A completed reasoning block. [signature] is the provider's opaque token, echoed back unchanged on
     *  later turns of the same provider (Anthropic requires it when a thinking turn also calls a tool). */
    data class ThinkingCompleted(val text: String, val signature: String?) : LlmStreamEvent
    data class ToolCallStarted(val id: String, val name: String) : LlmStreamEvent
    /** An incremental fragment of a tool call's JSON arguments. */
    data class ToolCallArgsDelta(val id: String, val partialJson: String) : LlmStreamEvent
    /** A fully-assembled tool call. Providers that do not stream arguments emit only this. [signature] is a
     *  provider-opaque token to echo back on the tool call when continuing (Gemini's thought signature). */
    data class ToolCallCompleted(val id: String, val name: String, val arguments: String, val signature: String? = null) : LlmStreamEvent
    data class Usage(val usage: TokenUsage) : LlmStreamEvent
    data class Completed(val stopReason: StopReason) : LlmStreamEvent
    data class Failed(val message: String, val cause: Throwable? = null) : LlmStreamEvent
}

/** Streams a single model turn. Cancellation of the collector cancels the underlying request. */
fun interface LlmClient {
    fun chat(request: LlmRequest): Flow<LlmStreamEvent>
}

/** Metadata for a model a provider offers. */
data class LlmModelInfo(val id: String, val displayName: String, val supportsThinking: Boolean = false)

/**
 * The per-provider configuration the user supplies (bring-your-own-key). [caCertificatePem] is an optional
 * additional CA certificate (PEM, one or more `-----BEGIN CERTIFICATE-----` blocks) to trust for this provider's
 * endpoint — for a custom `baseUrl` whose server uses a private or regional CA not in the system trust store
 * (e.g. GigaChat's "Russian Trusted Root CA"). It is trusted IN ADDITION to the system CAs; the certificate
 * chain is still fully validated, so this is not an insecure "trust all" bypass.
 */
data class ProviderConfig(val apiKey: String, val baseUrl: String? = null, val caCertificatePem: String? = null)

/** A named LLM provider. Implement this and register it to add a provider. */
interface LlmProvider {
    val id: String
    val displayName: String
    val models: List<LlmModelInfo>
    val defaultModel: String

    /**
     * True when the provider trims a long conversation itself, server-side. It does so against real token
     * counts rather than a client-side character estimate, so where this is true the host stands its own
     * compaction down instead of running two trimmers that would fight over the same prompt prefix.
     */
    val managesContext: Boolean get() = false

    fun client(config: ProviderConfig): LlmClient

    /** Query the provider's available models with the user's credentials. Defaults to the static [models]
     *  list; providers override to fetch live and fall back to [models] on any error. */
    suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = models
}

/** Resolves providers by id. Built-in providers are registered by AgentPlugin; plugins may add more. */
interface LlmProviderRegistry {
    val providers: List<LlmProvider>
    fun provider(id: String): LlmProvider?
}

class SimpleLlmProviderRegistry(override val providers: List<LlmProvider>) : LlmProviderRegistry {
    private val byId: Map<String, LlmProvider> = providers.associateBy { it.id }
    override fun provider(id: String): LlmProvider? = byId[id]
}
