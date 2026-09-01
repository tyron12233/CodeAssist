package dev.ide.platform

/**
 * A dependency-free JSON reader.
 *
 * Parsed values are plain Kotlin: [Map]<String, Any?> for objects (insertion-ordered), [List]<Any?> for
 * arrays, [String], [Long] for integers, [Double] for reals, [Boolean], and null. The typed accessors at
 * the bottom exist so callers stop writing `(x as? Map<*, *>)?.get("k") as? String` at every field.
 *
 * Reading only. Writing JSON is a few lines of string building at the call site and every producer wants
 * its own formatting (pretty-printed and diffable for an on-disk model file, minimal for an HTTP body),
 * so a shared writer would be the wrong shape.
 *
 * There are older private copies of a JSON reader in `deps-impl`, `project-model-impl` and
 * `analytics-impl`. This is the shared home; those can migrate onto it, which is a tidy-up worth doing
 * separately rather than as a side effect of whatever needed the fourth copy.
 */
object JsonReader {

    private const val FORM_FEED = '\u000C'

    /** Parse [text], or throw [IllegalArgumentException] if it is not valid JSON. */
    fun parse(text: String): Any? {
        val p = Cursor(text)
        val v = p.value()
        p.ws()
        require(p.done()) { "trailing content at offset ${p.pos}" }
        return v
    }

    /** Parse [text], or return null rather than throwing — the shape most HTTP callers want. */
    fun parseOrNull(text: String): Any? = runCatching { parse(text) }.getOrNull()

    private class Cursor(private val s: String) {
        var pos = 0

        fun done() = pos >= s.length

        fun ws() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            ws()
            require(pos < s.length) { "unexpected end of input" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else error("unexpected '$c' at offset $pos")
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                ws()
                val k = str()
                ws()
                require(pos < s.length && s[pos] == ':') { "expected ':' at offset $pos" }
                pos++
                out[k] = value()
                ws()
                require(pos < s.length) { "unterminated object" }
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> error("expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            ws()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                out += value()
                ws()
                require(pos < s.length) { "unterminated array" }
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> error("expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun str(): String {
            require(pos < s.length && s[pos] == '"') { "expected string at offset $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        require(pos < s.length) { "unterminated escape" }
                        when (val e = s[pos]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append(FORM_FEED)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 < s.length) { "truncated unicode escape" }
                                sb.append(s.substring(pos + 1, pos + 5).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("bad escape at offset $pos")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] in ".eE+-")) pos++
            val raw = s.substring(start, pos)
            // Integers stay Long so an id or a byte count never picks up a floating-point representation.
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "expected '$word' at offset $pos" }
            pos += word.length
            return value
        }
    }

    // ---- typed accessors ----

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    fun arr(v: Any?): List<Any?> = (v as? List<Any?>).orEmpty()

    fun str(v: Any?, key: String): String? = obj(v)?.get(key) as? String

    fun bool(v: Any?, key: String, default: Boolean = false): Boolean = obj(v)?.get(key) as? Boolean ?: default

    fun long(v: Any?, key: String, default: Long = 0L): Long = when (val x = obj(v)?.get(key)) {
        is Long -> x
        is Double -> x.toLong()
        else -> default
    }

    fun int(v: Any?, key: String, default: Int = 0): Int = long(v, key, default.toLong()).toInt()

    fun float(v: Any?, key: String): Float? = when (val x = obj(v)?.get(key)) {
        is Double -> x.toFloat()
        is Long -> x.toFloat()
        else -> null
    }

    /** A string array field, dropping any non-string entries rather than failing the whole parse. */
    fun strings(v: Any?, key: String): List<String> = arr(obj(v)?.get(key)).filterIsInstance<String>()
}
