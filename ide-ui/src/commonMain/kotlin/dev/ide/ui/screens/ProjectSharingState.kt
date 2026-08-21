package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiExportOptions
import dev.ide.ui.backend.UiImportPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the export flow is: configuring, packaging, done (with the written package), or failed. */
internal sealed interface ExportPhase {
    data object Configure : ExportPhase
    data object Exporting : ExportPhase
    data class Done(val path: String) : ExportPhase

    /** Packaging failed; the host renders the reason (there is one). */
    data object Failed : ExportPhase
}

/** State and intents for the `.caproj` import preview: extracting the package the user picked. */
@Stable
internal class ImportPreviewState(
    private val backend: IdeBackend,
    private val archivePath: String,
    private val preview: UiImportPreview,
    private val scope: CoroutineScope,
) {
    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** Extract the package into a new project; [onImported] opens it when it lands. */
    fun import(onImported: () -> Unit) {
        if (busy || !preview.compatible) return
        busy = true
        error = null
        scope.launch {
            val result = backend.projects.importPackage(archivePath)
            busy = false
            if (result.success) onImported() else error = result.message
        }
    }
}

@Composable
internal fun rememberImportPreviewState(
    backend: IdeBackend,
    archivePath: String,
    preview: UiImportPreview,
    scope: CoroutineScope = rememberCoroutineScope(),
): ImportPreviewState = remember(backend, archivePath, preview, scope) {
    ImportPreviewState(backend, archivePath, preview, scope)
}

/** State and intents for packaging a project into a shareable `.caproj`: the options form and the export. */
@Stable
internal class ExportProjectState(
    private val backend: IdeBackend,
    private val project: ProjectInfo,
    initialAuthor: String,
    private val onAuthorRemembered: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    var phase: ExportPhase by mutableStateOf(ExportPhase.Configure)
        private set
    var bundleDeps: Boolean by mutableStateOf(false)
        private set
    var author: String by mutableStateOf(initialAuthor)
        private set
    var description: String by mutableStateOf("")
        private set

    fun updateBundleDeps(value: Boolean) { bundleDeps = value }

    fun updateAuthor(value: String) { author = value }

    fun updateDescription(value: String) { description = value }

    fun backToConfigure() { phase = ExportPhase.Configure }

    /** Package the project, remembering the author for the next export. */
    fun export() {
        onAuthorRemembered(author.trim())
        phase = ExportPhase.Exporting
        scope.launch {
            val path = backend.projects.exportProject(
                project.rootPath,
                UiExportOptions(bundleDeps, author.trim(), description.trim()),
            )
            phase = if (path != null) ExportPhase.Done(path) else ExportPhase.Failed
        }
    }
}

@Composable
internal fun rememberExportProjectState(
    backend: IdeBackend,
    project: ProjectInfo,
    initialAuthor: String,
    onAuthorRemembered: (String) -> Unit,
    scope: CoroutineScope = rememberCoroutineScope(),
): ExportProjectState = remember(backend, project, scope) {
    ExportProjectState(backend, project, initialAuthor, onAuthorRemembered, scope)
}
