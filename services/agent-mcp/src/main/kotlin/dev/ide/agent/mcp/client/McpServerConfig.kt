package dev.ide.agent.mcp.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How the app reaches a server. */
enum class McpTransportKind {
    /** Streamable HTTP, falling back to the older SSE transport when the server only speaks that. */
    HTTP,

    /** The older HTTP+SSE transport only. */
    SSE,

    /** A local command (desktop only). */
    STDIO,
}

/**
 * One server the user added. The file format is the `mcpServers` object Claude Code and Cursor read from
 * `.mcp.json`, so a config written for them works here unchanged:
 *
 * ```json
 * { "mcpServers": {
 *     "linear": { "type": "http", "url": "https://mcp.linear.app/mcp" },
 *     "docs":   { "type": "sse",  "url": "https://example.com/sse", "headers": { "Authorization": "Bearer …" } },
 *     "files":  { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."] }
 * } }
 * ```
 *
 * A missing `type` is inferred: a `url` means HTTP, a `command` means stdio. `disabled: true` keeps an entry
 * without connecting it.
 */
data class McpServerConfig(
    val name: String,
    val transport: McpTransportKind,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val disabled: Boolean = false,
) {
    /** What identifies the server for trust and stored sign-ins: a changed URL or command is a different server. */
    val identity: String get() = when (transport) {
        McpTransportKind.STDIO -> "stdio:" + (listOfNotNull(command) + args).joinToString(" ")
        else -> "url:" + url.orEmpty()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

        /** Parses a `.mcp.json`-style file. Entries that make no sense (no url and no command) are skipped. */
        fun parseFile(text: String): List<McpServerConfig> {
            val root = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
            val servers = root["mcpServers"] as? JsonObject ?: root["servers"] as? JsonObject ?: return emptyList()
            return servers.mapNotNull { (name, value) -> (value as? JsonObject)?.let { parseEntry(name, it) } }
        }

        fun parseEntry(name: String, obj: JsonObject): McpServerConfig? {
            val url = obj.str("url")?.trim()?.takeIf { it.isNotEmpty() }
            val command = obj.str("command")?.trim()?.takeIf { it.isNotEmpty() }
            val transport = when (obj.str("type")?.lowercase()) {
                "sse" -> McpTransportKind.SSE
                "stdio" -> McpTransportKind.STDIO
                "http", "streamable-http", "streamablehttp" -> McpTransportKind.HTTP
                else -> when {
                    url != null -> McpTransportKind.HTTP
                    command != null -> McpTransportKind.STDIO
                    else -> return null
                }
            }
            if (transport == McpTransportKind.STDIO && command == null) return null
            if (transport != McpTransportKind.STDIO && url == null) return null
            return McpServerConfig(
                name = name,
                transport = transport,
                url = url,
                headers = obj.stringMap("headers"),
                command = command,
                args = (obj["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                env = obj.stringMap("env"),
                disabled = obj.bool("disabled") == true,
            )
        }

        /** Writes [servers] back out in the same format. */
        fun toFile(servers: List<McpServerConfig>): String = JSON.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                putJsonObject("mcpServers") {
                    servers.forEach { s -> put(s.name, toEntry(s)) }
                }
            },
        )

        fun toEntry(s: McpServerConfig): JsonObject = buildJsonObject {
            when (s.transport) {
                McpTransportKind.STDIO -> {
                    put("type", "stdio")
                    put("command", s.command.orEmpty())
                    if (s.args.isNotEmpty()) putJsonArray("args") { s.args.forEach { add(JsonPrimitive(it)) } }
                    if (s.env.isNotEmpty()) putJsonObject("env") { s.env.forEach { (k, v) -> put(k, v) } }
                }
                else -> {
                    put("type", if (s.transport == McpTransportKind.SSE) "sse" else "http")
                    put("url", s.url.orEmpty())
                    if (s.headers.isNotEmpty()) putJsonObject("headers") { s.headers.forEach { (k, v) -> put(k, v) } }
                }
            }
            if (s.disabled) put("disabled", true)
        }

        private fun JsonObject.stringMap(key: String): Map<String, String> =
            (this[key] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
    }
}
