package dev.ide.core.amper

/**
 * The YAML that Amper's `project.yaml` and `module.yaml` are written in: block mappings and sequences by
 * indentation, flow sequences (`[jvm, android]`) and flow mappings (`{ scope: runtime-only }`), plain and quoted
 * scalars, and `#` comments. A sequence item may itself be a mapping (`- $libs.x: exported`, or one key per line
 * under the dash), and a sequence may sit at its key's own indentation, as YAML allows.
 *
 * Not a YAML implementation: no anchors, tags, multi-documents or block scalars, none of which Amper's files
 * use. Hand-rolled rather than a library because the format is this small and the app runs on ART with no
 * reflection to spare. Values come back as `Map<String, Any?>` (insertion-ordered), `List<Any?>` and `String`.
 */
internal object AmperYaml {

    fun parse(text: String): Any? {
        val lines = text.lines().mapNotNull { raw ->
            val content = stripComment(raw).trimEnd()
            if (content.isBlank() || content.trim() == "---") null
            else Line(content.length - content.trimStart().length, content.trim())
        }
        if (lines.isEmpty()) return null
        return Parser(lines).block(lines.first().indent)
    }

    private data class Line(val indent: Int, val text: String)

    private class Parser(private val lines: List<Line>) {
        private var pos = 0

        fun block(indent: Int): Any? {
            if (pos >= lines.size) return null
            return if (lines[pos].text.startsWith("- ") || lines[pos].text == "-") sequence(indent) else mapping(indent)
        }

        private fun mapping(indent: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            while (pos < lines.size && lines[pos].indent == indent && !isItem(lines[pos].text)) {
                val kv = splitKey(lines[pos].text)
                pos++
                if (kv == null) continue   // a stray scalar line in a mapping: nothing to key it by
                out[kv.first] = if (kv.second.isNotEmpty()) scalarOrFlow(kv.second) else nested(indent)
            }
            return out
        }

        private fun sequence(indent: Int): List<Any?> {
            val out = ArrayList<Any?>()
            while (pos < lines.size && lines[pos].indent == indent && isItem(lines[pos].text)) {
                val body = lines[pos].text.removePrefix("-").trim()
                pos++
                if (body.isEmpty()) {
                    out += nested(indent)
                    continue
                }
                val kv = if (body.startsWith("[") || body.startsWith("{") || body.startsWith("\"") || body.startsWith("'")) null
                else splitKey(body)
                if (kv == null) {
                    out += scalarOrFlow(body)
                    continue
                }
                // An item that is a mapping: its first entry on the dash's line, the rest indented under it.
                val item = LinkedHashMap<String, Any?>()
                val itemIndent = indent + 2
                item[kv.first] = if (kv.second.isNotEmpty()) scalarOrFlow(kv.second) else nested(itemIndent, deeperThan = indent)
                while (pos < lines.size && lines[pos].indent > indent && !isItem(lines[pos].text)) {
                    val more = mapping(lines[pos].indent)
                    if (more.isEmpty()) break
                    item.putAll(more)
                }
                out += item
            }
            return out
        }

        /** The value of a key with nothing after its colon: a deeper block, a same-indent sequence, or null. */
        private fun nested(indent: Int, deeperThan: Int = indent): Any? {
            if (pos >= lines.size) return null
            val next = lines[pos]
            return when {
                next.indent > deeperThan -> block(next.indent)
                next.indent == indent && isItem(next.text) -> sequence(indent)
                else -> null
            }
        }

        private fun isItem(text: String) = text.startsWith("- ") || text == "-"
    }

    /** `key: value` / `key:` split at the first colon followed by a space or the end; null for a plain scalar. */
    private fun splitKey(text: String): Pair<String, String>? {
        var quote: Char? = null
        for (i in text.indices) {
            val c = text[i]
            if (quote != null) { if (c == quote) quote = null; continue }
            if ((c == '"' || c == '\'') && i == 0) { quote = c; continue }
            if (c == ':' && (i == text.length - 1 || text[i + 1] == ' ')) {
                return unquote(text.substring(0, i).trim()) to text.substring(i + 1).trim()
            }
        }
        return null
    }

    private fun scalarOrFlow(text: String): Any? = when {
        text.startsWith("[") && text.endsWith("]") ->
            splitFlow(text.substring(1, text.length - 1)).map { scalarOrFlow(it) }
        text.startsWith("{") && text.endsWith("}") -> LinkedHashMap<String, Any?>().apply {
            for (part in splitFlow(text.substring(1, text.length - 1))) {
                val kv = splitKey(part)
                if (kv != null) put(kv.first, scalarOrFlow(kv.second)) else put(unquote(part), null)
            }
        }
        text == "~" || text == "null" -> null
        else -> unquote(text)
    }

    /** Split a flow collection's body on top-level commas. */
    private fun splitFlow(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        val cur = StringBuilder()
        for (c in body) {
            when {
                quote != null -> { if (c == quote) quote = null; cur.append(c) }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                c == '[' || c == '{' -> { depth++; cur.append(c) }
                c == ']' || c == '}' -> { depth--; cur.append(c) }
                c == ',' && depth == 0 -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')))) s.substring(1, s.length - 1)
        else s

    /** A `#` comment starts at line start or after whitespace, outside quotes (`$libs.x#y` is not one). */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        for (i in line.indices) {
            val c = line[i]
            if (quote != null) { if (c == quote) quote = null; continue }
            if (c == '"' || c == '\'') quote = c
            else if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) return line.substring(0, i)
        }
        return line
    }
}
