package dev.ide.ui

import dev.ide.ui.components.clipForClipboard
import dev.ide.ui.editor.core.EditorDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    @Test
    fun renamingOntoAnAlreadyOpenTabLeavesOneTabPerPath() {
        val disk = mutableMapOf("/p/A.java" to "class A {}", "/p/B.java" to "class B {}")
        val backend = object : StubBackend() {
            override fun readFile(path: String) = disk[path] ?: ""
        }
        // Unconfined dispatchers make the async tab reload run synchronously here.
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        runBlocking {
            state.openSuspend("/p/A.java", "A.java")
            state.openSuspend("/p/B.java", "B.java")
        }
        assertEquals(2, state.openFiles.size, "two distinct files, two tabs")

        // Rename A.java onto B.java, which is itself open: the followed tab lands on a path another tab holds.
        disk["/p/B.java"] = "class A {}"
        state.reloadAfterRename(activePath = "/p/A.java", newPath = "/p/B.java")

        // Two tabs sharing a path repeat the tab strip's lazy key, which throws and takes the editor down.
        assertEquals(
            listOf("/p/B.java"), state.openFiles.map { it.path },
            "the redundant tab must be dropped, leaving one tab per path",
        )
        assertEquals(0, state.activeIndex, "the active tab follows its path to the tab that kept it")
    }

    @Test
    fun tabsSharingAPathStillGetDistinctStripKeys() {
        // Two OpenFile for one path (a re-point after rename/move, or a concurrent open on a slow device) must
        // NOT collide on the tab strip's LazyRow key — a duplicate key hard-crashes the measure pass
        // (`measureLazyList` precondition), the top non-native on-device crasher. The strip keys on the unique
        // tabId, so even a transient duplicate path yields distinct keys.
        val a = OpenFile("/p/Same.kt", "Same.kt", "class A")
        val b = OpenFile("/p/Same.kt", "Same.kt", "class A")
        assertTrue(a.tabId != b.tabId, "each tab gets a unique id even for the same path")
        val keys = listOf(a, b).map { it.tabId }
        assertEquals(keys.size, keys.toSet().size, "tab strip keys are unique (no repeated LazyRow key)")

        // A re-point that reuses the id (an in-place refresh of the SAME logical tab) keeps its strip identity.
        val refreshed = OpenFile("/p/Same.kt", "Same.kt", "class A v2", tabId = a.tabId)
        assertEquals(a.tabId, refreshed.tabId, "an in-place tab refresh keeps the tab's strip identity")
    }
}
