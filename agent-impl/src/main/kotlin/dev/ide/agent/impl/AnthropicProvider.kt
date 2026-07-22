package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
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
 * The Anthropic Messages API provider. Maps the neutral request to `POST /v1/messages` with adaptive
 * thinking and tools, and decodes the content-block SSE stream into [LlmStreamEvent]s. Thinking blocks are
 * echoed back with their signatures on later turns, as the API requires when a turn also calls a tool.
 */
class AnthropicProvider(private val transport: LlmTransport) : LlmProvider {
    override val id: String = "anthropic"
    override val displayName: String = "Anthropic (Claude)"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("claude-opus-4-8", "Claude Opus 4.8", supportsThinking = true),
        LlmModelInfo("claude-sonnet-5", "Claude Sonnet 5", supportsThinking = true),
        LlmModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", supportsThinking = false),
    )
    override val defaultModel: String = "claude-opus-4-8"

    override fun client(config: ProviderConfig): LlmClient = LlmClient { request ->
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val sse = SseRequest(
            url = "$base/v1/messages",
            headers = mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
                "content-type" to "application/json",
            ),
            jsonBody = buildBody(request),
        )
        stream(sse)
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val body = transport.get(
            "$base/v1/models?limit=1000",
            mapOf("x-api-key" to config.apiKey, "anthropic-version" to ANTHROPIC_VERSION),
        )
        val data = AgentJson.parseToJsonElement(body).asObj()?.get("data").asArr() ?: return@runCatching models
        data.mapNotNull { it.asObj() }
            .mapNotNull { m ->
                val modelId = m["id"].asStr() ?: return@mapNotNull null
                LlmModelInfo(
                    modelId,
                    m["display_name"].asStr() ?: modelId,
                    supportsThinking = modelId.contains("opus") || modelId.contains("sonnet"),
                )
            }
            .ifEmpty { models }
    }.getOrDefault(models)

    private fun stream(sse: SseRequest): Flow<LlmStreamEvent> = flow {
        val decoder = AnthropicStreamDecoder()
        transport.sse(sse).collect { data -> decoder.decode(data).forEach { emit(it) } }
        if (!decoder.completed) {
            emit(LlmStreamEvent.Usage(decoder.usage()))
            emit(LlmStreamEvent.Completed(decoder.stopReason))
        }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "Anthropic stream error", e)) }

    private fun modelSupportsThinking(model: String): Boolean =
        models.firstOrNull { it.id == model }?.supportsThinking ?: true

    private fun buildBody(request: LlmRequest): String = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        request.system?.takeIf { it.isNotBlank() }?.let { put("system", it) }
        if (request.thinking && modelSupportsThinking(request.model)) {
            put("thinking", buildJsonObject { put("type", "adaptive") })
        }
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { spec ->
                    add(buildJsonObject {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("input_schema", AgentJson.parseToJsonElement(spec.parameters))
                    })
                }
            })
        }
        put("messages", messages(request.messages))
    }.toString()

    private fun messages(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                LlmRole.SYSTEM -> i++ // system is a top-level field
                LlmRole.USER -> {
                    add(buildJsonObject { put("role", "user"); put("content", userContent(m.content)) })
                    i++
                }
                LlmRole.ASSISTANT -> {
                    add(buildJsonObject { put("role", "assistant"); put("content", assistantContent(m.content)) })
                    i++
                }
                LlmRole.TOOL -> {
                    // Anthropic carries tool results as tool_result blocks inside a single user message.
                    val results = ArrayList<ContentPart.ToolResultPart>()
                    while (i < messages.size && messages[i].role == LlmRole.TOOL) {
                        messages[i].content.forEach { if (it is ContentPart.ToolResultPart) results += it }
                        i++
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            results.forEach { r ->
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", r.toolCallId)
                                    put("content", r.content)
                                    if (r.isError) put("is_error", true)
                                })
                            }
                        })
                    })
                }
            }
        }
    }

    private fun userContent(parts: List<ContentPart>): JsonArray = buildJsonArray {
        parts.forEach { p ->
            if (p is ContentPart.Text) add(buildJsonObject { put("type", "text"); put("text", p.text) })
        }
    }

    private fun assistantContent(parts: List<ContentPart>): JsonArray = buildJsonArray {
        // Thinking blocks must precede tool_use and be echoed unchanged (signature included).
        parts.forEach { p ->
            if (p is ContentPart.Thinking) add(buildJsonObject {
                put("type", "thinking")
                put("thinking", p.text)
                p.signature?.let { put("signature", it) }
            })
        }
        parts.forEach { p ->
            when (p) {
                is ContentPart.Text -> if (p.text.isNotEmpty()) {
                    add(buildJsonObject { put("type", "text"); put("text", p.text) })
                }
                is ContentPart.ToolUse -> add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", p.id)
                    put("name", p.name)
                    put("input", AgentJson.parseToJsonElement(p.arguments.ifBlank { "{}" }))
                })
                else -> Unit
            }
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/** Stateful decoder for Anthropic's content-block SSE stream. One instance per request. */
internal class AnthropicStreamDecoder {
    private class Block(val kind: String) {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
        val thinking = StringBuilder()
        var signature: String? = null
    }

    private val blocks = HashMap<Int, Block>()
    private var inputTokens = 0
    private var outputTokens = 0
    var stopReason: StopReason = StopReason.END_TURN
        private set
    var completed: Boolean = false
        private set

    fun usage(): TokenUsage = TokenUsage(inputTokens, outputTokens)

    fun decode(data: String): List<LlmStreamEvent> {
        val json = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return emptyList()
        val out = ArrayList<LlmStreamEvent>(2)
        when (json["type"].asStr()) {
            "message_start" ->
                json["message"].asObj()?.get("usage").asObj()?.get("input_tokens").asInt()?.let { inputTokens = it }

            "content_block_start" -> {
                val idx = json["index"].asInt() ?: return out
                val cb = json["content_block"].asObj() ?: return out
                val block = Block(cb["type"].asStr() ?: "text")
                if (block.kind == "tool_use") {
                    block.id = cb["id"].asStr().orEmpty()
                    block.name = cb["name"].asStr().orEmpty()
                    out += LlmStreamEvent.ToolCallStarted(block.id, block.name)
                }
                blocks[idx] = block
            }

            "content_block_delta" -> {
                val idx = json["index"].asInt() ?: return out
                val delta = json["delta"].asObj() ?: return out
                when (delta["type"].asStr()) {
                    "text_delta" -> delta["text"].asStr()?.let { out += LlmStreamEvent.TextDelta(it) }
                    "thinking_delta" -> delta["thinking"].asStr()?.let {
                        blocks[idx]?.thinking?.append(it)
                        out += LlmStreamEvent.ThinkingDelta(it)
                    }
                    "signature_delta" -> delta["signature"].asStr()?.let {
                        val block = blocks[idx] ?: return@let
                        block.signature = (block.signature ?: "") + it
                    }
                    "input_json_delta" -> delta["partial_json"].asStr()?.let { frag ->
                        val block = blocks[idx]
                        if (block != null) {
                            block.args.append(frag)
                            out += LlmStreamEvent.ToolCallArgsDelta(block.id, frag)
                        }
                    }
                }
            }

            "content_block_stop" -> {
                val idx = json["index"].asInt() ?: return out
                val block = blocks[idx]
                when (block?.kind) {
                    "tool_use" -> out += LlmStreamEvent.ToolCallCompleted(block.id, block.name, block.args.toString())
                    "thinking" -> if (block.thinking.isNotEmpty()) {
                        out += LlmStreamEvent.ThinkingCompleted(block.thinking.toString(), block.signature)
                    }
                    else -> Unit
                }
            }

            "message_delta" -> {
                json["delta"].asObj()?.get("stop_reason").asStr()?.let { stopReason = mapStop(it) }
                json["usage"].asObj()?.get("output_tokens").asInt()?.let { outputTokens = it }
            }

            "message_stop" -> {
                completed = true
                out += LlmStreamEvent.Usage(usage())
                out += LlmStreamEvent.Completed(stopReason)
            }

            "error" -> out += LlmStreamEvent.Failed(
                LlmErrors.parseErrorObj(null, json["error"].asObj(), null).message,
            )
        }
        return out
    }

    private fun mapStop(s: String): StopReason = when (s) {
        "end_turn" -> StopReason.END_TURN
        "tool_use" -> StopReason.TOOL_USE
        "max_tokens" -> StopReason.MAX_TOKENS
        "stop_sequence" -> StopReason.STOP_SEQUENCE
        "refusal" -> StopReason.REFUSAL
        else -> StopReason.END_TURN
    }
}
