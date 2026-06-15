package dev.ide.model.impl.format

/**
 * A small, dependency-free JSON reader/writer for the model's own persisted files
 * (`workspace.json`, `libraries.json`, `sdks.json`). The IDE owns these files, so only the subset
 * they emit is supported; this keeps the on-device footprint at zero extra libraries.
 *
 * Parsed values are plain Kotlin: ordered [Map]<String, Any?> for objects (insertion order
 * preserved, so output is deterministic), [List]<Any?> for arrays, [String], [Long] (integers),
 * [Double] (reals), [Boolean], and null. Output is pretty-printed (2-space indent) to stay
 * human-diffable.
 */
object Json {

    private const val FORM_FEED = '\u000C'

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.readValue()
        p.skipWs()
        require(p.atEnd()) { "trailing content at offset ${p.pos}" }
        return v
    }

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(value, sb, 0)
        sb.append('\n')
        return sb.toString()
    }

    // --- writer ---

    private fun writeValue(v: Any?, sb: StringBuilder, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Double, is Float -> sb.append(v.toString())
            is Map<*, *> -> writeObject(v, sb, indent)
            is List<*> -> writeArray(v, sb, indent)
            else -> error("unsupported JSON value type: ${v::class}")
        }
    }

    private fun writeObject(map: Map<*, *>, sb: StringBuilder, indent: Int) {
        if (map.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append("{\n")
        val pad = "  ".repeat(indent + 1)
        val entries = map.entries.toList()
        for ((i, e) in entries.withIndex()) {
            sb.append(pad)
            writeString(e.key.toString(), sb)
            sb.append(": ")
            writeValue(e.value, sb, indent + 1)
            if (i != entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ".repeat(indent)).append('}')
    }

    private fun writeArray(list: List<*>, sb: StringBuilder, indent: Int) {
        if (list.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append("[\n")
        val pad = "  ".repeat(indent + 1)
        for ((i, item) in list.withIndex()) {
            sb.append(pad)
            writeValue(item, sb, indent + 1)
            if (i != list.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ".repeat(indent)).append(']')
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                FORM_FEED -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    // --- parser ---

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end of JSON" }
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> if (c == '-' || c in '0'..'9') readNumber() else error("unexpected '$c' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                map[key] = readValue()
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> error("expected ',' or '}' but found '$c' at ${pos - 1}")
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> error("expected ',' or ']' but found '$c' at ${pos - 1}")
                }
            }
            return list
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append(FORM_FEED)
                            'u' -> {
                                val hex = s.substring(pos, pos + 4); pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> error("invalid escape '\\$e' at ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && s[pos] in "0123456789.eE+-") pos++
            val token = s.substring(start, pos)
            return if (token.any { it == '.' || it == 'e' || it == 'E' }) token.toDouble() else token.toLong()
        }

        private fun readBoolean(): Boolean =
            when {
                s.startsWith("true", pos) -> { pos += 4; true }
                s.startsWith("false", pos) -> { pos += 5; false }
                else -> error("invalid literal at $pos")
            }

        private fun readNull(): Any? {
            require(s.startsWith("null", pos)) { "invalid literal at $pos" }
            pos += 4
            return null
        }

        private fun peek(): Char = s[pos]
        private fun next(): Char = s[pos++]
        private fun expect(c: Char) {
            require(pos < s.length && s[pos] == c) { "expected '$c' at $pos" }
            pos++
        }
    }
}
