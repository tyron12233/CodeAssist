package dev.ide.agent.mcp

import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpSchema
import java.io.IOException

/**
 * The wire mapping between the agent tool model (agent-api, JSON-schema strings, dependency-free) and the
 * MCP model (io.modelcontextprotocol, Jackson-backed). The agent's tool specs are already JSON Schema, so
 * they map 1:1 into MCP [McpSchema.Tool] declarations; arguments arrive from MCP as a `Map` and are read
 * back through the same [ToolArgs] accessor the in-IDE agent uses, so a tool implementation never needs to
 * know which side it is running on.
 */

private val MAP_TYPE_REF: TypeRef<Map<String, Any>> = object : TypeRef<Map<String, Any>>() {}

/** Converts an agent [ToolSpec] into an MCP tool declaration, preserving name, description, and schema. */
fun ToolSpec.toMcpTool(mapper: McpJsonMapper): McpSchema.Tool = McpSchema.Tool.builder(name, parseParameters(mapper, this))
    .description(description)
    .build()

private fun parseParameters(mapper: McpJsonMapper, spec: ToolSpec): Map<String, Any> {
    if (spec.parameters.isBlank()) return emptyMap()
    return try {
        mapper.readValue(spec.parameters, MAP_TYPE_REF)
    } catch (e: IOException) {
        throw IllegalArgumentException(
            "Tool '${spec.name}' advertises an invalid JSON-schema parameters string: ${spec.parameters}",
            e,
        )
    }
}

/**
 * A [ToolArgs] view over MCP's argument map. MCP decodes JSON arguments with Jackson, so numbers arrive as
 * [Number] and lists as `List<*>`; the accessors coerce instead of casting so a tool's `int`/`string`
 * reads work regardless of the exact decoded shape.
 */
class MapToolArgs(
    private val args: Map<String, Any?> = emptyMap(),
    private val mapper: McpJsonMapper? = null,
) : ToolArgs {

    override fun string(key: String): String =
        requireNotNull(optString(key)) { "Missing required argument '$key'." }

    override fun optString(key: String): String? = when (val v = args[key]) {
        null -> null
        is String -> v
        else -> v.toString()
    }

    override fun int(key: String): Int = requireNotNull(optInt(key)) { "Missing required argument '$key'." }

    override fun optInt(key: String): Int? = when (val v = args[key]) {
        null -> null
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    override fun boolean(key: String): Boolean =
        requireNotNull(optBoolean(key)) { "Missing required argument '$key'." }

    override fun optBoolean(key: String): Boolean? = when (val v = args[key]) {
        null -> null
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }

    override fun stringList(key: String): List<String> = when (val v = args[key]) {
        null -> emptyList()
        is List<*> -> v.mapNotNull { it?.toString() }
        else -> listOf(v.toString())
    }

    /** The raw argument object, re-serialized to its JSON form when a mapper is available. */
    override fun raw(): String {
        val mapper = mapper ?: return args.toString()
        return try {
            mapper.writeValueAsString(args)
        } catch (e: IOException) {
            args.toString()
        }
    }
}

/** Converts an agent [ToolExecutionResult] into an MCP [McpSchema.CallToolResult]. */
fun ToolExecutionResult.toCallToolResult(): McpSchema.CallToolResult = McpSchema.CallToolResult.builder()
    .content(listOf(McpSchema.TextContent.builder(content).build()))
    .isError(isError)
    .build()
