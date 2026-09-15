package dev.codeassist.ndk

import dev.ide.lang.completion.CompletionItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading the candidate list clang prints for `-code-completion-at`.
 *
 * Every sample here is a real line clang emits. The chunk markers are the whole difficulty: shown raw, a
 * popup row reads `[#void#]push_back(<#const value_type &__x#>)`, which is worse than no detail at all.
 */
class ClangCompletionsTest {

    @Test
    fun `a member with a result type and parameters`() {
        val c = ClangCompletions.parse(
            "COMPLETION: push_back : [#void#]push_back(<#const value_type &__x#>)"
        ).single()

        assertEquals("push_back", c.name)
        assertEquals("void", c.resultType)
        assertEquals("push_back(const value_type &__x)", c.signature)
        assertTrue(c.isCallable)
        assertTrue(!c.isPattern)
    }

    @Test
    fun `a nullary member still reads as callable`() {
        val c = ClangCompletions.parse("COMPLETION: size : [#size_type#]size()").single()
        assertEquals("size", c.name)
        assertEquals("size_type", c.resultType)
        assertEquals("size()", c.signature)
        assertTrue(c.isCallable)
    }

    @Test
    fun `a bare name with no chunks`() {
        val c = ClangCompletions.parse("COMPLETION: value_type").single()
        assertEquals("value_type", c.name)
        assertNull(c.signature)
        assertNull(c.resultType)
        assertTrue(!c.isCallable)
    }

    /** A pattern's typed text is the literal word "Pattern"; inserting that would be nonsense. */
    @Test
    fun `a pattern is named by the first word of its body, not by the word Pattern`() {
        val c = ClangCompletions.parse(
            "COMPLETION: Pattern : for(<#init#>; <#cond#>; <#inc#>) {<#body#>}"
        ).single()

        assertEquals("for", c.name)
        assertTrue(c.isPattern)
        assertTrue(c.signature!!.startsWith("for("), c.signature!!)
    }

    @Test
    fun `informative and optional chunks are unwrapped too`() {
        val c = ClangCompletions.parse(
            "COMPLETION: memcpy : [#void *#]memcpy({#void *__dst#}, (#const void *__src#))"
        ).single()
        assertEquals("memcpy(void *__dst, const void *__src)", c.signature)
        assertTrue("#" !in c.signature!!, "no chunk marker may survive into the popup: ${c.signature}")
    }

    /** clang lists every overload; three identical rows in a popup is noise. */
    @Test
    fun `overloads collapse to the first, which is the one clang ranked highest`() {
        val parsed = ClangCompletions.parse(
            """
            COMPLETION: insert : [#iterator#]insert(<#const_iterator __position#>)
            COMPLETION: insert : [#iterator#]insert(<#const_iterator __position#>, <#size_type __n#>)
            COMPLETION: insert : [#void#]insert(<#initializer_list<value_type> __il#>)
            """.trimIndent()
        )
        assertEquals(1, parsed.size)
        assertEquals("insert(const_iterator __position)", parsed.single().signature)
    }

    @Test
    fun `non-completion output is ignored`() {
        val parsed = ClangCompletions.parse(
            """
            <stdin>:3:5: error: expected ';'
            OVERLOAD: [#void#]f(<#int#>)
            clang version 18.1.8
            COMPLETION: real : [#int#]real
            """.trimIndent()
        )
        assertEquals(listOf("real"), parsed.map { it.name })
    }

    @Test
    fun `empty output is empty`() {
        assertTrue(ClangCompletions.parse("").isEmpty())
        assertTrue(ClangCompletions.parse("   \n \n").isEmpty())
    }

    @Test
    fun `order is preserved, because clang already ranked them`() {
        val parsed = ClangCompletions.parse(
            """
            COMPLETION: alpha : [#int#]alpha
            COMPLETION: beta : [#int#]beta
            COMPLETION: gamma : [#int#]gamma
            """.trimIndent()
        )
        assertEquals(listOf("alpha", "beta", "gamma"), parsed.map { it.name })
    }
}

/** How a candidate reaches the popup. clang's text format carries no kind, so the shape has to imply it. */
class ClangCompletionItemTest {

    @Test
    fun `something callable is a method`() {
        val item = ClangCompletions.parse("COMPLETION: size : [#size_type#]size()").single().toItem(cpp = true)
        assertEquals(CompletionItemKind.METHOD, item.kind)
        assertEquals("size", item.insertText)
        assertEquals("size()", item.detail)
        assertEquals("size_type", item.container)
    }

    @Test
    fun `a value with a type is a variable`() {
        val item = ClangCompletions.parse("COMPLETION: count : [#int#]count").single().toItem(cpp = true)
        assertEquals(CompletionItemKind.VARIABLE, item.kind)
    }

    @Test
    fun `a pattern is a snippet`() {
        val item = ClangCompletions.parse("COMPLETION: Pattern : while(<#cond#>) {<#body#>}").single()
            .toItem(cpp = true)
        assertEquals(CompletionItemKind.SNIPPET, item.kind)
        assertEquals("while", item.insertText)
    }

    /**
     * A bare name is a macro or a type, and which is likelier depends on the language: in C almost anything
     * with no type is a macro, in C++ it is usually a type name.
     */
    @Test
    fun `a bare name is read differently in C and C++`() {
        val c = ClangCompletions.parse("COMPLETION: EOF").single()
        assertEquals(CompletionItemKind.KEYWORD, c.toItem(cpp = false).kind)
        assertEquals(CompletionItemKind.CLASS, c.toItem(cpp = true).kind)
    }

    @Test
    fun `the label is what gets inserted`() {
        val item = ClangCompletions.parse("COMPLETION: push_back : [#void#]push_back(<#int __x#>)")
            .single().toItem(cpp = true)
        assertEquals("push_back", item.label)
        assertEquals("push_back", item.insertText, "the parameters are shown, not typed for the user")
    }
}

/** Offset to line and column, which is what a compiler wants and what an editor does not have. */
class LineColTest {

    @Test
    fun `offset maps to one-based line and column`() {
        val lines = LineOffsets("alpha\nbeta\ngamma")
        assertEquals(1 to 1, lines.lineColOf(0))
        assertEquals(1 to 6, lines.lineColOf(5))
        assertEquals(2 to 1, lines.lineColOf(6))
        // "alpha\nbeta\ngamma": line 3 starts at 11, so 14 is its fourth character.
        assertEquals(3 to 4, lines.lineColOf(14))
    }

    @Test
    fun `it round-trips with offsetOf`() {
        val text = "one\ntwo\nthree\n"
        val lines = LineOffsets(text)
        for (offset in text.indices) {
            val (line, col) = lines.lineColOf(offset)
            assertEquals(offset, lines.offsetOf(line, col), "offset $offset -> $line:$col did not round-trip")
        }
    }

    @Test
    fun `an offset past the end is clamped`() {
        val lines = LineOffsets("abc")
        assertEquals(1 to 4, lines.lineColOf(999))
    }
}
