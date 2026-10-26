package dev.ide.ui

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Closing a tab with unsaved edits asks first ([IdeUiState.unsavedClose]), and "Don't ask again" sticks. */
class UnsavedCloseTest {

    private open class FakeBackend(private val disk: MutableMap<String, String>) : StubBackend() {
        val saved = ArrayList<String>()
        val prefs = HashMap<String, String>()
        override fun readFile(path: String) = disk[path] ?: ""
        override fun saveFile(path: String, text: String) { disk[path] = text; saved += path }
        override fun preference(key: String): String? = prefs[key]
        override fun setPreference(key: String, value: String) { prefs[key] = value }
    }

    private fun open(backend: FakeBackend = FakeBackend(DISK.toMutableMap())): Pair<IdeUiState, FakeBackend> {
        val state = IdeUiState(backend)
        runBlocking {
            state.openSuspend("/p/A.java", "A.java")
            state.openSuspend("/p/B.java", "B.java")
        }
        return state to backend
    }

    private fun IdeUiState.tab(name: String) = openFiles.single { it.name == name }

    @Test
    fun aCleanTabClosesWithoutAsking() {
        val (state, _) = open()
        state.close(state.tab("A.java"))
        assertNull(state.unsavedClose)
        assertEquals(listOf("B.java"), state.openFiles.map { it.name })
    }

    @Test
    fun anEditedTabAsksAndCancelKeepsItAndItsEdits() {
        val (state, backend) = open()
        val a = state.tab("A.java")
        a.session.commitText("x")
        state.close(a)
        assertEquals(listOf(a), state.unsavedClose?.files, "the edited tab is held for the dialog")
        assertTrue(a in state.openFiles, "nothing closes before the user answers")

        state.dismissUnsavedClose()
        assertNull(state.unsavedClose)
        assertTrue(a in state.openFiles && a.modified, "Cancel keeps the tab and its edits")
        assertTrue(backend.saved.isEmpty())
    }

    @Test
    fun saveWritesThenCloses() {
        val (state, backend) = open()
        val a = state.tab("A.java")
        a.session.commitText("x")
        state.close(a)
        state.resolveUnsavedClose(save = true, remember = false)
        assertEquals(listOf("/p/A.java"), backend.saved)
        assertFalse(a in state.openFiles)
        assertNull(backend.prefs[IdeUiState.CLOSE_UNSAVED_PREF], "without the checkbox nothing is remembered")
    }

    @Test
    fun dontSaveClosesWithoutWriting() {
        val (state, backend) = open()
        val a = state.tab("A.java")
        a.session.commitText("x")
        state.close(a)
        state.resolveUnsavedClose(save = false, remember = false)
        assertTrue(backend.saved.isEmpty())
        assertFalse(a in state.openFiles)
    }

    @Test
    fun closingSeveralClosesTheCleanOnesAndAsksOnlyAboutTheEdited() {
        val (state, _) = open()
        val b = state.tab("B.java")
        b.session.commitText("x")
        state.closeAll()
        assertEquals(listOf(b), state.openFiles, "the clean tab closed at once")
        assertEquals(listOf(b), state.unsavedClose?.files)
    }

    @Test
    fun aFailedSaveLeavesTheTabOpen() {
        val backend = object : FakeBackend(DISK.toMutableMap()) {
            override fun saveFile(path: String, text: String): Unit = throw java.nio.file.AccessDeniedException(path)
        }
        val (state, _) = open(backend)
        val a = state.tab("A.java")
        a.session.commitText("x")
        state.close(a)
        state.resolveUnsavedClose(save = true, remember = false)
        assertTrue(a in state.openFiles && a.modified, "the edits are not on disk, so the tab must not close")
    }

    @Test
    fun dontAskAgainRemembersTheAnswerForLaterCloses() {
        val (state, backend) = open()
        val a = state.tab("A.java")
        val b = state.tab("B.java")
        a.session.commitText("x")
        state.close(a)
        state.resolveUnsavedClose(save = true, remember = true)
        assertEquals(IdeUiState.CLOSE_UNSAVED_SAVE, backend.prefs[IdeUiState.CLOSE_UNSAVED_PREF])

        b.session.commitText("y")
        state.close(b)
        assertNull(state.unsavedClose, "a remembered answer skips the dialog")
        assertEquals(listOf("/p/A.java", "/p/B.java"), backend.saved)
        assertTrue(state.openFiles.isEmpty())
    }

    @Test
    fun aRememberedDiscardClosesWithoutWriting() {
        val backend = FakeBackend(DISK.toMutableMap())
        backend.prefs[IdeUiState.CLOSE_UNSAVED_PREF] = IdeUiState.CLOSE_UNSAVED_DISCARD
        val (state, _) = open(backend)
        val a = state.tab("A.java")
        a.session.commitText("x")
        state.close(a)
        assertNull(state.unsavedClose)
        assertFalse(a in state.openFiles)
        assertTrue(backend.saved.isEmpty())
    }

    private companion object {
        val DISK = mapOf("/p/A.java" to "class A {}", "/p/B.java" to "class B {}")
    }
}
