package dev.ide.deps.impl

/**
 * A tiny recursive-descent JSON reader — just enough to read the Maven Central search response without
 * pulling in a JSON library (the framework is stdlib-only). Returns the standard ladder of types:
 * `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean`, or `null`.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); skipWs(); v }

    private class Parser(private val s: String) {
        private var i = 0

        fun value(): Any? {
            skipWs()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            expect('{'); skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = str()
                skipWs(); expect(':')
                out[key] = value()
                skipWs()
                when (s[i]) { ',' -> { i++; continue }; '}' -> { i++; break }; else -> error("bad object at $i") }
            }
            return out
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            expect('['); skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += value(); skipWs()
                when (s[i]) { ',' -> { i++; continue }; ']' -> { i++; break }; else -> error("bad array at $i") }
            }
            return out
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = s[i++]) {
                    '"' -> break
                    '\\' -> when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun <T> literal(word: String, v: T): T { require(s.startsWith(word, i)); i += word.length; return v }
        private fun peek(): Char { skipWs(); return s[i] }
        private fun expect(c: Char) { if (s[i] != c) error("expected '$c' at $i"); i++ }
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}
