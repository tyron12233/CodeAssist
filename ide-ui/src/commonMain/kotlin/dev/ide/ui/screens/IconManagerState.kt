package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiIconArtwork
import dev.ide.ui.backend.UiIconEntry
import dev.ide.ui.backend.UiIconImport
import dev.ide.ui.backend.UiIconImportResult
import dev.ide.ui.backend.UiIconRef
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.backend.IconSnippets
import dev.ide.ui.backend.UiIconRepo
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiResourceConfig
import dev.ide.ui.backend.UiResourceIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The Icon Manager's sections: what the project already has, icon libraries, and Compose's own icons. */
enum class IconTab { Project, Library, Compose }

/** What the detail pane is describing. */
sealed interface IconSelection {

    /** An icon from a repository (bundled, remote, or a plugin's). */
    data class FromRepo(val entry: UiIconEntry) : IconSelection

    /** A drawable or mipmap the project already declares, in one particular configuration. */
    data class FromProject(val icon: UiResourceIcon, val config: UiResourceConfig) : IconSelection

    /** An `Icons.*` property from the Compose icons library. */
    data class FromCompose(val entry: UiIconEntry) : IconSelection
}

/**
 * The Icon Manager's state: which section is showing, the icons in it, the artwork cache behind the grid, the
 * selected icon, and the import form.
 *
 * Artwork is fetched per tile as the grid scrolls it into view, because a remote repository has to download
 * each icon. Those fetches share a small semaphore so scrolling a 200-icon grid cannot open 200 connections,
 * and every result is cached for the life of the screen.
 */
@Stable
internal class IconManagerState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
    /** A `res/` directory to preselect, when the screen was opened from a file-tree node. */
    initialResDir: String? = null,
) {

    // --- sections ---

    var tab: IconTab by mutableStateOf(IconTab.Library)
        private set

    var query: String by mutableStateOf("")
        private set

    // --- repositories ---

    var repositories: List<UiIconRepo> by mutableStateOf(emptyList())
        private set

    var selectedRepoId: String? by mutableStateOf(null)
        private set

    var results: List<UiIconEntry> by mutableStateOf(emptyList())
        private set

    var loadingResults: Boolean by mutableStateOf(false)
        private set

    /** Non-null while a network repository's catalogue is downloading. */
    var loadingRepoId: String? by mutableStateOf(null)
        private set

    // --- the project's own icons ---

    var projectIcons: List<UiResourceIcon> by mutableStateOf(emptyList())
        private set

    // --- Compose icons ---

    var composeIcons: List<UiIconEntry> by mutableStateOf(emptyList())
        private set

    /** True once [composeIcons] has been queried, so an empty list can be told apart from "not asked yet". */
    var composeChecked: Boolean by mutableStateOf(false)
        private set

    // --- selection and the import form ---

    var selection: IconSelection? by mutableStateOf(null)
        private set

    var variant: UiIconVariant by mutableStateOf(UiIconVariant())
        private set

    var targets: List<UiIconTarget> by mutableStateOf(emptyList())
        private set

    var target: UiIconTarget? by mutableStateOf(null)
        private set

    var resType: String by mutableStateOf("drawable")
        private set

    var resourceName: String by mutableStateOf("")
        private set

    var sizeDp: Int by mutableStateOf(24)
        private set

    /** The colour to repaint an imported icon, or null to keep the artwork's own. */
    var tint: Long? by mutableStateOf(null)
        private set

    var importing: Boolean by mutableStateOf(false)
        private set

    /** The existing resource an import would replace, set when the user has to confirm. */
    var conflictPath: String? by mutableStateOf(null)
        private set

    /** A transient confirmation or failure line under the detail pane. */
    var message: String? by mutableStateOf(null)
        private set

    var warnings: List<String> by mutableStateOf(emptyList())
        private set

    // --- artwork cache ---

    private val artwork = mutableStateMapOf<String, UiDrawable>()
    private val rasters = mutableStateMapOf<String, ByteArray>()
    private val inFlight = HashSet<String>()
    private val fetchLimit = Semaphore(FETCH_CONCURRENCY)
    private var searchJob: Job? = null

    init {
        targets = backend.icons.importTargets()
        target = initialResDir
            ?.let { dir -> targets.firstOrNull { it.resDirPath == dir } }
            ?: targets.firstOrNull()
        repositories = backend.icons.iconRepositories()
        selectedRepoId = repositories.firstOrNull { !it.requiresNetwork }?.id ?: repositories.firstOrNull()?.id
        // A file-tree entry point means the user is already thinking about a res/ directory, so open there.
        if (initialResDir != null) tab = IconTab.Project
        refreshSection()
    }

    // --- intents ---

    fun selectTab(next: IconTab) {
        if (tab == next) return
        tab = next
        clearSelection()
        refreshSection()
    }

    fun updateQuery(value: String) {
        query = value
        // Debounced: typing in a 4000-icon repository would otherwise re-rank on every keystroke.
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            searchRepo()
        }
    }

    fun selectRepo(id: String) {
        if (selectedRepoId == id) return
        selectedRepoId = id
        clearSelection()
        searchRepo()
    }

    /** Download the selected repository's catalogue (only ever called from an explicit action). */
    fun loadRepo(id: String) {
        if (loadingRepoId != null) return
        loadingRepoId = id
        scope.launch {
            val result = backend.icons.loadRepository(id)
            loadingRepoId = null
            repositories = backend.icons.iconRepositories()
            message = if (result.ok) null else result.message
            if (result.ok) searchRepo()
        }
    }

    fun select(selection: IconSelection) {
        // An icon may not ship in the style that happens to be selected (a module might only have the filled
        // Compose family, say), so snap to one it does have rather than rendering or inserting the wrong one.
        val styles = when (selection) {
            is IconSelection.FromRepo -> selection.entry.styles
            is IconSelection.FromCompose -> selection.entry.styles
            is IconSelection.FromProject -> emptyList()
        }
        if (styles.isNotEmpty() && variant.style !in styles) {
            variant = variant.copy(style = styles.first())
        }
        this.selection = selection
        message = null
        conflictPath = null
        warnings = artworkWarnings.remove(keyOf(selection)) ?: emptyList()
        resourceName = suggestedName(selection)
        resType = when (selection) {
            is IconSelection.FromProject -> selection.icon.resType
            else -> "drawable"
        }
    }

    fun clearSelection() {
        selection = null
        conflictPath = null
        message = null
        warnings = emptyList()
    }

    fun selectVariant(next: UiIconVariant) {
        if (variant == next) return
        variant = next
        // The grid and the detail pane both key on the variant, so nothing needs invalidating: the new keys
        // simply are not cached yet and load on demand.
    }

    fun selectTarget(next: UiIconTarget) {
        target = next
        conflictPath = null
    }

    fun updateResourceName(value: String) {
        resourceName = value.trim().lowercase().replace(' ', '_').filter { it.isLetterOrDigit() || it == '_' }
        conflictPath = null
    }

    fun updateResType(value: String) {
        resType = value
        conflictPath = null
    }

    fun updateSize(value: Int) {
        sizeDp = value.coerceIn(8, 512)
    }

    fun updateTint(value: Long?) {
        tint = value
    }

    /** Surface [text] to the user (the screen shows it as a snackbar and then clears it). */
    fun showMessage(text: String) {
        message = text
    }

    fun dismissMessage() {
        message = null
    }

    fun dismissConflict() {
        conflictPath = null
    }

    // --- artwork ---

    /** A stable cache key for an icon plus the variant it is being shown in. */
    fun keyOf(selection: IconSelection): String = when (selection) {
        is IconSelection.FromRepo -> repoKey(selection.entry.repoId, selection.entry.name)
        is IconSelection.FromProject -> "res:${selection.config.path}"
        is IconSelection.FromCompose -> "compose:${selection.entry.name}:${variant.style}:${variant.filled}"
    }

    fun repoKey(repoId: String, name: String): String = "$repoId:$name:${variant.style}:${variant.filled}"

    fun artworkFor(key: String): UiDrawable? = artwork[key]

    fun rasterFor(path: String): ByteArray? = rasters["raster:$path"]

    private val artworkWarnings = HashMap<String, List<String>>()

    /** Load a repository icon's geometry if it isn't cached or already being fetched. */
    fun ensureRepoArtwork(repoId: String, name: String) {
        val key = repoKey(repoId, name)
        load(key) { backend.icons.iconArtwork(repoId, name, variant) }
    }

    /** Load a project resource's geometry (XML) or its bytes (a raster). */
    fun ensureResourceArtwork(config: UiResourceConfig) {
        if (config.isRaster) {
            val key = "raster:${config.path}"
            if (rasters.containsKey(key) || !inFlight.add(key)) return
            scope.launch {
                try {
                    fetchLimit.withPermit { backend.icons.resourceBytes(config.path) }?.let { rasters[key] = it }
                } finally {
                    inFlight.remove(key)
                }
            }
            return
        }
        load("res:${config.path}") { backend.icons.resourceArtwork(config.path) }
    }

    fun ensureComposeArtwork(name: String) {
        val key = "compose:$name:${variant.style}:${variant.filled}"
        load(key) { backend.icons.composeIconArtwork(name, variant) }
    }

    private fun load(key: String, fetch: suspend () -> UiIconArtwork?) {
        if (artwork.containsKey(key) || !inFlight.add(key)) return
        scope.launch {
            try {
                val result = fetchLimit.withPermit { runCatching { fetch() }.getOrNull() }
                if (result != null) {
                    artwork[key] = result.drawable
                    if (result.warnings.isNotEmpty()) artworkWarnings[key] = result.warnings
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    // --- importing ---

    /** Import the selected icon into the chosen target. [replace] confirms overwriting a reported conflict. */
    fun import(replace: Boolean = false, onDone: (String) -> Unit = {}) {
        val selected = selection ?: return
        val destination = target ?: run {
            message = "This project has no res/ directory to import into"
            return
        }
        if (importing) return
        importing = true
        conflictPath = null
        scope.launch {
            val request = UiIconImport(
                target = destination,
                resType = resType,
                name = resourceName,
                sizeDp = sizeDp.toFloat(),
                colorArgb = tint,
                overwrite = replace,
            )
            val result = when (selected) {
                is IconSelection.FromRepo ->
                    backend.icons.importIcon(selected.entry.repoId, selected.entry.name, variant, request)
                // A project icon is already a resource; "importing" it means copying it to another target.
                is IconSelection.FromProject -> copyProjectIcon(selected, request)
                is IconSelection.FromCompose -> importComposeIcon(selected, request)
            }
            importing = false
            warnings = result.warnings
            when {
                result.ok -> {
                    message = null
                    refreshSection()
                    result.path?.let(onDone)
                }

                result.conflictPath != null -> conflictPath = result.conflictPath
                else -> message = result.message
            }
        }
    }

    private suspend fun copyProjectIcon(selected: IconSelection.FromProject, request: UiIconImport) =
        backend.icons.copyResource(selected.config.path, request)

    /**
     * Add a Compose icon to `res/` as a vector drawable, which is what a View-based layout in the same project
     * needs. The Compose libraries and the icon repositories name and split the same Material artwork
     * differently: `ShoppingCart` in the `filled` package is `shopping_cart` with the fill flag set, so the
     * request has to be translated rather than passed through, or the wrong variant would be written.
     */
    private suspend fun importComposeIcon(
        selected: IconSelection.FromCompose,
        request: UiIconImport,
    ): UiIconImportResult {
        val repo = repositories.firstOrNull { !it.requiresNetwork && it.loaded }
            ?: repositories.firstOrNull { it.loaded }
            ?: return UiIconImportResult(
                ok = false,
                message = "No icon library is loaded to take the artwork from",
            )
        return backend.icons.importIcon(
            repoId = repo.id,
            name = snakeCase(selected.entry.name),
            variant = composeVariantForRepository(),
            request = request,
        )
    }

    /** The repository variant matching the selected Compose style: `filled` is the fill flag, not a family. */
    private fun composeVariantForRepository(): UiIconVariant = when (variant.style) {
        "filled" -> UiIconVariant(style = "outlined", filled = true)
        else -> variant.copy(filled = false)
    }

    /**
     * Import the SVG (or vector XML) at [path], which the host picked from storage. The file is converted and
     * written in one step, using the form's current name, size and colour: an SVG has no entry in any
     * repository to select first, so there is nothing to preview it as.
     */
    fun importSvgFile(path: String) {
        val destination = target ?: run {
            message = "This project has no res/ directory to import into"
            return
        }
        if (importing) return
        importing = true
        scope.launch {
            val text = runCatching { backend.files.readFile(path) }.getOrNull()
            if (text.isNullOrBlank()) {
                importing = false
                message = "Could not read that file"
                return@launch
            }
            val suggested = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            val request = UiIconImport(
                target = destination,
                resType = resType,
                name = resourceName.ifBlank { "ic_" + suggested.lowercase().filter { it.isLetterOrDigit() || it == '_' } },
                sizeDp = sizeDp.toFloat(),
                colorArgb = tint,
            )
            val result = backend.icons.importSvg(text, request)
            importing = false
            warnings = result.warnings
            when {
                result.ok -> {
                    message = null
                    refreshSection()
                }

                result.conflictPath != null -> conflictPath = result.conflictPath
                else -> message = result.message
            }
        }
    }

    /**
     * What the selected icon is, as a reference the languages can each render their own way. A library icon
     * becomes a *resource* reference, because that is what it will be once it is in the project.
     */
    fun selectedRef(): UiIconRef? = when (val selected = selection) {
        null -> null
        is IconSelection.FromProject -> UiIconRef.Resource(selected.icon.resType, selected.icon.name)
        is IconSelection.FromRepo -> UiIconRef.Resource(resType, resourceName.ifBlank { selected.entry.name })
        is IconSelection.FromCompose -> UiIconRef.ComposeIcon(selected.entry.name, variant.style)
    }

    /** The short reference the insert/copy actions would write into [target], or null when it has no form there. */
    fun referenceFor(target: UiInsertionTarget?): String? {
        val ref = selectedRef() ?: return null
        val language = target?.language ?: IconSnippets.XML
        if (!IconSnippets.supports(ref, language)) return null
        return IconSnippets.reference(ref, language)
    }

    /** The exact text an insertion would place at the caret, for the clipboard when there is no editor tab. */
    fun snippetFor(target: UiInsertionTarget?): String? {
        val ref = selectedRef() ?: return null
        if (target == null) return referenceFor(null)
        return IconSnippets.snippet(ref, target)
    }

    /** `@drawable/x`, for the copy-a-resource-reference action. Null for a Compose icon, which is not one. */
    fun resourceReference(): String? = (selectedRef() as? UiIconRef.Resource)?.let { "@${it.resType}/${it.name}" }

    /**
     * Resolve the selection into a reference that will actually resolve, then hand it to [onReady].
     *
     * A library icon is not in the project yet, so referencing it from source would dangle: this imports it
     * first (unless a resource of that name is already there) and only then reports the reference. A project
     * icon and a Compose icon both already exist, so they go straight through.
     */
    fun prepareInsertion(onReady: (UiIconRef) -> Unit) {
        val selected = selection ?: return
        val ref = selectedRef() ?: return
        if (selected !is IconSelection.FromRepo) {
            onReady(ref)
            return
        }
        val destination = target
        if (destination == null) {
            message = "This project has no res/ directory to import into"
            return
        }
        if (backend.icons.existingResource(destination, resType, resourceName) != null) {
            onReady(ref)
            return
        }
        // Import, then insert what was just written.
        import(onDone = { onReady(ref) })
    }

    // --- loading ---

    private fun refreshSection() {
        when (tab) {
            IconTab.Library -> searchRepo()
            IconTab.Project -> scope.launch {
                loadingResults = true
                projectIcons = runCatching { backend.icons.projectIcons() }.getOrDefault(emptyList())
                loadingResults = false
            }

            IconTab.Compose -> scope.launch {
                loadingResults = true
                composeIcons = runCatching { backend.icons.composeIcons() }.getOrDefault(emptyList())
                composeChecked = true
                loadingResults = false
            }
        }
    }

    private fun searchRepo() {
        val repoId = selectedRepoId ?: return
        scope.launch {
            loadingResults = true
            results = runCatching { backend.icons.searchIcons(repoId, query, SEARCH_LIMIT) }.getOrDefault(emptyList())
            loadingResults = false
        }
    }

    /** The project icons matching the current query (filtered here: the catalogue is small and already local). */
    fun filteredProjectIcons(): List<UiResourceIcon> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return projectIcons
        return projectIcons.filter { it.name.contains(q) || it.moduleName.lowercase().contains(q) }
    }

    fun filteredComposeIcons(): List<UiIconEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return composeIcons
        return composeIcons.filter { it.name.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
    }

    /** The repository being browsed, or null when none is registered. */
    fun selectedRepo(): UiIconRepo? = repositories.firstOrNull { it.id == selectedRepoId }

    private fun suggestedName(selection: IconSelection): String = when (selection) {
        is IconSelection.FromRepo -> "ic_${selection.entry.name}"
        // Compose icons are named as properties (`ShoppingCart`); a resource name has to be snake_case.
        is IconSelection.FromCompose -> "ic_" + snakeCase(selection.entry.name)
        is IconSelection.FromProject -> selection.icon.name
    }

    private companion object {
        const val SEARCH_LIMIT = 300
        const val SEARCH_DEBOUNCE_MS = 180L

        /** Enough parallelism to keep a scrolling grid filling in, few enough to be polite to a remote host. */
        const val FETCH_CONCURRENCY = 6

        /** `ShoppingCart` is `shopping_cart` as a resource name. */
        fun snakeCase(name: String): String = buildString {
            for ((index, ch) in name.withIndex()) {
                if (index > 0 && ch.isUpperCase() && !name[index - 1].isUpperCase()) append('_')
                append(ch.lowercaseChar())
            }
        }
    }
}

@Composable
internal fun rememberIconManagerState(
    backend: IdeBackend,
    initialResDir: String? = null,
    scope: CoroutineScope = rememberCoroutineScope(),
): IconManagerState = remember(backend, initialResDir, scope) {
    IconManagerState(backend, scope, initialResDir)
}
