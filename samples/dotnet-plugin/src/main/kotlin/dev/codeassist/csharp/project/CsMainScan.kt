package dev.codeassist.csharp.project


/**
 * Finds compiler-accepted `static Main` entry points and top-level statements in C# source text.
 *
 * Recognizes `void`, `int`, `Task`, and `Task<int>` return types, with optional `string[] args`,
 * any accessibility, and optional `async`. Entry points must be direct members of a non-generic
 * class, struct, record, or interface.
 *
 * Other signatures and generic `Main<T>` methods are ignored.
 *
 * Pure text processing without a parser, so it can run on every editor pass and be tested without an IDE.
 */
internal object CsMainScan {

    /** An entry point's compiler class name (`Namespace.Outer.Inner`) and the offset where `Main` starts. */
    class Hit(val className: String, val offset: Int)

    private enum class Kind { NAMESPACE, TYPE, BLOCK }

    private class Scope(val kind: Kind, val name: String?, val generic: Boolean)

    private val TOKEN = Regex(
        """\bnamespace\s+([\w.]+)\s*;""" +
            """|\bnamespace\s+([\w.]+)""" +
            """|\b(?:record\s+(?:class|struct)|class|struct|record|interface)\s+(\w+)(\s*<)?""" +
            """|\bMain\s*\(\s*(?:string\s*\[\s*\]\s*\w+\s*)?\)""" +
            """|[{};]"""
    )

    private val STATIC = Regex("""\bstatic\b""")
    private val TASKS = """(?:System\s*\.\s*Threading\s*\.\s*Tasks\s*\.\s*)?"""
    private val RETURN = Regex("""(?:\bvoid|\bint|$TASKS\bTask(?:\s*<\s*int\s*>)?)\s*$""")

    /** Keywords that may follow `class` without declaring a type, such as `where T : class`. */
    private val NOT_TYPE_NAMES = setOf("new", "where", "notnull", "unmanaged")

    /** Returns all recognized entry points in the source. */
    fun hits(text: String): List<Hit> = scan(text).first

    /**
     * Returns the offset of the first top-level statement, or null if none exists.
     *
     * Declarations and `using`/`extern` lines are excluded. Statements inside file-scoped namespaces
     * are not checked because C# does not allow them there.
     */
    fun topLevel(text: String): Int? = scan(text).second

    /** Declaration prefixes that distinguish top-level declarations from executable statements. */
    private val DECLARATION = Regex(
        """^(?:\[[^\]]*]\s*)*""" +
            """(?:(?:public|internal|private|protected|static|sealed|abstract|partial|unsafe|readonly|ref|file|new|""" +
            """global)\s+)*""" +
            """(?:enum|delegate|class|struct|interface|record|namespace|extern\s+alias|""" +
            """using\s+(?!var\b|\()|using\s*$)"""
    )

    private fun scan(text: String): Pair<List<Hit>, Int?> {
        val clean = mask(text)
        val out = ArrayList<Hit>()
        var topLevel: Int? = null

        // A top-level statement ends at `;` or opens the `{` following its header.
        fun checkStatement(to: Int, from: Int) {
            if (topLevel != null) return

            val header = clean.substring(from, to)
            val trimmed = header.trim()
            if (trimmed.isEmpty() || DECLARATION.containsMatchIn(trimmed)) return

            topLevel = from + header.indexOfFirst { !it.isWhitespace() }
        }

        val stack = ArrayList<Scope>()
        var fileNamespace: String? = null
        var pending: Scope? = null
        var headerStart = 0

        for (match in TOKEN.findAll(clean)) {
            val after = match.range.last + 1

            when {
                match.groups[1] != null -> {
                    fileNamespace = match.groupValues[1]
                    pending = null
                    headerStart = after
                }

                match.groups[2] != null -> {
                    pending = Scope(Kind.NAMESPACE, match.groupValues[2], false)
                }

                match.groups[3] != null -> {
                    val name = match.groupValues[3]
                    if (name !in NOT_TYPE_NAMES) {
                        pending = Scope(Kind.TYPE, name, match.groups[4] != null)
                    }
                }

                match.value == "{" -> {
                    if (pending == null && stack.isEmpty() && fileNamespace == null) {
                        checkStatement(match.range.first, headerStart)
                    }

                    stack += pending ?: Scope(Kind.BLOCK, null, false)
                    pending = null
                    headerStart = after
                }

                match.value == "}" -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    pending = null
                    headerStart = after
                }

                match.value == ";" -> {
                    if (stack.isEmpty() && fileNamespace == null) {
                        checkStatement(match.range.first, headerStart)
                    }

                    pending = null
                    headerStart = after
                }

                else -> {
                    val owner = stack.lastOrNull() ?: continue
                    if (owner.kind != Kind.TYPE || owner.generic) continue

                    val header = clean.substring(headerStart, match.range.first)
                    if (!STATIC.containsMatchIn(header) || !RETURN.containsMatchIn(header.trimEnd())) continue

                    val names = listOfNotNull(fileNamespace) + stack.mapNotNull { it.name }
                    out += Hit(names.joinToString("."), match.range.first)
                }
            }
        }

        return out to topLevel
    }

    /** Returns the zero-based line number containing [offset]. */
    fun lineOf(text: String, offset: Int): Int {
        var line = 0
        for (i in 0 until offset.coerceAtMost(text.length)) {
            if (text[i] == '\n') line++
        }
        return line
    }

    /**
     * Masks comments, string and character literals, and preprocessor directive lines with spaces.
     *
     * Newlines are preserved, keeping offsets aligned with the original source.
     */
    internal fun mask(text: String): String {
        val out = text.toCharArray()

        fun blank(from: Int, to: Int) {
            for (k in from until to.coerceAtMost(out.size)) {
                if (out[k] != '\n' && out[k] != '\r') out[k] = ' '
            }
        }

        var i = 0
        var lineStart = true

        while (i < text.length) {
            val c = text[i]

            if (c == '\n') {
                lineStart = true
                i++
                continue
            }

            if (lineStart && c.isWhitespace()) {
                i++
                continue
            }

            if (lineStart && c == '#') {
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                blank(i, end)
                i = end
                continue
            }

            lineStart = false

            when {
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    blank(i, end)
                    i = end
                }

                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    blank(i, end)
                    i = end
                }

                c == '"' || ((c == '$' || c == '@') && startsString(text, i)) -> {
                    i = maskString(text, i, ::blank)
                }

                c == '\'' -> {
                    i = maskChar(text, i, ::blank)
                }

                else -> i++
            }
        }

        return String(out)
    }

    /** Whether the `$` or `@` prefixes at [i] lead to a string literal. */
    private fun startsString(text: String, i: Int): Boolean {
        var j = i
        while (j < text.length && (text[j] == '$' || text[j] == '@')) j++
        return j < text.length && text[j] == '"'
    }

    private fun maskString(text: String, start: Int, blank: (Int, Int) -> Unit): Int {
        var i = start
        var verbatim = false

        while (text[i] == '$' || text[i] == '@') {
            if (text[i] == '@') verbatim = true
            i++
        }

        var quotes = 0
        while (i + quotes < text.length && text[i + quotes] == '"') quotes++

        val end: Int

        if (quotes >= 3) {
            // Raw strings close with a run of at least as many quotes as the opening delimiter.
            val close = "\"".repeat(quotes)
            end = text.indexOf(close, i + quotes).let { if (it < 0) text.length else it + quotes }
        } else {
            var j = i + 1

            while (j < text.length) {
                val ch = text[j]

                if (verbatim) {
                    if (ch != '"') {
                        j++
                    } else if (j + 1 < text.length && text[j + 1] == '"') {
                        j += 2
                    } else {
                        j++
                        break
                    }
                } else {
                    if (ch == '\\') {
                        j += 2
                    } else if (ch == '"') {
                        j++
                        break
                    } else if (ch == '\n') {
                        break
                    } else {
                        j++
                    }
                }
            }

            end = j.coerceAtMost(text.length)
        }

        blank(start, end)
        return end
    }

    private val CHAR_LITERAL = Regex("""'(?:\\.[^'\n]{0,8}|[^'\\\n])'""")

    /** Masks a character literal; an unmatched apostrophe is left unchanged. */
    private fun maskChar(text: String, start: Int, blank: (Int, Int) -> Unit): Int {
        val literal = CHAR_LITERAL.matchAt(text, start) ?: return start + 1
        blank(start, literal.range.last + 1)
        return literal.range.last + 1
    }
}