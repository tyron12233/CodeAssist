package dev.ide.ui.editor.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardHistoryTest {
    @AfterTest fun reset() = ClipboardHistory.clear()

    @Test
    fun newestFirstARepeatMovesToTheTopAndBlankIsIgnored() {
        ClipboardHistory.record("a")
        ClipboardHistory.record("b")
        ClipboardHistory.record("  \n")
        ClipboardHistory.record("a")
        assertEquals(listOf("a", "b"), ClipboardHistory.entries.toList())
    }

    @Test
    fun keepsTheLastTwentyAndSkipsHugeCopies() {
        for (i in 1..25) ClipboardHistory.record("item $i")
        assertEquals(ClipboardHistory.MAX_ENTRIES, ClipboardHistory.entries.size)
        assertEquals("item 25", ClipboardHistory.entries.first())
        assertEquals("item 6", ClipboardHistory.entries.last())
        ClipboardHistory.record("x".repeat(ClipboardHistory.MAX_ENTRY_CHARS + 1))
        assertEquals("item 25", ClipboardHistory.entries.first(), "a too-large copy is left out, not cut down")
    }
}
