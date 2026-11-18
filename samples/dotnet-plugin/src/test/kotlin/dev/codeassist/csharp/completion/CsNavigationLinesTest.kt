package dev.codeassist.csharp.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsNavigationLinesTest {

    @Test
    fun `a definition line gives the file, the offset and the label`() {
        val found = CsNavigationLines.definitions(
            listOf("def\t/p/Helper.cs\t1039\tint Helper.Compute(IEnumerable<int> values)"),
        )

        assertEquals(
            listOf(CsNavigationLines.Definition("/p/Helper.cs", 1039, "int Helper.Compute(IEnumerable<int> values)")),
            found,
        )
    }

    @Test
    fun `a partial type gives one definition per part`() {
        val found = CsNavigationLines.definitions(listOf("def\t/p/A.cs\t10\tShape", "def\t/p/B.cs\t22\tShape"))

        assertEquals(listOf("/p/A.cs", "/p/B.cs"), found.map { it.path })
    }

    @Test
    fun `lines that are not definitions are skipped`() {
        val found = CsNavigationLines.definitions(
            listOf("server: something went wrong", "def\t/p/A.cs\tnot-a-number\tShape", "def\t/p/A.cs", ""),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a hover line gives the symbol and its unescaped documentation`() {
        val hover = CsNavigationLines.hover(
            listOf("hover\tmethod\tCompute\tHelper\tint Helper.Compute(int x)\t<summary>Adds.\\nTwice.</summary>"),
        )!!

        assertEquals("method", hover.kind)
        assertEquals("Compute", hover.name)
        assertEquals("Helper", hover.container)
        assertEquals("int Helper.Compute(int x)", hover.signature)
        assertEquals("<summary>Adds.\nTwice.</summary>", hover.documentation)
    }

    @Test
    fun `a hover without documentation or container still reads`() {
        val hover = CsNavigationLines.hover(listOf("hover\tlocalVariable\tnumbers\t\tList<int> numbers\t"))!!

        assertEquals("", hover.container)
        assertEquals("", hover.documentation)
    }

    @Test
    fun `no hover line is no hover`() {
        assertNull(CsNavigationLines.hover(listOf("", "tok\t1\t2\tclass\t")))
    }

    @Test
    fun `a token line gives the range, the kind and the modifiers`() {
        val tokens = CsNavigationLines.tokens(
            listOf("tok\t6\t6\tnamespace\t", "tok\t40\t8\tmethod\tdeclaration,static", "tok\t50\t3\tclass"),
        )

        assertEquals(
            listOf(
                CsNavigationLines.Token(6, 6, "namespace", emptyList()),
                CsNavigationLines.Token(40, 8, "method", listOf("declaration", "static")),
                CsNavigationLines.Token(50, 3, "class", emptyList()),
            ),
            tokens,
        )
    }

    @Test
    fun `a token with no length or a bad number is skipped`() {
        val tokens = CsNavigationLines.tokens(listOf("tok\t6\t0\tclass\t", "tok\tx\t3\tclass\t", "tok\t-1\t3\tclass\t"))

        assertTrue(tokens.isEmpty())
    }
}
