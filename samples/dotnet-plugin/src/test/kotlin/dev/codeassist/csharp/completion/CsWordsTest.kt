package dev.codeassist.csharp.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsWordsTest {

    private fun words(text: String, typed: String, caretAtEnd: Boolean = true): List<CsWords.Word> {
        val caret = if (caretAtEnd) text.length else text.indexOf(typed)
        return CsWords.nearby(text, caret, { it.startsWith(typed, ignoreCase = true) })
    }

    @Test
    fun `a declared list is offered by its prefix`() {
        val text = "var people = new List<string>();\npeo"
        val found = words(text, "peo")
        assertEquals(listOf("people"), found.map { it.name })
        assertTrue(found.single().declared)
    }

    @Test
    fun `the word under the caret is not offered`() {
        assertEquals(emptyList<String>(), words("peo", "peo").map { it.name })
    }

    @Test
    fun `typed declarations are found`() {
        val text = "List<int> numbers = new();\nvoid F(string name, int count) { foreach (var item in numbers) { } }\nn"
        val declared = CsWords.nearby(text, text.length, { true }).filter { it.declared }.map { it.name }.toSet()
        assertTrue(declared.containsAll(setOf("numbers", "name", "count", "item")))
    }

    @Test
    fun `keywords, members after a dot, comments and strings are not words`() {
        val text = "// people here\nvar s = \"people there\";\nConsole.WriteLine(s);\nreturn;\n"
        val names = CsWords.nearby(text, text.length, { true }).map { it.name }
        assertFalse("people" in names)
        assertFalse("WriteLine" in names)
        assertFalse("return" in names)
        assertFalse("var" in names)
        assertTrue("Console" in names)
    }

    @Test
    fun `the nearest occurrence orders the words`() {
        val text = "int far = 1;\n" + "x;\n".repeat(20) + "int near = 2;\nne"
        assertEquals(listOf("near"), words(text, "ne").map { it.name })
        val both = CsWords.nearby(text, text.length, { it == "far" || it == "near" }).map { it.name }
        assertEquals(listOf("near", "far"), both)
    }

    @Test
    fun `keywords are the editor's keywords`() {
        assertTrue("foreach" in CsKeywords.ALL && "async" in CsKeywords.ALL)
    }
}
