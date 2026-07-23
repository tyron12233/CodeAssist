package dev.ide.agent

/**
 * The tool SPI. A tool declares a JSON-schema parameter object ([ToolSpec.parameters]) and executes an
 * invocation whose arguments are read through the dependency-free [ToolArgs] accessor, so a tool can be
 * written in a module that does not depend on any JSON library. Mutating tools ([AgentTool.mutating]) are
 * routed through the permission gate by the agent loop before they run.
 */

/** A tool declaration sent to the model. [parameters] is a JSON-schema object as a string. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: String,
)

/** Typed access to a tool call's parsed JSON arguments. */
interface ToolArgs {
    fun string(key: String): String
    fun optString(key: String): String?
    fun int(key: String): Int
    fun optInt(key: String): Int?
    fun boolean(key: String): Boolean
    fun optBoolean(key: String): Boolean?
    fun stringList(key: String): List<String>
    /** The raw JSON argument object, as an escape hatch. */
    fun raw(): String
}

/** The outcome of a tool call. [content] is returned to the model as the tool result. */
data class ToolExecutionResult(val content: String, val isError: Boolean = false) {
    companion object {
        fun ok(content: String): ToolExecutionResult = ToolExecutionResult(content, isError = false)
        fun error(message: String): ToolExecutionResult = ToolExecutionResult(message, isError = true)
    }
}

/** A callable tool. Implement this and add it to an [AgentToolRegistry] to make it available to the model. */
interface AgentTool {
    val spec: ToolSpec

    /** Whether the tool changes project state. Mutating tools are permission-gated by the loop. */
    val mutating: Boolean get() = false

    /** A short human-readable summary of a pending call, shown in the permission prompt and transcript. */
    fun summarize(args: ToolArgs): String = spec.name

    suspend fun execute(args: ToolArgs): ToolExecutionResult
}

/** The set of tools offered to the model for a session. */
interface AgentToolRegistry {
    val tools: List<AgentTool>
    fun specs(): List<ToolSpec> = tools.map { it.spec }
    fun find(name: String): AgentTool?
}

class SimpleToolRegistry(override val tools: List<AgentTool>) : AgentToolRegistry {
    private val byName: Map<String, AgentTool> = tools.associateBy { it.spec.name }
    override fun find(name: String): AgentTool? = byName[name]
}

/** Builds a JSON-schema object string for a tool's parameters without a JSON dependency. */
class ToolSchemaBuilder {
    private val properties = LinkedHashMap<String, String>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = true, enum: List<String>? = null) =
        add(name, required, prop("string", description, enum))

    fun integer(name: String, description: String, required: Boolean = true) =
        add(name, required, prop("integer", description, null))

    fun boolean(name: String, description: String, required: Boolean = true) =
        add(name, required, prop("boolean", description, null))

    fun stringArray(name: String, description: String, required: Boolean = true) = add(
        name, required,
        """{"type":"array","description":"${esc(description)}","items":{"type":"string"}}""",
    )

    private fun prop(type: String, description: String, enum: List<String>?): String {
        val enumFragment = if (enum != null) {
            ""","enum":[${enum.joinToString(",") { "\"${esc(it)}\"" }}]"""
        } else ""
        return """{"type":"$type","description":"${esc(description)}"$enumFragment}"""
    }

    private fun add(name: String, req: Boolean, json: String) {
        properties[name] = json
        if (req) required += name
    }

    fun build(): String {
        val props = properties.entries.joinToString(",") { "\"${esc(it.key)}\":${it.value}" }
        val req = required.joinToString(",") { "\"${esc(it)}\"" }
        return """{"type":"object","properties":{$props},"required":[$req],"additionalProperties":false}"""
    }

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/** Convenience for building a tool parameter schema: `toolSchema { string("path", "...") }`. */
fun toolSchema(block: ToolSchemaBuilder.() -> Unit): String = ToolSchemaBuilder().apply(block).build()
