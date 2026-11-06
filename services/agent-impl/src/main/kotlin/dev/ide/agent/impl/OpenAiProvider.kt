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
 * The OpenAI provider. The official endpoint is driven through the Responses API ([OpenAiResponses]); a
 * configurable base URL makes the same adapter serve OpenAI-compatible gateways (OpenRouter, Ollama, LocalAI, and
 * similar) over Chat Completions, which is what they implement, with the `max_tokens` they generally take.
 *
 * [explicitPromptCaching] switches on the breakpoint markers that gateways fronting Anthropic (and Alibaba Qwen)
 * models require: OpenAI, DeepSeek, Gemini 2.5+ and most others cache long prefixes by themselves, but a Claude
 * model reached through a gateway caches nothing unless the request carries `cache_control`. [stickySessions]
 * sends a per-conversation `session_id`, which OpenRouter uses to keep routing a conversation to the provider
 * endpoint that holds its cache. Both are OpenRouter's dialect — see [OpenRouterProvider].
 */
class OpenAiProvider(
    private val transport: LlmTransport,
    private val explicitPromptCaching: Boolean = false,
    private val stickySessions: Boolean = false,
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
            val base = normalizeBase(config.baseUrl) ?: DEFAULT_BASE
            val official = base == DEFAULT_BASE
            val headers = authHeaders(config.apiKey) + ("content-type" to "application/json")
            if (official) {
                // OpenAI itself speaks the Responses API, which takes reasoning and function tools together.
                val sse = SseRequest(
                    url = base + OpenAiResponses.PATH,
                    headers = headers,
                    jsonBody = OpenAiResponses.body(request, cacheKey),
                    caCertificatePem = config.caCertificatePem,
                )
                streamResponses(sse)
            } else {
                // Compatible gateways implement Chat Completions, not the Responses API.
                val sse = SseRequest(
                    url = "$base/v1/chat/completions",
                    headers = headers,
                    jsonBody = buildBody(request, cacheKey),
                    caCertificatePem = config.caCertificatePem,
                )
                stream(sse)
            }
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = normalizeBase(config.baseUrl) ?: DEFAULT_BASE
        val official = base == DEFAULT_BASE
        val body = transport.get("$base/v1/models", authHeaders(config.apiKey), config.caCertificatePem)
        val data = AgentJson.parseToJsonElement(body).asObj()?.get("data").asArr() ?: return@runCatching models
        // OpenAI's own list mixes in embedding, audio and image models; a gateway's list is what it serves.
        data.mapNotNull { it.asObj()?.get("id").asStr() }
            .filter { !official || it.startsWith("gpt") || it.startsWith("o1") || it.startsWith("o3") || it.startsWith("o4") || it.startsWith("chatgpt") }
            .sorted()
            .map { LlmModelInfo(it, it) }
            .ifEmpty { models }
    }.getOrDefault(models)

    private fun stream(sse: SseRequest): Flow<LlmStreamEvent> = flow {
        val decoder = OpenAiStreamDecoder()
        transport.sse(sse).collect { data -> decoder.decode(data).forEach { emit(it) } }
        if (!decoder.completed) decoder.finish().forEach { emit(it) }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "OpenAI stream error", e)) }

    private fun streamResponses(sse: SseRequest): Flow<LlmStreamEvent> = flow {
        val decoder = OpenAiResponsesDecoder()
        transport.sse(sse).collect { data -> decoder.decode(data).forEach { emit(it) } }
        if (!decoder.completed) decoder.finish(null, null).forEach { emit(it) }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "OpenAI stream error", e)) }

    private fun buildBody(request: LlmRequest, sessionKey: String): String = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        // Without it OpenRouter only turns sticky after it has seen a cache hit, so the second request of a
        // conversation can land on a different provider endpoint and write the whole prefix again.
        if (stickySessions) put("session_id", sessionKey)
        // Claude: a second, automatic breakpoint that OpenRouter puts on the last cacheable block, i.e. the
        // conversation tail. The system breakpoint alone re-billed every tool result of an agent turn at full
        // rate on each step; this is the tail breakpoint the native Anthropic provider has always set. It is
        // the request-level form because the tail is usually a `tool` message, which takes no per-block marker.
        if (anthropicCaching(request.model)) put("cache_control", buildJsonObject { put("type", "ephemeral") })
        put("max_tokens", request.maxTokens)
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
        put("messages", messages(request.system, request.messages, request.model))
    }.toString()

    /** Chat completions tops out at "high"; the levels above it are clamped rather than rejected. */
    private fun reasoningEffort(effort: String?): String? = when (effort?.takeIf { it.isNotBlank() }) {
        null -> null
        LlmEffort.XHIGH, LlmEffort.MAX -> LlmEffort.HIGH
        else -> effort
    }

    /** A gateway fronting an Anthropic model: explicit breakpoints, a 1-hour system TTL, the automatic tail. */
    private fun anthropicCaching(model: String): Boolean =
        explicitPromptCaching && ANTHROPIC_MODEL_MARKERS.any { model.contains(it, ignoreCase = true) }

    /** Whether the system prompt gets an explicit breakpoint: Claude, or a Qwen model that only caches on request. */
    private fun cacheable(model: String): Boolean =
        anthropicCaching(model) || (explicitPromptCaching && EXPLICIT_CACHE_MODELS.any { model.startsWith(it, ignoreCase = true) })

    private fun messages(system: String?, messages: List<LlmMessage>, model: String): JsonArray = buildJsonArray {
        val cacheable = cacheable(model)
        // Claude only: think-time between messages routinely passes 5 minutes, after which the default TTL had
        // the system prompt and tool set rewritten at full price. Other providers have a fixed TTL.
        val longTtl = anthropicCaching(model)
        // The system prompt and the tool set are the big shared prefix, so that is where the one breakpoint
        // goes; on this dialect the tools ride alongside it and are covered by the same marker.
        system?.takeIf { it.isNotBlank() }?.let {
            add(buildJsonObject {
                put("role", "system")
                if (cacheable) put("content", cachedTextContent(it, longTtl)) else put("content", it)
            })
        }
        // A `tool` message is text-only on this dialect, so images a tool produced are held back and sent in a
        // user message right after the run of tool results they belong to.
        val toolImages = ArrayList<ContentPart.Image>()
        fun flushToolImages() {
            if (toolImages.isEmpty()) return
            add(buildJsonObject {
                put("role", "user")
                put("content", userContent(listOf(ContentPart.Text("Images returned by the tool calls above:")) + toolImages))
            })
            toolImages.clear()
        }
        messages.forEach { m ->
            if (m.role != LlmRole.TOOL) flushToolImages()
            when (m.role) {
                LlmRole.SYSTEM -> add(buildJsonObject { put("role", "system"); put("content", plainText(m.content)) })
                LlmRole.USER -> add(buildJsonObject {
                    put("role", "user")
                    if (m.content.any { it is ContentPart.Image }) put("content", userContent(m.content))
                    else put("content", plainText(m.content))
                })
                LlmRole.ASSISTANT -> add(assistantMessage(m.content))
                LlmRole.TOOL -> m.content.forEach { part ->
                    if (part is ContentPart.ToolResultPart) {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", part.toolCallId)
                            put("content", part.content)
                        })
                        toolImages += part.images
                    }
                }
            }
        }
        flushToolImages()
    }

    /** The content-array form, needed as soon as a user turn carries an image. */
    private fun userContent(parts: List<ContentPart>): JsonArray = buildJsonArray {
        parts.forEach { p ->
            when (p) {
                is ContentPart.Text -> add(buildJsonObject { put("type", "text"); put("text", p.text) })
                is ContentPart.Image -> add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "data:${p.mediaType};base64,${p.data}") })
                })
                else -> Unit
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
    private fun cachedTextContent(text: String, longTtl: Boolean): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
            put("cache_control", buildJsonObject {
                put("type", "ephemeral")
                if (longTtl) put("ttl", "1h")
            })
        })
    }

    companion object {
        const val DEFAULT_BASE = "https://api.openai.com"

        private val URL_TOKEN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val ENDPOINT_SUFFIXES = listOf("/chat/completions", "/completions", "/responses", "/models")

        /**
         * The server root that `/v1/...` paths are appended to, from what a user typed or pasted. Gateways document
         * their base URL in the OpenAI SDK's form, which already ends in `/v1` (Ollama's is
         * `http://localhost:11434/v1`), and some users paste a full endpoint; both would otherwise double up into
         * `/v1/v1/...`. A paste that carries more than the URL (a "Base URL: ... / API Key: ..." block) keeps only
         * the first URL in it: OkHttp silently drops line breaks, so the rest used to end up in the request path.
         */
        fun normalizeBase(raw: String?): String? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            var base = URL_TOKEN.find(text)?.value ?: text.substringBefore('\n').trim().substringBefore(' ')
            base = base.trimEnd('/')
            ENDPOINT_SUFFIXES.firstOrNull { base.endsWith(it, ignoreCase = true) }?.let { base = base.dropLast(it.length).trimEnd('/') }
            if (base.endsWith("/v1", ignoreCase = true)) base = base.dropLast(3)
            return base.takeIf { it.isNotEmpty() }
        }

        /** A local server (Ollama, LM Studio, llama.cpp) needs no key; an empty bearer is not sent at all. */
        internal fun authHeaders(apiKey: String): Map<String, String> =
            apiKey.trim().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

        /** Model-id fragments that identify an Anthropic model behind an OpenAI-dialect gateway. */
        val ANTHROPIC_MODEL_MARKERS = listOf("anthropic/", "claude")

        /** OpenRouter models served by Alibaba, which caches only behind an explicit `cache_control` marker. */
        val EXPLICIT_CACHE_MODELS = listOf(
            "qwen/qwen3-max", "qwen/qwen-plus", "qwen/qwen3.6-plus", "qwen/qwen3-coder-plus", "qwen/qwen3-coder-flash",
        )
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
    private var cacheWriteTokens = 0
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
            usage["prompt_tokens_details"].asObj()?.let { details ->
                details["cached_tokens"].asInt()?.let { cacheReadTokens = it }
                // OpenRouter reports writes for explicitly cached models (Claude); they are inside prompt_tokens too.
                details["cache_write_tokens"].asInt()?.let { cacheWriteTokens = it }
            }
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
            TokenUsage((inputTokens - cacheReadTokens - cacheWriteTokens).coerceAtLeast(0), outputTokens, cacheReadTokens, cacheWriteTokens),
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
