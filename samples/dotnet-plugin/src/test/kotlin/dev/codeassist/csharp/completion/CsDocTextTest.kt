package dev.codeassist.csharp.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsDocTextTest {

    @Test
    fun `the summary is its text with tags gone and spaces collapsed`() {
        val xml = "<summary>\n  Writes the   value,\n  then a newline.\n</summary>"
        assertEquals("Writes the value, then a newline.", CsDocText.summary(xml))
    }

    @Test
    fun `no summary or an empty one gives null`() {
        assertNull(CsDocText.summary(""))
        assertNull(CsDocText.summary("<param name=\"a\">x</param>"))
        assertNull(CsDocText.summary("<summary>  </summary>"))
    }

    @Test
    fun `a reference shows the name it points at`() {
        assertEquals("Returns a String.", CsDocText.summary("<summary>Returns a <see cref=\"T:System.String\"/>.</summary>"))
        assertEquals(
            "Like Max and List<T>.",
            CsDocText.summary(
                "<summary>Like <see cref=\"M:System.Math.Max(System.Int32,System.Int32)\"/> and " +
                    "<see cref=\"T:System.Collections.Generic.List{T}\" />.</summary>",
            ),
        )
        assertEquals("List", CsDocText.shortName("T:System.Collections.Generic.List`1"))
    }

    @Test
    fun `keywords, parameter references and links keep their words`() {
        val xml = "<summary>Is <see langword=\"null\"/> when <paramref name=\"x\"/> is " +
            "<see href=\"https://a.b\">bad</see> or <see href=\"https://c.d\"/>.</summary>"
        assertEquals("Is null when x is bad or https://c.d.", CsDocText.summary(xml))
    }

    @Test
    fun `a paragraph ends a line and entities are decoded`() {
        val xml = "<summary><para>First &amp; second.</para><para>Third &lt;T&gt;.</para></summary>"
        assertEquals("First & second.\nThird <T>.", CsDocText.summary(xml))
    }

    @Test
    fun `a parameter's text is found by name`() {
        val xml = "<summary>S</summary><param name=\"a\">The first.</param><param name=\"ab\">The <c>second</c>.</param>"
        assertEquals("The first.", CsDocText.param(xml, "a"))
        assertEquals("The second.", CsDocText.param(xml, "ab"))
        assertNull(CsDocText.param(xml, "c"))
    }

    @Test
    fun `long text is cut at a word`() {
        val text = CsDocText.summary("<summary>" + "word ".repeat(300) + "</summary>")!!
        assertTrue(text.length <= 601)
        assertTrue(text.endsWith("…"))
        assertFalse(text.contains("wor…"))
    }

    @Test
    fun `inheritdoc is noticed`() {
        assertTrue(CsDocText.inherits("<inheritdoc/>"))
        assertFalse(CsDocText.inherits("<summary>x</summary>"))
    }

    @Test
    fun `the line protocol's escapes are undone`() {
        assertEquals("a\nb\tc\\d", CsDocText.unescape("a\\nb\\tc\\\\d"))
        assertEquals("plain", CsDocText.unescape("plain"))
        assertEquals("end\\", CsDocText.unescape("end\\"))
    }
}
