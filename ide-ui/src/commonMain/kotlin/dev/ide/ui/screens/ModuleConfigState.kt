package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiBuildFeatures
import dev.ide.ui.backend.UiCompilerPlugins
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiMissingProguardFile
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiPackagingOptions
import dev.ide.ui.backend.UiPackagingRules
import dev.ide.ui.backend.UiSigningAssignments
import dev.ide.ui.backend.UiSourceRootRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a module-configuration pane reports after an operation. The backend-provided [Message] carries its own
 * text; the others name what happened so the host renders the localized string.
 */
internal sealed interface ConfigToast {
    val error: Boolean

    data class Message(val text: String, override val error: Boolean) : ConfigToast
    data class Removed(val name: String) : ConfigToast { override val error: Boolean get() = false }
    data class Created(val name: String) : ConfigToast { override val error: Boolean get() = false }
    data class CreateFailed(val name: String) : ConfigToast { override val error: Boolean get() = true }
}

/** How long a result toast stays up before it dismisses itself. */
private const val TOAST_MILLIS = 2600L

/** Shared toast plumbing for the module-configuration panes: one toast at a time, self-dismissing. */
@Stable
internal abstract class ModulePaneState(protected val scope: CoroutineScope) {
    var toast: ConfigToast? by mutableStateOf(null)
        private set

    private var toastJob: Job? = null

    protected fun showToast(value: ConfigToast) {
        toast = value
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(TOAST_MILLIS)
            toast = null
        }
    }

    /** Toast a backend result, returning whether it succeeded (so callers can reload on success). */
    protected fun report(result: UiConfigResult): Boolean {
        showToast(ConfigToast.Message(result.message, error = !result.success))
        return result.success
    }
}

/** State and intents for the module list: the modules themselves plus the create/remove flows. */
@Stable
internal class ModulesListState(
    private val backend: IdeBackend,
    scope: CoroutineScope,
) : ModulePaneState(scope) {
    var modules: List<UiModuleRef> by mutableStateOf(backend.modules.configurableModules())
        private set
    var newModuleOpen: Boolean by mutableStateOf(false)
        private set

    /** The module awaiting remove confirmation. */
    var pendingRemove: String? by mutableStateOf(null)
        private set

    fun openNewModule() { newModuleOpen = true }

    fun closeNewModule() { newModuleOpen = false }

    fun askRemove(name: String) { pendingRemove = name }

    fun cancelRemove() { pendingRemove = null }

    fun createModule(
        name: String,
        typeId: String,
        languageLevel: String?,
        facetValues: Map<String, Map<String, Any?>>,
    ) {
        scope.launch {
            val result = backend.modules.createModule(name, typeId, languageLevel, facetValues)
            if (report(result)) {
                newModuleOpen = false
                reload()
            }
        }
    }

    fun confirmRemove() {
        val name = pendingRemove
        if (name != null && backend.modules.removeModule(name)) {
            showToast(ConfigToast.Removed(name))
            reload()
        }
        pendingRemove = null
    }

    private fun reload() {
        modules = backend.modules.configurableModules()
    }
}

@Composable
internal fun rememberModulesListState(
    backend: IdeBackend,
    scope: CoroutineScope = rememberCoroutineScope(),
): ModulesListState = remember(backend, scope) { ModulesListState(backend, scope) }

/**
 * State for a pane that lists switchable entries loaded as one model (build features, compiler plugins):
 * load once, flip one entry at a time, re-read on success. [T] is the loaded model; [load] returns null when
 * the pane does not apply to the module (a non-Android module), which the host renders as its empty state.
 */
@Stable
internal class ModuleTogglesState<T>(
    scope: CoroutineScope,
    private val load: suspend () -> T?,
    private val toggle: suspend (id: String, enabled: Boolean) -> UiConfigResult,
) : ModulePaneState(scope) {
    var model: T? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(true)
        private set

    /** The id of the entry currently toggling, or null when idle (only one flips at a time). */
    var busyId: String? by mutableStateOf(null)
        private set

    val idle: Boolean get() = busyId == null

    init {
        reload()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (busyId != null) return
        busyId = id
        scope.launch {
            val result = toggle(id, enabled)
            busyId = null
            if (report(result)) reload()
        }
    }

    private fun reload() {
        scope.launch {
            loading = true
            model = runCatching { load() }.getOrNull()
            loading = false
        }
    }
}

@Composable
internal fun rememberBuildFeaturesState(
    backend: IdeBackend,
    moduleName: String,
    scope: CoroutineScope = rememberCoroutineScope(),
): ModuleTogglesState<UiBuildFeatures> = remember(backend, moduleName, scope) {
    ModuleTogglesState(
        scope,
        load = { backend.modules.getBuildFeatures(moduleName) },
        toggle = { id, enabled -> backend.modules.setBuildFeature(moduleName, id, enabled) },
    )
}

@Composable
internal fun rememberCompilerPluginsState(
    backend: IdeBackend,
    moduleName: String,
    scope: CoroutineScope = rememberCoroutineScope(),
): ModuleTogglesState<UiCompilerPlugins> = remember(backend, moduleName, scope) {
    ModuleTogglesState(
        scope,
        load = { backend.modules.getCompilerPlugins(moduleName) },
        toggle = { id, enabled -> backend.modules.setCompilerPlugin(moduleName, id, enabled) },
    )
}

/**
 * State for the Android packaging pane: the loaded options plus editable copies of every merge-rule list,
 * re-seeded whenever the options are re-read.
 */
@Stable
internal class PackagingPaneState(
    private val backend: IdeBackend,
    private val moduleName: String,
    scope: CoroutineScope,
) : ModulePaneState(scope) {
    var options: UiPackagingOptions? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(true)
        private set
    var saving: Boolean by mutableStateOf(false)
        private set

    val resourceExcludes: SnapshotStateList<String> = mutableStateListOf()
    val resourcePickFirsts: SnapshotStateList<String> = mutableStateListOf()
    val resourceMerges: SnapshotStateList<String> = mutableStateListOf()
    val jniExcludes: SnapshotStateList<String> = mutableStateListOf()
    val jniPickFirsts: SnapshotStateList<String> = mutableStateListOf()

    init {
        reload()
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val result = backend.modules.updatePackagingOptions(
                moduleName,
                UiPackagingRules(resourceExcludes.toList(), resourcePickFirsts.toList(), resourceMerges.toList()),
                UiPackagingRules(jniExcludes.toList(), jniPickFirsts.toList()),
            )
            saving = false
            if (report(result)) reload()
        }
    }

    private fun reload() {
        scope.launch {
            loading = true
            val loaded = runCatching { backend.modules.getPackagingOptions(moduleName) }.getOrNull()
            options = loaded
            if (loaded != null) {
                resourceExcludes.reset(loaded.resources.excludes)
                resourcePickFirsts.reset(loaded.resources.pickFirsts)
                resourceMerges.reset(loaded.resources.merges)
                jniExcludes.reset(loaded.jniLibs.excludes)
                jniPickFirsts.reset(loaded.jniLibs.pickFirsts)
            }
            loading = false
        }
    }

    private fun SnapshotStateList<String>.reset(values: List<String>) {
        clear()
        addAll(values)
    }
}

@Composable
internal fun rememberPackagingPaneState(
    backend: IdeBackend,
    moduleName: String,
    scope: CoroutineScope = rememberCoroutineScope(),
): PackagingPaneState = remember(backend, moduleName, scope) { PackagingPaneState(backend, moduleName, scope) }

/** State for the signing pane: the per-build-type keystore assignments and the one-at-a-time assign. */
@Stable
internal class SigningPaneState(
    private val backend: IdeBackend,
    private val moduleName: String,
    scope: CoroutineScope,
) : ModulePaneState(scope) {
    var assignments: UiSigningAssignments? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(true)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set

    init {
        reload()
    }

    fun assign(buildType: String, keystoreId: String?) {
        if (busy) return
        busy = true
        scope.launch {
            val result = backend.signing.assignSigning(moduleName, buildType, keystoreId)
            busy = false
            if (report(result)) reload()
        }
    }

    private fun reload() {
        scope.launch {
            loading = true
            assignments = runCatching { backend.signing.signingAssignments(moduleName) }.getOrNull()
            loading = false
        }
    }
}

@Composable
internal fun rememberSigningPaneState(
    backend: IdeBackend,
    moduleName: String,
    scope: CoroutineScope = rememberCoroutineScope(),
): SigningPaneState = remember(backend, moduleName, scope) { SigningPaneState(backend, moduleName, scope) }

/**
 * State for the module Settings tab: the module configuration form, the source-root editor, and the
 * missing-proguard-file warning (which also follows files created or deleted outside this screen).
 */
@Stable
internal class ModuleSettingsState(
    private val backend: IdeBackend,
    val moduleName: String,
    scope: CoroutineScope,
) : ModulePaneState(scope) {
    var config: UiModuleConfig? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var missingProguard: List<UiMissingProguardFile> by mutableStateOf(emptyList())
        private set
    var addRootOpen: Boolean by mutableStateOf(false)
        private set

    val sourceSets: List<String> get() = backend.modules.moduleSourceSets(moduleName)

    init {
        reload()
        // A proguard keep-rule file created/deleted elsewhere (the file tree's New File, an external edit)
        // flips the warning without touching the module config: refresh just that, with no reload flash.
        scope.launch {
            backend.files.fileSystemEpoch.collect { refreshMissingProguard() }
        }
    }

    fun openAddSourceRoot() { addRootOpen = true }

    fun closeAddSourceRoot() { addRootOpen = false }

    fun addSourceRoot(module: String, sourceSet: String, dirName: String, role: UiSourceRootRole) {
        if (backend.modules.addSourceRoot(module, sourceSet, dirName, role) != null) reload()
    }

    fun removeSourceRoot(sourceSet: String, rootPath: String) {
        if (backend.modules.removeSourceRoot(moduleName, sourceSet, rootPath)) reload()
    }

    fun applyEdit(edit: UiModuleConfigEdit) {
        scope.launch {
            if (report(backend.modules.updateModuleConfig(moduleName, edit))) reload()
        }
    }

    /** Create the proguard keep-rule file a build config references but that is missing on disk. */
    fun createProguardFile(entry: String) {
        scope.launch {
            val created = backend.modules.createProguardFile(moduleName, entry)
            showToast(if (created != null) ConfigToast.Created(entry) else ConfigToast.CreateFailed(entry))
            if (created != null) reload()
        }
    }

    private fun reload() {
        scope.launch {
            loading = true
            config = runCatching { backend.modules.getModuleConfig(moduleName) }.getOrNull()
            missingProguard = runCatching { backend.modules.missingProguardFiles(moduleName) }.getOrDefault(emptyList())
            loading = false
        }
    }

    private fun refreshMissingProguard() {
        scope.launch {
            missingProguard = runCatching { backend.modules.missingProguardFiles(moduleName) }.getOrDefault(emptyList())
        }
    }
}

@Composable
internal fun rememberModuleSettingsState(
    backend: IdeBackend,
    moduleName: String,
    scope: CoroutineScope = rememberCoroutineScope(),
): ModuleSettingsState = remember(backend, moduleName, scope) { ModuleSettingsState(backend, moduleName, scope) }
