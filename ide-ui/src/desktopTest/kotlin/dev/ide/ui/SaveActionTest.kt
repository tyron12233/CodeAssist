package dev.ide.ui

import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tab dirty-flag + save semantics in [IdeUiState] (the orange-dot logic and the save action). */
class SaveActionTest {

    private class FakeBackend(private val disk: MutableMap<String, String>) : IdeBackend {
        val saved = ArrayList<Pair<String, String>>()
        override val project = ProjectInfo("p", "/p", 1)
        override fun fileTree() = TreeNode("root", "p", NodeKind.Workspace, null)
        override fun readFile(path: String) = disk[path] ?: ""
        override fun moduleNameForFile(path: String): String? = null
        override fun updateDocument(path: String, text: String) {}
        override fun saveFile(path: String, text: String) { disk[path] = text; saved += path to text }
        override suspend fun complete(path: String, text: String, offset: Int) = UiCompletionResult(emptyList(), offset, offset)
        override suspend fun analyze(path: String, text: String): List<UiDiagnostic> = emptyList()
        override val indexStatus: StateFlow<IndexUiStatus> = MutableStateFlow(IndexUiStatus())
        override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> = emptyList()
        override suspend fun searchMembers(query: String, limit: Int): List<SymbolHit> = emptyList()
        override val buildState: StateFlow<BuildState> = MutableStateFlow(BuildState())
        override fun runBuild() {}
        override fun stopBuild() {}
    }

    private fun openA(): Pair<IdeUiState, FakeBackend> {
        val backend = FakeBackend(mutableMapOf("/p/A.java" to "class A {}"))
        val state = IdeUiState(backend)
        state.open("/p/A.java", "A.java")
        return state to backend
    }

    @Test
    fun saveWritesBufferAndClearsDirty() {
        val (state, backend) = openA()
        val file = state.active!!
        file.session.commitText("x")  // editing the shared buffer auto-marks the tab dirty (OpenFile.init)
        assertTrue(file.modified, "an edit marks the tab modified")

        val expected = file.text
        state.saveActive()
        assertEquals(listOf("/p/A.java" to expected), backend.saved, "save persists the buffer")
        assertFalse(file.modified, "saving clears the dirty flag")
    }

    @Test
    fun caretMoveDoesNotDirty() {
        val (state, _) = openA()
        val file = state.active!!
        file.session.setCaret(2)  // a selection move never fires onTextEdit, so nothing marks it dirty
        file.recomputeDirty()     // and the debounced recompute confirms the buffer matches disk
        assertFalse(file.modified, "a caret/selection move must not dirty the file")
    }

    @Test
    fun revertingToSavedTextClearsDirty() {
        val (state, _) = openA()
        val file = state.active!!
        file.session.commitText("x")  // auto-marks dirty
        assertTrue(file.modified)
        file.session.backspace()      // delete it back to the on-disk text
        file.recomputeDirty()         // the debounced recompute clears the flag
        assertFalse(file.modified, "reverting to the saved text clears the dirty flag")
    }

    @Test
    fun saveIsNoOpWhenClean() {
        val (state, backend) = openA()
        state.saveActive()
        assertTrue(backend.saved.isEmpty(), "saving a clean file should not touch the backend")
    }
}
