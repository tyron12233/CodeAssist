package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Encodes a conversation ([LlmMessage]s) as JSON for a saved chat session, and back. Every part kind round-trips,
 * so a resumed session gives the model exactly the context it had. Unknown or malformed parts decode to nothing
 * rather than failing the whole session, so a file written by a newer build still opens.
 */
object ConversationCodec {
    /** The conversation as a JSON array string. */
    fun encode(messages: List<LlmMessage>): String = encodeJson(messages).toString()

    /** Decodes [encode]'s output; anything unreadable yields an empty conversation. */
    fun decode(json: String): List<LlmMessage> =
        decodeJson(runCatching { AgentJson.parseToJsonElement(json) }.getOrNull())

    private fun encodeJson(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        messages.forEach { m ->
            add(buildJsonObject {
                put("role", m.role.name)
                put("content", buildJsonArray { m.content.forEach { add(encodePart(it)) } })
            })
        }
    }

    private fun decodeJson(json: JsonElement?): List<LlmMessage> = json.asArr()?.mapNotNull { element ->
        val obj = element.asObj() ?: return@mapNotNull null
        val role = runCatching { LlmRole.valueOf(obj["role"].asStr().orEmpty()) }.getOrNull() ?: return@mapNotNull null
        val parts = obj["content"].asArr()?.mapNotNull { decodePart(it.asObj()) }.orEmpty()
        LlmMessage(role, parts)
    }.orEmpty()

    private fun encodePart(part: ContentPart): JsonObject = when (part) {
        is ContentPart.Text -> buildJsonObject { put("type", "text"); put("text", part.text) }
        is ContentPart.Thinking -> buildJsonObject {
            put("type", "thinking"); put("text", part.text); part.signature?.let { put("signature", it) }
        }
        is ContentPart.ToolUse -> buildJsonObject {
            put("type", "tool_use"); put("id", part.id); put("name", part.name); put("arguments", part.arguments)
            part.signature?.let { put("signature", it) }
        }
        is ContentPart.ToolResultPart -> buildJsonObject {
            put("type", "tool_result"); put("id", part.toolCallId); put("content", part.content)
            if (part.isError) put("error", true)
            if (part.images.isNotEmpty()) put("images", buildJsonArray { part.images.forEach { add(encodePart(it)) } })
        }
        is ContentPart.Image -> buildJsonObject { put("type", "image"); put("media_type", part.mediaType); put("data", part.data) }
    }

    private fun decodePart(obj: JsonObject?): ContentPart? {
        obj ?: return null
        return when (obj["type"].asStr()) {
            "text" -> ContentPart.Text(obj["text"].asStr().orEmpty())
            "thinking" -> ContentPart.Thinking(obj["text"].asStr().orEmpty(), obj["signature"].asStr())
            "tool_use" -> ContentPart.ToolUse(
                obj["id"].asStr() ?: return null,
                obj["name"].asStr() ?: return null,
                obj["arguments"].asStr() ?: "{}",
                obj["signature"].asStr(),
            )
            "tool_result" -> ContentPart.ToolResultPart(
                obj["id"].asStr() ?: return null,
                obj["content"].asStr().orEmpty(),
                (obj["error"] as? JsonPrimitive)?.content == "true",
                obj["images"].asArr()?.mapNotNull { decodePart(it.asObj()) as? ContentPart.Image }.orEmpty(),
            )
            "image" -> ContentPart.Image(obj["media_type"].asStr() ?: return null, obj["data"].asStr() ?: return null)
            else -> null
        }
    }
}
