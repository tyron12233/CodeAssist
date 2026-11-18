package dev.codeassist.csharp.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsSymbolTableTest {

    private fun table(vararg lines: String) = CsSymbolTable(lines.toList())

    @Test
    fun `a type line is read`() {
        val type = table("type\tSystem\tConsole\tclass\t1").types.single()
        assertEquals("System", type.namespace)
        assertEquals("Console", type.name)
        assertEquals("class", type.kind)
        assertTrue(type.isStatic)
        assertEquals("System.Console", type.fullName)
    }

    @Test
    fun `a type in no namespace has its bare name`() {
        val type = table("type\t-\tProgram\tclass\t0").types.single()
        assertEquals("Program", type.fullName)
        assertFalse(type.isStatic)
    }

    @Test
    fun `an interface is read as such`() {
        assertEquals("interface", table("type\tSystem\tIDisposable\tinterface\t0").types.single().kind)
    }

    @Test
    fun `a method line carries its parameter count`() {
        val member = table("member\tSystem\tConsole\tmethod\tWriteLine\t1\t2").members.single()
        assertEquals("System.Console", member.typeFullName)
        assertEquals("method", member.kind)
        assertEquals("WriteLine", member.name)
        assertTrue(member.isStatic)
        assertEquals(2, member.paramCount)
    }

    @Test
    fun `a member line without a count has zero parameters`() {
        val member = table("member\tSystem\tConsole\tproperty\tOut\t1").members.single()
        assertEquals(0, member.paramCount)
    }

    @Test
    fun `a count that is not a number is zero`() {
        val member = table("member\tSystem\tConsole\tmethod\tX\t0\tmany").members.single()
        assertEquals(0, member.paramCount)
    }

    @Test
    fun `a member of a type in no namespace names the type bare`() {
        val member = table("member\t-\tProgram\tfield\tCount\t0").members.single()
        assertEquals("Program", member.typeFullName)
        assertFalse(member.isStatic)
    }

    @Test
    fun `short and unknown lines are skipped`() {
        val parsed = table(
            "type\tSystem\tConsole\tclass", // one field short
            "member\tSystem\tConsole\tmethod\tX", // one field short
            "other\tx\ty",
            "",
            "garbage",
        )
        assertTrue(parsed.types.isEmpty())
        assertTrue(parsed.members.isEmpty())
    }

    @Test
    fun `lines keep their order`() {
        val parsed = table(
            "type\tA\tOne\tclass\t0",
            "member\tA\tOne\tmethod\tM\t0\t0",
            "type\tA\tTwo\tclass\t0",
        )
        assertEquals(listOf("One", "Two"), parsed.types.map { it.name })
        assertEquals(1, parsed.members.size)
    }
}
