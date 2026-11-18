package dev.codeassist.csharp.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsSignatureLinesTest {

    private val lines = listOf(
        "sighelp\t1\t2",
        "sig\tMax(int a, int b)\t4,9;11,16",
        "sig\tMax<T>(T a, T b, params T[] rest)\t7,10;12,15;17,31",
    )

    @Test
    fun `overloads, the active one and the active parameter are read`() {
        val result = CsSignatureLines.parse(lines)!!
        assertEquals(2, result.signatures.size)
        assertEquals(1, result.activeSignature)
        assertEquals(2, result.activeParameter)
        val first = result.signatures[0]
        assertEquals(listOf(4 until 9, 11 until 16), first.parameters)
        assertEquals("int a", first.label.substring(first.parameters[0].first, first.parameters[0].last + 1))
    }

    @Test
    fun `no sighelp line means the caret is in no call`() {
        assertNull(CsSignatureLines.parse(emptyList()))
        assertNull(CsSignatureLines.parse(listOf("sig\tMax()\t")))
    }

    @Test
    fun `a header without signatures or a bad header gives nothing`() {
        assertNull(CsSignatureLines.parse(listOf("sighelp\t0\t0")))
        assertNull(CsSignatureLines.parse(listOf("sighelp\tx\t0", "sig\tF()\t")))
    }

    @Test
    fun `a parameterless overload has no ranges`() {
        val result = CsSignatureLines.parse(listOf("sighelp\t0\t0", "sig\tF()\t"))!!
        assertEquals(emptyList<IntRange>(), result.signatures.single().parameters)
    }

    @Test
    fun `ranges outside the label are dropped, not clamped`() {
        val result = CsSignatureLines.parse(listOf("sighelp\t0\t0", "sig\tF(int a)\t2,9;9,3;2,7;x,1"))!!
        assertEquals(listOf(2 until 7), result.signatures.single().parameters)
    }

    @Test
    fun `an active signature past the end is kept in range`() {
        val result = CsSignatureLines.parse(listOf("sighelp\t5\t-1", "sig\tF(int a)\t2,7"))!!
        assertEquals(0, result.activeSignature)
        assertEquals(0, result.activeParameter)
    }

    @Test
    fun `documentation and parameter names follow the ranges`() {
        val result = CsSignatureLines.parse(
            listOf("sighelp\t0\t0", "sig\tF(int a, int b)\t2,7;9,14\t<summary>S</summary>\\n<param name=\"a\">A</param>\ta;b"),
        )!!
        val signature = result.signatures.single()
        assertEquals("<summary>S</summary>\n<param name=\"a\">A</param>", signature.documentation)
        assertEquals(listOf("a", "b"), signature.names)
    }

    @Test
    fun `a line without the documentation fields has none`() {
        val signature = CsSignatureLines.parse(listOf("sighelp\t0\t0", "sig\tF(int a)\t2,7"))!!.signatures.single()
        assertEquals("", signature.documentation)
        assertEquals(emptyList<String>(), signature.names)
    }
}
