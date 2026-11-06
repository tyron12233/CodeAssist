package dev.ide.core.agent

import dev.ide.ui.backend.UiAgentRecentChat
import dev.ide.ui.backend.UiAgentSessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Conversations across every project, newest first, so the AI home tab can list them without opening each project.
 * The conversations themselves stay in their projects ([AgentSessionStore]); this is only an index, in the app's
 * shared folder (`agent/recent-chats.json`). An entry whose conversation file is gone (the chat or the whole project
 * deleted) is left out when listed and dropped on the next write.
 */
internal class AgentRecentChats(private val file: () -> Path?) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun record(projectRoot: Path, saved: UiAgentSessionSummary) {
        val entry = UiAgentRecentChat(projectRoot.toString(), saved.id, saved.title, saved.updatedAtMs, saved.messageCount)
        write(listOf(entry) + read().filterNot { it.projectRoot == entry.projectRoot && it.sessionId == entry.sessionId })
    }

    @Synchronized
    fun remove(projectRoot: Path, sessionId: String) {
        write(read().filterNot { it.projectRoot == projectRoot.toString() && it.sessionId == sessionId })
    }

    @Synchronized
    fun list(): List<UiAgentRecentChat> = read().filter(::exists).sortedByDescending { it.updatedAtMs }

    private fun exists(chat: UiAgentRecentChat): Boolean = runCatching {
        AgentSessionStore.sessionsDir(Paths.get(chat.projectRoot)).resolve("${chat.sessionId}.json").isRegularFile()
    }.getOrDefault(false)

    private fun read(): List<UiAgentRecentChat> {
        val f = file()?.takeIf { it.isRegularFile() } ?: return emptyList()
        return runCatching {
            (json.parseToJsonElement(f.readText()) as JsonArray).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                UiAgentRecentChat(
                    projectRoot = o.str("projectRoot") ?: return@mapNotNull null,
                    sessionId = o.str("sessionId") ?: return@mapNotNull null,
                    title = o.str("title").orEmpty(),
                    updatedAtMs = (o["updatedAtMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    messageCount = (o["messageCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(chats: List<UiAgentRecentChat>) {
        val f = file() ?: return
        val kept = chats.filter(::exists).take(MAX)
        runCatching {
            Files.createDirectories(f.parent)
            val temp = f.resolveSibling(f.fileName.toString() + ".tmp")
            temp.writeText(
                buildJsonArray {
                    kept.forEach { c ->
                        add(buildJsonObject {
                            put("projectRoot", c.projectRoot)
                            put("sessionId", c.sessionId)
                            put("title", c.title)
                            put("updatedAtMs", c.updatedAtMs)
                            put("messageCount", c.messageCount)
                        })
                    }
                }.toString(),
            )
            Files.move(temp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val MAX = 100
    }
}
