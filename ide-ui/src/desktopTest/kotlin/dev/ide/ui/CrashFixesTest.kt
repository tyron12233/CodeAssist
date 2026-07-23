package dev.ide.ui

import dev.ide.ui.components.clipForClipboard
import dev.ide.ui.editor.core.EditorDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression guards for the two top on-device crashes found in the analytics (see docs/analytics.md). */
class CrashFixesTest {

    @Test
    fun lineAccessorsClampStaleIndex() {
        val doc = EditorDocument.of("a\nbb\nccc") // 3 lines: starts [0, 2, 5]
        // A line index captured a frame before an edit shortened the document must clamp to a valid line
        // instead of throwing ArrayIndexOutOfBounds (was the #1 editor crash: EditorDocument.lineStart).
        assertEquals(doc.lineStart(2), doc.lineStart(99))
        assertEquals(doc.lineEnd(2), doc.lineEnd(99))
        assertEquals(doc.lineStart(0), doc.lineStart(-5))
        // Valid indices unaffected.
        assertEquals(0, doc.lineStart(0))
        assertEquals(2, doc.lineStart(1))
        assertEquals(8, doc.lineEnd(2))
    }

    @Test
    fun clipboardCopyIsCappedKeepingTail() {
        assertEquals("hello", clipForClipboard("hello"))
        val big = "X".repeat(500_000) + "TAIL_END"
        val clipped = clipForClipboard(big)
        assertTrue(clipped.length < big.length, "a large log must be truncated (else TransactionTooLargeException)")
        assertTrue(clipped.length <= 200_000 + 200, "kept within the clipboard cap")
        assertTrue(clipped.endsWith("TAIL_END"), "keeps the tail where a build's errors/status live")
        assertTrue(clipped.startsWith("["), "notes that earlier text was dropped")
    }
}
