package dev.ide.core.agent

import dev.ide.agent.LlmMessage
import dev.ide.agent.impl.ConversationCodec
import dev.ide.ui.backend.UiAgentAttachment
import dev.ide.ui.backend.UiAgentAttachmentKind
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentSessionSummary
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.backend.UiAgentUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Saved chat sessions, one JSON file per conversation under `<project>/.platform/agent/sessions/`. A file holds
 * both halves of a conversation: the transcript as the user saw it, and the model-side history, so a resumed
 * session looks the same AND gives the model the context it had.
 *
 * Written whole after every turn (via a temp file and a move, so a crash mid-write never leaves half a file).
 * Image attachments keep their label but not their pixels in the transcript; the model-side history keeps
 * them, since that is what the model actually saw.
 */
internal class AgentSessionStore(private val root: () -> Path?) {
    data class Saved(val messages: List<UiAgentMessage>, val history: List<LlmMessage>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(): Path? = root()?.resolve(".platform")?.resolve("agent")?.resolve("sessions")

    fun list(): List<UiAgentSessionSummary> {
        val dir = dir() ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching { dir.listDirectoryEntries("*.json") }.getOrDefault(emptyList())
            .filter { it.isRegularFile() }
            .mapNotNull { file ->
                val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return@mapNotNull null
                UiAgentSessionSummary(
                    id = file.name.removeSuffix(".json"),
                    title = obj.str("title").ifBlank { "Untitled chat" },
                    updatedAtMs = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    messageCount = (obj["messages"] as? JsonArray)?.size ?: 0,
                )
            }
            .sortedByDescending { it.updatedAtMs }
    }

    fun save(id: String, messages: List<UiAgentMessage>, history: List<LlmMessage>) {
        val dir = dir() ?: return
        if (messages.none { it.role == UiAgentRole.USER }) return
        val title = messages.firstOrNull { it.role == UiAgentRole.USER }?.text?.lineSequence()?.firstOrNull()
            ?.trim()?.take(TITLE_CHARS).orEmpty()
        val body = buildJsonObject {
            put("title", title)
            put("updatedAt", System.currentTimeMillis())
            put("messages", buildJsonArray { messages.filter { !it.streaming }.forEach { add(encode(it)) } })
            put("history", json.parseToJsonElement(ConversationCodec.encode(history)))
        }
        runCatching {
            Files.createDirectories(dir)
            val target = dir.resolve("$id.json")
            val temp = dir.resolve("$id.json.tmp")
            temp.writeText(body.toString())
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun load(id: String): Saved? {
        val file = dir()?.resolve("$id.json")?.takeIf { it.isRegularFile() } ?: return null
        val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        val messages = (obj["messages"] as? JsonArray)?.mapNotNull { decode(it as? JsonObject) }.orEmpty()
        val history = ConversationCodec.decode(obj["history"]?.toString() ?: "[]")
        return Saved(messages, history)
    }

    fun delete(id: String) {
        runCatching { dir()?.resolve("$id.json")?.let { Files.deleteIfExists(it) } }
    }

    private fun encode(m: UiAgentMessage): JsonObject = buildJsonObject {
        put("id", m.id)
        put("role", m.role.name)
        put("text", m.text)
        if (m.thinking.isNotEmpty()) put("thinking", m.thinking)
        if (m.isError) put("error", true)
        if (m.toolCalls.isNotEmpty()) put("tools", buildJsonArray {
            m.toolCalls.forEach { c ->
                addJsonObject { put("id", c.id); put("title", c.title); put("status", c.status.name); put("detail", c.detail) }
            }
        })
        m.usage?.let { u ->
            put("usage", buildJsonObject {
                put("input", u.input); put("output", u.output); put("cacheRead", u.cacheRead); put("cacheWrite", u.cacheWrite)
            })
        }
        if (m.attachments.isNotEmpty()) put("attachments", buildJsonArray {
            m.attachments.forEach { a ->
                addJsonObject {
                    put("kind", a.kind.name); put("name", a.name)
                    a.path?.let { put("path", it) }
                    a.startLine?.let { put("startLine", it) }
                    a.endLine?.let { put("endLine", it) }
                }
            }
        })
    }

    private fun decode(obj: JsonObject?): UiAgentMessage? {
        obj ?: return null
        val role = runCatching { UiAgentRole.valueOf(obj.str("role")) }.getOrNull() ?: return null
        val usage = (obj["usage"] as? JsonObject)?.let { u ->
            UiAgentUsage(u.int("input"), u.int("output"), u.int("cacheRead"), u.int("cacheWrite"))
        }
        return UiAgentMessage(
            id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return null,
            role = role,
            text = obj.str("text"),
            thinking = obj.str("thinking"),
            isError = obj.str("error") == "true",
            toolCalls = (obj["tools"] as? JsonArray)?.mapNotNull { t ->
                val c = t as? JsonObject ?: return@mapNotNull null
                UiAgentToolCall(
                    c.str("id"), c.str("title"),
                    runCatching { UiAgentToolStatus.valueOf(c.str("status")) }.getOrDefault(UiAgentToolStatus.OK),
                    c.str("detail"),
                )
            }.orEmpty(),
            usage = usage,
            attachments = (obj["attachments"] as? JsonArray)?.mapNotNull { a ->
                val o = a as? JsonObject ?: return@mapNotNull null
                UiAgentAttachment(
                    kind = runCatching { UiAgentAttachmentKind.valueOf(o.str("kind")) }.getOrNull() ?: return@mapNotNull null,
                    name = o.str("name"),
                    path = o.str("path").ifBlank { null },
                    startLine = (o["startLine"] as? JsonPrimitive)?.intOrNull,
                    endLine = (o["endLine"] as? JsonPrimitive)?.intOrNull,
                )
            }.orEmpty(),
        )
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private companion object {
        const val TITLE_CHARS = 80
    }
}
