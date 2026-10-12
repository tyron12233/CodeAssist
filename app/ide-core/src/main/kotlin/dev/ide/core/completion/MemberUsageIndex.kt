package dev.ide.core.completion

import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter

/**
 * `source.memberUsage`: how often each name follows a `.` in the project's own Java and Kotlin sources
 * (`view.setVisibility(...)` counts `setVisibility`), so completion can float the members this codebase
 * already calls (the `platform.projectUsage` weigher). Keyed by the name, one count per file.
 *
 * Purely lexical, so it needs no parse and costs one pass over the text: comments, string and char literals
 * and `import`/`package` lines are skipped, and everything else that follows a `.` (or `?.`) is counted. A
 * qualified type name (`android.view.View`) is counted too; that only ever boosts a member that happens to
 * share a package segment's name, which is harmless.
 */
object MemberUsageIndex : IndexExtension<String, Int> {
    override val id = IndexId("source.memberUsage")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = IntExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter {
        it.origin == IndexOrigin.SOURCE && it.unitName.let { n -> n != null && (n.endsWith(".kt") || n.endsWith(".java")) }
    }

    override fun index(input: IndexInput): Map<String, Collection<Int>> {
        val text = input.text() ?: return emptyMap()
        return countMemberNames(text).mapValues { (_, n) -> listOf(n) }
    }

    /** Each name that follows a `.` in [text], with how many times it does. */
    internal fun countMemberNames(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        val n = text.length
        var i = 0
        var lineStart = true
        while (i < n) {
            val c = text[i]
            if (lineStart) {
                var j = i
                while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
                if (text.startsWith("import ", j) || text.startsWith("package ", j)) {
                    i = skipLine(text, j)
                    continue
                }
                lineStart = false
            }
            when {
                c == '\n' -> { lineStart = true; i++ }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> i = skipLine(text, i)
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 2
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) n else end + 3
                }
                c == '"' || c == '\'' -> i = skipQuoted(text, i, c)
                c == '.' -> {
                    var j = i + 1
                    while (j < n && text[j].isWhitespace()) j++
                    if (j < n && (text[j].isLetter() || text[j] == '_')) {
                        val start = j
                        while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                        val name = text.substring(start, j)
                        counts[name] = (counts[name] ?: 0) + 1
                        i = j
                    } else i++
                }
                else -> i++
            }
        }
        return counts
    }

    private fun skipLine(text: String, from: Int): Int {
        val end = text.indexOf('\n', from)
        return if (end < 0) text.length else end
    }

    /** Past a `"..."` or `'.'` literal starting at [from], stopping at the line's end if it never closes. */
    private fun skipQuoted(text: String, from: Int, quote: Char): Int {
        var i = from + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == quote) return i + 1
            if (c == '\n') return i
            i++
        }
        return i
    }

    object IntExternalizer : Externalizer<Int> {
        override fun write(out: DataWriter, value: Int) = out.writeInt(value)
        override fun read(inp: DataReader): Int = inp.readInt()
    }
}
