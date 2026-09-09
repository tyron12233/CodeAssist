package dev.ide.ui.screens

import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.UiProjectResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picker's "Import Gradle project" wiring ([doImportGradle]): a chosen folder must be handed to the
 * backend importer with [onBusy] fired first; a cancelled picker must import nothing and report null.
 */
class ImportGradleTest {

    /** A [FileActions] whose directory picker hands back a preset path (null = the user cancelled). */
    private class PickDirActions(private val picked: String?) : FileActions {
        override val canImport = false
        override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {}
        override val canShare = false
        override fun share(path: String) {}
        override val canPickDirectory = true
        override fun pickDirectory(onPicked: (String?) -> Unit) = onPicked(picked)
    }

    // Unconfined runs the launched import synchronously, so the assertions see the final state.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun importsTheChosenFolderAfterSignallingBusy() {
        var busy = false
        var importedPath: String? = null
        var result: UiProjectResult? = null

        doImportGradle(
            fileActions = PickDirActions("/downloads/my-gradle-app"),
            scope = scope,
            onBusy = { busy = true },
            import = { p -> importedPath = p; UiProjectResult(true, "Imported", "/projects/my-gradle-app") },
        ) { result = it }

        assertTrue(busy, "the busy indicator must be shown before the slow import runs")
        assertEquals("/downloads/my-gradle-app", importedPath, "the picked folder is imported verbatim")
        assertEquals(true, result?.success)
        assertEquals("/projects/my-gradle-app", result?.rootPath)
    }

    @Test
    fun cancelledPickerImportsNothing() {
        var busy = false
        var imported = false
        var resultSet = false
        var result: UiProjectResult? = null

        doImportGradle(
            fileActions = PickDirActions(null),
            scope = scope,
            onBusy = { busy = true },
            import = { _ -> imported = true; UiProjectResult(true, "unused") },
        ) { resultSet = true; result = it }

        assertTrue(resultSet, "onResult must fire even on cancel so the caller can clear its busy state")
        assertNull(result, "a cancelled picker reports a null result")
        assertTrue(!busy, "no busy indicator when nothing was picked")
        assertTrue(!imported, "the importer is never called on cancel")
    }
}
