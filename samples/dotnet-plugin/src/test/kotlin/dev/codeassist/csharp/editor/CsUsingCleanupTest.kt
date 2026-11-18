package dev.codeassist.csharp.editor

import dev.codeassist.csharp.completion.CsSymbolTable
import dev.codeassist.csharp.editor.diagnostics.CsDiagnostics
import dev.codeassist.csharp.editor.quickfixes.CsUsingFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CsUsingCleanupTest {

    private fun apply(source: String, edits: List<CsUsingCleanup.Edit>): String =
        edits.sortedByDescending { it.offset }.fold(source) { text, e ->
            text.substring(0, e.offset) + e.text + text.substring(e.offset + e.length)
        }

    @Test
    fun `directives are read from the head only`() {
        val source = "using System;\nusing static System.Math;\nusing L = System.Collections.Generic.List<int>;\n" +
            "\nclass A { void M() { using var x = Foo(); } }\n"
        val head = CsUsingCleanup.head(source)
        assertEquals(
            listOf(CsUsingCleanup.Kind.PLAIN, CsUsingCleanup.Kind.STATIC, CsUsingCleanup.Kind.ALIAS),
            head.directives.map { it.kind },
        )
        assertEquals("System.Math", head.directives[1].name)
    }

    @Test
    fun `a using statement is not a directive`() {
        assertEquals(0, CsUsingCleanup.head("using var f = File.Open(p);\n").directives.size)
    }

    @Test
    fun `directiveAt finds the line under an offset`() {
        val source = "using System;\nusing System.Linq;\n\nclass A {}\n"
        assertEquals("using System.Linq;", CsUsingCleanup.directiveAt(source, source.indexOf("Linq")))
        assertNull(CsUsingCleanup.directiveAt(source, source.indexOf("class")))
    }

    @Test
    fun `remove drops exactly that line`() {
        val source = "using System;\nusing System.Linq;\n\nclass A {}\n"
        val out = apply(source, CsUsingCleanup.remove(source, "using System.Linq;"))
        assertEquals("using System;\n\nclass A {}\n", out)
    }

    @Test
    fun `organize removes unused and sorts with System first`() {
        val source = "using Zed;\nusing System.Linq;\nusing System;\nusing Alpha;\n\nclass A {}\n"
        val out = apply(source, CsUsingCleanup.organize(source, setOf("using Alpha;")))
        assertEquals("using System;\nusing System.Linq;\nusing Zed;\n\nclass A {}\n", out)
    }

    @Test
    fun `organize puts global, plain, static, alias in that order and keeps one of a duplicate`() {
        val source = "using A = X.Y;\nusing static System.Math;\nusing B;\nusing B;\nglobal using G;\n"
        val out = apply(source, CsUsingCleanup.organize(source, emptySet()))
        assertEquals("global using G;\nusing B;\nusing static System.Math;\nusing A = X.Y;\n", out)
    }

    @Test
    fun `a comment between usings stops the sort but not the removal`() {
        val source = "using B;\n// keep me\nusing A;\nusing C;\n"
        val out = apply(source, CsUsingCleanup.organize(source, setOf("using C;")))
        assertEquals("using B;\n// keep me\nusing A;\n", out)
    }

    @Test
    fun `sorted already gives no edit`() {
        assertEquals(0, CsUsingCleanup.organize("using System;\nusing A;\n", emptySet()).size)
    }

    @Test
    fun `crlf is kept`() {
        val source = "using B;\r\nusing A;\r\n"
        assertEquals("using A;\r\nusing B;\r\n", apply(source, CsUsingCleanup.organize(source, emptySet())))
    }

    @Test
    fun `the unused hint line is read`() {
        val d = CsDiagnostics.parseUnusedUsing("/w/A.cs(2,1): hidden CS8019: Unnecessary using directive.")
        assertNotNull(d)
        assertEquals(2, d!!.line)
        assertNull(CsDiagnostics.parseUnusedUsing("/w/A.cs(2,1): warning CS0168: x"))
        assertNull(CsDiagnostics.parse("/w/A.cs(2,1): hidden CS8019: Unnecessary using directive."))
    }

    @Test
    fun `a name that is a static member offers using static`() {
        val t = CsSymbolTable(
            listOf(
                "type\tSystem\tMath\tclass\t1",
                "type\tSystem\tConsole\tclass\t1",
                "member\tSystem\tMath\tmethod\tSqrt\t1\t1",
                "member\tSystem\tMath\tfield\tPI\t1",
                "member\tSystem\tConsole\tmethod\tWriteLine\t1\t1",
            ),
        )
        assertEquals(listOf("System.Math"), CsUsingFix.staticCandidates("Sqrt", t, ""))
        assertEquals(listOf("System.Math"), CsUsingFix.staticCandidates("PI", t, ""))
        assertEquals(emptyList<String>(), CsUsingFix.staticCandidates("Sqrt", t, "using static System.Math;\n"))
        // `Math` itself is a type: that is the plain using's job.
        assertEquals(emptyList<String>(), CsUsingFix.staticCandidates("Math", t, ""))
    }

    @Test
    fun `using static goes after the last using`() {
        val source = "using System;\n\nclass A {}\n"
        val e = CsUsingFix.staticInsertion(source, "System.Math")
        assertEquals(
            "using System;\nusing static System.Math;\n\nclass A {}\n",
            source.substring(0, e.offset) + e.text + source.substring(e.offset),
        )
    }
}
