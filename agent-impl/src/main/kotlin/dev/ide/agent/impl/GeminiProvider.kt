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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Google Gemini provider (`streamGenerateContent?alt=sse`). Gemini uses `user`/`model` roles and
 * carries tool results as `functionResponse` parts. It has no per-call id, so the tool name is used as the
 * correlation id. Its function-declaration schema does not accept `additionalProperties`, so that key is
 * stripped from tool parameter schemas.
 */
class GeminiProvider(private val transport: LlmTransport) : LlmProvider {
    override val id: String = "gemini"
    override val displayName: String = "Google Gemini"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro"),
        LlmModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash"),
    )
    override val defaultModel: String = "gemini-2.5-pro"

    override fun client(config: ProviderConfig): LlmClient = LlmClient { request ->
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val sse = SseRequest(
            url = "$base/v1beta/models/${request.model}:streamGenerateContent?alt=sse",
            headers = mapOf(
                "x-goog-api-key" to config.apiKey,
                "content-type" to "application/json",
            ),
            jsonBody = buildBody(request),
        )
        stream(sse)
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val body = transport.get("$base/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to config.apiKey))
        val listed = AgentJson.parseToJsonElement(body).asObj()?.get("models").asArr() ?: return@runCatching models
        listed.mapNotNull { it.asObj() }
            .filter { m -> m["supportedGenerationMethods"].asArr()?.any { it.asStr() == "generateContent" } == true }
            .mapNotNull { m ->
                val name = m["name"].asStr()?.removePrefix("models/") ?: return@mapNotNull null
                LlmModelInfo(name, m["displayName"].asStr() ?: name)
            }
            .filter { it.id.startsWith("gemini") }
            .ifEmpty { models }
    }.getOrDefault(models)

    private fun stream(sse: SseRequest): Flow<LlmStreamEvent> = flow {
        val decoder = GeminiStreamDecoder()
        transport.sse(sse).collect { data -> decoder.decode(data).forEach { emit(it) } }
        decoder.finish().forEach { emit(it) }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "Gemini stream error", e)) }

    private fun buildBody(request: LlmRequest): String = buildJsonObject {
        request.system?.takeIf { it.isNotBlank() }?.let {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", it) }) })
            })
        }
        put("contents", contents(request.messages))
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("function_declarations", buildJsonArray {
                        request.tools.forEach { spec ->
                            add(buildJsonObject {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("parameters", stripAdditionalProperties(AgentJson.parseToJsonElement(spec.parameters)))
                            })
                        }
                    })
                })
            })
        }
        put("generationConfig", buildJsonObject { put("maxOutputTokens", request.maxTokens) })
    }.toString()

    private fun contents(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                LlmRole.SYSTEM -> i++ // carried in system_instruction
                LlmRole.USER -> {
                    add(buildJsonObject { put("role", "user"); put("parts", userParts(m.content)) })
                    i++
                }
                LlmRole.ASSISTANT -> {
                    add(buildJsonObject { put("role", "model"); put("parts", modelParts(m.content)) })
                    i++
                }
                LlmRole.TOOL -> {
                    val results = ArrayList<ContentPart.ToolResultPart>()
                    while (i < messages.size && messages[i].role == LlmRole.TOOL) {
                        messages[i].content.forEach { if (it is ContentPart.ToolResultPart) results += it }
                        i++
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            results.forEach { r ->
                                add(buildJsonObject {
                                    put("functionResponse", buildJsonObject {
                                        put("name", r.toolCallId.substringBefore('#'))
                                        put("response", buildJsonObject { put("result", r.content) })
                                    })
                                })
                            }
                        })
                    })
                }
            }
        }
    }

    private fun userParts(parts: List<ContentPart>): JsonArray = buildJsonArray {
        parts.forEach { if (it is ContentPart.Text) add(buildJsonObject { put("text", it.text) }) }
    }

    private fun modelParts(parts: List<ContentPart>): JsonArray = buildJsonArray {
        parts.forEach { p ->
            when (p) {
                is ContentPart.Text -> if (p.text.isNotEmpty()) add(buildJsonObject { put("text", p.text) })
                is ContentPart.ToolUse -> add(buildJsonObject {
                    put("functionCall", buildJsonObject {
                        put("name", p.name)
                        put("args", AgentJson.parseToJsonElement(p.arguments.ifBlank { "{}" }))
                    })
                    // Echo the thought signature captured on the way in (required for Gemini tool use).
                    p.signature?.let { put("thoughtSignature", it) }
                })
                else -> Unit
            }
        }
    }

    /** Removes `additionalProperties`, which Gemini's function-declaration schema rejects. */
    private fun stripAdditionalProperties(element: JsonElement): JsonElement {
        val obj = element.asObj() ?: return element
        return JsonObject(obj.filterKeys { it != "additionalProperties" })
    }

    companion object {
        const val DEFAULT_BASE = "https://generativelanguage.googleapis.com"
    }
}

/** Decoder for Gemini's SSE candidate stream. Function calls arrive whole (no streamed arguments). */
internal class GeminiStreamDecoder {
    private var inputTokens = 0
    private var outputTokens = 0
    private var stopReason: StopReason = StopReason.END_TURN
    private var sawCompletion = false
    private val usedIds = HashMap<String, Int>()
    var completed: Boolean = false
        private set

    fun decode(data: String): List<LlmStreamEvent> {
        val json = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return emptyList()
        val out = ArrayList<LlmStreamEvent>()

        json["usageMetadata"].asObj()?.let { usage ->
            usage["promptTokenCount"].asInt()?.let { inputTokens = it }
            usage["candidatesTokenCount"].asInt()?.let { outputTokens = it }
        }

        val candidate = json["candidates"].asArr()?.firstOrNull().asObj()
        candidate?.get("content").asObj()?.get("parts").asArr()?.forEach { partElement ->
            val part = partElement.asObj() ?: return@forEach
            part["text"].asStr()?.let { if (it.isNotEmpty()) out += LlmStreamEvent.TextDelta(it) }
            part["functionCall"].asObj()?.let { fc ->
                val name = fc["name"].asStr().orEmpty()
                val id = correlationId(name)
                val args = fc["args"]?.toString() ?: "{}"
                // Gemini 2.5 returns a per-part thought signature that MUST be echoed back on the functionCall
                // when continuing, or tool use fails with "missing a thought signature".
                val signature = part["thoughtSignature"].asStr()
                out += LlmStreamEvent.ToolCallStarted(id, name)
                out += LlmStreamEvent.ToolCallCompleted(id, name, args, signature)
            }
        }

        candidate?.get("finishReason").asStr()?.let {
            stopReason = mapStop(it)
            sawCompletion = true
        }
        return out
    }

    /** Emits usage and completion at stream end (Gemini has no explicit terminal event). */
    fun finish(): List<LlmStreamEvent> {
        if (completed) return emptyList()
        completed = true
        return listOf(
            LlmStreamEvent.Usage(TokenUsage(inputTokens, outputTokens)),
            LlmStreamEvent.Completed(if (sawCompletion) stopReason else StopReason.END_TURN),
        )
    }

    /** Gemini has no per-call id; disambiguate repeated tool names within a turn with a suffix. */
    private fun correlationId(name: String): String {
        val count = usedIds.getOrDefault(name, 0)
        usedIds[name] = count + 1
        return if (count == 0) name else "$name#$count"
    }

    private fun mapStop(s: String): StopReason = when (s) {
        "STOP" -> StopReason.END_TURN
        "MAX_TOKENS" -> StopReason.MAX_TOKENS
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> StopReason.REFUSAL
        else -> StopReason.END_TURN
    }
}
