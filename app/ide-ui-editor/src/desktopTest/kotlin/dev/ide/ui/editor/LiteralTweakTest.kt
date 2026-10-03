package dev.ide.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LiteralTweakTest {

    /** The literal at the `|` marker in [marked] (the marker is removed first). */
    private fun at(marked: String): TweakableLiteral? {
        val caret = marked.indexOf('|')
        return tweakableLiteralAt(marked.removeRange(caret, caret + 1), caret)
    }

    private fun TweakableLiteral.text(marked: String): String = marked.replace("|", "").substring(start, end)

    @Test
    fun integerBeforeAUnit() {
        val src = "Modifier.padding(1|6.dp)"
        val n = assertIs<TweakableLiteral.Number>(at(src))
        assertEquals("16", n.text(src))
        assertEquals(16.0, n.value)
        assertEquals("24", n.format(24.0))
        assertEquals(1.0, n.step)
    }

    @Test
    fun caretRightAfterTheLiteralCounts() {
        assertIs<TweakableLiteral.Number>(at("Spacer(Modifier.height(8|.dp))"))
    }

    @Test
    fun floatKeepsDecimalsAndSuffix() {
        val src = "alpha(0.5|f)"
        val n = assertIs<TweakableLiteral.Number>(at(src))
        assertEquals("0.5f", n.text(src))
        assertEquals(0.1, n.step, 1e-12)
        assertEquals("0.6f", n.format(0.6))
        assertEquals("1.0f", n.format(1.0))
        assertEquals("16f", assertIs<TweakableLiteral.Number>(at("x(16|f)")).format(16.0))
    }

    @Test
    fun longSuffixIsKept() {
        assertEquals("43L", assertIs<TweakableLiteral.Number>(at("f(4|2L)")).format(43.0))
    }

    @Test
    fun unaryMinusBelongsToTheValue() {
        val src = "offset(x = -|4.dp)"
        val n = assertIs<TweakableLiteral.Number>(at(src))
        assertEquals("-4", n.text(src))
        assertEquals(-4.0, n.value)
        // A binary minus is not part of the literal.
        val bin = "val y = a - |4"
        assertEquals("4", assertIs<TweakableLiteral.Number>(at(bin)).text(bin))
    }

    @Test
    fun colorHexInsideColorCall() {
        val src = "val Purple = Color(0xFF66|50A4)"
        val c = assertIs<TweakableLiteral.ColorHex>(at(src))
        assertEquals("0xFF6650A4", c.text(src))
        assertEquals(0xFF6650A4L, c.argb)
        assertEquals("0xFFD0BCFF", c.format(0xFFD0BCFFL))
        assertEquals("0xffd0bcff", assertIs<TweakableLiteral.ColorHex>(at("Color(0xff|6650a4)")).format(0xFFD0BCFFL))
        assertNull(at("val mask = 0xFF66|50A4"), "hex outside Color(...) has no meaningful control")
    }

    @Test
    fun booleans() {
        val b = assertIs<TweakableLiteral.Bool>(at("enabled = tr|ue"))
        assertEquals("false", b.toggled())
    }

    @Test
    fun notALiteral() {
        assertNull(at("val x1|6 = 2"), "digits inside an identifier")
        assertNull(at("Text(\"size 1|6\")"), "inside a string")
        assertNull(at("foo() // 1|6 dp"), "inside a line comment")
        assertNull(at("val d = 1e1|0"), "exponent form has no natural step")
        assertNull(at("Mod|ifier"))
    }

    @Test
    fun secondLineOffsetsAreAbsolute() {
        val src = "val a = 1\nval b = 2|0"
        assertEquals("20", assertIs<TweakableLiteral.Number>(at(src)).text(src))
    }

    @Test
    fun fixedFormatting() {
        assertEquals("1.50", formatFixed(1.5, 2))
        assertEquals("-0.3", formatFixed(-0.3, 1))
        assertEquals("0.0", formatFixed(-0.04, 1))
        assertEquals("12", formatFixed(12.4, 0))
    }
}
