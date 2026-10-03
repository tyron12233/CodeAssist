package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmEffort
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The OpenAI Responses API (`/v1/responses`), used for the official endpoint.
 *
 * Chat Completions can't carry reasoning and function tools together on newer reasoning models: such a request
 * is rejected unless reasoning is turned off (`reasoning_effort: none`). The Responses API takes both, so the agent
 * keeps its tools and its reasoning. It also returns the model's reasoning as an encrypted item that is passed back
 * on the next request, which keeps the reasoning across a run of tool calls without storing anything server-side
 * (`store: false`).
 *
 * The encrypted reasoning rides in [ContentPart.Thinking.signature] (see [ReasoningItem]) and a function call's
 * item id in [ContentPart.ToolUse.signature]; both are opaque to the rest of the agent.
 */
internal object OpenAiResponses {

    const val PATH = "/v1/responses"

    fun body(request: LlmRequest, cacheKey: String): String = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("store", false)
        put("prompt_cache_key", cacheKey)
        put("max_output_tokens", request.maxTokens)
        request.system?.takeIf { it.isNotBlank() }?.let { put("instructions", it) }
        if (isReasoningModel(request.model)) {
            effort(request.effort)?.let { e -> put("reasoning", buildJsonObject { put("effort", e) }) }
            // With store=false the reasoning is only reusable on the next request in its encrypted form.
            put("include", buildJsonArray { add("reasoning.encrypted_content") })
        }
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { spec ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", spec.name)
                        put("description", spec.description)
                        put("parameters", AgentJson.parseToJsonElement(spec.parameters))
                        put("strict", false)
                    })
                }
            })
        }
        put("input", input(request.messages))
    }.toString()

    /** The neutral effort as a Responses `reasoning.effort`; the levels above `high` map to `xhigh`. */
    private fun effort(effort: String?): String? = when (effort?.takeIf { it.isNotBlank() }) {
        null -> null
        LlmEffort.XHIGH, LlmEffort.MAX -> "xhigh"
        else -> effort
    }

    /**
     * Whether [model] is a reasoning model, which the `reasoning` parameter and the encrypted-reasoning include are
     * only valid for. The GPT-4 and GPT-3.5 families are not; every newer OpenAI model is.
     */
    fun isReasoningModel(model: String): Boolean {
        val m = model.lowercase().substringAfterLast('/')
        return !(m.startsWith("gpt-4") || m.startsWith("gpt-3") || m.startsWith("chatgpt-4"))
    }

    private fun input(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        // A function_call_output is text here, so images a tool produced follow its run of results as a user turn.
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
                LlmRole.SYSTEM -> add(buildJsonObject { put("role", "developer"); put("content", plainText(m.content)) })
                LlmRole.USER -> add(buildJsonObject { put("role", "user"); put("content", userContent(m.content)) })
                LlmRole.ASSISTANT -> assistantItems(m.content)
                LlmRole.TOOL -> m.content.forEach { part ->
                    if (part is ContentPart.ToolResultPart) {
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", part.toolCallId)
                            put("output", part.content)
                        })
                        toolImages += part.images
                    }
                }
            }
        }
        flushToolImages()
    }

    /** An assistant turn as Responses items, in the order the model produced them: reasoning, text, calls. */
    private fun JsonArrayBuilder.assistantItems(parts: List<ContentPart>) {
        parts.filterIsInstance<ContentPart.Thinking>().forEach { t ->
            // Reasoning from another provider has no OpenAI form; only our own encrypted items go back.
            val item = ReasoningItem.decode(t.signature) ?: return@forEach
            add(buildJsonObject {
                put("type", "reasoning")
                put("id", item.id)
                put("encrypted_content", item.encrypted)
                put("summary", buildJsonArray {
                    if (t.text.isNotEmpty()) add(buildJsonObject { put("type", "summary_text"); put("text", t.text) })
                })
            })
        }
        val text = plainText(parts)
        if (text.isNotEmpty()) add(buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
        })
        parts.filterIsInstance<ContentPart.ToolUse>().forEach { tu ->
            add(buildJsonObject {
                put("type", "function_call")
                tu.signature?.takeIf { it.startsWith("fc") }?.let { put("id", it) }
                put("call_id", tu.id)
                put("name", tu.name)
                put("arguments", tu.arguments.ifBlank { "{}" })
            })
        }
    }

    private fun userContent(parts: List<ContentPart>): JsonArray = buildJsonArray {
        parts.forEach { p ->
            when (p) {
                is ContentPart.Text -> add(buildJsonObject { put("type", "input_text"); put("text", p.text) })
                is ContentPart.Image -> add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:${p.mediaType};base64,${p.data}")
                })
                else -> Unit
            }
        }
    }

    private fun plainText(parts: List<ContentPart>): String =
        parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
}

/**
 * An OpenAI reasoning item as carried in [ContentPart.Thinking.signature]: its id and encrypted content, tagged
 * so a signature from another provider (Anthropic's thinking signature) is never mistaken for one.
 */
internal data class ReasoningItem(val id: String, val encrypted: String) {
    fun encode(): String = buildJsonObject {
        put("openai_reasoning", id)
        put("encrypted_content", encrypted)
    }.toString()

    companion object {
        fun decode(signature: String?): ReasoningItem? {
            if (signature == null || !signature.startsWith("{")) return null
            val obj = runCatching { AgentJson.parseToJsonElement(signature).asObj() }.getOrNull() ?: return null
            val id = obj["openai_reasoning"].asStr() ?: return null
            val encrypted = obj["encrypted_content"].asStr() ?: return null
            return ReasoningItem(id, encrypted)
        }
    }
}

/** Decoder for the Responses API's SSE stream: one typed JSON event per `data:` payload. */
internal class OpenAiResponsesDecoder {
    private class Call(val callId: String, val name: String, val itemId: String)

    private val calls = HashMap<String, Call>() // by item id
    private var sawToolCall = false
    var completed: Boolean = false
        private set

    fun decode(data: String): List<LlmStreamEvent> {
        if (data.trim() == "[DONE]") return finish(null, null)
        val json = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return emptyList()
        return when (json["type"].asStr()) {
            "response.output_text.delta" ->
                json["delta"].asStr()?.takeIf { it.isNotEmpty() }?.let { listOf(LlmStreamEvent.TextDelta(it)) } ?: emptyList()
            "response.reasoning_summary_text.delta" ->
                json["delta"].asStr()?.takeIf { it.isNotEmpty() }?.let { listOf(LlmStreamEvent.ThinkingDelta(it)) } ?: emptyList()
            "response.output_item.added" -> itemAdded(json["item"].asObj())
            "response.function_call_arguments.delta" -> {
                val call = json["item_id"].asStr()?.let { calls[it] }
                val delta = json["delta"].asStr()
                if (call != null && !delta.isNullOrEmpty()) listOf(LlmStreamEvent.ToolCallArgsDelta(call.callId, delta)) else emptyList()
            }
            "response.output_item.done" -> itemDone(json["item"].asObj())
            "response.completed" -> finish(json["response"].asObj(), null)
            "response.incomplete" -> {
                val reason = json["response"].asObj()?.get("incomplete_details").asObj()?.get("reason").asStr()
                finish(json["response"].asObj(), if (reason == "content_filter") StopReason.REFUSAL else StopReason.MAX_TOKENS)
            }
            "response.failed" -> failed(json["response"].asObj()?.get("error").asObj())
            "error" -> failed(json["error"].asObj() ?: json)
            else -> emptyList()
        }
    }

    private fun itemAdded(item: JsonObject?): List<LlmStreamEvent> {
        if (item == null || item["type"].asStr() != "function_call") return emptyList()
        val itemId = item["id"].asStr() ?: return emptyList()
        val call = Call(item["call_id"].asStr() ?: itemId, item["name"].asStr().orEmpty(), itemId)
        calls[itemId] = call
        sawToolCall = true
        return listOf(LlmStreamEvent.ToolCallStarted(call.callId, call.name))
    }

    private fun itemDone(item: JsonObject?): List<LlmStreamEvent> {
        if (item == null) return emptyList()
        return when (item["type"].asStr()) {
            "function_call" -> {
                val itemId = item["id"].asStr().orEmpty()
                val callId = item["call_id"].asStr() ?: calls[itemId]?.callId ?: return emptyList()
                val name = item["name"].asStr() ?: calls[itemId]?.name.orEmpty()
                sawToolCall = true
                listOf(LlmStreamEvent.ToolCallCompleted(callId, name, item["arguments"].asStr().orEmpty(), itemId.ifEmpty { null }))
            }
            "reasoning" -> {
                val id = item["id"].asStr() ?: return emptyList()
                val encrypted = item["encrypted_content"].asStr() ?: return emptyList()
                val summary = item["summary"].asArr()?.mapNotNull { it.asObj()?.get("text").asStr() }?.joinToString("\n\n").orEmpty()
                listOf(LlmStreamEvent.ThinkingCompleted(summary, ReasoningItem(id, encrypted).encode()))
            }
            else -> emptyList()
        }
    }

    private fun failed(error: JsonObject?): List<LlmStreamEvent> {
        completed = true
        val message = error?.get("message").asStr() ?: "OpenAI request failed"
        return listOf(LlmStreamEvent.Failed(message))
    }

    /** Usage and completion, once: from the final response object when there is one. */
    fun finish(response: JsonObject?, stop: StopReason?): List<LlmStreamEvent> {
        if (completed) return emptyList()
        completed = true
        val usage = response?.get("usage").asObj()
        val input = usage?.get("input_tokens").asInt() ?: 0
        val output = usage?.get("output_tokens").asInt() ?: 0
        // Like Chat Completions, input_tokens includes the cached share; the neutral model keeps it separate.
        val cached = usage?.get("input_tokens_details").asObj()?.get("cached_tokens").asInt() ?: 0
        return listOf(
            LlmStreamEvent.Usage(TokenUsage((input - cached).coerceAtLeast(0), output, cached)),
            LlmStreamEvent.Completed(stop ?: if (sawToolCall) StopReason.TOOL_USE else StopReason.END_TURN),
        )
    }
}
