package dev.ide.bench

/**
 * A deliberately tiny JSON codec for the ONLY shape a baseline file has: a flat object whose values are
 * numbers — `{ "metric.key": 12345.0, ... }`. This is not a general JSON library; it exists so baselines
 * are human-readable and git-diffable without pulling in a serialization dependency (the project is
 * stdlib-only). [read] accepts exactly what [write] emits (plus tolerant whitespace), and the round-trip
 * is verified by FlatJsonTest.
 */
internal object FlatJson {

    fun write(map: Map<String, Double>): String {
        val keys = map.keys.sorted()
        val sb = StringBuilder("{\n")
        keys.forEachIndexed { i, k ->
            sb.append("  ").append(quote(k)).append(": ").append(num(map.getValue(k)))
            if (i < keys.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("}\n")
        return sb.toString()
    }

    fun read(text: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        val n = text.length
        var i = 0

        fun skipWs() { while (i < n && text[i].isWhitespace()) i++ }

        fun readString(): String {
            // precondition: text[i] == '"'
            i++
            val sb = StringBuilder()
            while (i < n) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < n -> { sb.append(unescape(text[i + 1])); i += 2 }
                    c == '"' -> { i++; return sb.toString() }
                    else -> { sb.append(c); i++ }
                }
            }
            error("unterminated string in baseline JSON")
        }

        skipWs()
        require(i < n && text[i] == '{') { "expected '{' at offset $i" }
        i++
        skipWs()
        if (i < n && text[i] == '}') return out
        while (i < n) {
            skipWs()
            require(text[i] == '"') { "expected a quoted key at offset $i" }
            val key = readString()
            skipWs()
            require(i < n && text[i] == ':') { "expected ':' after key '$key'" }
            i++
            skipWs()
            val start = i
            while (i < n && text[i] != ',' && text[i] != '}' && !text[i].isWhitespace()) i++
            out[key] = text.substring(start, i).toDouble()
            skipWs()
            when {
                i < n && text[i] == ',' -> { i++ }
                i < n && text[i] == '}' -> break
                else -> break
            }
        }
        return out
    }

    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> c // covers \" and \\ (the only ones we ever emit)
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Plain decimal for readable diffs: integral values print without a fraction or exponent. */
    private fun num(v: Double): String =
        if (v.isFinite() && v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
