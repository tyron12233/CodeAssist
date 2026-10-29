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
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiIconRef
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.backend.IconSnippets
import dev.ide.ui.backend.UiIconRepo
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiNavTarget
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

    // --- batch selection ---

    /** Icons ticked for a batch import, in the order they were ticked. Empty outside batch selection. */
    var checked: List<IconSelection> by mutableStateOf(emptyList())
        private set

    /** What the last batch import did, for the screen to report; cleared once shown. */
    var batchOutcome: BatchOutcome? by mutableStateOf(null)
        private set

    // --- searching every section ---

    /** Matches for the current query in each repository other than the selected one (empty with no query). */
    var otherRepoMatches: Map<String, Int> by mutableStateOf(emptyMap())
        private set

    private var cataloguesRequested = false
    private var autoLoadRequested = false

    // --- the selected project icon ---

    /** Where the selected project icon is used; null while it is being looked up (or for any other icon). */
    var usages: List<UiNavTarget>? by mutableStateOf(null)
        private set

    private var usagesJob: Job? = null

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
        clearChecked()
        refreshSection()
    }

    fun updateQuery(value: String) {
        query = value
        // Debounced: typing in a 4000-icon repository would otherwise re-rank on every keystroke.
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            if (value.isNotBlank()) {
                ensureCatalogues()
                autoLoadNetworkRepos()
            }
            searchRepo()
        }
    }

    fun selectRepo(id: String) {
        if (selectedRepoId == id) return
        selectedRepoId = id
        clearSelection()
        // Picking a set that still has to download is the request to download it.
        val repo = selectedRepo()
        if (repo != null && repo.requiresNetwork && !repo.loaded) loadRepo(id) else searchRepo()
    }

    /** Download the selected repository's catalogue (only ever called from an explicit action). */
    fun loadRepo(id: String, quietly: Boolean = false) {
        if (loadingRepoId != null) return
        loadingRepoId = id
        scope.launch {
            val result = runCatching { backend.icons.loadRepository(id) }.getOrElse { null }
            loadingRepoId = null
            repositories = backend.icons.iconRepositories()
            // A download nobody asked for (the first search) fails silently: the bundled set still answers.
            if (!quietly) message = if (result?.ok == true) null else result?.message
            if (result?.ok == true) searchRepo()
        }
    }

    /** The first search downloads the network sets in the background, so their matches can be offered. */
    private fun autoLoadNetworkRepos() {
        if (autoLoadRequested) return
        autoLoadRequested = true
        repositories.firstOrNull { it.requiresNetwork && !it.loaded }?.let { loadRepo(it.id, quietly = true) }
    }

    /** A query searches every section, so the sections not on screen need their catalogues too. */
    private fun ensureCatalogues() {
        if (cataloguesRequested) return
        cataloguesRequested = true
        if (projectIcons.isEmpty()) scope.launch {
            projectIcons = runCatching { backend.icons.projectIcons() }.getOrDefault(emptyList())
        }
        if (!composeChecked) scope.launch {
            composeIcons = runCatching { backend.icons.composeIcons() }.getOrDefault(emptyList())
            composeChecked = true
        }
    }

    /** How many icons match the query in [section], or null with no query (or before its catalogue loaded). */
    fun matchesIn(section: IconTab): Int? {
        if (query.isBlank()) return null
        return when (section) {
            IconTab.Library -> results.size
            IconTab.Project -> filteredProjectIcons().size
            IconTab.Compose -> if (composeChecked) filteredComposeIcons().size else null
        }
    }

    /**
     * The style families the grid can be switched between: those the icons in the current section ship in, in
     * a fixed order. One family or none means there is nothing to switch.
     */
    fun availableStyles(): List<String> {
        val entries = when (tab) {
            IconTab.Library -> results
            IconTab.Compose -> composeIcons
            IconTab.Project -> return emptyList()
        }
        val styles = entries.asSequence().take(STYLE_SAMPLE).flatMap { it.styles }.toSet()
        return styles.sortedWith(compareBy({ STYLE_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
    }

    /** True when some icon in the current section has a filled variant of its style. */
    fun sectionSupportsFill(): Boolean = when (tab) {
        IconTab.Library -> results.asSequence().take(STYLE_SAMPLE).any { it.supportsFill }
        IconTab.Compose -> composeIcons.asSequence().take(STYLE_SAMPLE).any { it.supportsFill }
        IconTab.Project -> false
    }

    /** The style the toolbar shows as chosen: the selected one when the section has it, else its first. */
    fun effectiveStyle(): String? {
        val styles = availableStyles()
        return if (variant.style in styles) variant.style else styles.firstOrNull()
    }

    /**
     * The variant [entry] is shown and written in: the toolbar's style when the icon ships in it (else its
     * first), and filled only when it has a filled form.
     */
    fun variantFor(entry: UiIconEntry): UiIconVariant {
        val style = if (entry.styles.isEmpty() || variant.style in entry.styles) variant.style else entry.styles.first()
        return UiIconVariant(style = style, filled = variant.filled && entry.supportsFill)
    }

    fun select(selection: IconSelection) {
        // The style toolbar sets the style for the whole grid; an icon that does not ship in it is shown and
        // written in one it does have (see [variantFor]), so the toolbar is not moved by a tap.
        this.selection = selection
        message = null
        conflictPath = null
        warnings = artworkWarnings.remove(keyOf(selection)) ?: emptyList()
        resourceName = suggestedName(selection)
        resType = when (selection) {
            is IconSelection.FromProject -> selection.icon.resType
            else -> "drawable"
        }
        usagesJob?.cancel()
        usages = null
        if (selection is IconSelection.FromProject) {
            usagesJob = scope.launch {
                usages = runCatching { backend.icons.iconUsages(selection.icon.resType, selection.icon.name) }
                    .getOrDefault(emptyList())
            }
        }
    }

    fun clearSelection() {
        selection = null
        conflictPath = null
        message = null
        warnings = emptyList()
        usagesJob?.cancel()
        usages = null
    }

    // --- batch selection ---

    /**
     * Tick or untick [item] for a batch import. Only icons that are not in the project yet can be batched; a
     * project icon is already there. Ticking closes the single-icon sheet, which the batch bar replaces.
     */
    fun toggleChecked(item: IconSelection) {
        if (item is IconSelection.FromProject) return
        val id = identityOf(item)
        checked = if (checked.any { identityOf(it) == id }) checked.filterNot { identityOf(it) == id } else checked + item
        if (checked.isNotEmpty()) clearSelection()
    }

    fun isChecked(item: IconSelection): Boolean {
        if (checked.isEmpty()) return false
        val id = identityOf(item)
        return checked.any { identityOf(it) == id }
    }

    fun clearChecked() {
        checked = emptyList()
    }

    fun dismissBatchOutcome() {
        batchOutcome = null
    }

    /**
     * Add every ticked icon to the project with the form's folder, size, colour and target, each under its
     * suggested name. A name that is already taken is skipped rather than overwritten: a batch is no place to
     * confirm replacements one at a time.
     */
    fun importChecked() {
        val items = checked
        if (items.isEmpty() || importing) return
        val destination = target ?: run {
            message = "This project has no res/ directory to import into"
            return
        }
        importing = true
        scope.launch {
            var added = 0
            var existing = 0
            val failed = ArrayList<String>()
            for (item in items) {
                val request = UiIconImport(
                    target = destination,
                    resType = resType,
                    name = suggestedName(item),
                    sizeDp = sizeDp.toFloat(),
                    colorArgb = tint,
                )
                val result = runCatching { importOne(item, request) }
                    .getOrElse { UiIconImportResult(ok = false, message = it.message) }
                when {
                    result.ok -> added++
                    result.conflictPath != null -> existing++
                    else -> failed += suggestedName(item)
                }
            }
            importing = false
            checked = emptyList()
            batchOutcome = BatchOutcome(added, existing, failed)
            if (tab == IconTab.Project) refreshSection()
        }
    }

    // --- project icon actions ---

    /**
     * Rename the selected project icon to [newName]: its file in every qualifier folder and every reference to
     * it. [onDone] hears whether it worked and, when it did, where each of its files went (old path to new),
     * so open editor tabs can follow; a failure is also reported through [message].
     */
    fun renameSelected(newName: String, onDone: (Boolean, Map<String, String>) -> Unit = { _, _ -> }) {
        val selected = selection as? IconSelection.FromProject ?: return
        val name = newName.trim()
        if (name.isEmpty() || name == selected.icon.name) return onDone(false, emptyMap())
        scope.launch {
            val result = runCatching { backend.icons.renameIcon(selected.icon.resType, selected.icon.name, name) }
                .getOrElse { null }
            if (result?.success == true) {
                clearSelection()
                refreshSection()
                message = result.message
                onDone(true, selected.icon.configurations.associate { it.path to renamedPath(it.path, name) })
            } else {
                message = result?.message ?: "Could not rename ${selected.icon.name}"
                onDone(false, emptyMap())
            }
        }
    }

    /** Delete the selected project icon: every configuration of it, in every qualifier folder. */
    fun deleteSelected(onDone: () -> Unit = {}) {
        val selected = selection as? IconSelection.FromProject ?: return
        scope.launch {
            val failed = selected.icon.configurations.filterNot { config ->
                runCatching { backend.files.deletePath(config.path) }.getOrDefault(false)
            }
            clearSelection()
            refreshSection()
            if (failed.isNotEmpty()) message = "Could not delete ${failed.joinToString { it.path.substringAfterLast('/') }}"
            onDone()
        }
    }

    /** [path] with its file's base name replaced by [name], extension kept: where a resource rename moves it. */
    private fun renamedPath(path: String, name: String): String {
        val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        val file = path.substring(cut + 1)
        val extension = file.substringAfter('.', "")
        return path.substring(0, cut + 1) + if (extension.isEmpty()) name else "$name.$extension"
    }

    /**
     * The app-icon layer [item] becomes when it is used as the launcher icon's foreground, or null when it
     * cannot be one. A Compose icon has no artwork of its own here, so it is taken from a loaded repository
     * under that repository's naming, the same translation adding it to `res/` makes.
     */
    fun appIconLayerFor(item: IconSelection): UiIconLayer? = when (item) {
        is IconSelection.FromRepo -> UiIconLayer.RepoIcon(item.entry.repoId, item.entry.name, variantFor(item.entry))
        is IconSelection.FromProject -> UiIconLayer.Resource(item.config.path)
        is IconSelection.FromCompose -> artworkRepository()?.let { repo ->
            UiIconLayer.RepoIcon(repo.id, snakeCase(item.entry.name), composeVariantForRepository(item.entry))
        }
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
        is IconSelection.FromRepo -> entryKey(selection.entry)
        is IconSelection.FromProject -> "res:${selection.config.path}"
        is IconSelection.FromCompose -> composeKey(selection.entry)
    }

    fun repoKey(repoId: String, name: String): String = "$repoId:$name:${variant.style}:${variant.filled}"

    /** [entry]'s cache key in the variant it is actually shown in (see [variantFor]). */
    fun entryKey(entry: UiIconEntry): String = variantFor(entry).let { "${entry.repoId}:${entry.name}:${it.style}:${it.filled}" }

    fun composeKey(entry: UiIconEntry): String = variantFor(entry).let { "compose:${entry.name}:${it.style}:${it.filled}" }

    fun artworkFor(key: String): UiDrawable? = artwork[key]

    fun rasterFor(path: String): ByteArray? = rasters["raster:$path"]

    private val artworkWarnings = HashMap<String, List<String>>()

    /** Load a repository icon's geometry if it isn't cached or already being fetched. */
    fun ensureRepoArtwork(repoId: String, name: String) {
        val key = repoKey(repoId, name)
        load(key) { backend.icons.iconArtwork(repoId, name, variant) }
    }

    /** Load [entry]'s geometry in the variant it is shown in. */
    fun ensureRepoArtwork(entry: UiIconEntry) {
        val shown = variantFor(entry)
        load(entryKey(entry)) { backend.icons.iconArtwork(entry.repoId, entry.name, shown) }
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

    fun ensureComposeArtwork(entry: UiIconEntry) {
        val shown = variantFor(entry)
        load(composeKey(entry)) { backend.icons.composeIconArtwork(entry.name, shown) }
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
            val result = importOne(selected, request)
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

    private suspend fun importOne(selected: IconSelection, request: UiIconImport): UiIconImportResult = when (selected) {
        is IconSelection.FromRepo ->
            backend.icons.importIcon(selected.entry.repoId, selected.entry.name, variantFor(selected.entry), request)
        // A project icon is already a resource; "importing" it means copying it to another target.
        is IconSelection.FromProject -> backend.icons.copyResource(selected.config.path, request)
        is IconSelection.FromCompose -> importComposeIcon(selected, request)
    }

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
        val repo = artworkRepository()
            ?: return UiIconImportResult(
                ok = false,
                message = "No icon library is loaded to take the artwork from",
            )
        return backend.icons.importIcon(
            repoId = repo.id,
            name = snakeCase(selected.entry.name),
            variant = composeVariantForRepository(selected.entry),
            request = request,
        )
    }

    /** The repository a Compose icon's artwork is taken from: an offline one when there is one. */
    private fun artworkRepository(): UiIconRepo? =
        repositories.firstOrNull { !it.requiresNetwork && it.loaded } ?: repositories.firstOrNull { it.loaded }

    /** The repository variant matching [entry]'s Compose style: `filled` is the fill flag, not a family. */
    private fun composeVariantForRepository(entry: UiIconEntry): UiIconVariant {
        val shown = variantFor(entry)
        return when (shown.style) {
            "filled" -> UiIconVariant(style = "outlined", filled = true)
            else -> shown.copy(filled = false)
        }
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
        is IconSelection.FromCompose -> UiIconRef.ComposeIcon(selected.entry.name, variantFor(selected.entry).style)
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
            // Uncapped: the grid is lazy and fetches artwork only for the tiles on screen, so listing every
            // icon costs one small entry each. A cap here hid most of a 4000-icon repository with no way to
            // scroll to the rest, while the header still reported the full count.
            results = runCatching { backend.icons.searchIcons(repoId, query, Int.MAX_VALUE) }.getOrDefault(emptyList())
            loadingResults = false
            // The other sets' matches label their chips, so a set the selected one has nothing in still shows
            // where the icon is.
            val q = query
            otherRepoMatches = if (q.isBlank()) emptyMap() else repositories
                .filter { it.id != repoId && it.loaded }
                .associate { repo ->
                    repo.id to runCatching { backend.icons.searchIcons(repo.id, q, Int.MAX_VALUE).size }.getOrDefault(0)
                }
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

    /** An icon's identity regardless of the variant it is shown in, for ticking it in and out of a batch. */
    private fun identityOf(item: IconSelection): String = when (item) {
        is IconSelection.FromRepo -> "repo:${item.entry.repoId}:${item.entry.name}"
        is IconSelection.FromCompose -> "compose:${item.entry.name}"
        is IconSelection.FromProject -> "res:${item.config.path}"
    }

    private fun suggestedName(selection: IconSelection): String = when (selection) {
        is IconSelection.FromRepo -> "ic_${selection.entry.name}"
        // Compose icons are named as properties (`ShoppingCart`); a resource name has to be snake_case.
        is IconSelection.FromCompose -> "ic_" + snakeCase(selection.entry.name)
        is IconSelection.FromProject -> selection.icon.name
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L

        /** Enough parallelism to keep a scrolling grid filling in, few enough to be polite to a remote host. */
        const val FETCH_CONCURRENCY = 6

        /** How many entries are sampled to learn a section's styles: every set is uniform well within this. */
        const val STYLE_SAMPLE = 400

        /** The order style families are offered in; anything else follows, alphabetically. */
        val STYLE_ORDER = listOf("outlined", "filled", "rounded", "sharp", "twotone")

        /** `ShoppingCart` is `shopping_cart` as a resource name. */
        fun snakeCase(name: String): String = buildString {
            for ((index, ch) in name.withIndex()) {
                if (index > 0 && ch.isUpperCase() && !name[index - 1].isUpperCase()) append('_')
                append(ch.lowercaseChar())
            }
        }
    }
}

/** What a batch import did: how many were added, how many names were already taken, and which failed. */
data class BatchOutcome(val added: Int, val existing: Int, val failed: List<String>)

@Composable
internal fun rememberIconManagerState(
    backend: IdeBackend,
    initialResDir: String? = null,
    scope: CoroutineScope = rememberCoroutineScope(),
): IconManagerState = remember(backend, initialResDir, scope) {
    IconManagerState(backend, scope, initialResDir)
}
