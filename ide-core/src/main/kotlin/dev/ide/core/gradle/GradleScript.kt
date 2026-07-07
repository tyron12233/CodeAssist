package dev.ide.core.gradle

/**
 * Tolerant, brace-aware reader for Gradle build scripts (Groovy or Kotlin DSL). NOT a Gradle evaluator:
 * it never executes anything and never throws on malformed input — it recovers and returns what it can.
 *
 * It replaces the old line-by-line regex approach with structure-aware extraction: strip comments once,
 * then locate named blocks (`android { … }`, `dependencies { … }`, `plugins { … }`) by balanced braces,
 * split a block into its top-level statements, and enumerate a block's direct child blocks (build types,
 * product flavors). All scanning respects string literals so a `{`, `}`, or `//` inside a quoted string
 * never confuses brace matching. See [GradleImport] for how these primitives are composed.
 */
internal object GradleScript {

    /** A direct child block of some scope: `name { body }` or `create("name") { body }` — [name] is the
     *  quoted argument when present (the build-type / flavor name), else the leading identifier. */
    data class Block(val name: String, val body: String)

    /** Remove `//` line comments and `/* … */` block comments, leaving string literals untouched. */
    fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' || c == '\'' -> { val end = literalEnd(src, i); sb.append(src, i, end); i = end }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = minOf(i + 2, src.length)
                    sb.append(' ') // keep a token boundary where the comment was
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** The body inside the braces of the first `name { … }` (or `name(args) { … }`) block, or null. */
    fun blockBody(text: String, name: String): String? = allBlockBodies(text, name).firstOrNull()

    /** The bodies of every `name { … }` block found (used where a name can repeat, e.g. `sourceSets`). */
    fun allBlockBodies(text: String, name: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' || c == '\'') { i = literalEnd(text, i); continue }
            if (isIdentStart(c)) {
                val start = i
                while (i < text.length && isIdentPart(text[i])) i++
                if (text.substring(start, i) == name && text.getOrNull(start - 1) != '.') {
                    var j = skipSpaces(text, i)
                    if (j < text.length && text[j] == '(') {
                        val close = matching(text, j, '(', ')')
                        if (close < 0) continue
                        j = skipSpaces(text, close + 1)
                    }
                    if (j < text.length && text[j] == '{') {
                        val close = matching(text, j, '{', '}')
                        if (close > j) { out.add(text.substring(j + 1, close)); i = close + 1; continue }
                    }
                }
                continue
            }
            i++
        }
        return out
    }

    /** Split [text] into its top-level statements (broken on newlines and `;`), each trimmed and non-empty.
     *  Content nested inside braces/parens/brackets stays within its enclosing statement. */
    fun statements(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var i = 0
        fun flush() { val s = cur.toString().trim(); if (s.isNotEmpty()) out.add(s); cur.setLength(0) }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> { val e = literalEnd(text, i); cur.append(text, i, e); i = e; continue }
                c == '{' || c == '(' || c == '[' -> { depth++; cur.append(c) }
                c == '}' || c == ')' || c == ']' -> { if (depth > 0) depth--; cur.append(c) }
                (c == '\n' || c == ';') && depth == 0 -> flush()
                else -> cur.append(c)
            }
            i++
        }
        flush()
        return out
    }

    /** The direct child blocks of [text] — each `word { … }` or `word("arg") { … }` at depth 0. */
    fun childBlocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' || c == '\'') { i = literalEnd(text, i); continue }
            if (c == '{') { val close = matching(text, i, '{', '}'); i = if (close > i) close + 1 else i + 1; continue }
            if (isIdentStart(c)) {
                val start = i
                while (i < text.length && isIdentPart(text[i])) i++
                val word = text.substring(start, i)
                var j = skipSpaces(text, i)
                var arg: String? = null
                if (j < text.length && text[j] == '(') {
                    val close = matching(text, j, '(', ')')
                    if (close < 0) continue
                    arg = firstQuoted(text.substring(j + 1, close))
                    j = skipSpaces(text, close + 1)
                }
                if (j < text.length && text[j] == '{') {
                    val close = matching(text, j, '{', '}')
                    if (close > j) { out.add(Block(arg ?: word, text.substring(j + 1, close))); i = close + 1; continue }
                }
                continue
            }
            i++
        }
        return out
    }

    /** The content of the first quoted string in [s], or null. */
    fun firstQuoted(s: String): String? = Regex("""['"]([^'"]*)['"]""").find(s)?.groupValues?.get(1)

    // --- internals ---

    /** Index just past the string literal starting at [start] (handles `"`, `'`, `"""`, `'''`). */
    private fun literalEnd(t: String, start: Int): Int {
        val q = t[start]
        val triple = "$q$q$q"
        if (t.startsWith(triple, start)) {
            var i = start + 3
            while (i < t.length) { if (t.startsWith(triple, i)) return i + 3 else i++ }
            return t.length
        }
        var i = start + 1
        while (i < t.length) {
            when (t[i]) {
                '\\' -> i += 2
                q -> return i + 1
                '\n' -> return i // unterminated single-line string: recover at the newline
                else -> i++
            }
        }
        return t.length
    }

    /** The index of the closer matching the [open] bracket at [t]\[from], respecting strings; -1 if none. */
    private fun matching(t: String, from: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = from
        while (i < t.length) {
            val c = t[i]
            when {
                c == '"' || c == '\'' -> { i = literalEnd(t, i); continue }
                c == open -> depth++
                c == close -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun skipSpaces(t: String, from: Int): Int {
        var i = from
        while (i < t.length && (t[i] == ' ' || t[i] == '\t' || t[i] == '\r' || t[i] == '\n')) i++
        return i
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'
    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
