package dev.ide.model.impl.format

/**
 * A small, dependency-free TOML reader/writer for the native `module.toml` manifest. The IDE owns
 * this format, so only the subset the manifest uses is supported rather than the whole TOML spec:
 * comments, `[table]` / `[table.sub]` headers, `key = value`, and values that are quoted strings,
 * integers, booleans, arrays, and inline tables, including arrays that mix strings and inline tables
 * (the `dependencies` blocks).
 *
 * Parsed documents are nested ordered [Map]<String, Any?> (a `[a.b]` header creates `a` -> `b`),
 * values being [String], [Long], [Boolean], [List]<Any?>, or nested [Map] (inline table).
 *
 * The writer emits a [Map] back to TOML: each nested map becomes a `[dotted.path]` table (a
 * super-table with only sub-tables emits no header of its own), maps inside arrays become inline
 * tables. Round-tripping is value-stable (compare the parsed structures, not the text).
 */
object Toml {

    fun parse(text: String): Map<String, Any?> = Parser(text).parseDocument()

    fun write(doc: Map<String, Any?>): String {
        val sb = StringBuilder()
        writeTable(emptyList(), doc, sb)
        return sb.toString()
    }

    // --- writer ---

    private fun writeTable(prefix: List<String>, map: Map<String, Any?>, sb: StringBuilder) {
        val scalars = map.entries.filter { it.value !is Map<*, *> }
        val subTables = map.entries.filter { it.value is Map<*, *> }

        // A nested table emits a header when it has direct key/values, or when it is an empty leaf
        // (no sub-tables). A pure super-table (only sub-tables) emits no header of its own.
        if (prefix.isNotEmpty() && (scalars.isNotEmpty() || subTables.isEmpty())) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append('[').append(prefix.joinToString(".") { formatKey(it) }).append("]\n")
        }
        for (e in scalars) {
            sb.append(formatKey(e.key)).append(" = ").append(formatValue(e.value)).append('\n')
        }
        for (e in subTables) {
            @Suppress("UNCHECKED_CAST")
            writeTable(prefix + e.key, e.value as Map<String, Any?>, sb)
        }
    }

    private fun formatValue(v: Any?): String = when (v) {
        is String -> formatString(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double, is Float -> v.toString()
        is List<*> -> v.joinToString(prefix = "[", postfix = "]", separator = ", ") { formatValue(it) }
        is Map<*, *> -> v.entries.joinToString(prefix = "{ ", postfix = " }", separator = ", ") {
            "${formatKey(it.key.toString())} = ${formatValue(it.value)}"
        }
        else -> error("unsupported TOML value type: ${v?.let { it::class }}")
    }

    private val BARE_KEY = Regex("[A-Za-z0-9_-]+")
    private fun formatKey(k: String): String = if (BARE_KEY.matches(k)) k else formatString(k)

    private fun formatString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // --- parser ---

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseDocument(): Map<String, Any?> {
            val root = LinkedHashMap<String, Any?>()
            var current = root
            while (true) {
                skipInsignificant()
                if (pos >= s.length) break
                if (s[pos] == '[') {
                    current = enterTable(root)
                } else {
                    val key = readKey()
                    skipSpaces()
                    expect('=')
                    skipSpaces()
                    current[key] = readValue()
                    skipToLineEnd()
                }
            }
            return root
        }

        @Suppress("UNCHECKED_CAST")
        private fun enterTable(root: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
            expect('[')
            skipSpaces()
            val path = ArrayList<String>()
            while (true) {
                path.add(readKey())
                skipSpaces()
                when (s[pos]) {
                    '.' -> { pos++; skipSpaces() }
                    ']' -> { pos++; break }
                    else -> error("expected '.' or ']' in table header at $pos")
                }
            }
            skipToLineEnd()
            var node = root
            for (segment in path) {
                node = (node.getOrPut(segment) { LinkedHashMap<String, Any?>() } as LinkedHashMap<String, Any?>)
            }
            return node
        }

        private fun readValue(): Any? {
            skipSpaces()
            return when (val c = s[pos]) {
                '"' -> readString()
                '[' -> readArray()
                '{' -> readInlineTable()
                't', 'f' -> readBoolean()
                else -> if (c == '-' || c == '+' || c in '0'..'9') readNumber() else error("unexpected '$c' at $pos")
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            while (true) {
                skipInsignificant() // arrays may span lines and contain comments
                if (s[pos] == ']') { pos++; break }
                list.add(readValue())
                skipInsignificant()
                when (s[pos]) {
                    ',' -> { pos++; }
                    ']' -> { pos++; break }
                    else -> error("expected ',' or ']' in array at $pos")
                }
            }
            return list
        }

        private fun readInlineTable(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (s[pos] == '}') { pos++; return map }
            while (true) {
                skipSpaces()
                val key = readKey()
                skipSpaces()
                expect('=')
                skipSpaces()
                map[key] = readValue()
                skipSpaces()
                when (s[pos]) {
                    ',' -> { pos++; }
                    '}' -> { pos++; break }
                    else -> error("expected ',' or '}' in inline table at $pos")
                }
            }
            return map
        }

        private fun readKey(): String {
            skipSpaces()
            if (s[pos] == '"') return readString()
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '-')) pos++
            require(pos > start) { "expected a key at $pos" }
            return s.substring(start, pos)
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { val hex = s.substring(pos, pos + 4); pos += 4; sb.append(hex.toInt(16).toChar()) }
                        else -> error("invalid escape '\\$e' at ${pos - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-' || s[pos] == '+') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "._eE+-")) pos++
            val token = s.substring(start, pos).replace("_", "")
            return if (token.any { it == '.' || it == 'e' || it == 'E' }) token.toDouble() else token.toLong()
        }

        private fun readBoolean(): Boolean = when {
            s.startsWith("true", pos) -> { pos += 4; true }
            s.startsWith("false", pos) -> { pos += 5; false }
            else -> error("invalid literal at $pos")
        }

        /** Skip spaces and tabs only (stay on the current line). */
        private fun skipSpaces() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t')) pos++
        }

        /** Skip whitespace (incl. newlines), comments, and blank lines. */
        private fun skipInsignificant() {
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> pos++
                    c == '#' -> while (pos < s.length && s[pos] != '\n') pos++
                    else -> return
                }
            }
        }

        /** After a value: skip trailing spaces and an optional comment up to and including end-of-line. */
        private fun skipToLineEnd() {
            skipSpaces()
            if (pos < s.length && s[pos] == '#') while (pos < s.length && s[pos] != '\n') pos++
        }

        private fun expect(c: Char) {
            require(pos < s.length && s[pos] == c) { "expected '$c' at $pos but found '${if (pos < s.length) s[pos] else "<eof>"}'" }
            pos++
        }
    }
}
