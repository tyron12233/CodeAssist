package dev.ide.agent

import kotlinx.coroutines.flow.Flow

/**
 * The provider-neutral LLM client model. A [LlmProvider] (Anthropic, OpenAI, Gemini, or a plugin-supplied
 * one) builds an [LlmClient] from a [ProviderConfig]; the client streams an [LlmRequest] as a flow of
 * normalized [LlmStreamEvent]s. Providers translate their own wire and streaming formats into this model,
 * so the agent loop and the chat UI never see a provider-specific shape.
 */

/** The author of a message in the conversation. Tool results carry [LlmRole.TOOL]. */
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

/** Token accounting reported by the provider. */
data class TokenUsage(val inputTokens: Int = 0, val outputTokens: Int = 0) {
    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}

/** Why the model stopped generating a turn. */
enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE, REFUSAL, ERROR }

/** A single request to the model. The loop sets [tools] and [thinking]; the UI picks [model]. */
data class LlmRequest(
    val model: String,
    val system: String?,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 8192,
    /** Request adaptive reasoning when the model supports it. Providers ignore it on models that do not. */
    val thinking: Boolean = true,
)

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

/** The per-provider configuration the user supplies (bring-your-own-key). */
data class ProviderConfig(val apiKey: String, val baseUrl: String? = null)

/** A named LLM provider. Implement this and register it to add a provider. */
interface LlmProvider {
    val id: String
    val displayName: String
    val models: List<LlmModelInfo>
    val defaultModel: String
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
