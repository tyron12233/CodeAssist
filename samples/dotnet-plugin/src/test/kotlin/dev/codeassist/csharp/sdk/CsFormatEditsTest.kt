package dev.codeassist.csharp.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsFormatEditsTest {

    @Test
    fun `an edit line gives the range and the unescaped text`() {
        val edits = CsFormatEdits.parse(listOf("edit\t12\t3\t\\n    ", "edit\t40\t0\t "))

        assertEquals(listOf(CsFormatEdits.Edit(12, 3, "\n    "), CsFormatEdits.Edit(40, 0, " ")), edits)
    }

    @Test
    fun `an edit that only removes text has an empty replacement`() {
        assertEquals(listOf(CsFormatEdits.Edit(5, 2, "")), CsFormatEdits.parse(listOf("edit\t5\t2\t")))
    }

    @Test
    fun `escapes for newline, carriage return, tab and backslash are reversed`() {
        assertEquals("a\r\nb\tc\\d", CsFormatEdits.unescape("a\\r\\nb\\tc\\\\d"))
    }

    @Test
    fun `lines that are not edits are skipped`() {
        assertTrue(CsFormatEdits.parse(listOf("server: failed", "edit\tx\t1\ta", "edit\t-1\t1\ta", "edit\t1", "")).isEmpty())
    }
}
