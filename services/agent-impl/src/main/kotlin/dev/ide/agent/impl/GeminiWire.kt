package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRole
import dev.ide.agent.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Gemini `generateContent` request shape for [GeminiProvider]: the `contents`/`parts` dialect (`user`/
 * `model` roles, tool results as `functionResponse` parts, tool calls echoing their `thoughtSignature`). Pure
 * builders over the neutral [LlmMessage]/[ToolSpec] model.
 */
internal object GeminiWire {
    fun systemInstruction(text: String): JsonObject = buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
    }

    fun toolDeclarations(tools: List<ToolSpec>, webSearch: Boolean = false): JsonArray = buildJsonArray {
        if (tools.isNotEmpty()) {
            add(buildJsonObject {
                put("function_declarations", buildJsonArray {
                    tools.forEach { spec ->
                        add(buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", stripAdditionalProperties(AgentJson.parseToJsonElement(spec.parameters)))
                        })
                    }
                })
            })
        }
        // Gemini's server-side search grounding is a sibling tool entry; the model runs it itself and folds the
        // results (and grounding metadata we ignore) into the turn, so the loop never sees a function call.
        if (webSearch) add(buildJsonObject { put("google_search", buildJsonObject { }) })
    }

    fun contents(messages: List<LlmMessage>): JsonArray {
        val out = ArrayList<JsonObject>(messages.size)
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                // Gemini has no mid-conversation system role, so per-turn operator state rides as a
                // <system-reminder> part on the user turn it follows. That keeps it behind the cached
                // system instruction and tool declarations, which is the point of sending it here at all.
                LlmRole.SYSTEM -> {
                    addReminder(out, m.content)
                    i++
                }
                LlmRole.USER -> {
                    out += buildJsonObject { put("role", "user"); put("parts", userParts(m.content)) }
                    i++
                }
                LlmRole.ASSISTANT -> {
                    out += buildJsonObject { put("role", "model"); put("parts", modelParts(m.content)) }
                    i++
                }
                LlmRole.TOOL -> {
                    val results = ArrayList<ContentPart.ToolResultPart>()
                    while (i < messages.size && messages[i].role == LlmRole.TOOL) {
                        messages[i].content.forEach { if (it is ContentPart.ToolResultPart) results += it }
                        i++
                    }
                    out += buildJsonObject {
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
                    }
                }
            }
        }
        return JsonArray(out)
    }

    /** Folds an operator instruction into the preceding user turn, or starts one when there is none. */
    private fun addReminder(out: MutableList<JsonObject>, content: List<ContentPart>) {
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
        if (text.isBlank()) return
        val part = buildJsonObject { put("text", "<system-reminder>\n$text\n</system-reminder>") }
        val last = out.lastOrNull()
        val lastParts = last?.get("parts") as? JsonArray
        if (last != null && last["role"].asStr() == "user" && lastParts != null) {
            out[out.size - 1] = JsonObject(last + ("parts" to JsonArray(lastParts + part)))
        } else {
            out += buildJsonObject { put("role", "user"); put("parts", JsonArray(listOf(part))) }
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
    fun stripAdditionalProperties(element: JsonElement): JsonElement {
        val obj = element.asObj() ?: return element
        return JsonObject(obj.filterKeys { it != "additionalProperties" })
    }
}
