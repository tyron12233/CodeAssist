package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiCachedVersion
import dev.ide.ui.backend.UiDependencyNode
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.backend.UiRepository
import dev.ide.ui.icons.CaIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Top-level split: what the module DECLARES (the roots you added) vs the RESOLVED transitive closure. The
 *  Declared tab is the place a declared-but-unresolved dependency stays visible (with a red badge) instead of
 *  silently vanishing from the resolved graph. */
internal enum class DepTab(val icon: ImageVector) {
    Declared(CaIcons.resources), Resolved(CaIcons.gitBranch)
}

/** Sub-views of the Resolved tab: the transitive closure as an expandable tree or a flat listing. */
internal enum class DepView(val icon: ImageVector) {
    Tree(CaIcons.layers), Graph(CaIcons.gitBranch)
}

/** The Add flow can add a library/AAR, import a BOM as a platform (Gradle `platform(...)`), depend on
 *  another module, or attach a local jar/aar file. */
internal enum class AddMode { Library, Platform, Module, Local }

/** A transient confirmation/result toast. */
internal data class ToastMsg(val text: String, val error: Boolean)

/** The Gradle configurations a declaration can go into, in the order the chips are offered. */
internal val DEP_CONFIGURATIONS = listOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation")

/** A typed string is treated as a direct coordinate when it carries a `:`: `group:name[:version]`. */
internal fun looksLikeCoordinate(s: String): Boolean =
    s.split(":").let { it.size in 2..3 && it.all { p -> p.isNotBlank() } }

internal fun shortCoord(coord: String): String =
    coord.split(":").let { if (it.size >= 3) "${it[1]}:${it[2]}" else coord }

/**
 * State and intents for the per-module dependency manager ([DependenciesPane]): which tab/view is showing,
 * the loaded model, the open overlays, and the edit/remove/exclude operations against [IdeBackend].
 *
 * Loading and every mutation run in [scope], so the pane composable renders and reports events only.
 */
@Stable
internal class DependenciesPaneState(
    val backend: IdeBackend,
    val moduleName: String,
    private val scope: CoroutineScope,
) {
    var tab: DepTab by mutableStateOf(DepTab.Declared)
        private set
    var resolvedView: DepView by mutableStateOf(DepView.Tree)
        private set
    var deps: UiModuleDeps? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var addOpen: Boolean by mutableStateOf(false)
        private set
    var reposOpen: Boolean by mutableStateOf(false)
        private set

    /** The coordinate awaiting remove confirmation, and the dependency open in the edit sheet. */
    var pendingRemove: String? by mutableStateOf(null)
        private set
    var pendingEdit: UiDependencyNode? by mutableStateOf(null)
        private set

    var toast: ToastMsg? by mutableStateOf(null)
        private set

    private var loadJob: Job? = null
    private var toastJob: Job? = null

    init {
        reload()
    }

    fun selectTab(value: DepTab) { tab = value }

    fun selectResolvedView(value: DepView) { resolvedView = value }

    fun openAdd() { addOpen = true }

    fun closeAdd() { addOpen = false }

    fun openRepositories() { reposOpen = true }

    fun closeRepositories() { reposOpen = false }

    fun askRemove(coordinate: String) { pendingRemove = coordinate }

    fun cancelRemove() { pendingRemove = null }

    fun startEdit(node: UiDependencyNode) { pendingEdit = node }

    fun cancelEdit() { pendingEdit = null }

    /** Re-read the module's declared + resolved dependencies, replacing any load already in flight. */
    fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            val loaded = runCatching { backend.deps.moduleDependencies(moduleName) }
            // A cancelled load (a reload, or the pane leaving composition) must leave the current model
            // alone: swallowing it here would blank the pane to "couldn't load" on the way to a reload.
            loaded.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            deps = loaded.getOrNull()
            loading = false
        }
    }

    /** Force a fresh resolve of the declared deps, then re-read the model. */
    fun retryResolution() {
        scope.launch {
            backend.deps.retryDependencyResolution()
            reload()
        }
    }

    /**
     * Exclude a transitive dependency: append its `group:name` to the exclusions of the direct dependency it
     * came from, then re-resolve. Reuses the same exclusion mechanism as the per-dependency editor.
     */
    fun excludeTransitive(root: UiDependencyNode, transitive: UiDependencyNode) {
        scope.launch {
            val gn = "${transitive.group}:${transitive.name}"
            val result = backend.deps.setDependencyExclusions(
                moduleName, root.coordinate, (root.exclusions + gn).distinct(),
            )
            report(result, success = "Excluded $gn from ${root.name}")
        }
    }

    /** Re-include a previously-excluded entry: drop it from the direct dependency's exclusions and re-resolve. */
    fun removeExclusion(root: UiDependencyNode, exclusion: String) {
        scope.launch {
            val result = backend.deps.setDependencyExclusions(moduleName, root.coordinate, root.exclusions - exclusion)
            report(result, success = "Re-included $exclusion")
        }
    }

    /** Remove the dependency the confirmation dialog is up for. */
    fun confirmRemove() {
        val coordinate = pendingRemove
        if (coordinate != null && backend.deps.removeDependency(moduleName, coordinate)) {
            showToast("Removed ${shortCoord(coordinate)}", error = false)
            reload()
        }
        pendingRemove = null
    }

    /** Apply the edit sheet's version / configuration / exclusions to [node] and close the sheet. */
    fun saveEdit(node: UiDependencyNode, version: String, configuration: String, exclusions: List<String>) {
        scope.launch {
            val result = backend.deps.updateDependency(moduleName, node.coordinate, version, configuration, exclusions)
            report(result, success = result.message)
        }
        pendingEdit = null
    }

    /** A dependency was added through the Add flow: close it, re-read, and confirm. */
    fun onDependencyAdded(result: UiAddResult) {
        if (!result.success) return
        addOpen = false
        reload()
        showToast(result.message, error = false)
    }

    fun showToast(text: String, error: Boolean) {
        toast = ToastMsg(text, error)
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2600)
            toast = null
        }
    }

    /** Toast the outcome of a mutation, re-reading the model when it stuck. */
    private fun report(result: UiAddResult, success: String) {
        showToast(if (result.success) success else result.message, error = !result.success)
        if (result.success) reload()
    }
}

@Composable
internal fun rememberDependenciesPaneState(
    backend: IdeBackend,
    moduleName: String,
    scope: CoroutineScope = rememberCoroutineScope(),
): DependenciesPaneState = remember(backend, moduleName, scope) {
    DependenciesPaneState(backend, moduleName, scope)
}

/**
 * State and intents for the Add-dependency flow: the mode, the debounced artifact search, the target lists
 * each mode needs, and the add itself. A successful add is reported to [pane], which closes the flow and
 * re-reads the module.
 */
@Stable
internal class AddDependencyState(
    private val pane: DependenciesPaneState,
    private val fileActions: FileActions,
    private val scope: CoroutineScope,
) {
    private val backend: IdeBackend get() = pane.backend
    private val moduleName: String get() = pane.moduleName

    var mode: AddMode by mutableStateOf(AddMode.Library)
        private set
    var query: String by mutableStateOf("")
        private set
    var results: List<UiArtifactHit> by mutableStateOf(emptyList())
        private set
    var searching: Boolean by mutableStateOf(false)
        private set

    /** The Gradle configuration the declaration goes into (`implementation`, `api`, …). */
    var configuration: String by mutableStateOf("implementation")
        private set

    /** The build variant this declaration is scoped to (null = shared / all variants, a plain `implementation`). */
    var variant: String? by mutableStateOf(null)
        private set
    var variants: List<String> by mutableStateOf(emptyList())
        private set

    var moduleTargets: List<String> by mutableStateOf(emptyList())
        private set
    var localCandidates: List<String> by mutableStateOf(emptyList())
        private set

    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** What is being added right now, for the progress panel's label. */
    var adding: String? by mutableStateOf(null)
        private set

    /** Whether the host can hand over a local jar/aar to copy into the module's `libs/`. */
    val canPickLocalFile: Boolean
        get() = fileActions.canImport && backend.deps.localLibraryDropDir(moduleName) != null

    init {
        scope.launch {
            variants = runCatching { backend.build.listVariants(moduleName) }.getOrDefault(emptyList())
        }
        // Debounced artifact search. `collectLatest` cancels an in-flight search when the query moves on.
        scope.launch {
            snapshotFlow { query.trim() to mode }.collectLatest { (typed, current) ->
                if (typed.length < 2 || current == AddMode.Module || current == AddMode.Local) {
                    results = emptyList()
                    searching = false
                    return@collectLatest
                }
                searching = true
                delay(320)
                // distinctBy coordinate: the same GAV can come back from more than one repo; duplicate keys
                // crash the list.
                results = runCatching { backend.deps.searchArtifacts(typed, moduleName) }
                    .getOrDefault(emptyList()).distinctBy { it.coordinate }
                searching = false
            }
        }
        // Load candidate modules / project-local jars when their tab is selected.
        scope.launch {
            snapshotFlow { mode }.collect { current ->
                if (current == AddMode.Module) {
                    moduleTargets = runCatching { backend.deps.moduleDependencyTargets(moduleName) }
                        .getOrDefault(emptyList())
                }
                if (current == AddMode.Local) {
                    localCandidates = runCatching { backend.deps.localLibraryCandidates(moduleName) }
                        .getOrDefault(emptyList())
                }
            }
        }
    }

    fun selectMode(value: AddMode) {
        if (busy) return
        mode = value
        error = null
    }

    fun updateQuery(value: String) {
        query = value
        error = null
    }

    fun selectConfiguration(value: String) {
        if (!busy) configuration = value
    }

    fun selectVariant(value: String?) {
        if (!busy) variant = value
    }

    /** Add a versioned library/AAR, a BOM platform, a module-on-module dependency, or a local file. */
    fun add(coordinate: String) {
        start(coordinate)
        scope.launch {
            val result = when (mode) {
                AddMode.Platform -> backend.deps.addPlatform(moduleName, coordinate, variant = variant)
                AddMode.Module -> backend.deps.addModuleDependency(moduleName, coordinate, configuration, variant = variant)
                AddMode.Local -> backend.deps.addLocalLibrary(moduleName, coordinate, configuration)
                AddMode.Library -> backend.deps.addDependency(moduleName, coordinate, configuration, variant = variant)
            }
            finish(result)
        }
    }

    /**
     * One-click add of a common Google library (Library mode). Firebase imports the BoM plus
     * firebase-analytics; Play Services adds the named artifacts. The backend rejects them on a non-Android
     * module, which surfaces as the same inline error as any other failed add.
     */
    fun quickAdd(label: String, action: suspend () -> UiAddResult) {
        if (busy) return
        start(label)
        scope.launch { finish(action()) }
    }

    /** Pick a jar/aar through the platform file picker; it is copied into the module's `libs/`, then attached. */
    fun pickLocalFile() {
        val dropDir = backend.deps.localLibraryDropDir(moduleName) ?: return
        fileActions.importInto(dropDir) { imported ->
            if (imported.isEmpty()) return@importInto
            start(imported.first().substringAfterLast('/').substringAfterLast('\\'))
            scope.launch {
                var last: UiAddResult? = null
                for (path in imported) {
                    val result = backend.deps.addLocalLibrary(moduleName, path, configuration)
                    last = result
                    if (!result.success) break
                }
                last?.let { finish(it) } ?: run { busy = false; adding = null }
            }
        }
    }

    private fun start(what: String) {
        busy = true
        error = null
        adding = what
    }

    private fun finish(result: UiAddResult) {
        busy = false
        adding = null
        if (result.success) pane.onDependencyAdded(result) else error = result.message
    }
}

@Composable
internal fun rememberAddDependencyState(
    pane: DependenciesPaneState,
    fileActions: FileActions,
    scope: CoroutineScope = rememberCoroutineScope(),
): AddDependencyState = remember(pane, fileActions, scope) {
    AddDependencyState(pane, fileActions, scope)
}

/** State and intents for the custom-Maven-repository manager. */
@Stable
internal class RepositoriesState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
) {
    var repositories: List<UiRepository> by mutableStateOf(backend.deps.repositories())
        private set
    var name: String by mutableStateOf("")
        private set
    var url: String by mutableStateOf("")
        private set

    /** True when the last add was rejected (the host renders the localized reason). */
    var invalid: Boolean by mutableStateOf(false)
        private set

    fun updateName(value: String) {
        name = value
        invalid = false
    }

    fun updateUrl(value: String) {
        url = value
        invalid = false
    }

    fun add() {
        if (!backend.deps.addRepository(name, url)) {
            invalid = true
            return
        }
        repositories = backend.deps.repositories()
        name = ""
        url = ""
        invalid = false
    }

    fun remove(url: String) {
        if (backend.deps.removeRepository(url)) repositories = backend.deps.repositories()
    }
}

@Composable
internal fun rememberRepositoriesState(
    backend: IdeBackend,
    scope: CoroutineScope = rememberCoroutineScope(),
): RepositoriesState = remember(backend, scope) { RepositoriesState(backend, scope) }

/**
 * State for the per-dependency edit sheet: the editable version / configuration / exclusions, the versions
 * offered by the repositories, and the copies already in the shared download cache.
 */
@Stable
internal class EditDependencyState(
    private val backend: IdeBackend,
    private val moduleName: String,
    val node: UiDependencyNode,
    private val scope: CoroutineScope,
) {
    var versionText: String by mutableStateOf(node.version)
        private set
    var configuration: String by mutableStateOf(node.scope ?: "implementation")
        private set
    var exclusionsText: String by mutableStateOf(node.exclusions.joinToString(", "))
        private set

    var versions: List<String> by mutableStateOf(emptyList())
        private set
    var loadingVersions: Boolean by mutableStateOf(true)
        private set

    /** Versions of this artifact already downloaded to the shared cache, so old ones can be pruned to free
     *  disk. Only meaningful for a Maven artifact (a local jar has no coordinate). */
    var cached: List<UiCachedVersion> by mutableStateOf(emptyList())
        private set

    val showDownloaded: Boolean = node.group.isNotBlank() && node.name.isNotBlank() && !node.local

    /** The newest release the repositories offer, and whether it is ahead of the declared version. The list
     *  is newest-first, so an update exists when the current version is not at the top of it. */
    val newest: String? get() = versions.firstOrNull()
    val updateAvailable: Boolean
        get() = newest != null && node.version in versions && newest != node.version

    val trimmedVersion: String get() = versionText.trim()
    val parsedExclusions: List<String>
        get() = exclusionsText.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    init {
        scope.launch {
            loadingVersions = true
            versions = runCatching { backend.deps.availableVersions(moduleName, node.coordinate) }
                .getOrDefault(emptyList())
            loadingVersions = false
        }
        reloadCached()
    }

    fun updateVersion(value: String) { versionText = value }

    fun selectConfiguration(value: String) { configuration = value }

    fun updateExclusions(value: String) { exclusionsText = value }

    /** Delete one downloaded copy from the shared cache, then re-read what is left. */
    fun deleteCached(version: String) {
        scope.launch {
            backend.deps.deleteCachedVersion(node.group, node.name, version)
            reloadCached()
        }
    }

    private fun reloadCached() {
        scope.launch {
            cached = if (showDownloaded) {
                runCatching { backend.deps.cachedVersions(node.group, node.name) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
    }
}

@Composable
internal fun rememberEditDependencyState(
    backend: IdeBackend,
    moduleName: String,
    node: UiDependencyNode,
    scope: CoroutineScope = rememberCoroutineScope(),
): EditDependencyState = remember(backend, moduleName, node.coordinate, scope) {
    EditDependencyState(backend, moduleName, node, scope)
}
