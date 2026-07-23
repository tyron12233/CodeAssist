package dev.ide.agent.impl

import dev.ide.agent.ToolArgs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** The shared JSON reader/writer for all providers. Tolerant of unknown and missing fields so provider
 *  responses decode across model and API versions. */
internal val AgentJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    isLenient = true
}

// Null-safe navigation helpers used by the streaming decoders.
internal fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement?.asInt(): Int? =
    (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonElement?.asBool(): Boolean? =
    (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

/** Parses a tool call's raw JSON argument object; an empty or malformed payload yields no arguments. */
internal fun parseArgsObject(raw: String): JsonObject =
    runCatching { AgentJson.parseToJsonElement(raw.ifBlank { "{}" }).asObj() }.getOrNull() ?: JsonObject(emptyMap())

/** [ToolArgs] over a parsed JSON object, so tools read arguments without a JSON dependency of their own. */
internal class JsonToolArgs(private val obj: JsonObject) : ToolArgs {
    override fun string(key: String): String =
        optString(key) ?: throw IllegalArgumentException("missing required argument '$key'")

    override fun optString(key: String): String? = obj[key].asStr()

    override fun int(key: String): Int =
        optInt(key) ?: throw IllegalArgumentException("missing required integer argument '$key'")

    override fun optInt(key: String): Int? = obj[key].asInt()

    override fun boolean(key: String): Boolean =
        optBoolean(key) ?: throw IllegalArgumentException("missing required boolean argument '$key'")

    override fun optBoolean(key: String): Boolean? = obj[key].asBool()

    override fun stringList(key: String): List<String> =
        obj[key].asArr()?.mapNotNull { it.asStr() } ?: emptyList()

    override fun raw(): String = obj.toString()
}
