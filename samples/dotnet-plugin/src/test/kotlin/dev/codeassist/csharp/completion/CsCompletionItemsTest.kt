package dev.codeassist.csharp.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsCompletionItemsTest {

    @Test
    fun `an item line is read field by field`() {
        val items = CsCompletionItems.parse(listOf("item\tSelect\textension\tIEnumerable<int>\t1\tEnumerable\t0\t2"))
        val item = items.single()
        assertEquals("Select", item.name)
        assertEquals("extension", item.kind)
        assertEquals("IEnumerable<int>", item.detail)
        assertTrue(item.takesArguments)
        assertEquals("Enumerable", item.container)
        assertFalse(item.deprecated)
        assertEquals(2, item.overloads)
        assertTrue(item.isMethod)
    }

    @Test
    fun `lines that are not items are skipped`() {
        val items = CsCompletionItems.parse(
            listOf(
                "complete: boom",
                "item\tshort",
                "item\t\tlocal\tint\t0\t\t0\t1",
                "item\tcount\tlocal\tint\t0\t\t0\t1",
            ),
        )
        assertEquals(listOf("count"), items.map { it.name })
    }

    @Test
    fun `types and methods are told apart`() {
        val items = CsCompletionItems.parse(
            listOf(
                "item\tPerson\tclass\t\t0\tDemo\t0\t1",
                "item\tGreet\tmethod\tstring\t0\tPerson\t1\t1",
                "item\tname\tproperty\tstring\t0\tPerson\t0\t1",
            ),
        )
        assertTrue(items[0].isType)
        assertFalse(items[0].isMethod)
        assertTrue(items[1].isMethod)
        assertTrue(items[1].deprecated)
        assertFalse(items[2].isMethod)
    }

    @Test
    fun `empty trailing fields survive the split`() {
        val item = CsCompletionItems.parse(listOf("item\tx\tlocal\tint\t0\t\t0\t1")).single()
        assertEquals("", item.container)
    }

    @Test
    fun `the documentation field is unescaped, and absent means empty`() {
        val with = CsCompletionItems.parse(listOf("item\tF\tmethod\tvoid\t0\tC\t0\t1\t<summary>a\\nb</summary>")).single()
        assertEquals("<summary>a\nb</summary>", with.documentation)
        val without = CsCompletionItems.parse(listOf("item\tF\tmethod\tvoid\t0\tC\t0\t1")).single()
        assertEquals("", without.documentation)
    }

    @Test
    fun `a type that needs a using carries its namespace`() {
        val items = CsCompletionItems.parse(
            listOf(
                "item\tStringBuilder\tclass\t\t0\tSystem.Text\t0\t1\t\tSystem.Text",
                "item\tString\tclass\t\t0\tSystem\t0\t1\t",
            ),
        )

        assertEquals("System.Text", items[0].importNamespace)
        assertEquals("", items[1].importNamespace)
    }
}
