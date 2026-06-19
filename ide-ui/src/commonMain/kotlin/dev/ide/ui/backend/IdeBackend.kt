package dev.ide.ui.backend

import kotlinx.coroutines.flow.StateFlow

/**
 * Thrown by [IdeBackend.analyze] when a higher-priority call (code completion) preempted the analysis pass
 * before it finished — they share one serialized engine thread. The buffer's diagnostics are left unchanged;
 * the caller should retry shortly (once the interactive call frees the engine), so a settled buffer still
 * gets a full pass.
 */
class AnalysisPreempted : RuntimeException("analysis preempted by a higher-priority engine call")

/**
 * The seam between the reusable Compose UI and whatever drives it. The UI never touches IdeServices,
 * the project model, JDT, or `java.nio` directly — it talks only to this port, in platform-neutral
 * terms (paths are opaque strings). The desktop host implements it over IdeServices; an Android host
 * would implement the same contract.
 */
interface IdeBackend {
    val project: ProjectInfo

    /**
     * The workspace as a tree, shaped by [mode]:
     *  - [TreeViewMode.Project]: a curated view — modules → source folders → packages → files, plus the
     *    manifest and each module's root config files (`module.toml`, build scripts, README…).
     *  - [TreeViewMode.AllFiles]: the raw on-disk tree from the workspace root (everything except bulky
     *    derived output like `build/`, `.gradle/`, and the platform caches), IntelliJ "Project Files" style.
     */
    fun fileTree(mode: TreeViewMode = TreeViewMode.Project): TreeNode

    /**
     * Create a new file `[dirPath]/[fileName]` with [content] (creating intermediate directories).
     * Returns the new file's absolute path, or null if it already exists or creation failed. The default
     * is a no-op for read-only backends; hosts that can write override it. A successful write bumps
     * [fileSystemEpoch].
     */
    fun createFile(dirPath: String, fileName: String, content: String): String? = null

    /**
     * Like [createFile] but writes raw [bytes] verbatim — for importing arbitrary (possibly binary)
     * files where text decoding would corrupt them. Returns the new path, or null if it exists/failed.
     */
    fun createFileBytes(dirPath: String, fileName: String, bytes: ByteArray): String? = null

    /**
     * Create a file under [dirPath] where [name] may include nested folders (`a/b/Helper.kt`), all created
     * along the way. Content is scaffolded from the extension: `.java`/`.kt` → a class stub with the package
     * resolved from the target location; `.xml` → a root element; anything else → an empty file. Returns the
     * new file's absolute path, or null if it already exists / creation failed. Bumps [fileSystemEpoch].
     */
    fun createFileSmart(dirPath: String, name: String): String? = null

    /**
     * Create a typed source file named [name] (a bare type name, no extension) under [dirPath] from
     * [template] — a Java/Kotlin class/interface/enum/… stub whose `package` is resolved from the target
     * location. The extension follows the template's language (`.java` / `.kt`). Returns the new file's
     * absolute path, or null if it already exists / [name] is not a valid identifier / creation failed.
     * Bumps [fileSystemEpoch]. The default is a no-op for read-only backends.
     */
    fun createSourceFile(dirPath: String, name: String, template: UiNewFileTemplate): String? = null

    /**
     * Create directory `[parentPath]/[name]` (intermediate dirs included). Returns the new path, or null if
     * it already exists / failed. Used by the New-Directory flow (e.g. standard Android `res/` folders). A
     * successful create bumps [fileSystemEpoch] so the tree refreshes.
     */
    fun createDirectory(parentPath: String, name: String): String? = null

    /**
     * Delete a file or directory/package (recursively). Returns true on success. Bumps [fileSystemEpoch] so
     * the tree refreshes. The default is a no-op for read-only backends.
     */
    fun deletePath(path: String): Boolean = false

    /** Source-set names declared on [moduleName] (for the Add-Source-Root selector). */
    fun moduleSourceSets(moduleName: String): List<String> = emptyList()

    /**
     * Add a typed source root named [dirName] (its leaf folder, e.g. `java`/`kotlin`/`resources`) to
     * [sourceSetName] of [moduleName], placed under the set's base dir (`src/<set>/<dirName>`). Creates the
     * source set if it doesn't exist. Returns the new directory path, or null on failure. Bumps
     * [fileSystemEpoch]. The default is a no-op for read-only backends.
     */
    fun addSourceRoot(moduleName: String, sourceSetName: String, dirName: String, role: UiSourceRootRole): String? = null

    /** Unmark the content root at [rootPath] from [sourceSetName] of [moduleName] (model-only; the folder on
     *  disk is kept). Returns true on success. */
    fun removeSourceRoot(moduleName: String, sourceSetName: String, rootPath: String): Boolean = false

    /** Create an empty source set [name] on [moduleName]. Returns false if it already exists / failed. */
    fun addSourceSet(moduleName: String, name: String): Boolean = false

    /**
     * Rename a file or directory/package in place (same parent) to [newName] (the full new name, with
     * extension for files). For a Java source file whose public type matches the file name, the type and all
     * its references are renamed too (and the backing file moved). On success [UiRenameResult.newPath] is the
     * new path so the UI can reopen it. Bumps [fileSystemEpoch].
     */
    suspend fun renamePath(path: String, newName: String): UiRenameResult =
        UiRenameResult(false, "Rename is not supported by this backend")

    /**
     * Move a file or directory/package into [destDir]. Returns the new path, or null on conflict/failure.
     * Bumps [fileSystemEpoch].
     */
    fun movePath(path: String, destDir: String): String? = null

    /**
     * Copy a file or directory/package into [destDir]. Returns the new path, or null on conflict/failure.
     * Bumps [fileSystemEpoch].
     */
    fun copyPath(path: String, destDir: String): String? = null

    /**
     * Immediate children of [dirPath] — subdirectories and files — for the mobile move/copy directory
     * browser (a file-manager-style picker that descends folder by folder). Derived/transient dirs
     * (`build/`, `.gradle/`, platform caches) and dotfiles are excluded; directories sort before files,
     * each alphabetical. Empty when [dirPath] isn't a readable directory.
     */
    fun listDirectory(dirPath: String): List<UiDirEntry> = emptyList()

    /** Read a file's current on-disk text. */
    fun readFile(path: String): String

    /** Name of the module owning [path], or null if outside the project. */
    fun moduleNameForFile(path: String): String?

    /** Enclosing declarations (type/method names, outer→inner) at [offset] in [text] — for the breadcrumb. */
    suspend fun breadcrumbAt(path: String, text: String, offset: Int): List<String> = emptyList()

    /** Register/refresh the live editor buffer so cross-file analysis sees in-progress edits. */
    fun updateDocument(path: String, text: String)

    /** Android platform-sources status (for `android.*` parameter names + javadoc), or null if no Android SDK. */
    fun androidSourcesInfo(): UiAndroidSourcesInfo? = null

    /** Download the Android platform sources (desktop `sdkmanager`); returns a status message. */
    suspend fun downloadAndroidSources(): String = "Not supported."

    // ---- SDK / toolchain manager ----
    // The SDK manager downloads *sources & documentation* for the editor (javadoc, parameter names,
    // go-to-source into the SDK/JDK). Downloads run in the backend and keep going after the screen is
    // closed — observe [sdkManagerState], don't block on the start call.

    /** Live download queue + progress. Shared with the editor — observe, don't poll. */
    val sdkManagerState: StateFlow<UiSdkManagerState> get() = kotlinx.coroutines.flow.MutableStateFlow(UiSdkManagerState())

    /** The installable Android SDK source packages (for editor docs/go-to-source). Empty offline. */
    suspend fun sdkPackages(): List<UiSdkPackage> = emptyList()

    /** Start downloading one Android package by its id (`sources;android-34`). Returns immediately; the
     *  download continues in the background and reports on [sdkManagerState]. */
    suspend fun installSdkPackage(path: String): String = "Not supported."

    /** Cancel an in-flight SDK/JDK download by its id (the package path, or `jdk-<feature>`). */
    fun cancelSdkDownload(id: String) {}

    /** Drop the finished (done/failed) entries from the download queue. */
    fun clearSdkDownloads() {}

    /** Current JDK + whether sources are available for docs, or null if unknown. */
    fun jdkInfo(): UiJdkInfo? = null

    /** Start downloading a JDK (Temurin) [feature] for its sources; returns immediately. Desktop only. */
    suspend fun downloadJdkSources(feature: Int): String = "Not supported."

    /** Persist the buffer [text] for [path] to disk (and keep it as the live buffer). */
    fun saveFile(path: String, text: String)

    /** Code completion for the live buffer [text] at [offset], bound to [path]'s module. */
    suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult

    /** Diagnostics for the live buffer [text], bound to [path]'s module. May throw [AnalysisPreempted] when a
     *  completion request took priority on the shared engine thread — the caller retries when it's free. */
    suspend fun analyze(path: String, text: String): List<UiDiagnostic>

    /**
     * Inlay hints for `[startOffset, endOffset)` of [path]'s live buffer [text] — inferred `var`/lambda
     * types, call-site parameter names, fluent-chain types. The editor renders them inline (between
     * characters); they never change the document. Default empty for backends without hint support.
     *
     * May throw [AnalysisPreempted] when a higher-priority engine call (completion) cuts ahead — the host
     * should retry once the buffer settles, keeping the current hints in the meantime (an empty result would
     * wrongly clear them until the next edit).
     */
    suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> = emptyList()

    /**
     * Code actions available at the selection `[selStart, selEnd)` (a collapsed range = the bare caret) in
     * [path]'s live buffer [text]: quick-fixes for the diagnostics there plus caret intentions like
     * "Introduce local variable". What the editor lightbulb / Alt-Enter menu lists. The order is stable for a
     * given (path, text, selection) so the UI round-trips a choice back through [applyAction] by [UiAction.id].
     */
    suspend fun actionsAt(path: String, text: String, selStart: Int, selEnd: Int): List<UiAction> = emptyList()

    /**
     * Compute the edits for the action [actionId] (from [actionsAt] over the same buffer + selection). The
     * UI applies the returned [UiTextEdit]s to its buffer (in descending offset order) — reparse + re-analyze
     * then follow the normal text path. Empty ⇒ no-op / stale.
     */
    suspend fun applyAction(path: String, text: String, selStart: Int, selEnd: Int, actionId: Int): List<UiTextEdit> = emptyList()

    /**
     * Go-to-definition for the symbol/reference at [offset] in the live buffer [text] of [path] — e.g. an
     * Android resource reference (`@string/x` in XML, `R.layout.y` in Java) → its declaration. Null when
     * there's nothing to navigate to (or the host doesn't resolve definitions).
     */
    suspend fun definitionAt(path: String, text: String, offset: Int): UiDefinition? = null

    /**
     * The renameable symbol under the caret at [offset] in [path]'s buffer [text] (its current name + a kind
     * label), or null when the caret isn't on one. The UI uses this to prompt for the new name. Java only.
     */
    suspend fun prepareRename(path: String, text: String, offset: Int): UiRenameTarget? = null

    /**
     * Rename the symbol under [offset] to [newName] across the whole project, applying the multi-file edit
     * to disk and bumping [fileSystemEpoch] so open editors/the tree refresh. On success [UiRenameResult.newPath]
     * is set when the backing file was renamed too (the UI should reopen it).
     */
    suspend fun rename(path: String, text: String, offset: Int, newName: String): UiRenameResult =
        UiRenameResult(false, "Rename is not supported by this backend")

    // ---- block-based editing (projectional editor) ----

    /**
     * Project the live buffer [text] of [path] into a block tree — the same AST the text view edits,
     * rendered as nested blocks. Null when the host has no projection for the
     * file (read-only/demo backends). Re-called (debounced) after every edit, like [analyze].
     */
    suspend fun projectBlocks(path: String, text: String): UiBlockNode? = null

    /**
     * A render-ready model of the drawable XML in [path] (live buffer [text]) for the Preview view — with
     * every `@color`/`@dimen`/`@drawable` reference resolved against the project's resources. Null when the
     * file isn't an Android drawable/color/mipmap resource (or the backend has no Android support).
     */
    suspend fun drawablePreview(path: String, text: String): UiDrawable? = null

    /** The `<color>` swatches of a `res/values` color file ([path], live buffer [text]) — empty if none. */
    suspend fun colorResources(path: String, text: String): List<UiColorEntry> = emptyList()

    /** Raw bytes of an image resource at [path] (png/webp/jpg/…) for bitmap preview; null if unreadable. */
    suspend fun resourceImageBytes(path: String): ByteArray? = null

    /**
     * The `@Preview @Composable` functions in [path]'s live buffer [text] — the editor's Compose preview
     * targets (drives the toolbar Preview button). Empty when the file has none or there's no Kotlin support.
     */
    suspend fun composePreviews(path: String, text: String): List<UiComposePreview> = emptyList()

    /**
     * Run the `@Preview` composable [functionName] in [path] (live buffer [text]) through the on-device
     * Compose interpreter, returning a status: whether it is interpretable, and (when a render host is wired)
     * the render outcome. See `docs/compose-interpreter.md`.
     */
    suspend fun runComposePreview(path: String, text: String, functionName: String): UiPreviewResult =
        UiPreviewResult(ok = false, message = "Compose preview is not available")

    /**
     * Compile a block edit against [path]'s current buffer [text] into surgical [UiTextEdit]s. The UI
     * applies them to its text buffer (in descending offset order) — reparse + re-projection then follow
     * through the normal text path, so the two views never sync to each other. Empty ⇒ not applicable.
     */
    suspend fun applyBlockEdit(path: String, text: String, edit: UiBlockEdit): List<UiTextEdit> = emptyList()

    // ---- indexing-backed features ----

    /** Live indexing status, for the status chip + console detail. */
    val indexStatus: StateFlow<IndexUiStatus>

    /** Go-to-symbol over project declarations (navigable: filePath + offset). */
    suspend fun searchSymbols(query: String, limit: Int = 50): List<SymbolHit>

    /** Member search (complete-by-member) across the classpath (informational; owner in [SymbolHit.detail]). */
    suspend fun searchMembers(query: String, limit: Int = 50): List<SymbolHit>

    /**
     * Full-text find-in-files across the workspace's source/resource files. Matches the live editor buffer
     * when a file is open (so unsaved edits are searched), else the on-disk text. Each hit carries the file,
     * the 1-based line/column, the line's text, and the match span within that line plus the absolute
     * [UiTextMatch.offset] for navigation. Default backends return nothing.
     */
    suspend fun findInFiles(query: String, options: UiSearchOptions = UiSearchOptions(), limit: Int = 200): List<UiTextMatch> = emptyList()

    /** Re-invalidate and rebuild the workspace indexes from scratch (the "Re-index" action). */
    fun reindex() {}

    // ---- build & run ----

    /** Live build/run state for the console pane. */
    val buildState: StateFlow<BuildState>

    /** The tasks the Run picker can launch — run a CLI, assemble an APK variant, etc. */
    fun runTasks(): List<RunTaskOption> = emptyList()

    /** Launch the task with [id] (from [runTasks]); streams into [buildState]. */
    fun runTask(id: String) {}

    /** Run the default task (first of [runTasks]) — the plain Run button. Streams into [buildState]. */
    fun runBuild()

    /** Cancel an in-progress build/run. */
    fun stopBuild()

    // ---- runtime permission prompts (the run sandbox/guard) ----

    /** The pending permission a running program is asking for (network/file/reflection/exec), or null.
     *  The running program is blocked until [answerPermission] is called with this request's id. */
    val permissionRequest: StateFlow<UiPermissionRequest?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)

    /** Answer the pending [permissionRequest] [id] with [decision] — unblocks (or aborts) the running program. */
    fun answerPermission(id: Int, decision: UiPermissionDecision) {}

    // ---- dependency management (the Dependencies screen) ----

    /** Live resolution progress (a spinner/message while downloading + walking transitives). Shared across
     *  the Dependencies screen and the editor's resolve bar — observe, don't poll. */
    val depsState: StateFlow<DepsResolveState> get() = EMPTY_DEPS_STATE

    /**
     * Kick off resolving a newly-created project's template dependencies in the background — call once after
     * the project is opened. Idempotent and a no-op when there's nothing pending (an opened existing project).
     * Progress streams on [depsState], so the user can leave any screen while it resolves.
     */
    fun startPendingDependencyResolution() {}

    /** Modules that can declare dependencies, with their build system + whether they accept `.aar`s. */
    fun dependencyModules(): List<UiDepModule> = emptyList()

    /**
     * The full dependency picture for [moduleName]: declared entries, the resolved transitive graph,
     * version conflicts, cycles, and per-artifact build-system compatibility. Null for an unknown module.
     * Resolves over the network (cached on disk), so it's [suspend] and may take a moment on first call.
     */
    suspend fun moduleDependencies(moduleName: String): UiModuleDeps? = null

    /** Search repositories (Maven Central) for [query]; hits are flagged compatible with [moduleName]. */
    suspend fun searchArtifacts(query: String, moduleName: String): List<UiArtifactHit> = emptyList()

    /**
     * Resolve and add [coordinate] to [moduleName] at [scope] (e.g. `implementation`), bundling its resolved
     * transitive closure onto the module classpath. [coordinate] is `group:name:version`, or the versionless
     * `group:name` when a platform (BOM) imported via [addPlatform] supplies the version. Blocked (with a
     * reason) when the artifact is incompatible — e.g. an `.aar` on a pure-Java module.
     */
    suspend fun addDependency(moduleName: String, coordinate: String, scope: String): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /**
     * Import a Maven BOM ([coordinate], `group:name:version`) as a platform of [moduleName] — Gradle
     * `platform(...)` semantics. It adds no artifact; it supplies the version for versionless dependencies
     * (added via [addDependency] with a `group:name` coordinate). Verified resolvable before it's added.
     */
    suspend fun addPlatform(moduleName: String, coordinate: String): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** Remove the declared dependency or platform [coordinate] from [moduleName]. False if it wasn't present. */
    fun removeDependency(moduleName: String, coordinate: String): Boolean = false

    // ---- module configuration (the Module Settings editor) ----

    /** Modules whose configuration can be edited (the settings screen's module switcher). */
    fun configurableModules(): List<UiModuleRef> = emptyList()

    /**
     * The editable configuration of [moduleName] — its type, language level, source sets, and one
     * [UiFacetConfig] panel per registered facet (Android, etc.), with fields derived generically from the
     * facet codec so new facets / new model fields surface without bespoke UI. Null for an unknown module
     * or a backend that doesn't expose config.
     */
    suspend fun getModuleConfig(moduleName: String): UiModuleConfig? = null

    /**
     * Persist [edit] to [moduleName] (language level + facet field values) through a model transaction,
     * atomically writing `module.toml`. On success bumps [fileSystemEpoch] so the tree re-reads. The facet
     * values use the same `Map<String, Any?>` shape the facet codec round-trips.
     */
    suspend fun updateModuleConfig(moduleName: String, edit: UiModuleConfigEdit): UiConfigResult =
        UiConfigResult(false, "Module configuration not supported by this backend")

    // ---- project management (Create Project + the picker) ----

    /** Every project the host knows about (for the picker). Defaults to just the open [project]. */
    fun projects(): List<ProjectInfo> = listOf(project)

    /**
     * The on-disk directory that holds every project (one workspace dir each) — what the in-app "project
     * folder" panel shows and what [FileActions.reveal] opens in a file manager. Null when the backend has
     * no managed projects root (e.g. a single fixed-workspace host).
     */
    fun projectsRootPath(): String? = null

    /** The templates the Create-Project gallery offers, with the inputs each one collects. */
    fun projectTemplates(): List<UiProjectTemplate> = emptyList()

    /**
     * Create a new project from [templateId] with the collected [args] (`name` + `packageName` plus any
     * template-specific keys). On success it becomes the active project: [projectEpoch] bumps and the
     * [project]/[fileTree]/state flows now describe the new project.
     */
    suspend fun createProject(templateId: String, args: Map<String, String>): UiProjectResult =
        UiProjectResult(false, "Project creation not supported by this backend")

    /** Open the existing project rooted at [rootPath]; becomes the active project (bumps [projectEpoch]). */
    suspend fun openProject(rootPath: String): Boolean = false

    /**
     * Permanently delete the project rooted at [rootPath] from disk. The UI confirms with the user first.
     * Returns true on success (or if it was already gone); false if unsupported or the delete failed.
     */
    suspend fun deleteProject(rootPath: String): Boolean = false

    /**
     * Bumps whenever the active project changes (create/open). The UI keys its per-project state on this so
     * the editor subtree recomposes and re-reads the (now-swapped) [buildState]/[indexStatus]/tree.
     */
    val projectEpoch: StateFlow<Int> get() = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Bumps whenever a file is created/imported through this backend (incl. external "Open with" imports
     * the UI didn't drive). The UI observes it to re-read [fileTree] so newly-written files appear without
     * an explicit refresh.
     */
    val fileSystemEpoch: StateFlow<Int> get() = kotlinx.coroutines.flow.MutableStateFlow(0)

    // ---- app preferences (onboarding flag, etc.) ----

    /** Read an app-global preference, or null if unset (e.g. `"onboarding.seen"`). */
    fun preference(key: String): String? = null

    /** Persist an app-global preference. */
    fun setPreference(key: String, value: String) {}

    // ---- editor session (open tabs) ----

    /**
     * The editor tabs that were open the last time the active project was used — file paths in tab order
     * plus the active tab's index. Empty when none were saved or the backend doesn't persist them. Scoped
     * per project (the backend keys it off the active project), so it's re-read after every [projectEpoch] bump.
     */
    fun openTabs(): UiOpenTabs = UiOpenTabs()

    /** Persist the open editor tabs for the active project so they reopen on the next launch. */
    fun saveOpenTabs(tabs: UiOpenTabs) {}

    /**
     * Back up the user's projects (and any project files from a previous, incompatible app version) into
     * a single `.zip`, returning its path — the host then shares/saves it via [FileActions.share]. Null if
     * unsupported or the backup failed. Used by the build-system migration flow.
     */
    suspend fun backupProjects(): String? = null
}

// ---- editor-session DTOs ----

/** The editor tabs persisted for a project: the open file paths in tab order + the active tab's index (-1 if none). */
data class UiOpenTabs(val paths: List<String> = emptyList(), val activeIndex: Int = -1)

// ---- project-management DTOs ----

/** A project template surfaced in the Create-Project gallery; [parameters] drive the configure form. */
data class UiProjectTemplate(
    val id: String,
    val displayName: String,
    val description: String,
    /** "Android" | "Java" | "Other" — buckets the gallery. */
    val category: String,
    /** Icon id resolved by `TreeIcons` (e.g. `module.android`, `java`). */
    val iconId: String,
    val parameters: List<UiTemplateParam>,
)

/** One configurable input of a template, neutral to the model layer that produced it. */
sealed interface UiTemplateParam {
    val key: String
    val label: String
    val help: String?

    data class Text(
        override val key: String,
        override val label: String,
        val default: String = "",
        val placeholder: String = "",
        /** "none" | "identifier" | "package" | "project" — drives client-side validation. */
        val validation: String = "none",
        override val help: String? = null,
    ) : UiTemplateParam

    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val defaultIndex: Int = 0,
        override val help: String? = null,
    ) : UiTemplateParam {
        data class Option(val value: String, val label: String)
    }

    data class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean = false,
        override val help: String? = null,
    ) : UiTemplateParam
}

/** Outcome of a create: [success] + a human message (the reason on failure) + the new project's root path. */
data class UiProjectResult(val success: Boolean, val message: String, val rootPath: String? = null)

/** A `@Preview @Composable` target in the open editor file. */
data class UiComposePreview(val functionName: String, val offset: Int)

/** The outcome of running a Compose preview: [ok] = interpretable/rendered; [message] explains the status. */
data class UiPreviewResult(val ok: Boolean, val message: String)

// ---- dependency-management DTOs ----

/** A stable empty progress flow for the [IdeBackend.depsState] default (a fresh flow per get would churn). */
private val EMPTY_DEPS_STATE: StateFlow<DepsResolveState> = kotlinx.coroutines.flow.MutableStateFlow(DepsResolveState())

/** Live resolve progress, mirrored from the engine (mirrors [BuildState]'s role for the console).
 *  [log] is a bounded, ordered history of resolution activity (POMs walked, artifacts downloaded), surfaced
 *  when the user expands the editor's resolve bar. */
data class DepsResolveState(
    val resolving: Boolean = false,
    val message: String = "",
    val fraction: Double = -1.0,
    val log: List<String> = emptyList(),
)

/** A dependency-declaring module for the screen's module switcher. */
data class UiDepModule(
    val name: String,
    val buildSystem: String,
    /** True when the module accepts Android archives (`.aar`) — i.e. it's an Android app/library module. */
    val acceptsAar: Boolean,
    val dependencyCount: Int,
)

/** A repository search hit, pre-judged for compatibility with the target module. */
data class UiArtifactHit(
    val coordinate: String,        // "group:name:version"
    val packaging: String,         // "jar" | "aar" | "pom" | …
    val compatible: Boolean,
    val incompatibleReason: String? = null,
)

/** What a node in the dependency graph *is* — drives its icon and the compatibility rules. */
enum class UiDepKind { Jar, Aar, Module, Sdk, Platform }

/**
 * One node in a module's dependency picture — a declared dependency or one pulled in transitively. The
 * graph/tree is reconstructed from [children] (the coordinates this node depends on); the same node list
 * backs the flat List view, the Tree view (expanding [declared] roots through [children]), and the Graph.
 */
data class UiDependencyNode(
    val coordinate: String,        // "group:name:version" (or the module name for a module dependency)
    val group: String,
    val name: String,
    val version: String,
    val kind: UiDepKind,
    /** True for a directly-declared dependency; false when it's only present transitively. */
    val declared: Boolean,
    /** The declared scope (e.g. `implementation`), for declared entries; null for transitives. */
    val scope: String? = null,
    val compatible: Boolean = true,
    val incompatibleReason: String? = null,
    /** Its `group:name` is requested at more than one version somewhere in the graph. */
    val inConflict: Boolean = false,
    val children: List<String> = emptyList(),
)

/** A resolved version clash: [artifact] (`group:name`) was requested at [requested]; [chosen] won. */
data class UiVersionConflict(val artifact: String, val requested: List<String>, val chosen: String)

/** Everything the Dependencies screen renders for one module. */
data class UiModuleDeps(
    val moduleName: String,
    val buildSystem: String,
    val acceptsAar: Boolean,
    /** Declared dependencies in declaration order (the List/Tree view roots). */
    val declared: List<UiDependencyNode>,
    /** Every node in the resolved graph (declared + transitive), deduplicated by coordinate. */
    val nodes: List<UiDependencyNode>,
    val conflicts: List<UiVersionConflict> = emptyList(),
    /** Each entry is a cycle expressed as a coordinate path (last == first). */
    val cycles: List<List<String>> = emptyList(),
    /** Coordinates that couldn't be resolved from any repository. */
    val unresolved: List<String> = emptyList(),
)

/** Outcome of an add: [success] + a human message (the reason, when blocked) + how many artifacts landed. */
data class UiAddResult(val success: Boolean, val message: String, val added: Int = 0)

/** A runnable/assemblable task offered by the Run picker. [group] buckets them (e.g. `run`, `android`). */
data class RunTaskOption(val id: String, val label: String, val group: String)

enum class RunStatus { Idle, Running, Succeeded, Failed }

enum class StepStatus { Pending, Running, Done, UpToDate, NoSource, Skipped, Failed }

data class BuildStepUi(val name: String, val status: StepStatus)

data class BuildState(
    val status: RunStatus = RunStatus.Idle,
    val moduleName: String = "",
    val steps: List<BuildStepUi> = emptyList(),
    val log: List<String> = emptyList(),
    val elapsedMs: Long = 0,
    /** An informational notice shown above the step graph (e.g. the first-build dex-cache warning). */
    val banner: String? = null,
)

/** A running program asking permission for a guarded operation. [category] is a guard-category id
 *  (network / file_read / file_write / reflection / exec); [detail] is the concrete target (host/path/…). */
data class UiPermissionRequest(val id: Int, val category: String, val detail: String)

/** The user's answer to a [UiPermissionRequest]: deny, or allow for this one call / this run / always (persisted). */
enum class UiPermissionDecision { DENY, ALLOW_ONCE, ALLOW_RUN, ALLOW_ALWAYS }

data class IndexUiStatus(
    val building: Boolean = false,
    val message: String = "",
    val fraction: Double = -1.0,
)

/** A search hit. For go-to-symbol [filePath]/[offset] are set (navigable); for members they're null. */
data class SymbolHit(
    val name: String,
    val detail: String,
    val kind: String,
    val filePath: String? = null,
    val offset: Int? = null,
)

/** Find-in-files options. Default is a case-insensitive literal substring search. */
data class UiSearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/**
 * One full-text match: [filePath]/[fileName] for grouping, the 1-based [line]/[col], the matched line's
 * [lineText] with the hit at `[matchStart, matchEnd)` (column indices into [lineText]), and the absolute
 * document [offset] the editor navigates to.
 */
data class UiTextMatch(
    val filePath: String,
    val fileName: String,
    val line: Int,
    val col: Int,
    val lineText: String,
    val matchStart: Int,
    val matchEnd: Int,
    val offset: Int,
)

// ---- module-configuration DTOs (the Module Settings editor) ----

/** A configurable module for the settings screen's switcher. */
data class UiModuleRef(val name: String, val typeDisplay: String)

/**
 * The editable configuration of one module. [facets] is rendered as one collapsible panel each; its
 * [UiConfigField]s are derived from the facet codec's value shape, so the renderer is generic.
 */
data class UiModuleConfig(
    val name: String,
    val typeId: String,
    val typeDisplay: String,
    val languageLevel: String,
    /** The selectable language-level options (enum names). */
    val languageLevels: List<String>,
    val outputDir: String,
    val sourceSets: List<UiSourceSetInfo>,
    val facets: List<UiFacetConfig>,
)

data class UiSourceSetInfo(val name: String, val scope: String, val roots: List<String>)

/** One facet panel (e.g. the `[android]` table) with a generic, codec-derived field list. */
data class UiFacetConfig(val table: String, val title: String, val fields: List<UiConfigField>)

/** A configurable field, typed by the underlying value so the UI picks the right control. */
sealed interface UiConfigField {
    val key: String
    val label: String

    data class Text(override val key: String, override val label: String, val value: String) : UiConfigField
    data class Bool(override val key: String, override val label: String, val value: Boolean) : UiConfigField
    data class Number(override val key: String, override val label: String, val value: Long) : UiConfigField
    data class StringList(override val key: String, override val label: String, val values: List<String>) : UiConfigField
    /** A repeatable inline-table list (Android build types / product flavors): each row is its own field list. */
    data class TableList(override val key: String, override val label: String, val rows: List<List<UiConfigField>>) : UiConfigField
}

/**
 * The changes to persist. [facetValues] maps a facet table (e.g. `"android"`) to the full value map the
 * facet codec decodes — the UI rebuilds it from the (possibly edited) [UiConfigField]s.
 */
data class UiModuleConfigEdit(
    val languageLevel: String? = null,
    val facetValues: Map<String, Map<String, Any?>> = emptyMap(),
)

data class UiConfigResult(val success: Boolean, val message: String)

data class ProjectInfo(
    val name: String,
    val rootPath: String,
    val moduleCount: Int,
)

/** How the project tree is shaped — a curated module view, or the raw filesystem (see [IdeBackend.fileTree]). */
enum class TreeViewMode { Project, AllFiles }

/** The kind of a content/source root the user can add to a module (maps to a backend `ContentRole`). */
enum class UiSourceRootRole { Source, Resource, AndroidRes, Assets, Aidl }

/**
 * A typed source-file template the file-tree "New" submenu can scaffold. The prefix names the language
 * (Java → `.java`, Kotlin → `.kt`); the backend prepends the package resolved from the target directory.
 */
enum class UiNewFileTemplate {
    JavaClass, JavaInterface, JavaEnum, JavaAbstractClass, JavaAnnotation,
    KotlinFile, KotlinClass, KotlinInterface, KotlinDataClass, KotlinEnum, KotlinObject,
}

/** Structural role of a tree node. The *icon* is chosen separately via [TreeNode.iconId]. */
enum class NodeKind { Workspace, Module, SourceRoot, Package, Folder, File }

enum class GitStatus { Added, Modified, Deleted, Untracked }

data class TreeNode(
    val id: String,
    val name: String,
    val kind: NodeKind,
    /** Absolute path if this node is an openable file; null for grouping nodes. */
    val filePath: String?,
    /** Icon id resolved by the UI's `TreeIcons` registry (e.g. `java`, `sourceset.android-res`, `package`). */
    val iconId: String = "file",
    val children: List<TreeNode> = emptyList(),
    val gitStatus: GitStatus? = null,
    /** The owning Java/Kotlin source root, for `SourceRoot`/`Package` nodes; null elsewhere. New-file targets. */
    val sourceRootPath: String? = null,
    /**
     * For a (possibly compacted) `Package` node, one entry per package level — its dotted package name and
     * directory — so a New-Class action can target any intermediate level (e.g. `com.tyron` inside a
     * collapsed `com.tyron.codeassist`). Empty for non-package nodes.
     */
    val packageSegments: List<PackageSegment> = emptyList(),
    /**
     * For an Android `res/` folder node (or the `res/` root): the directory a new XML resource would be
     * created in. Non-null marks an XML new-file target — the counterpart to [sourceRootPath] for Java.
     */
    val resDirPath: String? = null,
    /**
     * When non-null, opening this node opens the Module Settings editor for the named module instead of a
     * text editor — set on a `module.toml` file (and the module node itself). The host only sets it for a
     * module that actually resolves, so the UI can route on it without re-validating.
     */
    val moduleConfigName: String? = null,
    /**
     * The on-disk directory this node represents (workspace/module/source-root/package/folder), so the UI
     * can create a new file or folder *anywhere* in the tree — into this dir for a directory node, or into a
     * file node's parent. Null only when no directory applies.
     */
    val dirPath: String? = null,
)

/** One level of a (possibly compacted) package: its dotted [packageName] and the [dirPath] backing it. */
data class PackageSegment(val packageName: String, val dirPath: String)

/**
 * One entry in the mobile move/copy directory browser: a subdirectory to descend into ([isDirectory] true)
 * or a file shown for context. [iconId] resolves via the UI's `TreeIcons` registry (e.g. `folder`, `java`).
 */
data class UiDirEntry(val name: String, val path: String, val isDirectory: Boolean, val iconId: String = "file")

enum class UiCompletionKind {
    Class, Interface, Enum, AnnotationType, Record,
    Method, Constructor, Field, EnumConstant,
    Variable, Parameter, TypeParameter,
    Package, Keyword, Snippet,
    /** A word lifted from the current buffer (hippie/word completion). */
    Word,
}

data class UiCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String?,
    /** Documentation (javadoc) for the side doc panel; null when none is known. */
    val documentation: String? = null,
    val kind: UiCompletionKind,
    val sortPriority: Int,
    /** Extra edits to apply on accept beyond the main insertion — e.g. an auto-`import`. */
    val additionalEdits: List<UiTextEdit> = emptyList(),
    /** Where the caret lands after accept, relative to [insertText]; null ⇒ end of the insertion. */
    val caret: UiCaret? = null,
    /**
     * Present for snippet/postfix items: tab stops to step through after the [insertText] is inserted, all
     * offsets relative to it. When set the editor drives a snippet session (Tab/Shift-Tab/Escape) instead of
     * just placing [caret]; [caret] still holds a sensible fallback (the first stop) for callers that don't.
     */
    val snippet: UiSnippet? = null,
)

/** A snippet to drive after accept: ordered tab [stops] (offsets relative to the inserted text) + the final
 *  caret. A stop with more than one range is linked (mirrored) — the same placeholder in several places. */
data class UiSnippet(val stops: List<UiSnippetStop>, val finalCaretOffset: Int)

/** One tab stop: [index] (0 = final caret, otherwise visit order), its [ranges] (>1 ⇒ linked), and any
 *  choice [choices] to offer. Each range is `[start, end)` relative to the inserted text. */
data class UiSnippetStop(val index: Int, val ranges: List<UiTextRange>, val choices: List<String> = emptyList())

/** A half-open `[start, end)` span. */
data class UiTextRange(val start: Int, val end: Int)

/** A text edit: replace [start]..[end] with [newText]. */
data class UiTextEdit(val start: Int, val end: Int, val newText: String)

/**
 * Post-accept caret placement, neutral to the language that produced it: put the caret [offset] chars into
 * the inserted text and, if [selectionLength] > 0, select that many chars from there (a placeholder to
 * overtype). The editor clamps both into the inserted range.
 */
data class UiCaret(val offset: Int, val selectionLength: Int = 0)

/** Items already ranked; [replaceStart]..[replaceEnd] is the partial identifier an accept replaces. */
data class UiCompletionResult(
    val items: List<UiCompletionItem>,
    val replaceStart: Int,
    val replaceEnd: Int,
    /**
     * Whether the editor may narrow this set locally as the user types more of the identifier, instead
     * of waiting for a fresh query. True for ordinary prefix completion (the overwhelming case): the items
     * are the full candidate set for the token at [replaceStart], so the editor can keep the popup live and
     * filter it in-place while a slow provider catches up — no close/reopen flicker. A provider should set
     * this to false when its result is not a stable prefix set (e.g. server-side fuzzy ranking, or a
     * context-sensitive result that changes meaning as the token grows), forcing a re-query on each edit.
     */
    val mayFilterLocally: Boolean = true,
)

/** A go-to-definition target: open [path] and move the caret to [offset]. */
data class UiDefinition(val path: String, val offset: Int)

/** The renameable symbol under the caret: its current [oldName] and a human [kind] label (e.g. "method"). */
data class UiRenameTarget(val oldName: String, val kind: String)

/** Outcome of a project-wide rename. [newPath] is set when the edited file was itself renamed (reopen it). */
data class UiRenameResult(
    val success: Boolean,
    val message: String,
    val occurrences: Int = 0,
    val filesChanged: Int = 0,
    val newPath: String? = null,
)

/** Whether an action fixes a problem ([QUICK_FIX]), is a context action with no diagnostic ([INTENTION]), or refactors ([REFACTOR]). Drives the menu icon. */
enum class UiActionKind { QUICK_FIX, INTENTION, REFACTOR }

/** One entry in the editor's code-action menu. [id] is the stable index used to round-trip via [IdeBackend.applyAction]. */
data class UiAction(val id: Int, val title: String, val kind: UiActionKind)

/** What an inlay hint conveys — drives its tint/affinity. */
enum class UiInlayKind { Type, Parameter, Chaining, Other }

/** One run of an inlay hint's label; [navOffset] (when set) is a document offset to jump to on click. */
data class UiInlayPart(val text: String, val navOffset: Int? = null)

/**
 * An inline annotation rendered between characters at [offset] (it does not change the document): an inferred
 * `var`/lambda type, a call-site parameter name, a fluent-chain type. [paddingLeft]/[paddingRight] add a thin
 * gap so it reads as separate from the code.
 */
data class UiInlayHint(
    val offset: Int,
    val parts: List<UiInlayPart>,
    val kind: UiInlayKind,
    val tooltip: String? = null,
    val paddingLeft: Boolean = false,
    val paddingRight: Boolean = false,
) {
    val text: String get() = parts.joinToString("") { it.text }
}

/** Android platform-sources status: the [platform] (e.g. "android-36"), whether sources are [installed],
 *  and whether they're [downloadable] (an sdkmanager is present). */
data class UiAndroidSourcesInfo(val platform: String, val installed: Boolean, val downloadable: Boolean)

/** Aggregate SDK/JDK download progress. [busy]/[message]/[fraction] summarise the active work (fraction < 0
 *  ⇒ indeterminate); [downloads] is the per-item queue (active + recently finished). */
data class UiSdkManagerState(
    val busy: Boolean = false,
    val message: String = "",
    val fraction: Double = -1.0,
    val downloads: List<UiSdkDownload> = emptyList(),
)

/** One queued/in-flight/finished download. [id] is the package path or `jdk-<feature>`;
 *  [status] ∈ {DOWNLOADING, EXTRACTING, INSTALLING, DONE, FAILED}; [fraction] < 0 ⇒ indeterminate. */
data class UiSdkDownload(
    val id: String,
    val label: String,
    val status: String,
    val fraction: Double = -1.0,
    val detail: String = "",
)

/** An installable Android SDK package. [category] is always SOURCES (the SDK Manager is sources/docs only).
 *  [incomplete] ⇒ a previous install was interrupted; re-installing resumes/repairs it. */
data class UiSdkPackage(
    val path: String,
    val displayName: String,
    val category: String,
    val sizeBytes: Long,
    val installed: Boolean,
    val installable: Boolean,
    val incomplete: Boolean = false,
)

/** The active JDK and whether its sources are available for docs. */
data class UiJdkInfo(val home: String, val version: String, val srcZip: String?)

enum class UiSeverity { Error, Warning, Info, Hint }

data class UiDiagnostic(
    val severity: UiSeverity,
    val line: Int,
    val col: Int,
    val message: String,
    val startOffset: Int,
    val endOffset: Int,
    /** Tagged UNUSED (e.g. an unused import) — the editor renders it muted rather than alarming. */
    val unused: Boolean = false,
)

// ---- block-based editing DTOs (neutral; the host maps its BlockTree onto these) ----

/**
 * A projected block, neutral to the language that produced it. [parts] is the render order — interleaved
 * inline [UiBlockPart.Field]s (chrome + editable tokens) and child [UiBlockPart.Slot]s. [label] is the
 * short header chip (`if`, `method`, `call`; empty = render transparently). [start]/[end] is the source
 * span (for caret correspondence). [id] is stable only within one projection.
 */
data class UiBlockNode(
    val id: String,
    val kind: String,
    val label: String,
    val category: String,
    val start: Int,
    val end: Int,
    val parts: List<UiBlockPart>,
    /**
     * The value kind this block PRODUCES as an expression — drives the typed socket/pill shape.
     * One of "boolean" | "number" | "string" | "object" | "type" | "unknown".
     */
    val valueKind: String = "unknown",
)

sealed interface UiBlockPart {
    /** An inline token. Chrome (punctuation/keywords) is `editable = false`; names/literals are editable. */
    data class Field(val role: String, val text: String, val editable: Boolean, val start: Int, val end: Int) : UiBlockPart
    /**
     * A child position. [multiple] = a list slot (statements/members) vs a single slot (a condition).
     * [valueKind] is the value kind the slot EXPECTS ("boolean" | "number" | "string" | "object" |
     * "type" | "unknown") — drives the empty-socket shape and hint.
     */
    data class Slot(val category: String, val multiple: Boolean, val start: Int, val end: Int, val children: List<UiBlockNode>, val valueKind: String = "unknown") : UiBlockPart
}

/** A structural edit the block view issues; the host compiles it to surgical [UiTextEdit]s. */
sealed interface UiBlockEdit {
    /** Set an editable field's text in place (rename, re-literal, swap an operator). */
    data class SetField(val blockId: String, val role: String, val text: String) : UiBlockEdit
    /** Replace a slot's content with raw text — type-text→blocks; reparses and re-projects. */
    data class ReplaceSlot(val blockId: String, val slotIndex: Int, val text: String) : UiBlockEdit
    /** Delete a block (and the syntax that only held it — a trailing `;`/newline). */
    data class DeleteBlock(val blockId: String) : UiBlockEdit
    /** Insert [text] at position [index] in the list slot [slotIndex] of [ownerBlockId] (palette / `+ block`). */
    data class InsertTemplate(val ownerBlockId: String, val slotIndex: Int, val index: Int, val text: String) : UiBlockEdit
    /** Wrap a statement block in a fresh `if (true) { … }` (the action-bar Wrap-if). */
    data class WrapInIf(val blockId: String) : UiBlockEdit
    /** Relocate [blockId] to position [toIndex] in the list slot [toSlotIndex] of [toOwnerBlockId] (drag). */
    data class MoveBlock(val blockId: String, val toOwnerBlockId: String, val toSlotIndex: Int, val toIndex: Int) : UiBlockEdit
}
