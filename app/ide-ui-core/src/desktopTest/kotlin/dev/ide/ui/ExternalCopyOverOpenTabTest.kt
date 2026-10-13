package dev.ide.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A file copied over an open one from outside the IDE (a file manager) raises no event. The tab used to keep the
 * old text until the user deleted the file and copied it again; reopening it, or coming back to the app, now
 * reads the disk.
 */
class ExternalCopyOverOpenTabTest {

    private val disk = mutableMapOf("/p/A.kt" to "fun old() = 1\n", "/p/B.kt" to "fun b() = 2\n")

    private fun state(): IdeUiState {
        val backend = object : StubBackend() {
            override fun readFile(path: String) = disk[path] ?: ""
            override fun saveFile(path: String, text: String) { disk[path] = text }
        }
        // Unconfined dispatchers run the async disk reads synchronously here.
        return IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
    }

    @Test
    fun reopeningAnOpenFileShowsWhatIsOnDiskNow() {
        val state = state()
        runBlocking {
            state.openSuspend("/p/A.kt", "A.kt")
            state.openSuspend("/p/B.kt", "B.kt")
        }
        disk["/p/A.kt"] = "fun new() = 2\n"

        runBlocking { state.openSuspend("/p/A.kt", "A.kt") }

        assertEquals(2, state.openFiles.size, "the existing tab is reused, not duplicated")
        assertEquals("/p/A.kt", state.active?.path)
        assertEquals("fun new() = 2\n", state.active?.text)
        assertFalse(state.active!!.modified, "the buffer matches the file again")
    }

    @Test
    fun returningToTheAppReloadsCleanTabsAndOnlyFlagsEditedOnes() {
        val state = state()
        runBlocking {
            state.openSuspend("/p/A.kt", "A.kt")
            state.openSuspend("/p/B.kt", "B.kt")
        }
        val a = state.openFiles.first { it.path == "/p/A.kt" }
        val b = state.openFiles.first { it.path == "/p/B.kt" }
        b.session.replaceRange(0, 0, "// mine\n", androidx.compose.ui.text.TextRange(8))
        assertTrue(b.modified)
        disk["/p/A.kt"] = "fun new() = 2\n"
        disk["/p/B.kt"] = "fun copiedOver() = 3\n"
        val epoch = state.analysisEpoch

        state.syncWithDiskOnReturn()

        assertEquals("fun new() = 2\n", a.text, "a clean tab takes the copied file")
        assertEquals("// mine\nfun b() = 2\n", b.text, "an edited tab keeps the user's work")
        assertTrue(b.staleOnDisk, "and is marked as diverged from the file")
        assertEquals(epoch + 1, state.analysisEpoch, "a changed tab re-analyzes the open files")
    }

    @Test
    fun returningWithNothingChangedDoesNotReanalyze() {
        val state = state()
        runBlocking { state.openSuspend("/p/A.kt", "A.kt") }
        val epoch = state.analysisEpoch

        state.syncWithDiskOnReturn()

        assertEquals(epoch, state.analysisEpoch)
        assertEquals("fun old() = 1\n", state.active?.text)
    }

    @Test
    fun anEditedTabWhoseFileABuildFlushedIsSavedNotChangedOnDisk() {
        val state = state()
        runBlocking { state.openSuspend("/p/A.kt", "A.kt") }
        val a = state.active!!
        a.session.replaceRange(0, 0, "// mine\n", androidx.compose.ui.text.TextRange(8))
        disk["/p/A.kt"] = a.text // the pre-build flush wrote the buffer

        state.syncWithDiskOnReturn()

        assertFalse(a.modified, "the file holds the buffer, so the tab is saved")
        assertFalse(a.staleOnDisk, "and has not diverged from it")
    }

    @Test
    fun runningSavesEditedTabsFirst() {
        val state = state()
        runBlocking { state.openSuspend("/p/A.kt", "A.kt") }
        val a = state.active!!
        a.session.replaceRange(0, 0, "// mine\n", androidx.compose.ui.text.TextRange(8))
        var ranWith: String? = null

        state.requestRun { ranWith = disk["/p/A.kt"] }

        assertEquals("// mine\nfun old() = 1\n", ranWith, "the run sees the edit on disk")
        assertFalse(a.modified)
    }

    @Test
    fun reloadTakesTheFileAndUndoBringsTheEditsBack() {
        val state = state()
        runBlocking { state.openSuspend("/p/A.kt", "A.kt") }
        val a = state.active!!
        a.session.replaceRange(0, 0, "// mine\n", androidx.compose.ui.text.TextRange(8))
        disk["/p/A.kt"] = "fun copiedOver() = 3\n"
        state.syncWithDiskOnReturn()
        assertTrue(a.staleOnDisk)

        state.reloadFromDisk(a)

        assertEquals("fun copiedOver() = 3\n", a.text)
        assertFalse(a.modified)
        assertFalse(a.staleOnDisk)
        assertTrue(a.session.undo())
        assertEquals("// mine\nfun old() = 1\n", a.text, "the discarded edits are one undo away")
        assertTrue(a.modified)
    }

    @Test
    fun keepMineSavesTheBufferOverTheFile() {
        val state = state()
        runBlocking { state.openSuspend("/p/A.kt", "A.kt") }
        val a = state.active!!
        a.session.replaceRange(0, 0, "// mine\n", androidx.compose.ui.text.TextRange(8))
        disk["/p/A.kt"] = "fun copiedOver() = 3\n"
        state.syncWithDiskOnReturn()

        state.save(a)

        assertEquals("// mine\nfun old() = 1\n", disk["/p/A.kt"])
        assertFalse(a.staleOnDisk)
        assertFalse(a.modified)
    }
}
