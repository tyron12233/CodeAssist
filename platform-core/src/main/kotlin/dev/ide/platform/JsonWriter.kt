package dev.ide.platform

/**
 * Minimal JSON encoding, the counterpart to [JsonReader].
 *
 * Values round-trip through the two: whatever [JsonReader] produces, this re-encodes. That matters where
 * a payload is read from one place and handed to another without the intermediate layers needing to
 * understand its shape, such as a challenge's test inputs travelling from the server to the on-device
 * runner.
 */
object JsonWriter {

    /** Encodes a value made of maps, lists, strings, numbers, booleans and nulls. */
    fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> string(v)
        is Boolean -> v.toString()
        is Int, is Long, is Short, is Byte -> v.toString()
        is Float -> number(v.toDouble())
        is Double -> number(v)
        is Number -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { string(it.key.toString()) + ":" + value(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]") { value(it) }
        is Array<*> -> v.joinToString(",", "[", "]") { value(it) }
        is IntArray -> v.joinToString(",", "[", "]")
        is LongArray -> v.joinToString(",", "[", "]")
        is DoubleArray -> v.joinToString(",", "[", "]") { number(it) }
        is BooleanArray -> v.joinToString(",", "[", "]")
        else -> string(v.toString())
    }

    /** Builds an object from pairs, dropping nothing: a null value is encoded as JSON null. */
    fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { string(it.first) + ":" + value(it.second) }

    fun string(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append("\\u").append(c.code.toString(16).padStart(4, '0')) else b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }

    /**
     * Whole doubles are written without a fractional part.
     *
     * JSON does not distinguish integers from reals, and a value that arrives as `2` should not become
     * `2.0` merely by passing through here: the far side may be comparing it against what it sent.
     */
    private fun number(v: Double): String = when {
        v.isNaN() || v.isInfinite() -> string(v.toString())
        v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
        else -> v.toString()
    }
}
