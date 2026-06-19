package dev.ide.analytics.impl

/**
 * A minimal, encode-only JSON writer for the event wire format. We never parse JSON (the disk queue uses
 * its own escaped line format, see [EventQueueCodec]), so this is deliberately tiny — just enough to build
 * a PostgREST insert body. No dependency on kotlinx-serialization, keeping the framework stdlib-only.
 */
internal object Json {
    /** A JSON object from ordered key→value pairs. */
    fun obj(pairs: List<Pair<String, Any?>>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Number -> v.toString()
        is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "${str(it.key.toString())}:${value(it.value)}" }
        is List<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> str(v.toString())
    }

    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
