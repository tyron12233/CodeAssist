package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmEffort
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The OpenAI Chat Completions provider. A configurable base URL makes the same adapter serve
 * OpenAI-compatible gateways (OpenRouter, Ollama, LocalAI, and similar). The official endpoint takes
 * `max_completion_tokens`; compatible gateways generally take `max_tokens`, so the parameter name is
 * chosen from whether a custom base URL was set.
 *
 * [explicitPromptCaching] switches on the breakpoint markers that gateways fronting Anthropic models require:
 * OpenAI caches long prefixes by itself, but a Claude model reached through a gateway caches nothing unless
 * the request carries `cache_control` — see [OpenRouterProvider].
 */
class OpenAiProvider(
    private val transport: LlmTransport,
    private val explicitPromptCaching: Boolean = false,
) : LlmProvider {
    override val id: String = "openai"
    override val displayName: String = "OpenAI"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("gpt-5", "GPT-5"),
        LlmModelInfo("gpt-5-mini", "GPT-5 mini"),
        LlmModelInfo("gpt-4.1", "GPT-4.1"),
        LlmModelInfo("gpt-4o", "GPT-4o"),
    )
    override val defaultModel: String = "gpt-5"

    override fun client(config: ProviderConfig): LlmClient {
        // One key per client, i.e. per conversation. OpenAI routes same-key requests to the same cache, which
        // is what lifts the automatic prefix cache's hit rate; it is a routing hint only, never an identifier.
        val cacheKey = "codeassist-" + java.util.UUID.randomUUID().toString()
        return LlmClient { request ->
            val official = config.baseUrl.isNullOrBlank()
            val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
            val sse = SseRequest(
                url = "$base/v1/chat/completions",
                headers = mapOf(
                    "Authorization" to "Bearer ${config.apiKey}",
                    "content-type" to "application/json",
                ),
                jsonBody = buildBody(request, official, cacheKey),
                caCertificatePem = config.caCertificatePem,
            )
            stream(sse)
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val body = transport.get("$base/v1/models", mapOf("Authorization" to "Bearer ${config.apiKey}"), config.caCertificatePem)
        val data = AgentJson.parseToJsonElement(body).asObj()?.get("data").asArr() ?: return@runCatching models
        data.mapNotNull { it.asObj()?.get("id").asStr() }
            .filter { it.startsWith("gpt") || it.startsWith("o1") || it.startsWith("o3") || it.startsWith("o4") || it.startsWith("chatgpt") }
            .sorted()
            .map { LlmModelInfo(it, it) }
            .ifEmpty { models }
    }.getOrDefault(models)

    private fun stream(sse: SseRequest): Flow<LlmStreamEvent> = flow {
        val decoder = OpenAiStreamDecoder()
        transport.sse(sse).collect { data -> decoder.decode(data).forEach { emit(it) } }
        if (!decoder.completed) decoder.finish().forEach { emit(it) }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "OpenAI stream error", e)) }

    private fun buildBody(request: LlmRequest, official: Boolean, cacheKey: String): String = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        if (official) put("prompt_cache_key", cacheKey)
        put(if (official) "max_completion_tokens" else "max_tokens", request.maxTokens)
        // A reasoning model applies a default reasoning effort even when none is sent; on chat completions
        // that default plus function tools is rejected, so forwarding "none" is how a tool-using agent runs
        // against such a model. Non-reasoning models ignore the field. Sent only when explicitly requested.
        reasoningEffort(request.effort)?.let { put("reasoning_effort", it) }
        put("stream_options", buildJsonObject { put("include_usage", true) })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { spec ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", AgentJson.parseToJsonElement(spec.parameters))
                        })
                    })
                }
            })
        }
        put("messages", messages(request.system, request.messages, cacheable(request.model)))
    }.toString()

    /** Chat completions tops out at "high"; the levels above it are clamped rather than rejected. */
    private fun reasoningEffort(effort: String?): String? = when (effort?.takeIf { it.isNotBlank() }) {
        null -> null
        LlmEffort.XHIGH, LlmEffort.MAX -> LlmEffort.HIGH
        else -> effort
    }

    /** Whether to emit explicit cache breakpoints for this model (a gateway fronting an Anthropic model). */
    private fun cacheable(model: String): Boolean =
        explicitPromptCaching && ANTHROPIC_MODEL_MARKERS.any { model.contains(it, ignoreCase = true) }

    private fun messages(system: String?, messages: List<LlmMessage>, cacheable: Boolean): JsonArray = buildJsonArray {
        // The system prompt and the tool set are the big shared prefix, so that is where the one breakpoint
        // goes; on this dialect the tools ride alongside it and are covered by the same marker.
        system?.takeIf { it.isNotBlank() }?.let {
            add(buildJsonObject {
                put("role", "system")
                if (cacheable) put("content", cachedTextContent(it)) else put("content", it)
            })
        }
        messages.forEach { m ->
            when (m.role) {
                LlmRole.SYSTEM -> add(buildJsonObject { put("role", "system"); put("content", plainText(m.content)) })
                LlmRole.USER -> add(buildJsonObject { put("role", "user"); put("content", plainText(m.content)) })
                LlmRole.ASSISTANT -> add(assistantMessage(m.content))
                LlmRole.TOOL -> m.content.forEach { part ->
                    if (part is ContentPart.ToolResultPart) add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", part.toolCallId)
                        put("content", part.content)
                    })
                }
            }
        }
    }

    private fun assistantMessage(parts: List<ContentPart>) = buildJsonObject {
        put("role", "assistant")
        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val toolUses = parts.filterIsInstance<ContentPart.ToolUse>()
        if (text.isNotEmpty()) put("content", text)
        if (toolUses.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                toolUses.forEach { tu ->
                    add(buildJsonObject {
                        put("id", tu.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tu.name)
                            put("arguments", tu.arguments.ifBlank { "{}" })
                        })
                    })
                }
            })
        }
        if (text.isEmpty() && toolUses.isEmpty()) put("content", "")
    }

    private fun plainText(parts: List<ContentPart>): String =
        parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    /** A single text part carrying a cache breakpoint, the content-array form gateways expect. */
    private fun cachedTextContent(text: String): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
            put("cache_control", buildJsonObject { put("type", "ephemeral") })
        })
    }

    companion object {
        const val DEFAULT_BASE = "https://api.openai.com"

        /** Model-id fragments that identify an Anthropic model behind an OpenAI-dialect gateway. */
        val ANTHROPIC_MODEL_MARKERS = listOf("anthropic/", "claude")
    }
}

/** Decoder for OpenAI Chat Completions SSE. Tool-call fragments accumulate by index and flush at end. */
internal class OpenAiStreamDecoder {
    private class Call {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
        var started = false
    }

    private val calls = LinkedHashMap<Int, Call>()
    private var inputTokens = 0
    private var outputTokens = 0
    private var cacheReadTokens = 0
    private var stopReason: StopReason = StopReason.END_TURN
    var completed: Boolean = false
        private set

    fun decode(data: String): List<LlmStreamEvent> {
        if (data.trim() == "[DONE]") return finish()
        val json = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return emptyList()
        val out = ArrayList<LlmStreamEvent>(2)

        json["usage"].asObj()?.let { usage ->
            usage["prompt_tokens"].asInt()?.let { inputTokens = it }
            usage["completion_tokens"].asInt()?.let { outputTokens = it }
            // This dialect counts cached tokens INSIDE prompt_tokens; the neutral model keeps the uncached
            // remainder in inputTokens, so subtract the cached share rather than double-counting it.
            usage["prompt_tokens_details"].asObj()?.get("cached_tokens").asInt()?.let { cacheReadTokens = it }
        }

        val choice = json["choices"].asArr()?.firstOrNull().asObj() ?: return out
        choice["finish_reason"].asStr()?.let { stopReason = mapStop(it) }

        val delta = choice["delta"].asObj() ?: return out
        delta["content"].asStr()?.let { if (it.isNotEmpty()) out += LlmStreamEvent.TextDelta(it) }

        delta["tool_calls"].asArr()?.forEach { tcElement ->
            val tc = tcElement.asObj() ?: return@forEach
            val index = tc["index"].asInt() ?: 0
            val call = calls.getOrPut(index) { Call() }
            tc["id"].asStr()?.let { call.id = it }
            val fn = tc["function"].asObj()
            fn?.get("name").asStr()?.let { call.name = it }
            if (!call.started && call.id.isNotEmpty()) {
                call.started = true
                out += LlmStreamEvent.ToolCallStarted(call.id, call.name)
            }
            fn?.get("arguments").asStr()?.let { frag ->
                call.args.append(frag)
                if (call.id.isNotEmpty()) out += LlmStreamEvent.ToolCallArgsDelta(call.id, frag)
            }
        }
        return out
    }

    /** Emits the assembled tool calls, usage, and completion once the stream ends. */
    fun finish(): List<LlmStreamEvent> {
        if (completed) return emptyList()
        completed = true
        val out = ArrayList<LlmStreamEvent>()
        calls.values.forEach { c ->
            if (c.id.isNotEmpty()) out += LlmStreamEvent.ToolCallCompleted(c.id, c.name, c.args.toString())
        }
        out += LlmStreamEvent.Usage(
            TokenUsage((inputTokens - cacheReadTokens).coerceAtLeast(0), outputTokens, cacheReadTokens),
        )
        out += LlmStreamEvent.Completed(stopReason)
        return out
    }

    private fun mapStop(s: String): StopReason = when (s) {
        "stop" -> StopReason.END_TURN
        "length" -> StopReason.MAX_TOKENS
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "content_filter" -> StopReason.REFUSAL
        else -> StopReason.END_TURN
    }
}
