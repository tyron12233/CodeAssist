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
import kotlinx.serialization.json.JsonObject
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
        LlmModelInfo("claude-opus-5", "Claude Opus 5", supportsThinking = true),
        LlmModelInfo("claude-opus-4-8", "Claude Opus 4.8", supportsThinking = true),
        LlmModelInfo("claude-sonnet-5", "Claude Sonnet 5", supportsThinking = true),
        LlmModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", supportsThinking = false),
    )
    override val defaultModel: String = "claude-opus-5"
    override val managesContext: Boolean = true

    override fun client(config: ProviderConfig): LlmClient = LlmClient { request ->
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val sse = SseRequest(
            url = "$base/v1/messages",
            headers = buildMap {
                put("x-api-key", config.apiKey)
                put("anthropic-version", ANTHROPIC_VERSION)
                put("content-type", "application/json")
                betaHeader(request)?.let { put("anthropic-beta", it) }
            },
            jsonBody = buildBody(request),
            caCertificatePem = config.caCertificatePem,
        )
        stream(sse)
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val body = transport.get(
            "$base/v1/models?limit=1000",
            mapOf("x-api-key" to config.apiKey, "anthropic-version" to ANTHROPIC_VERSION),
            config.caCertificatePem,
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
        // Prompt caching: the system prompt, the tool set, and the conversation prefix are re-sent verbatim
        // on every step of a turn, so a cache breakpoint on each lets the API bill them once and reuse them
        // (big token + latency win over a long agentic loop). Breakpoints on a prefix below the provider
        // minimum are simply ignored, so this never hurts.
        request.system?.takeIf { it.isNotBlank() }?.let { put("system", systemBlocks(it)) }
        if (request.thinking && modelSupportsThinking(request.model)) {
            put("thinking", buildJsonObject { put("type", "adaptive") })
        }
        effortLevel(request.effort)?.let { level ->
            put("output_config", buildJsonObject { put("effort", level) })
        }
        val toolsArray = toolBlocks(request.tools, request.webSearch)
        if (toolsArray.isNotEmpty()) put("tools", toolsArray)
        put("context_management", contextManagement(request.webSearch))
        put("messages", cacheLastBlock(messages(request.messages, request.model)))
    }.toString()

    /**
     * Server-side trimming of a long conversation: once the prompt passes [CLEAR_TRIGGER_TOKENS], the oldest
     * tool results beyond the most recent [KEEP_TOOL_USES] are dropped before the model reads them.
     *
     * This does the job the host's own compactor would otherwise do, but against real token counts instead of a
     * character estimate. Clearing invalidates the cached prefix from the point it edits, which is why
     * `clear_at_least` is set: a clear only happens when it can free enough to be worth the re-write, rather
     * than nibbling away at the prefix and paying that cost repeatedly. Server tools are excluded — their
     * results are the model's own search output, not something it can re-fetch with a client tool call.
     */
    private fun contextManagement(webSearch: Boolean): JsonObject = buildJsonObject {
        put("edits", buildJsonArray {
            add(buildJsonObject {
                put("type", CLEAR_TOOL_USES)
                put("trigger", buildJsonObject { put("type", "input_tokens"); put("value", CLEAR_TRIGGER_TOKENS) })
                put("keep", buildJsonObject { put("type", "tool_uses"); put("value", KEEP_TOOL_USES) })
                put("clear_at_least", buildJsonObject { put("type", "input_tokens"); put("value", CLEAR_AT_LEAST_TOKENS) })
                if (webSearch) put("exclude_tools", buildJsonArray { add("web_search") })
            })
        })
    }

    /**
     * Maps the neutral effort level onto `output_config.effort`. The two sub-`low` levels exist for the OpenAI
     * dialect's tool-use constraint and have no counterpart here, so they become `low` rather than switching
     * thinking off — disabled thinking on this family has documented failure modes in a tool loop, where the
     * model can write a tool call into its visible text and the call simply never runs.
     */
    private fun effortLevel(effort: String?): String? = when (effort?.takeIf { it.isNotBlank() }) {
        null -> null
        LlmEffort.NONE, LlmEffort.MINIMAL -> LlmEffort.LOW
        else -> effort
    }

    /** The system prompt as a single cached text block, on the long TTL (see [ephemeral]). */
    private fun systemBlocks(text: String): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
            put("cache_control", ephemeral(LONG_TTL))
        })
    }

    /**
     * The tool declarations: the client tools, plus Anthropic's server-side `web_search` tool when
     * [webSearch] is on (the API runs the search itself and folds the results into the turn — the agent loop
     * never sees it as a client tool call, and the stream decoder simply ignores its `server_tool_use` /
     * `web_search_tool_result` blocks). A cache breakpoint on the last entry caches the whole tool block.
     */
    private fun toolBlocks(tools: List<dev.ide.agent.ToolSpec>, webSearch: Boolean): JsonArray {
        val blocks = ArrayList<JsonObject>(tools.size + 1)
        tools.forEach { spec ->
            blocks += buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("input_schema", AgentJson.parseToJsonElement(spec.parameters))
                // ToolSchemaBuilder already emits additionalProperties:false plus a required list, which is
                // what strict mode needs. It guarantees the arguments validate, so a malformed call can no
                // longer cost a whole extra round trip to discover and correct.
                put("strict", true)
            }
        }
        if (webSearch) {
            blocks += buildJsonObject {
                put("type", WEB_SEARCH_TOOL)
                put("name", "web_search")
                put("max_uses", WEB_SEARCH_MAX_USES)
            }
        }
        if (blocks.isEmpty()) return JsonArray(emptyList())
        val last = JsonObject(blocks.last() + ("cache_control" to ephemeral(LONG_TTL)))
        return JsonArray(blocks.dropLast(1) + last)
    }

    /**
     * Marks the last content block of the last *non-system* message as a cache breakpoint, caching the
     * conversation prefix incrementally as it grows across tool rounds. A trailing mid-conversation system
     * message is skipped deliberately: it carries the per-turn operator state, so it is the one part of the
     * request that changes every turn — putting the breakpoint before it keeps the cached prefix stable.
     */
    private fun cacheLastBlock(messages: JsonArray): JsonArray {
        val index = messages.indexOfLast { (it as? JsonObject)?.get("role").asStr() != "system" }
        if (index < 0) return messages
        val msg = messages[index] as? JsonObject ?: return messages
        val content = msg["content"] as? JsonArray ?: return messages
        val lastBlock = content.lastOrNull() as? JsonObject ?: return messages
        val newContent = JsonArray(content.dropLast(1) + JsonObject(lastBlock + ("cache_control" to ephemeral())))
        return JsonArray(messages.mapIndexed { i, m -> if (i == index) JsonObject(msg + ("content" to newContent)) else m })
    }

    /**
     * A cache breakpoint. The default 5-minute TTL is right for the conversation tail, which is re-sent within
     * seconds on the next step of a turn. The system prompt and tool set instead take [LONG_TTL]: they are
     * identical across a whole session, and the gap between one user message and the next in an IDE is
     * routinely longer than five minutes (read the diff, run a build, come back), so on the short TTL the
     * expensive shared prefix would expire and be rewritten at full price every single message. The API
     * requires longer-TTL entries to precede shorter ones, which the tools -> system -> messages render order
     * already satisfies.
     */
    private fun ephemeral(ttl: String? = null): JsonObject = buildJsonObject {
        put("type", "ephemeral")
        ttl?.let { put("ttl", it) }
    }

    /**
     * The betas this request needs, comma-separated, or null for none. The 1-hour cache TTL is generally
     * available and deliberately NOT listed here — it is a plain `cache_control` field, and naming a retired
     * beta would fail the request outright.
     */
    private fun betaHeader(request: LlmRequest): String? {
        val betas = ArrayList<String>(2)
        betas += CONTEXT_MANAGEMENT_BETA
        // Let the model keep reasoning across tool calls within a turn (reasons about tool results, not only
        // up front) — the agentic-loop quality lever.
        if (request.thinking && modelSupportsThinking(request.model)) betas += INTERLEAVED_THINKING_BETA
        return betas.joinToString(",").takeIf { it.isNotEmpty() }
    }

    /**
     * Whether the model accepts a `role: "system"` message inside `messages` — the operator channel that lets
     * per-turn state be refreshed without re-rendering the prompt ahead of the conversation. Unrecognized ids
     * (a proxy, a gateway, a model newer than this list) fall back to the `<system-reminder>` form, which every
     * model understands and which caches identically.
     */
    private fun modelSupportsSystemMessages(model: String): Boolean =
        SYSTEM_MESSAGE_MODELS.any { model.contains(it) }

    private fun messages(messages: List<LlmMessage>, model: String): JsonArray {
        val out = ArrayList<JsonObject>(messages.size)
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                LlmRole.SYSTEM -> {
                    addSystemMessage(out, plainText(m.content), model)
                    i++
                }
                LlmRole.USER -> {
                    out += buildJsonObject { put("role", "user"); put("content", userContent(m.content)) }
                    i++
                }
                LlmRole.ASSISTANT -> {
                    out += buildJsonObject { put("role", "assistant"); put("content", assistantContent(m.content)) }
                    i++
                }
                LlmRole.TOOL -> {
                    // Anthropic carries tool results as tool_result blocks inside a single user message.
                    val results = ArrayList<ContentPart.ToolResultPart>()
                    while (i < messages.size && messages[i].role == LlmRole.TOOL) {
                        messages[i].content.forEach { if (it is ContentPart.ToolResultPart) results += it }
                        i++
                    }
                    out += buildJsonObject {
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
                    }
                }
            }
        }
        return JsonArray(out)
    }

    /**
     * Renders a mid-conversation operator instruction. On a model that supports the `role: "system"` channel
     * it goes in as its own message, which the API renders after the cached history; elsewhere it is folded
     * into the preceding user turn as a `<system-reminder>` text block, which occupies the same position in
     * the prompt and so caches the same way.
     */
    private fun addSystemMessage(out: MutableList<JsonObject>, text: String, model: String) {
        if (text.isBlank()) return
        if (modelSupportsSystemMessages(model)) {
            out += buildJsonObject { put("role", "system"); put("content", text) }
            return
        }
        val reminder = buildJsonObject {
            put("type", "text")
            put("text", "<system-reminder>\n$text\n</system-reminder>")
        }
        val last = out.lastOrNull()
        val lastContent = last?.get("content") as? JsonArray
        if (last != null && last["role"].asStr() == "user" && lastContent != null) {
            out[out.size - 1] = JsonObject(last + ("content" to JsonArray(lastContent + reminder)))
        } else {
            out += buildJsonObject { put("role", "user"); put("content", JsonArray(listOf(reminder))) }
        }
    }

    private fun plainText(parts: List<ContentPart>): String =
        parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }

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
        const val INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14"

        /** Required for the `context_management` field below. */
        const val CONTEXT_MANAGEMENT_BETA = "context-management-2025-06-27"
        const val CLEAR_TOOL_USES = "clear_tool_uses_20250919"

        /** Prompt size at which the server starts dropping stale tool results. */
        const val CLEAR_TRIGGER_TOKENS = 100_000

        /** Recent tool use/result pairs always kept — the model's active working set. */
        const val KEEP_TOOL_USES = 4

        /** A clear must free at least this much, so it is worth the cache re-write it forces. */
        const val CLEAR_AT_LEAST_TOKENS = 20_000


        /** The long cache TTL, used for the system prompt and tool set (see `ephemeral`). */
        const val LONG_TTL = "1h"

        /** Model-id fragments whose models accept a `role: "system"` message inside `messages`. */
        val SYSTEM_MESSAGE_MODELS = listOf("opus-5", "opus-4-8", "fable-5", "mythos-5")

        /** Anthropic's server-side web-search tool (GA; no beta header required). */
        const val WEB_SEARCH_TOOL = "web_search_20250305"
        const val WEB_SEARCH_MAX_USES = 5
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
    private var cacheReadTokens = 0
    private var cacheWriteTokens = 0
    var stopReason: StopReason = StopReason.END_TURN
        private set
    var completed: Boolean = false
        private set

    fun usage(): TokenUsage = TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens)

    fun decode(data: String): List<LlmStreamEvent> {
        val json = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return emptyList()
        val out = ArrayList<LlmStreamEvent>(2)
        when (json["type"].asStr()) {
            // Anthropic reports input_tokens as the UNCACHED remainder, with the cache hit and write counted
            // separately — so the three are already disjoint and need no normalization.
            "message_start" -> json["message"].asObj()?.get("usage").asObj()?.let { usage ->
                usage["input_tokens"].asInt()?.let { inputTokens = it }
                usage["cache_read_input_tokens"].asInt()?.let { cacheReadTokens = it }
                usage["cache_creation_input_tokens"].asInt()?.let { cacheWriteTokens = it }
            }

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
