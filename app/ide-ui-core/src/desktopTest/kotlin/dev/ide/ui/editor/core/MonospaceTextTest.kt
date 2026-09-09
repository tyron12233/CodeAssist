package dev.ide.ui.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate for the raw monospace draw path: [monospaceSafe] must accept exactly the lines that can be
 * positioned by `column · charWidth`, and reject everything that breaks the one-cell-per-column assumption.
 * This is the correctness-critical decision a "looks right on a device" check would miss, so it's pinned here.
 */
class MonospaceTextTest {

    @Test
    fun printableAsciiIsSafe() {
        assertTrue(monospaceSafe(""), "empty line")
        assertTrue(monospaceSafe("    val x = foo(a, b) // ok!"))
        assertTrue(monospaceSafe("fun main() { println(\"hi\") }"))
        // Every printable-ASCII code point individually.
        for (code in 0x20..0x7E) assertTrue(monospaceSafe(code.toChar().toString()), "code point $code")
    }

    @Test
    fun tabsAndControlCharsAreUnsafe() {
        assertFalse(monospaceSafe("a" + '\t' + "b"), "tab has a tab-stop advance")
        assertFalse(monospaceSafe('\t' + "indented"), "leading tab")
        assertFalse(monospaceSafe("a" + 0.toChar() + "b"), "NUL")
        assertFalse(monospaceSafe("a" + 27.toChar() + "b"), "ESC")
        assertFalse(monospaceSafe("a" + 127.toChar() + "b"), "DEL is just past '~'")
        assertFalse(monospaceSafe("a" + '\n' + "b"), "newline (defensively rejected)")
    }

    @Test
    fun nonAsciiIsUnsafe() {
        assertFalse(monospaceSafe("caf" + 'é'), "accented letter may use a fallback advance")
        assertFalse(monospaceSafe("日本語"), "CJK is double-width")
        assertFalse(monospaceSafe("x😀"), "emoji is not one cell")
        assertFalse(monospaceSafe("á"), "combining acute accent is zero-width")
        assertFalse(monospaceSafe("​zwsp"), "zero-width space")
        assertFalse(monospaceSafe("אב"), "RTL Hebrew reorders")
    }

    @Test
    fun columnGeometryRoundTrips() {
        val cw = 9.5f
        for (col in 0..40) {
            val x = monospaceColumnX(col, cw)
            assertEquals(col * cw, x, 0f)
            // The center of a cell rounds back to that column.
            assertEquals(col, monospaceColumnAt(x + cw * 0.25f, cw), "center of column $col")
        }
        assertEquals(0, monospaceColumnAt(-5f, cw), "negative x clamps to column 0")
        assertEquals(0, monospaceColumnAt(10f, 0f), "zero charWidth is guarded")
    }
}
