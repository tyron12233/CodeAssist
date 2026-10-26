package dev.ide.lang.java.completion

/** Where a Java live template may expand: the kinds of position IntelliJ's template contexts distinguish. */
internal enum class JavaPlace {
    /** Among a class's members, where a field or method is declared. */
    CLASS_BODY,
    /** At the start of a statement inside a method, constructor, initializer or lambda body. */
    STATEMENT,
    /** Inside an expression: an argument, an initializer, after `return`, a condition. */
    EXPRESSION,
    /** Nowhere a template fits: a comment, a string, after a `.` or `@`, or outside any class. */
    NONE,
}

/**
 * The position a template would expand at, plus the names a template can fill in from it: the innermost
 * enclosing class, and the enclosing method with its parameter names.
 */
internal class JavaTemplateContext(
    val place: JavaPlace,
    val className: String?,
    val methodName: String?,
    val methodParams: List<String>,
)

/**
 * Reads a [JavaTemplateContext] off the source text in one pass up to the caret: it skips comments, strings,
 * character literals and text blocks, and keeps a stack of the blocks it is inside, each classified by the
 * text before its `{` (a class body, an array initializer, or code). It works on the raw text rather than the
 * compiler's tree because the incomplete line being typed (`psv`, `sou`) is exactly what the parser recovers
 * from unpredictably: the same token reads as a type at one spot and as a name at the next.
 */
internal object JavaTemplateContexts {

    private enum class Kind { CLASS, CODE, INIT }

    private class Block(val kind: Kind, val className: String?, val methodName: String?, val params: List<String>) {
        var parens = 0
        var statementStart = 0
    }

    /** Past this the scan isn't worth its cost per keystroke; every template is then treated as fitting. */
    private const val SCAN_LIMIT = 300_000

    fun at(text: CharSequence, offset: Int): JavaTemplateContext {
        val end = offset.coerceIn(0, text.length)
        if (end > SCAN_LIMIT) return JavaTemplateContext(JavaPlace.STATEMENT, null, null, emptyList())
        val stack = ArrayList<Block>()
        var topStatementStart = 0
        var i = 0
        while (i < end) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    val nl = indexOf(text, '\n', i + 2, end)
                    if (nl < 0) return none()
                    i = nl
                }
                c == '/' && next == '*' -> {
                    val close = indexOf(text, "*/", i + 2, end)
                    if (close < 0) return none()
                    i = close + 1
                }
                c == '"' && next == '"' && i + 2 < text.length && text[i + 2] == '"' -> {
                    val close = indexOf(text, "\"\"\"", i + 3, end)
                    if (close < 0) return none()
                    i = close + 2
                }
                c == '"' || c == '\'' -> {
                    val close = closingQuote(text, i, end)
                    if (close < 0) return none()
                    i = close
                }
                c == '{' -> {
                    val top = stack.lastOrNull()
                    val header = text.subSequence(top?.statementStart ?: topStatementStart, i).toString().trim()
                    stack += open(header, top)
                    stack.last().statementStart = i + 1
                }
                c == '}' -> {
                    stack.removeLastOrNull()
                    val top = stack.lastOrNull()
                    if (top != null) top.statementStart = i + 1 else topStatementStart = i + 1
                }
                c == ';' -> {
                    val top = stack.lastOrNull()
                    if (top == null) topStatementStart = i + 1 else if (top.parens == 0) top.statementStart = i + 1
                }
                c == '(' -> stack.lastOrNull()?.let { it.parens++ }
                c == ')' -> stack.lastOrNull()?.let { if (it.parens > 0) it.parens-- }
            }
            i++
        }

        val top = stack.lastOrNull() ?: return none()
        val className = stack.lastOrNull { it.className != null }?.className
        val method = stack.lastOrNull { it.kind == Kind.CODE && it.methodName != null }
        fun ctx(place: JavaPlace) = JavaTemplateContext(place, className, method?.methodName, method?.params.orEmpty())

        // A member access (`list.it|`) or an annotation (`@Ov|`) names something; it starts no template.
        val before = lastNonSpace(text, end)
        if (before == '.' || before == '@') return ctx(JavaPlace.NONE)
        val sofar = text.subSequence(top.statementStart.coerceAtMost(end), end).toString().trim()
        return when (top.kind) {
            Kind.INIT -> ctx(JavaPlace.EXPRESSION)
            Kind.CLASS -> ctx(if (top.parens > 0 || '=' in sofar) JavaPlace.EXPRESSION else JavaPlace.CLASS_BODY)
            Kind.CODE -> ctx(
                when {
                    top.parens > 0 -> JavaPlace.EXPRESSION
                    sofar.isEmpty() || opensStatement(sofar) -> JavaPlace.STATEMENT
                    else -> JavaPlace.EXPRESSION
                },
            )
        }
    }

    private fun none() = JavaTemplateContext(JavaPlace.NONE, null, null, emptyList())

    /** The block a `{` opens, judged by the [header] text before it and the block it sits in. */
    private fun open(header: String, parent: Block?): Block {
        val last = header.lastOrNull()
        if (last == '=' || last == ']' || last == ',' || (parent != null && parent.kind == Kind.INIT)) {
            return Block(Kind.INIT, null, parent?.methodName, parent?.params.orEmpty())
        }
        val words = words(header)
        val typeKeyword = words.indexOfFirst { it in TYPE_KEYWORDS }
        if (typeKeyword >= 0) return Block(Kind.CLASS, words.getOrNull(typeKeyword + 1), null, emptyList())
        // An anonymous class: `new Runnable() {`.
        if (last == ')' && "new" in words && !header.contains("->")) return Block(Kind.CLASS, parent?.className, null, emptyList())
        // A method or constructor body opens straight under a class; anything nested keeps its method.
        if (parent == null || parent.kind == Kind.CLASS) {
            val (name, params) = methodHeader(header) ?: (null to emptyList())
            return Block(Kind.CODE, null, name, params)
        }
        return Block(Kind.CODE, null, parent.methodName, parent.params)
    }

    /**
     * The method name and parameter names of a header like `public void onCreate(Bundle state) throws X`, or
     * null for an initializer (`static`) or anything without a parameter list.
     */
    private fun methodHeader(header: String): Pair<String, List<String>>? {
        val open = header.indexOf('(')
        val close = header.lastIndexOf(')')
        if (open <= 0 || close < open) return null
        val name = header.substring(0, open).trim().substringAfterLast(' ').substringAfterLast('>')
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' || it == '$' }) return null
        if (name in CONTROL_KEYWORDS) return null
        val params = splitTopLevel(header.substring(open + 1, close))
            .map { p -> p.trim().removeSuffix("...").substringAfterLast(' ').trim() }
            .filter { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '$' } }
        return name to params
    }

    /** Commas that separate parameters, not the ones inside `Map<K, V>`. */
    private fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var from = 0
        for ((i, c) in s.withIndex()) {
            when (c) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
                ',' -> if (depth == 0) { out += s.substring(from, i); from = i + 1 }
            }
        }
        if (from < s.length) out += s.substring(from)
        return out
    }

    /** Whether a statement may start right after [sofar]: a brace-less `if (…)`/`for (…)`/`while (…)` header,
     *  `else`/`do`, or a `case …:` / `default:` / `case … ->` label. */
    private fun opensStatement(sofar: String): Boolean {
        if (sofar == "else" || sofar == "do" || sofar.endsWith("->")) return true
        if ((sofar.startsWith("case ") || sofar.startsWith("default")) && sofar.endsWith(":")) return true
        val head = words(sofar).firstOrNull() ?: return false
        return head in setOf("if", "for", "while") && sofar.endsWith(")") && balanced(sofar)
    }

    private fun balanced(s: String): Boolean = s.count { it == '(' } == s.count { it == ')' }

    private fun words(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$' || s[i] == '@') {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$' || s[i] == '@')) i++
                out += s.substring(start, i)
            } else {
                i++
            }
        }
        return out
    }

    private fun lastNonSpace(text: CharSequence, end: Int): Char? {
        var i = end - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return if (i >= 0) text[i] else null
    }

    private fun indexOf(text: CharSequence, c: Char, from: Int, end: Int): Int {
        for (i in from until end) if (text[i] == c) return i
        return -1
    }

    private fun indexOf(text: CharSequence, s: String, from: Int, end: Int): Int {
        var i = from
        while (i + s.length <= end) {
            if (text.regionMatches(i, s, 0, s.length)) return i
            i++
        }
        return -1
    }

    /** The index of the quote closing the literal opened at [open], or -1 when the caret is inside it. */
    private fun closingQuote(text: CharSequence, open: Int, end: Int): Int {
        val q = text[open]
        var i = open + 1
        while (i < end) {
            when (text[i]) {
                '\\' -> i++
                q -> return i
                '\n' -> return i // an unclosed literal ends at the line break
            }
            i++
        }
        return -1
    }

    private val TYPE_KEYWORDS = setOf("class", "interface", "enum", "record", "@interface")
    private val CONTROL_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "synchronized", "try")
}
