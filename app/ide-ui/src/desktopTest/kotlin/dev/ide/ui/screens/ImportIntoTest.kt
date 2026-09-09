package dev.ide.ui.screens

import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.FileActions
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-directory "Import from file manager" action ([doImportInto]) must route the host file picker at the
 * EXACT directory chosen from the tree row's context menu (not the source-root fallback that the header-level
 * [doImport] uses), and open the first imported file on success.
 */
class ImportIntoTest {

    private class RecordingFileActions(private val imported: List<String>) : FileActions {
        var lastTarget: String? = null
        override val canImport = true
        override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
            lastTarget = targetDir
            onImported(imported)
        }
        override val canShare = false
        override fun share(path: String) {}
    }

    @Test
    fun importsIntoTheSelectedDirectoryAndOpensTheFirstFile() {
        // Unconfined dispatchers make the async file-open in `doImportInto` run synchronously here.
        val state = IdeUiState(StubBackend(), mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        val actions = RecordingFileActions(listOf("/proj/app/src/main/res/drawable/icon.png"))

        doImportInto(state, actions, "/proj/app/src/main/res/drawable")

        assertEquals(
            "/proj/app/src/main/res/drawable", actions.lastTarget,
            "import must target the exact directory chosen from the row menu",
        )
        assertEquals(
            listOf("/proj/app/src/main/res/drawable/icon.png"), state.openFiles.map { it.path },
            "the first imported file opens",
        )
    }

    @Test
    fun cancelledImportOpensNothing() {
        // Unconfined dispatchers make the async file-open in `doImportInto` run synchronously here.
        val state = IdeUiState(StubBackend(), mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        val actions = RecordingFileActions(emptyList())

        doImportInto(state, actions, "/proj/app/src")

        assertEquals("/proj/app/src", actions.lastTarget)
        assertEquals(emptyList(), state.openFiles.map { it.path }, "a cancelled import opens nothing")
    }
}
