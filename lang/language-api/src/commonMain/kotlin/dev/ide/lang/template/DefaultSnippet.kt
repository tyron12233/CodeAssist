package dev.ide.lang.template

import dev.ide.lang.dom.TextRange

/**
 * The reference [SnippetParser] / [SnippetEngine] — the first (and shared) implementation of the template
 * contracts. Turns stored template text into an editor-agnostic [SnippetExpansion], so user macros, built-in
 * live templates, and postfix templates all expand through one path.
 *
 * Supported syntax (a superset of the user-facing macro form):
 *  - **Tab stops** `$1`, `$2`, … and the final caret `$0` / `$END$`.
 *  - **Placeholders** `${1:default}` (the default is itself a parsed snippet, so it may nest stops).
 *  - **Choices** `${1|a,b,c|}` (offered as a popup when the editor reaches the stop).
 *  - **Variables** `$NAME$` (IntelliJ-style, dollar-delimited) and `${NAME}` / `${NAME:default}` (brace form) —
 *    resolved via [SnippetVariableResolver] (`$FILE$`, `$DATE$`, `$USER$`, …); an unresolved one falls back to
 *    its default, then to the empty string.
 *  - **Escapes** `$$` → a literal `$`; `\$`, `\{`, `\}`, `\\` → the literal character.
 *
 * Tolerant by contract: a malformed construct degrades to literal text rather than throwing. Inner newlines are
 * re-indented by [SnippetContext.indent] so a multi-line expansion lines up under the trigger.
 */
class DefaultSnippetParser : SnippetParser {

    override fun parse(template: SnippetTemplate): ParsedSnippet = parse(template.raw)

    private fun parse(s: String): ParsedSnippet {
        val segs = ArrayList<SnippetSegment>()
        val lit = StringBuilder()
        fun flush() {
            if (lit.isNotEmpty()) { segs.add(SnippetSegment.Literal(lit.toString())); lit.clear() }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // Backslash escape of a syntax char.
            if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                lit.append(s[i + 1]); i += 2; continue
            }
            if (c != '$') { lit.append(c); i++; continue }

            // `$$` → literal `$`.
            if (i + 1 < s.length && s[i + 1] == '$') { lit.append('$'); i += 2; continue }

            // `${…}` brace form.
            if (i + 1 < s.length && s[i + 1] == '{') {
                val close = matchingBrace(s, i + 1)
                if (close < 0) { lit.append(c); i++; continue } // unbalanced → literal `$`
                flush(); segs.add(parseBraced(s.substring(i + 2, close))); i = close + 1; continue
            }

            // `$N` bare tab stop.
            if (i + 1 < s.length && s[i + 1].isDigit()) {
                var j = i + 1
                while (j < s.length && s[j].isDigit()) j++
                flush(); segs.add(SnippetSegment.TabStop(s.substring(i + 1, j).toInt())); i = j; continue
            }

            // `$NAME$` (dollar-delimited variable / `$END$`), or a lenient bare `$NAME`.
            if (i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_')) {
                var j = i + 1
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                val name = s.substring(i + 1, j)
                val delimited = j < s.length && s[j] == '$'
                flush()
                segs.add(if (name == END) SnippetSegment.TabStop(0) else SnippetSegment.Variable(name))
                i = if (delimited) j + 1 else j
                continue
            }

            // Lone `$` → literal.
            lit.append(c); i++
        }
        flush()
        return ParsedSnippet(segs)
    }

    /** Parse the text between `${` and `}`: `N`, `N:default`, `N|a,b,c|`, `NAME`, or `NAME:default`. */
    private fun parseBraced(inner: String): SnippetSegment {
        val digits = inner.takeWhile { it.isDigit() }
        if (digits.isNotEmpty()) {
            val index = digits.toInt()
            val rest = inner.substring(digits.length)
            return when {
                rest.startsWith(":") -> SnippetSegment.TabStop(index, default = parse(rest.substring(1)))
                rest.startsWith("|") && rest.endsWith("|") && rest.length >= 2 ->
                    SnippetSegment.TabStop(index, choices = rest.substring(1, rest.length - 1).split(",").map { it.trim() }.filter { it.isNotEmpty() })
                else -> SnippetSegment.TabStop(index)
            }
        }
        val name = inner.takeWhile { it.isLetterOrDigit() || it == '_' }
        val rest = inner.substring(name.length)
        val default = if (rest.startsWith(":")) parse(rest.substring(1)) else null
        if (name == END) return SnippetSegment.TabStop(0)
        return SnippetSegment.Variable(name, default)
    }

    /** Index of the `}` matching the `{` at [open], honoring nesting; -1 if unbalanced. */
    private fun matchingBrace(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++ // skip the escaped char
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private companion object {
        const val END = "END"
        val ESCAPABLE = setOf('\\', '$', '{', '}')
    }
}

/** Expands a parsed template against a context into offset-relative [SnippetExpansion] the editor drives. */
class DefaultSnippetEngine(private val parser: SnippetParser = DefaultSnippetParser()) : SnippetEngine {

    override fun expand(
        template: SnippetTemplate,
        ctx: SnippetContext,
        resolver: SnippetVariableResolver,
    ): SnippetExpansion {
        val parsed = parser.parse(template)
        val sb = StringBuilder()
        val rangesByIndex = LinkedHashMap<Int, MutableList<TextRange>>()
        val choicesByIndex = HashMap<Int, List<String>>()
        var finalCaret = -1

        fun append(text: String) { sb.append(if (ctx.indent.isEmpty()) text else text.replace("\n", "\n" + ctx.indent)) }

        fun emit(segments: List<SnippetSegment>) {
            for (seg in segments) when (seg) {
                is SnippetSegment.Literal -> append(seg.text)
                is SnippetSegment.TabStop -> {
                    val start = sb.length
                    seg.default?.let { emit(it.segments) }
                    val end = sb.length
                    if (seg.index == 0) {
                        if (finalCaret < 0) finalCaret = start
                    } else {
                        rangesByIndex.getOrPut(seg.index) { ArrayList() }.add(TextRange(start, end))
                        if (seg.choices.isNotEmpty()) choicesByIndex[seg.index] = seg.choices
                    }
                }
                is SnippetSegment.Variable -> {
                    val resolved = resolver.resolve(seg.name, ctx)
                    if (resolved != null) append(resolved) else seg.default?.let { emit(it.segments) }
                }
            }
        }

        emit(parsed.segments)
        if (finalCaret < 0) finalCaret = sb.length
        val stops = rangesByIndex.entries.sortedBy { it.key }
            .map { ExpandedStop(it.key, it.value, choicesByIndex[it.key] ?: emptyList()) }
        return SnippetExpansion(sb.toString(), stops, finalCaret)
    }
}
