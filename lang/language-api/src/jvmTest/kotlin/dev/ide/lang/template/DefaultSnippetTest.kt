package dev.ide.lang.template

import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [DefaultSnippetParser]/[DefaultSnippetEngine]: the final caret (`$END$`), placeholders with defaults,
 * linked tab stops, choices, variable resolution + fallback, escapes, and newline re-indentation. This is the
 * machinery user macros expand through, so its offsets must be exact (the editor drives carets off them).
 */
class DefaultSnippetTest {

    private val engine = DefaultSnippetEngine()

    // The engine reads only ctx.indent (and the resolver); the document is never touched, so `file` throws.
    private val fakeDoc = object : DocumentSnapshot {
        override val file: VirtualFile get() = error("document is unused in the snippet engine")
        override val version = 1L
        override val text: CharSequence = ""
        override fun length() = 0
    }

    private fun ctx(indent: String = "") = SnippetContext(fakeDoc, offset = 0, selection = null, indent = indent)

    private fun resolver(vars: Map<String, String> = emptyMap()) = object : SnippetVariableResolver {
        override fun resolve(name: String, ctx: SnippetContext): String? = vars[name]
    }

    private fun expand(template: String, indent: String = "", vars: Map<String, String> = emptyMap()) =
        engine.expand(SnippetTemplate(template), ctx(indent), resolver(vars))

    @Test
    fun finalCaretFromEnd() {
        val e = expand("System.out.println(\$END\$);")
        assertEquals("System.out.println();", e.text)
        assertEquals("System.out.println(".length, e.finalCaretOffset)
        assertEquals(emptyList(), e.stops)
    }

    @Test
    fun placeholderWithDefault() {
        val e = expand("if (\${1:cond}) {}")
        assertEquals("if (cond) {}", e.text)
        assertEquals(listOf(ExpandedStop(1, listOf(TextRange(4, 8)))), e.stops)
        assertEquals(e.text.length, e.finalCaretOffset) // no $0 → end of text
    }

    @Test
    fun linkedTabStopsMirror() {
        // The same index twice → one stop with two (zero-width) ranges: the linked-edit contract.
        val e = expand("\$1.\$1")
        assertEquals(".", e.text)
        assertEquals(listOf(ExpandedStop(1, listOf(TextRange(0, 0), TextRange(1, 1)))), e.stops)
    }

    @Test
    fun choiceStop() {
        val e = expand("\${1|a,b,c|}")
        assertEquals("", e.text)
        assertEquals(listOf(ExpandedStop(1, listOf(TextRange(0, 0)), listOf("a", "b", "c"))), e.stops)
    }

    @Test
    fun variableResolvedElseDefault() {
        val e = expand("// \$USER\$ \${FOO:def}", vars = mapOf("USER" to "tyron"))
        assertEquals("// tyron def", e.text)
    }

    @Test
    fun escapedDollarIsLiteral() {
        val e = expand("a\$\$b")
        assertEquals("a\$b", e.text)
    }

    @Test
    fun reindentsInnerNewlines() {
        val e = expand("{\n\$END\$\n}", indent = "    ")
        assertEquals("{\n    \n    }", e.text)
        assertEquals("{\n    ".length, e.finalCaretOffset)
    }
}
