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
 * The Gemini `generateContent` request shape, shared by the direct [GeminiProvider] and the Antigravity
 * gateway [AntigravityProvider] — both speak the same `contents`/`parts` dialect (`user`/`model` roles, tool
 * results as `functionResponse` parts, tool calls echoing their `thoughtSignature`); the gateway just wraps
 * it in an envelope. Pure builders over the neutral [LlmMessage]/[ToolSpec] model.
 */
internal object GeminiWire {
    fun systemInstruction(text: String): JsonObject = buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
    }

    fun toolDeclarations(tools: List<ToolSpec>): JsonArray = buildJsonArray {
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

    fun contents(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                LlmRole.SYSTEM -> i++ // carried in the system instruction
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
    fun stripAdditionalProperties(element: JsonElement): JsonElement {
        val obj = element.asObj() ?: return element
        return JsonObject(obj.filterKeys { it != "additionalProperties" })
    }
}
