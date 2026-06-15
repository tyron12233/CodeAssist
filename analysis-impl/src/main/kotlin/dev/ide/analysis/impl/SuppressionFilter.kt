package dev.ide.analysis.impl

import dev.ide.analysis.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange

/**
 * Inline suppression, checked centrally before any diagnostic is published. Honors:
 *  - `@Suppress("code", …)` — scoped to the enclosing declaration, located via the DOM
 *    (`nodeAt` → climb to the nearest class/method/field/local node).
 *  - `// noinspection code …` (or `//noinspection …`) — scoped to the following line.
 * A directive with no codes suppresses everything in its scope. Compiler diagnostics are filtered the
 * same way as analyzer ones, since by this point they share the [Diagnostic] model.
 */
internal class SuppressionFilter private constructor(private val scopes: List<Scope>) {

    private class Scope(val codes: Set<String>?, val range: TextRange)

    fun isSuppressed(d: Diagnostic): Boolean = scopes.any { s ->
        covers(s.range, d.range) && (s.codes == null || (d.code != null && d.code in s.codes))
    }

    /** Drop suppressed diagnostics, keep the rest (order preserved). */
    fun retain(diagnostics: List<Diagnostic>): List<Diagnostic> = diagnostics.filterNot(::isSuppressed)

    private fun covers(scope: TextRange, d: TextRange): Boolean = scope.start <= d.start && d.end <= scope.end

    companion object {
        private val DECL_KINDS = setOf(
            NodeKind.CLASS_DECL, NodeKind.METHOD_DECL, NodeKind.FIELD_DECL, NodeKind.LOCAL_VAR, NodeKind.PARAMETER,
        )
        private val NOINSPECTION = Regex("""//\s*noinspection\s+([^\r\n]*)""")
        private val SUPPRESS = Regex("""@Suppress\s*\(([^)]*)\)""")
        private val STRING_LIT = Regex("\"([^\"]*)\"")
        private val EMPTY = SuppressionFilter(emptyList())

        /** Parse the suppression directives in [parsed]'s source into scoped rules. */
        fun from(parsed: ParsedFile): SuppressionFilter {
            val text = parsed.text().toString()
            if (text.isEmpty()) return EMPTY
            val scopes = ArrayList<Scope>()

            for (m in NOINSPECTION.findAll(text)) {
                val codes = parseIds(m.groupValues[1])
                nextLineRange(text, m.range.last)?.let { scopes += Scope(codes, it) }
            }
            for (m in SUPPRESS.findAll(text)) {
                val codes = STRING_LIT.findAll(m.groupValues[1]).map { it.groupValues[1] }.toSet().ifEmpty { null }
                val range = enclosingDecl(parsed, m.range.first)?.range ?: lineRangeAt(text, m.range.first)
                scopes += Scope(codes, range)
            }
            return if (scopes.isEmpty()) EMPTY else SuppressionFilter(scopes)
        }

        /** Tokenize `Foo, Bar baz` / `"Foo","Bar"` into ids; `null` (= suppress all) when none are listed. */
        private fun parseIds(raw: String): Set<String>? {
            val quoted = STRING_LIT.findAll(raw).map { it.groupValues[1] }.toList()
            val ids = if (quoted.isNotEmpty()) quoted
            else raw.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            return ids.toSet().ifEmpty { null }
        }

        private fun enclosingDecl(parsed: ParsedFile, offset: Int): DomNode? {
            val at = offset.coerceIn(0, maxOf(0, parsed.range.end - 1))
            var n: DomNode? = parsed.nodeAt(at)
            while (n != null && n.kind !in DECL_KINDS) n = n.parent
            return n
        }

        /** The half-open range of the line *after* the one containing [offset], or null if there is none. */
        private fun nextLineRange(text: String, offset: Int): TextRange? {
            var i = offset.coerceIn(0, text.length)
            while (i < text.length && text[i] != '\n') i++
            if (i >= text.length) return null
            val start = i + 1
            var end = start
            while (end < text.length && text[end] != '\n') end++
            return TextRange(start, end)
        }

        /** The half-open range of the line containing [offset]. */
        private fun lineRangeAt(text: String, offset: Int): TextRange {
            val o = offset.coerceIn(0, text.length)
            var start = o
            while (start > 0 && text[start - 1] != '\n') start--
            var end = o
            while (end < text.length && text[end] != '\n') end++
            return TextRange(start, end)
        }
    }
}
