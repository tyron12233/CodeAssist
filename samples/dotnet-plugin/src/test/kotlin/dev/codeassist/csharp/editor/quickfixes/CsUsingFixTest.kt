package dev.codeassist.csharp.editor.quickfixes

import dev.codeassist.csharp.completion.CsSymbolTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsUsingFixTest {

    private fun apply(source: String, namespace: String): String {
        val edit = CsUsingFix.insertion(source, namespace)
        return source.substring(0, edit.offset) + edit.text + source.substring(edit.offset)
    }

    private fun table(vararg types: Pair<String, String>) =
        CsSymbolTable(types.map { (ns, name) -> "type\t$ns\t$name\tclass\t0" })

    @Test
    fun `the missing name comes from the message`() {
        val message = "The type or namespace name 'Foo' could not be found " +
            "(are you missing a using directive or an assembly reference?)"
        assertEquals("Foo", CsUsingFix.missingName(message))
        assertEquals("Console", CsUsingFix.missingName("The name 'Console' does not exist in the current context"))
        assertEquals("List", CsUsingFix.missingName("The type or namespace name 'List<>' could not be found"))
    }

    @Test
    fun `a message with no plain name gives none`() {
        assertNull(CsUsingFix.missingName("no quotes here"))
        assertNull(CsUsingFix.missingName("The name 'a.b' does not exist"))
    }

    @Test
    fun `candidates are the namespaces that declare the name`() {
        val t = table("System" to "Console", "My.Ui" to "Console", "System.IO" to "File", "-" to "Console")
        assertEquals(listOf("System", "My.Ui"), CsUsingFix.candidates("Console", t, ""))
    }

    @Test
    fun `an imported namespace is not offered again`() {
        val t = table("System" to "Console", "My.Ui" to "Console")
        assertEquals(listOf("My.Ui"), CsUsingFix.candidates("Console", t, "using System;\nclass A { }\n"))
    }

    @Test
    fun `no usings and a declaration - the line goes in front with a blank line`() {
        assertEquals("using System;\n\nclass A { }\n", apply("class A { }\n", "System"))
    }

    @Test
    fun `a header comment stays above the new using`() {
        assertEquals("// header\n\nusing System;\n\nnamespace A;\n", apply("// header\n\nnamespace A;\n", "System"))
    }

    @Test
    fun `System sorts first and the rest alphabetically`() {
        val source = "using System;\nusing System.Linq;\nusing Zed;\n\nclass A { }\n"
        assertEquals(
            "using System;\nusing System.IO;\nusing System.Linq;\nusing Zed;\n\nclass A { }\n",
            apply(source, "System.IO"),
        )
        assertEquals(
            "using System;\nusing System.Linq;\nusing Alpha;\nusing Zed;\n\nclass A { }\n",
            apply(source, "Alpha"),
        )
    }

    @Test
    fun `a name sorting last goes after the last using`() {
        val source = "using System;\n\nclass A { }\n"
        assertEquals("using System;\nusing Zed;\n\nclass A { }\n", apply(source, "Zed"))
    }

    @Test
    fun `static and alias usings count as the using block`() {
        val source = "using static System.Math;\n\nclass A { }\n"
        assertEquals("using static System.Math;\nusing System;\n\nclass A { }\n", apply(source, "System"))
    }

    @Test
    fun `a using inside a method is not part of the block`() {
        val source = "class A\n{\n    void M() { using (var x = Y()) { } }\n}\n"
        assertEquals("using System;\n\n" + source, apply(source, "System"))
    }

    @Test
    fun `windows line endings are kept`() {
        assertEquals("using System;\r\n\r\nclass A { }\r\n", apply("class A { }\r\n", "System"))
    }

    @Test
    fun `a last using with no newline at the end of the file`() {
        assertEquals("using A;\nusing B;", apply("using A;", "B"))
    }

    @Test
    fun `an empty file gets the line`() {
        assertEquals("using System;\n", apply("", "System"))
    }

    @Test
    fun `a block comment before the usings is skipped`() {
        val source = "/* one\n two */\nusing B;\n\nclass A { }\n"
        assertEquals("/* one\n two */\nusing A;\nusing B;\n\nclass A { }\n", apply(source, "A"))
    }

    @Test
    fun `imports sees a plain using only`() {
        val source = "using System;\nusing static System.Math;\n\nclass A { }\n"
        assertEquals(true, CsUsingFix.imports(source, "System"))
        assertEquals(false, CsUsingFix.imports(source, "System.Math"))
        assertEquals(false, CsUsingFix.imports(source, "System.IO"))
    }

    @Test
    fun `an extension method message names the method`() {
        val message = "'string' does not contain a definition for 'ToList' and no accessible extension method " +
            "'ToList' accepting a first argument of type 'string' could be found"
        assertEquals("ToList", CsUsingFix.extensionName(message))
        assertNull(CsUsingFix.extensionName("'Foo' does not contain a definition for 'Bar'"))
        assertEquals(true, "CS1061" in CsUsingFix.CODES)
    }

    @Test
    fun `extension candidates are static methods of static classes with a parameter`() {
        val t = CsSymbolTable(
            listOf(
                "type\tSystem.Linq\tEnumerable\tclass\t1",
                "member\tSystem.Linq\tEnumerable\tmethod\tToList\t1\t1",
                "type\tMy.Ext\tListExt\tclass\t1",
                "member\tMy.Ext\tListExt\tmethod\tToList\t1\t1",
                "type\tMy.Plain\tHelper\tclass\t0",
                "member\tMy.Plain\tHelper\tmethod\tToList\t1\t1",
                "type\tMy.Zero\tZ\tclass\t1",
                "member\tMy.Zero\tZ\tmethod\tToList\t1\t0",
                "type\tMy.Inst\tI\tclass\t1",
                "member\tMy.Inst\tI\tmethod\tToList\t0\t1",
            ),
        )
        assertEquals(listOf("System.Linq", "My.Ext"), CsUsingFix.extensionCandidates("ToList", t, ""))
        assertEquals(listOf("My.Ext"), CsUsingFix.extensionCandidates("ToList", t, "using System.Linq;\n"))
    }

    @Test
    fun `a sequence receiver gets System Linq alone, another receiver gets every candidate`() {
        val t = CsSymbolTable(
            listOf(
                "type\tSystem.Linq\tEnumerable\tclass\t1",
                "member\tSystem.Linq\tEnumerable\tmethod\tSelect\t1\t2",
                "type\tSystem.Data\tEnumerableRowCollectionExtensions\tclass\t1",
                "member\tSystem.Data\tEnumerableRowCollectionExtensions\tmethod\tSelect\t1\t2",
            ),
        )
        val sequences = listOf(
            "IEnumerable<int>", "List<string>", "int[]", "string", "System.Collections.Generic.HashSet<int>",
        )
        for (receiver in sequences) {
            assertEquals(receiver, listOf("System.Linq"), CsUsingFix.extensionCandidates("Select", t, "", receiver))
        }
        assertEquals(
            listOf("System.Data", "System.Linq"),
            CsUsingFix.extensionCandidates("Select", t, "", "DataTable"),
        )
        assertEquals(listOf("System.Data", "System.Linq"), CsUsingFix.extensionCandidates("Select", t, ""))
        // Linq is not a candidate: the sequence rule has nothing to prefer.
        assertEquals(
            listOf("System.Data"),
            CsUsingFix.extensionCandidates("Select", t, "using System.Linq;", "IEnumerable<int>"),
        )
    }

    @Test
    fun `the receiver is read from the message`() {
        val message = "'x' does not contain a definition for 'Select' and no accessible extension method " +
            "'Select' accepting a first argument of type 'IEnumerable<int>' could be found"
        assertEquals("IEnumerable<int>", CsUsingFix.extensionReceiver(message))
        assertNull(CsUsingFix.extensionReceiver("nothing here"))
    }
}
