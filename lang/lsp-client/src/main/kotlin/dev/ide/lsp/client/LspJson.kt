package dev.ide.lsp.client

import dev.ide.lang.dom.TextRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Line starts of one text, for converting between the editor's character offsets and LSP's
 * `{line, character}` positions. Both count UTF-16 code units (the client negotiates `utf-16`, LSP's default),
 * so no re-encoding is involved. `\n`, `\r\n` and a lone `\r` all end a line, as the protocol says.
 */
class LineIndex(private val text: CharSequence) {
    private val starts: IntArray = run {
        val out = ArrayList<Int>()
        out += 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') out += i + 1
            else if (c == '\r') {
                if (i + 1 < text.length && text[i + 1] == '\n') i++
                out += i + 1
            }
            i++
        }
        out.toIntArray()
    }

    val lineCount: Int get() = starts.size

    /** The offset of [line]:[character], clamped into the line (a position past its end means its end). */
    fun offset(line: Int, character: Int): Int {
        if (line < 0) return 0
        if (line >= starts.size) return text.length
        val start = starts[line]
        return (start + character.coerceAtLeast(0)).coerceAtMost(lineEnd(line))
    }

    /** The offset just before [line]'s terminator. */
    fun lineEnd(line: Int): Int {
        if (line >= starts.size - 1) return text.length
        var end = starts[line + 1]
        if (end > 0 && text[end - 1] == '\n') end--
        if (end > starts[line] && text[end - 1] == '\r') end--
        return end
    }

    fun line(offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= o) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun position(offset: Int): JsonObject {
        val o = offset.coerceIn(0, text.length)
        val line = line(o)
        return position(line, o - starts[line])
    }

    fun range(range: TextRange): JsonObject = buildJsonObject {
        put("start", position(range.start))
        put("end", position(range.end))
    }

    /** An LSP `Range` object as editor offsets, or null when it is malformed. */
    fun toRange(range: JsonElement?): TextRange? {
        val r = range as? JsonObject ?: return null
        val start = toOffset(r["start"]) ?: return null
        val end = toOffset(r["end"]) ?: return null
        return if (end >= start) TextRange(start, end) else TextRange(end, start)
    }

    fun toOffset(position: JsonElement?): Int? {
        val p = position as? JsonObject ?: return null
        val line = p.int("line") ?: return null
        val character = p.int("character") ?: return null
        return offset(line, character)
    }

    companion object {
        fun position(line: Int, character: Int): JsonObject = buildJsonObject {
            put("line", line)
            put("character", character)
        }
    }
}

// ---- reading -----------------------------------------------------------------------------------------------

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** A string or number field as text: diagnostic codes and request ids are either. */
internal fun JsonObject.scalar(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/**
 * Whether a server capability is on: absent and `false` are off, `true` and any options object are on. Most
 * `*Provider` capabilities are `boolean | Options`.
 */
internal fun JsonObject.capability(key: String): Boolean = when (val v = this[key]) {
    null, JsonNull -> false
    is JsonPrimitive -> v.booleanOrNull ?: false
    else -> true
}

/** `MarkupContent | MarkedString | MarkedString[] | string` as Markdown text. */
internal fun markupText(value: JsonElement?): String? = when (value) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull
    is JsonArray -> value.mapNotNull { markupText(it) }.filter { it.isNotBlank() }.joinToString("\n\n").ifEmpty { null }
    is JsonObject -> {
        val v = value.str("value")
        val language = value.str("language")
        when {
            v == null -> null
            // A MarkedString with a language is a code block; MarkupContent has a `kind` instead.
            language != null -> "```$language\n$v\n```"
            else -> v
        }
    }
}

internal fun parseJsonOrNull(text: String?): JsonElement? =
    text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

// ---- URIs --------------------------------------------------------------------------------------------------

internal fun uriOf(path: Path): String = path.toAbsolutePath().normalize().toUri().toString()

/** The local path a `file:` URI names, or null for any other scheme (a server's virtual documents). */
internal fun pathOf(uri: String): Path? = runCatching {
    val u = URI(uri)
    if (!u.scheme.equals("file", ignoreCase = true)) null else Paths.get(u).toAbsolutePath().normalize()
}.getOrNull()

internal fun textDocument(uri: String): JsonObject = buildJsonObject { put("uri", uri) }

internal inline fun params(builder: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(builder)
