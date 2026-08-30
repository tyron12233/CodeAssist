package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiExportModule
import dev.ide.ui.backend.UiExportOptions
import dev.ide.ui.backend.UiExportPlan
import dev.ide.ui.backend.UiImportPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the export flow is: configuring, packaging, done (with the written file), or failed. */
internal sealed interface ExportPhase {
    data object Configure : ExportPhase
    data object Exporting : ExportPhase

    /** Written: [path] is the file, [notes] what a best-effort export could not carry (Gradle only). */
    data class Done(val path: String, val notes: List<String> = emptyList()) : ExportPhase

    /** Packaging failed; the host renders the reason (there is one). */
    data object Failed : ExportPhase
}

/**
 * What the export writes. A `.caproj` is the lossless form another CodeAssist reads back; a Gradle project
 * is the way out to Android Studio or a `gradle` build, generated from the project model and therefore best
 * effort (see [dev.ide.ui.backend.UiGradleExport]).
 */
internal enum class ExportFormat { Package, Gradle }

/** Cap on the screenshots one export can carry (the packager drops anything past this too). */
internal const val MAX_EXPORT_SCREENSHOTS = 8

/**
 * State and intents for the `.caproj` import preview: the name the project will take on disk (and the
 * directory that implies), whether the packaged-file list is expanded, and the extraction itself.
 */
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

    /** The name the imported project takes; starts from the one baked into the package. */
    var name: String by mutableStateOf(preview.name)
        private set

    /** The directory [import] would create, kept in step with [name] (it is resolved off the main thread, so
     *  it trails a keystroke). Null when the host has no projects directory to import into. */
    var destination: String? by mutableStateOf(null)
        private set

    /** Whether the full packaged-file list under the contents summary is showing. */
    var filesExpanded: Boolean by mutableStateOf(false)
        private set

    init {
        resolveDestination()
    }

    fun updateName(value: String) {
        name = value
        resolveDestination()
    }

    /** Ask the backend where this name would land. A slower answer for a name the user has already typed past
     *  is dropped, so the line can't end up showing a destination for stale input. */
    private fun resolveDestination() {
        val asked = effectiveName()
        scope.launch {
            val resolved = backend.projects.importDestination(asked)
            if (asked == effectiveName()) destination = resolved
        }
    }

    fun toggleFiles() {
        filesExpanded = !filesExpanded
    }

    /** The name to import under: what the user typed, or the package's own name if they cleared the field. */
    private fun effectiveName(): String = name.trim().ifEmpty { preview.name }

    /** Extract the package into a new project; [onImported] opens it when it lands. */
    fun import(onImported: () -> Unit) {
        if (busy || !preview.compatible) return
        busy = true
        error = null
        scope.launch {
            val result = backend.projects.importPackage(archivePath, effectiveName())
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

/**
 * State and intents for packaging a project into a shareable `.caproj`: the metadata fields, which modules
 * go in, the screenshots to embed, and the export itself. The module list arrives asynchronously as
 * [plan] — until it does, the export packages everything, which is what it always did.
 */
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

    /** What to write. The metadata, module, and screenshot choices below only apply to a `.caproj`. */
    var format: ExportFormat by mutableStateOf(ExportFormat.Package)
        private set
    var bundleDeps: Boolean by mutableStateOf(false)
        private set
    var author: String by mutableStateOf(initialAuthor)
        private set
    var description: String by mutableStateOf("")
        private set

    /** The project's modules and what bundling its dependencies costs; null until the read finishes. */
    var plan: UiExportPlan? by mutableStateOf(null)
        private set

    /** Modules the user dropped from the package. Kept dependency-closed by [toggleModule]. */
    private var excluded: Set<String> by mutableStateOf(emptySet())

    /** Paths of the images to embed as preview screenshots, in display order. */
    val screenshots = mutableStateListOf<String>()

    init {
        scope.launch { plan = backend.projects.exportPlan(project.rootPath) }
    }

    fun updateFormat(value: ExportFormat) { format = value }

    fun updateBundleDeps(value: Boolean) { bundleDeps = value }

    fun updateAuthor(value: String) { author = value }

    fun updateDescription(value: String) { description = value }

    fun isIncluded(name: String): Boolean = name !in excluded

    /**
     * Drop [name] from the package or put it back, keeping the selection buildable: dropping a module also
     * drops everything that depends on it, and putting one back brings in everything it needs. Refuses to
     * empty the package.
     */
    fun toggleModule(name: String) {
        val modules = plan?.modules ?: return
        val next = if (name in excluded) excluded - dependencyClosure(modules, name) else excluded + dependentClosure(modules, name)
        if (modules.all { it.name in next }) return
        excluded = next
    }

    /** The modules to package, or null when that is all of them (an untouched export takes the old path). */
    fun includedModules(): Set<String>? {
        val modules = plan?.modules ?: return null
        if (excluded.isEmpty()) return null
        return modules.map { it.name }.filterNot { it in excluded }.toSet()
    }

    fun addScreenshot(path: String) {
        if (path !in screenshots && screenshots.size < MAX_EXPORT_SCREENSHOTS) screenshots.add(path)
    }

    fun removeScreenshot(path: String) {
        screenshots.remove(path)
    }

    /** Roughly what the package will weigh: the included modules' files plus the bundled dependencies. Files
     *  outside every module (the workspace's own config) aren't counted, so it reads low by a little. */
    fun estimatedBytes(): Long {
        val plan = plan ?: return 0L
        val modules = plan.modules.filter { isIncluded(it.name) }.sumOf { it.sizeBytes }
        return modules + if (bundleDeps) plan.bundledDepsBytes else 0L
    }

    fun backToConfigure() { phase = ExportPhase.Configure }

    /** Write the project in the chosen [format], remembering the author for the next package export. */
    fun export() {
        if (format == ExportFormat.Gradle) {
            exportGradle()
            return
        }
        onAuthorRemembered(author.trim())
        phase = ExportPhase.Exporting
        scope.launch {
            val path = backend.projects.exportProject(
                project.rootPath,
                UiExportOptions(
                    bundleDependencies = bundleDeps,
                    author = author.trim(),
                    description = description.trim(),
                    includedModules = includedModules(),
                    screenshotPaths = screenshots.toList(),
                ),
            )
            phase = if (path != null) ExportPhase.Done(path) else ExportPhase.Failed
        }
    }

    /** Generate the Gradle build files and zip them with the sources; its notes ride to the done screen. */
    private fun exportGradle() {
        phase = ExportPhase.Exporting
        scope.launch {
            val result = backend.projects.exportGradleProject(project.rootPath)
            phase = if (result != null) ExportPhase.Done(result.path, result.notes) else ExportPhase.Failed
        }
    }
}

/** [name] plus every module that reaches it through a dependency — all the modules a drop has to take with it. */
private fun dependentClosure(modules: List<UiExportModule>, name: String): Set<String> {
    val closure = mutableSetOf(name)
    var grew = true
    while (grew) {
        grew = false
        for (m in modules) {
            if (m.name !in closure && m.dependsOn.any { it in closure }) {
                closure += m.name
                grew = true
            }
        }
    }
    return closure
}

/** [name] plus everything it depends on, transitively — what putting a module back has to bring with it. */
private fun dependencyClosure(modules: List<UiExportModule>, name: String): Set<String> {
    val byName = modules.associateBy { it.name }
    val closure = mutableSetOf(name)
    val pending = ArrayDeque(listOf(name))
    while (pending.isNotEmpty()) {
        val next = byName[pending.removeFirst()] ?: continue
        for (dep in next.dependsOn) if (closure.add(dep)) pending.addLast(dep)
    }
    return closure
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
