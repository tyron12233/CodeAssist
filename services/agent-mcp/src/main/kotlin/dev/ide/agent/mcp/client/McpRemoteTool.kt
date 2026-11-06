package dev.ide.agent.mcp.client

import dev.ide.agent.AgentTool
import dev.ide.agent.ContentPart
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One tool of a connected MCP server, offered to the model as `mcp__<server>__<tool>`.
 *
 * Mutating unless the server marks it read-only, so the permission gate asks before a call that could change
 * something outside the project (an issue filed, a message sent), the same way it asks before a file edit.
 */
class McpRemoteTool(
    val serverName: String,
    val info: McpToolInfo,
    toolName: String,
    private val call: suspend (tool: String, arguments: JsonObject) -> McpCallResult,
) : AgentTool {

    override val spec = ToolSpec(
        name = toolName,
        description = buildString {
            append("[MCP server \"").append(serverName).append("\"] ")
            append((info.description ?: info.title ?: info.name).trim().take(MAX_DESCRIPTION))
        },
        parameters = McpSchemas.normalize(info.inputSchema).toString(),
    )

    override val mutating: Boolean get() = info.readOnly != true

    override fun summarize(args: ToolArgs): String {
        val label = "$serverName: ${info.title ?: info.name}"
        val brief = runCatching { briefArgs(Json.parseToJsonElement(args.raw()) as? JsonObject) }.getOrNull()
        return if (brief.isNullOrBlank()) label else "$label ($brief)"
    }

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        val arguments = runCatching { Json.parseToJsonElement(args.raw()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val result = call(info.name, arguments)
        return ToolExecutionResult(
            content = result.text,
            isError = result.isError,
            images = result.images.map { (mime, data) -> ContentPart.Image(mime, data) },
        )
    }

    companion object {
        private const val MAX_DESCRIPTION = 1_000
        const val PREFIX = "mcp__"

        /**
         * `mcp__<server>__<tool>` in the characters every provider accepts in a tool name, at most 64 long. [taken]
         * holds names already used, so two servers' tools that clean up to the same name both survive.
         */
        fun toolName(server: String, tool: String, taken: Set<String>): String {
            val base = (PREFIX + clean(server) + "__" + clean(tool)).take(64)
            if (base !in taken) return base
            var i = 2
            while (true) {
                val suffix = "_$i"
                val candidate = base.take(64 - suffix.length) + suffix
                if (candidate !in taken) return candidate
                i++
            }
        }

        private fun clean(s: String): String =
            s.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifEmpty { "x" }

        /** The first few scalar arguments, for the permission prompt and transcript. */
        private fun briefArgs(obj: JsonObject?): String? = obj?.entries
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { "$k: ${it.take(40)}" } }
            ?.take(3)
            ?.joinToString(", ")
    }
}

/**
 * Third-party tool schemas, cut down to what every provider accepts. Servers write full JSON Schema (`$ref`,
 * `$defs`, `oneOf`, type arrays, formats Gemini has never heard of); the providers each accept a different
 * subset, and one bad schema fails the whole request. This keeps the subset that means something to the model.
 */
object McpSchemas {
    private val KEEP = setOf(
        "type", "description", "properties", "required", "items", "enum", "anyOf", "minimum", "maximum",
        "minItems", "maxItems", "title", "default", "nullable", "format",
    )
    private val SAFE_FORMATS = setOf("date-time", "date", "enum")

    fun normalize(schema: JsonObject): JsonObject {
        val defs = HashMap<String, JsonElement>()
        (schema["\$defs"] as? JsonObject)?.forEach { (k, v) -> defs["#/\$defs/$k"] = v }
        (schema["definitions"] as? JsonObject)?.forEach { (k, v) -> defs["#/definitions/$k"] = v }
        val cleaned = clean(schema, defs, depth = 0) as? JsonObject ?: JsonObject(emptyMap())
        // The top level must be an object schema with properties, whatever the server sent.
        val props = cleaned["properties"] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(
            buildMap {
                put("type", JsonPrimitive("object"))
                put("properties", props)
                (cleaned["required"] as? JsonArray)?.let { req ->
                    JsonArray(req.filter { (it as? JsonPrimitive)?.content in props.keys }).takeIf { it.isNotEmpty() }?.let { put("required", it) }
                }
            },
        )
    }

    private fun clean(el: JsonElement, defs: Map<String, JsonElement>, depth: Int): JsonElement {
        if (depth > 12 || el !is JsonObject) return el
        // A reference is inlined (bounded by depth, so a recursive type ends as a plain object).
        (el["\$ref"] as? JsonPrimitive)?.contentOrNull?.let { ref ->
            val target = defs[ref] ?: return JsonObject(mapOf("type" to JsonPrimitive("object")))
            return clean(target, defs, depth + 1)
        }
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in el) {
            when (key) {
                "type" -> out["type"] = when (value) {
                    // ["string", "null"]: the providers want one type; the first non-null one is what is meant.
                    is JsonArray -> value.firstOrNull { (it as? JsonPrimitive)?.content != "null" } ?: JsonPrimitive("string")
                    else -> value
                }
                "properties" -> (value as? JsonObject)?.let { props ->
                    out["properties"] = JsonObject(props.mapValues { (_, v) -> clean(v, defs, depth + 1) })
                }
                "items" -> out["items"] = clean(value, defs, depth + 1)
                "anyOf", "oneOf" -> (value as? JsonArray)?.let { options ->
                    val kept = options.map { clean(it, defs, depth + 1) }
                        .filterNot { (it as? JsonObject)?.get("type")?.let { t -> (t as? JsonPrimitive)?.content == "null" } == true }
                    when (kept.size) {
                        0 -> Unit
                        1 -> (kept[0] as? JsonObject)?.forEach { (k, v) -> if (k !in out) out[k] = v }
                        else -> out["anyOf"] = JsonArray(kept)
                    }
                }
                "format" -> if ((value as? JsonPrimitive)?.content in SAFE_FORMATS) out[key] = value
                "enum" -> (value as? JsonArray)?.let { values ->
                    // Gemini takes string enums only.
                    if (values.all { (it as? JsonPrimitive)?.isString == true }) out[key] = value
                }
                else -> if (key in KEEP) out[key] = value
            }
        }
        if ("type" !in out && "anyOf" !in out) {
            out["type"] = JsonPrimitive(if ("properties" in out) "object" else "string")
        }
        if ((out["type"] as? JsonPrimitive)?.content == "array" && "items" !in out) {
            out["items"] = JsonObject(mapOf("type" to JsonPrimitive("string")))
        }
        return JsonObject(out)
    }
}
