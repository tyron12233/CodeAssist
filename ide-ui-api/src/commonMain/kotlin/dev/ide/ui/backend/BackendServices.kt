package dev.ide.ui.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The concern-segmented services that make up [IdeBackend]. Each groups one area of the UI/engine boundary
 * and owns its own observable [StateFlow]s. The UI reaches them through the aggregator, e.g.
 * `backend.editor.complete(...)`, `backend.build.runBuild()`. Method defaults preserve the historical
 * "unsupported" behaviour so a partial backend (or a test fake) only overrides what it implements.
 */

// ---------------------------------------------------------------------------
// Files / VFS
// ---------------------------------------------------------------------------

/** The workspace file tree and file/directory operations. */
interface FileService {
    /** The workspace as a tree, shaped by [mode] (curated project view or the raw filesystem). */
    fun fileTree(mode: TreeViewMode = TreeViewMode.Project): TreeNode

    /**
     * The tree-node ids the user last left expanded for [mode], persisted per project, or null if none has
     * been persisted yet (the caller then applies the default expansion). Ids are the stable, path-based
     * [TreeNode.id]s from [fileTree], so they survive restarts and refreshes.
     */
    fun expandedTreeState(mode: TreeViewMode = TreeViewMode.Project): List<String>? = null

    /** Persist the expanded tree-node [ids] for [mode] (per project) so [fileTree] reopens the same way. */
    fun saveExpandedTreeState(mode: TreeViewMode, ids: List<String>) {}

    /** Read a file's current on-disk text. */
    fun readFile(path: String): String

    /** Name of the module owning [path], or null if outside the project. */
    fun moduleNameForFile(path: String): String?

    /** Create `[dirPath]/[fileName]` with [content]; returns the new path or null. Bumps [fileSystemEpoch]. */
    fun createFile(dirPath: String, fileName: String, content: String): String? = null

    /** Like [createFile] but writes raw [bytes] (for binary imports). */
    fun createFileBytes(dirPath: String, fileName: String, bytes: ByteArray): String? = null

    /** Create a file under [dirPath] where [name] may include nested folders; content scaffolded by extension. */
    fun createFileSmart(dirPath: String, name: String): String? = null

    /** Create a typed source file [name] under [dirPath] from [template]; package resolved from the location. */
    fun createSourceFile(dirPath: String, name: String, template: UiNewFileTemplate): String? = null

    /** Create `[parentPath]/[name]` (intermediate dirs included). Bumps [fileSystemEpoch]. */
    fun createDirectory(parentPath: String, name: String): String? = null

    /** Delete a file or directory/package (recursively). Bumps [fileSystemEpoch]. */
    fun deletePath(path: String): Boolean = false

    /** Immediate children of [dirPath] for the move/copy directory browser. */
    fun listDirectory(dirPath: String): List<UiDirEntry> = emptyList()

    /** Rename a file/dir in place to [newName] (for a Java public type, renames the type + references). */
    suspend fun renamePath(path: String, newName: String): UiRenameResult =
        UiRenameResult(false, "Rename is not supported by this backend")

    /** Move a file/dir into [destDir]; returns the new path or null. Bumps [fileSystemEpoch]. */
    fun movePath(path: String, destDir: String): String? = null

    /** Copy a file/dir into [destDir]; returns the new path or null. Bumps [fileSystemEpoch]. */
    fun copyPath(path: String, destDir: String): String? = null

    /** Bumps whenever a file is created/imported/removed, so the UI re-reads [fileTree]. */
    val fileSystemEpoch: StateFlow<Int> get() = MutableStateFlow(0)
}

// ---------------------------------------------------------------------------
// Editor language services
// ---------------------------------------------------------------------------

/** Editor-time language services for the active buffer: completion, analysis, hints, navigation, rename. */
interface EditorService {
    /** Register/refresh the live editor buffer so cross-file analysis sees in-progress edits. */
    fun updateDocument(path: String, text: String)

    /** Persist the buffer [text] for [path] to disk (and keep it as the live buffer). */
    fun saveFile(path: String, text: String)

    // --- Editor-session lifecycle notifications (fire-and-forget; default no-op like completionAccepted) -----
    // Pure notifications the UI pushes so the engine can publish editor-lifecycle events on the message bus for
    // plugins; nothing is awaited. A backend that doesn't care (test fakes, previews) inherits the no-op default.

    /** The user opened [path] in a new editor tab (not fired when re-focusing an already-open tab). */
    fun onFileOpened(path: String) {}

    /** The user closed [path]'s editor tab. */
    fun onFileClosed(path: String) {}

    /** The focused editor changed; [path] is null when the last tab closed (nothing focused). */
    fun onActiveEditorChanged(path: String?) {}

    /** The selection/caret in [path] settled at `[start, end)` (a bare caret has `start == end`). The UI
     *  debounces this, so it fires on settle rather than on every keystroke. */
    fun onSelectionChanged(path: String, start: Int, end: Int) {}

    /** Enclosing declarations at [offset] in [text] (type/method names, outer→inner) for the breadcrumb. */
    suspend fun breadcrumbAt(path: String, text: String, offset: Int): List<String> = emptyList()

    /** The file's declarations in document order (with nesting depth) for the structure/outline view and
     *  sticky scroll headers. Empty when the backend can't enumerate declarations for [path]. */
    suspend fun fileStructure(path: String, text: String): List<UiFileSymbol> = emptyList()

    /** Code completion for the live buffer [text] at [offset]. */
    suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult

    /** Notify that the user ACCEPTED a completion item — feeds the backend's acceptance-frequency
     *  ranking (frequently picked items float up on later completions). Fire-and-forget; default no-op. */
    suspend fun completionAccepted(path: String, label: String) {}

    /** Diagnostics for the live buffer [text]. May throw [AnalysisPreempted] when completion took priority. */
    suspend fun analyze(path: String, text: String): List<UiDiagnostic>

    /** Inlay hints for `[startOffset, endOffset)`. May throw [AnalysisPreempted]. */
    suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> = emptyList()

    /** Parameter-info / signature help for the call surrounding [offset], or null. */
    suspend fun signatureHelp(path: String, text: String, offset: Int): UiSignatureHelp? = null

    /** Type-aware semantic-highlight tokens. May throw [AnalysisPreempted]. */
    suspend fun semanticTokens(path: String, text: String): List<UiSemanticToken> = emptyList()

    /** Foldable regions for the live buffer. May throw [AnalysisPreempted]. */
    suspend fun codeFolds(path: String, text: String): List<UiFoldRegion> = emptyList()

    /** Code actions (quick-fixes + intentions) at the selection `[selStart, selEnd)`. */
    suspend fun actionsAt(path: String, text: String, selStart: Int, selEnd: Int): List<UiAction> = emptyList()

    /** Compute the edits for the code action [actionId] from [actionsAt] over the same buffer + selection. */
    suspend fun applyAction(path: String, text: String, selStart: Int, selEnd: Int, actionId: Int): List<UiTextEdit> = emptyList()

    /** Reformat the whole buffer to the active code style. Empty if unsupported / already formatted. */
    suspend fun formatDocument(path: String, text: String): List<UiTextEdit> = emptyList()

    /** Reformat only the text overlapping the selection `[selStart, selEnd)`. */
    suspend fun formatRange(path: String, text: String, selStart: Int, selEnd: Int): List<UiTextEdit> = emptyList()

    /** Go-to-definition for the symbol/reference at [offset], or null. */
    suspend fun definitionAt(path: String, text: String, offset: Int): UiDefinition? = null

    /** Gutter inheritor ("implementations") markers for [text] — one per inheritable type with direct subtypes.
     *  Empty for languages/files without the subtype relation indexed. */
    suspend fun inheritorMarkers(path: String, text: String): List<UiInheritorMarker> = emptyList()

    /** Resolve an inheritor [fqn] (from an [inheritorMarkers] target) to its source location for
     *  go-to-implementation, relative to [contextPath]'s module. Null when it's classpath-only (no source). */
    suspend fun implementationLocationOf(contextPath: String, fqn: String): UiDefinition? = null

    /** Quick documentation (signature + doc comment) for the symbol at [offset], or null. */
    suspend fun quickDocAt(path: String, text: String, offset: Int): UiQuickDoc? = null

    /** The renameable symbol under the caret at [offset], or null. */
    suspend fun prepareRename(path: String, text: String, offset: Int): UiRenameTarget? = null

    /** Rename the symbol under [offset] to [newName] project-wide. Bumps [FileService.fileSystemEpoch]. */
    suspend fun rename(path: String, text: String, offset: Int, newName: String): UiRenameResult =
        UiRenameResult(false, "Rename is not supported by this backend")
}

// ---------------------------------------------------------------------------
// Block-based editing (projectional editor)
// ---------------------------------------------------------------------------

/** The projectional (block) editor projection + edit compilation. */
interface BlockService {
    /** Project the live buffer [text] of [path] into a block tree, or null when unsupported. */
    suspend fun projectBlocks(path: String, text: String): UiBlockNode? = null

    /** Compile a block edit against [path]'s current buffer [text] into surgical text edits. */
    suspend fun applyBlockEdit(path: String, text: String, edit: UiBlockEdit): List<UiTextEdit> = emptyList()
}

// ---------------------------------------------------------------------------
// Preview (drawables / colors / images / Compose)
// ---------------------------------------------------------------------------

/** Resource + Compose preview rendering for the Preview view. */
interface PreviewService {
    /** Live state of the real-view layout-render pipeline, for the floating status chip — non-null while a
     *  render is in progress (e.g. "Merging resources", "Linking resources", "Dexing", "Rendering"), null when
     *  idle/done. Drives a small spinner + label like the build/index status indicators. */
    val previewProgress: StateFlow<PreviewProgress?> get() = MutableStateFlow(null)

    /** A render-ready model of the drawable XML in [path] (live buffer [text]), or null. */
    suspend fun drawablePreview(path: String, text: String): UiDrawable? = null

    /** The `<color>` swatches of a `res/values` color file. */
    suspend fun colorResources(path: String, text: String): List<UiColorEntry> = emptyList()

    /** Raw bytes of an image resource at [path] for bitmap preview; null if unreadable. */
    suspend fun resourceImageBytes(path: String): ByteArray? = null

    /** The `@Preview @Composable` functions in [path]'s live buffer [text]. */
    suspend fun composePreviews(path: String, text: String): List<UiComposePreview> = emptyList()

    /** Run the `@Preview` composable [functionName] through the on-device interpreter. */
    suspend fun runComposePreview(path: String, text: String, functionName: String): UiPreviewResult =
        UiPreviewResult(ok = false, message = "Compose preview is not available")

    // ---- Real-view layout attribute editor ----
    // Backs the Preview's editable attribute sheet: it edits the layout XML source (the same buffer the Code
    // view shows) driven by the SAME allowed-attribute metadata + completion the XML editor uses. [sourceOffset]
    // comes from the tapped view's `PreviewViewNode.sourceOffset`.

    // [id] (the tapped view's `@id/…` entry name, or null) anchors the element robustly: the raw [sourceOffset]
    // from the captured tree can lag the live buffer after an edit shifts offsets, so an id'd element is
    // re-located by id in the current [text]; un-id'd views fall back to the offset.

    /** The editable model for the layout element at [sourceOffset] (or [id]) in [path]'s live buffer [text] — its
     *  set attributes plus the allowed-but-unset attributes for that view. Null when it isn't an editable element. */
    suspend fun layoutElementAt(path: String, text: String, sourceOffset: Int, id: String?): UiLayoutElement? = null

    /** Value completion for [attrName] on the element at [sourceOffset]/[id], as if [fieldText] (caret at [caret])
     *  were typed into the value — the same candidates the XML editor gives. Ranges are field-relative. */
    suspend fun completeLayoutAttributeValue(
        path: String, text: String, sourceOffset: Int, id: String?, attrName: String, fieldText: String, caret: Int
    ): UiCompletionResult = UiCompletionResult(emptyList(), 0, 0)

    /** Edits that set [attrName]="[value]" on the element at [sourceOffset]/[id] (replace if present, else insert +
     *  auto-declare its `xmlns`). Apply them to the shared buffer to update both the Code view and the preview. */
    suspend fun setLayoutAttribute(
        path: String, text: String, sourceOffset: Int, id: String?, attrName: String, value: String
    ): List<UiTextEdit> = emptyList()

    /** Edits that remove [attrName] from the element at [sourceOffset]/[id]. */
    suspend fun removeLayoutAttribute(
        path: String, text: String, sourceOffset: Int, id: String?, attrName: String
    ): List<UiTextEdit> = emptyList()
}

/** A stage of the real-view layout-render pipeline, shown in the floating status chip. [stage] is a short
 *  human label (e.g. "Linking resources", "Rendering"); a non-null value means that stage is in progress. */
data class PreviewProgress(val stage: String)

// ---------------------------------------------------------------------------
// Indexing & search
// ---------------------------------------------------------------------------

/** The workspace index status + symbol/member/text search. */
interface SearchService {
    /** Live indexing status, for the status chip + console detail. */
    val indexStatus: StateFlow<IndexUiStatus>

    /** Go-to-symbol over project declarations (navigable: filePath + offset). */
    suspend fun searchSymbols(query: String, limit: Int = 50): List<SymbolHit>

    /** Member search across the classpath (informational; owner in [SymbolHit.detail]). */
    suspend fun searchMembers(query: String, limit: Int = 50): List<SymbolHit>

    /** Full-text find-in-files across the workspace's source/resource files. */
    suspend fun findInFiles(query: String, options: UiSearchOptions = UiSearchOptions(), limit: Int = 200): List<UiTextMatch> = emptyList()

    /** Re-invalidate and rebuild the workspace indexes from scratch (the "Re-index" action). */
    fun reindex() {}
}

// ---------------------------------------------------------------------------
// Build / run / console / sandbox
// ---------------------------------------------------------------------------

/** Build & run: the build console state, run tasks, interactive console I/O, and the run-sandbox prompts. */
interface BuildService {
    /** Live build/run state for the console pane. */
    val buildState: StateFlow<BuildState>

    /** The tasks the Run picker can launch. */
    fun runTasks(): List<RunTaskOption> = emptyList()

    /** Launch the task with [id] (from [runTasks]); streams into [buildState]. */
    fun runTask(id: String) {}

    /** Run the default task (the plain Run button). */
    fun runBuild()

    /** Cancel an in-progress build/run. */
    fun stopBuild()

    /** Live program I/O + lifecycle for an interactive console run, or null when none has started. */
    val runConsole: StateFlow<RunConsoleUi?> get() = MutableStateFlow(null)

    /** Feed one line of standard input to the running program. */
    fun sendRunInput(text: String) {}

    /** Signal end-of-input (EOF / Ctrl-D) to the running program's stdin. */
    fun closeRunInput() {}

    /** The pending permission a running program is asking for (the run sandbox), or null. */
    val permissionRequest: StateFlow<UiPermissionRequest?> get() = MutableStateFlow(null)

    /** Answer the pending [permissionRequest] [id] with [decision]. */
    fun answerPermission(id: Int, decision: UiPermissionDecision) {}

    /** Live logcat-style logs forwarded by the running (debug) app, for the "Logcat" console tab. Empty off
     *  device / when app-log forwarding is unavailable. */
    val appLog: StateFlow<AppLogUi> get() = MutableStateFlow(AppLogUi())

    /** Clear the app-log buffer shown in the Logcat tab. */
    fun clearAppLog() {}

    /** Selectable build-variant names for [moduleName] (e.g. `freeDebug`), empty for a non-Android module. */
    fun listVariants(moduleName: String): List<String> = emptyList()

    /** The active build variant for [moduleName] — what the editor analyzes against and Run/assemble targets. */
    fun activeVariant(moduleName: String): String? = null

    /** Select [variant] as [moduleName]'s active variant (re-analyzes the editor + re-indexes). */
    fun setActiveVariant(moduleName: String, variant: String) {}
}

// ---------------------------------------------------------------------------
// Dependencies (Maven + local libraries + repositories)
// ---------------------------------------------------------------------------

/** Dependency management: declared/resolved graph, add/remove, local libraries, repositories. */
interface DependencyService {
    /** Live resolution progress (a spinner/message while downloading + walking transitives). */
    val depsState: StateFlow<DepsResolveState> get() = MutableStateFlow(DepsResolveState())

    /** Kick off resolving a newly-created project's template dependencies in the background. */
    fun startPendingDependencyResolution() {}

    /** Re-attempt resolving every declared dependency (e.g. after the network comes back). */
    suspend fun retryDependencyResolution() {}

    /** Modules that can declare dependencies. */
    fun dependencyModules(): List<UiDepModule> = emptyList()

    /** The full dependency picture for [moduleName] (declared + resolved graph, conflicts, cycles). */
    suspend fun moduleDependencies(moduleName: String): UiModuleDeps? = null

    /** Search repositories for [query]; hits flagged compatible with [moduleName]. */
    suspend fun searchArtifacts(query: String, moduleName: String): List<UiArtifactHit> = emptyList()

    /** Resolve and add [coordinate] to [moduleName] at [scope], bundling its transitive closure.
     *  [variant] scopes the declaration to a build variant (e.g. `debug` → `debugImplementation`); null = shared. */
    suspend fun addDependency(moduleName: String, coordinate: String, scope: String, exclusions: List<String> = emptyList(), variant: String? = null): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** Import a Maven BOM as a platform of [moduleName] (Gradle `platform(...)`); [variant] scopes it to a build variant. */
    suspend fun addPlatform(moduleName: String, coordinate: String, variant: String? = null): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** One-click Firebase setup (BoM + [artifacts]). */
    suspend fun addFirebase(moduleName: String, artifacts: List<String> = listOf("firebase-analytics")): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** One-click Google Play Services: add each fully-qualified [coordinates] entry. */
    suspend fun addGooglePlayServices(moduleName: String, coordinates: List<String>): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** Remove the declared dependency or platform [coordinate] from [moduleName]. */
    fun removeDependency(moduleName: String, coordinate: String): Boolean = false

    /** Replace the transitive exclusions on a declared library [coordinate], then re-resolve. */
    suspend fun setDependencyExclusions(moduleName: String, coordinate: String, exclusions: List<String>): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** Published versions of the declared library [coordinate]'s artifact, newest-first (the version picker). */
    suspend fun availableVersions(moduleName: String, coordinate: String): List<String> = emptyList()

    /** Update a declared library [coordinate] — change its version/scope/exclusions in one re-resolve. */
    suspend fun updateDependency(moduleName: String, coordinate: String, version: String, scope: String, exclusions: List<String>): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** Other modules [moduleName] may depend on (no self/cycle/duplicate). */
    fun moduleDependencyTargets(moduleName: String): List<String> = emptyList()

    /** Add a module-on-module dependency from [moduleName] onto [targetModule] at [scope]; [variant] scopes it. */
    suspend fun addModuleDependency(moduleName: String, targetModule: String, scope: String, variant: String? = null): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** The directory a picked local library should be copied into, or null. */
    fun localLibraryDropDir(moduleName: String): String? = null

    /** Existing `.jar`/`.aar` files under the project that [moduleName] could depend on. */
    fun localLibraryCandidates(moduleName: String): List<String> = emptyList()

    /** Attach the local library at [path] to [moduleName] at [scope]. */
    suspend fun addLocalLibrary(moduleName: String, path: String, scope: String): UiAddResult =
        UiAddResult(false, "Dependency management not supported by this backend")

    /** The Maven repositories libraries resolve from. */
    fun repositories(): List<UiRepository> = emptyList()

    /** Add a custom Maven repository. */
    fun addRepository(name: String, url: String): Boolean = false

    /** Remove the user-added repository at [url]. */
    fun removeRepository(url: String): Boolean = false
}

// ---------------------------------------------------------------------------
// Modules (config + management + source roots)
// ---------------------------------------------------------------------------

/** Module configuration + management: source sets/roots, language level, facets, add/remove modules. */
interface ModuleService {
    /** Source-set names declared on [moduleName]. */
    fun moduleSourceSets(moduleName: String): List<String> = emptyList()

    /** Add a typed source root named [dirName] to [sourceSetName] of [moduleName]. */
    fun addSourceRoot(moduleName: String, sourceSetName: String, dirName: String, role: UiSourceRootRole): String? = null

    /** Unmark the content root at [rootPath] from [sourceSetName] of [moduleName] (model-only). */
    fun removeSourceRoot(moduleName: String, sourceSetName: String, rootPath: String): Boolean = false

    /** Create an empty source set [name] on [moduleName]. */
    fun addSourceSet(moduleName: String, name: String): Boolean = false

    /** Modules whose configuration can be edited. */
    fun configurableModules(): List<UiModuleRef> = emptyList()

    /** The editable configuration of [moduleName] (type, language level, source sets, facet panels). */
    suspend fun getModuleConfig(moduleName: String): UiModuleConfig? = null

    /** Persist [edit] to [moduleName] (language level + facet values) through a model transaction. */
    suspend fun updateModuleConfig(moduleName: String, edit: UiModuleConfigEdit): UiConfigResult =
        UiConfigResult(false, "Module configuration not supported by this backend")

    /** The Android `buildFeatures` toggles for [moduleName], or null when it is not an Android module. */
    suspend fun getBuildFeatures(moduleName: String): UiBuildFeatures? = null

    /**
     * Turn an Android build feature ([feature] = `viewBinding`/`compose`) on or off for [moduleName].
     * Enabling a feature also adds the dependencies it needs (the ViewBinding/Compose runtime), like AGP.
     */
    suspend fun setBuildFeature(moduleName: String, feature: String, enabled: Boolean): UiConfigResult =
        UiConfigResult(false, "Build features not supported by this backend")

    /** The Kotlin compiler plugins available to [moduleName] (Compose, Serialization, Parcelize) with their
     *  enable-state, or null when it is not an Android module. */
    suspend fun getCompilerPlugins(moduleName: String): UiCompilerPlugins? = null

    /**
     * Turn a Kotlin compiler plugin ([pluginId] = the plugin's `pluginId`) on or off for [moduleName]. Enabling
     * persists the enable-state and adds the plugin's runtime dependency, which is what activates it at build
     * time (the plugin auto-applies once its runtime is on the classpath) and in the editor.
     */
    suspend fun setCompilerPlugin(moduleName: String, pluginId: String, enabled: Boolean): UiConfigResult =
        UiConfigResult(false, "Compiler plugins not supported by this backend")

    /** The Android packaging options (Java-resource + native-lib merge rules) for [moduleName], or null when
     *  it is not an Android module. */
    suspend fun getPackagingOptions(moduleName: String): UiPackagingOptions? = null

    /** Persist the Android packaging merge rules for [moduleName] (empty lists clear the block). */
    suspend fun updatePackagingOptions(
        moduleName: String, resources: UiPackagingRules, jniLibs: UiPackagingRules
    ): UiConfigResult = UiConfigResult(false, "Packaging options not supported by this backend")

    /** For an Android module, the referenced-but-missing module-relative keep-rule files. */
    suspend fun missingProguardFiles(moduleName: String): List<UiMissingProguardFile> = emptyList()

    /** Create the referenced-but-missing keep-rule file [entry] for [moduleName]. */
    suspend fun createProguardFile(moduleName: String, entry: String): String? = null

    /** The module types a new module can be created as. */
    fun availableModuleTypes(): List<UiModuleTypeOption> = emptyList()

    /** Create a new module [name] of [typeId] with [languageLevel] and [facetValues]. */
    suspend fun createModule(name: String, typeId: String, languageLevel: String?, facetValues: Map<String, Map<String, Any?>>): UiConfigResult =
        UiConfigResult(false, "Module management not supported by this backend")

    /** Remove the module [name] from the project model (its files are left on disk). */
    fun removeModule(name: String): Boolean = false
}

// ---------------------------------------------------------------------------
// Signing keystores
// ---------------------------------------------------------------------------

/**
 * Signing-keystore management: the global registry (create/import/validate/delete) plus per-module
 * assignment of a keystore to a build type. Keystores + their passwords live in the app-home registry, never
 * in a project; a build type stores only the keystore's id.
 */
interface SigningService {
    /** Every registered keystore, with a best-effort certificate summary. */
    suspend fun keystores(): List<UiKeystore> = emptyList()

    /** Generate a new keystore (keypair + self-signed cert) and register it. */
    suspend fun createKeystore(spec: UiKeystoreSpec): UiKeystoreResult = UiKeystoreResult(false, "Not supported by this backend")

    /** Import the keystore at [filePath] after verifying [storePass]; register it under [name]. */
    suspend fun importKeystore(filePath: String, name: String, storePass: String, keyAlias: String, keyPass: String): UiKeystoreResult =
        UiKeystoreResult(false, "Not supported by this backend")

    /** Open [filePath] with [storePass] and report its aliases + certs, or the error. */
    suspend fun validateKeystore(filePath: String, storePass: String): UiKeystoreValidation =
        UiKeystoreValidation(false, emptyList(), emptyList(), "Not supported by this backend")

    /** Remove keystore [id] from the registry (and delete its file). */
    fun deleteKeystore(id: String): Boolean = false

    /** The names of modules that produce a signed APK (android-app) — the modules whose signing is meaningful. */
    fun signableModules(): List<String> = emptyList()

    /** Per-build-type signing assignments for [moduleName] + the assignable keystores. Null ⇒ not Android. */
    suspend fun signingAssignments(moduleName: String): UiSigningAssignments? = null

    /** Assign [keystoreId] (null ⇒ the default debug keystore) to sign [moduleName]'s [buildType]. */
    suspend fun assignSigning(moduleName: String, buildType: String, keystoreId: String?): UiConfigResult =
        UiConfigResult(false, "Not supported by this backend")
}

// ---------------------------------------------------------------------------
// Projects (the picker + create/open + session)
// ---------------------------------------------------------------------------

/** Project management: the picker, create/open/delete, templates, storage roots, open-tab session. */
interface ProjectService {
    /** Every project the host knows about (for the picker). */
    fun projects(): List<ProjectInfo> = emptyList()

    /**
     * The launcher icon for the Android project rooted at [rootPath] — raster bytes or a render-ready
     * drawable (see [UiProjectIcon]) — or null when the project is not Android or has no resolvable icon.
     * Resolved off the main thread so the picker stays responsive; the UI renders/decodes it per card.
     */
    suspend fun projectIcon(rootPath: String): UiProjectIcon? = null

    /** The on-disk directory that holds every project, or null. */
    fun projectsRootPath(): String? = null

    /** The whole app storage root (projects + SDK + caches + sibling data). Defaults to [projectsRootPath]. */
    fun storageRootPath(): String? = projectsRootPath()

    /** The templates the Create-Project gallery offers. */
    fun projectTemplates(): List<UiProjectTemplate> = emptyList()

    /** Create a new project from [templateId] with [args]; becomes active (bumps [projectEpoch]). */
    suspend fun createProject(templateId: String, args: Map<String, String>): UiProjectResult =
        UiProjectResult(false, "Project creation not supported by this backend")

    /** Open the existing project rooted at [rootPath]; becomes active (bumps [projectEpoch]). */
    suspend fun openProject(rootPath: String): Boolean = false

    /** Permanently delete the project rooted at [rootPath] from disk. */
    suspend fun deleteProject(rootPath: String): Boolean = false

    /** Bumps whenever the active project changes (create/open). The UI keys per-project state on this. */
    val projectEpoch: StateFlow<Int> get() = MutableStateFlow(0)

    /** Back up the user's projects into a single `.zip`, returning its path, or null. */
    suspend fun backupProjects(): String? = null

    /** The editor tabs open the last time the active project was used. */
    fun openTabs(): UiOpenTabs = UiOpenTabs()

    /** Persist the open editor tabs for the active project. */
    fun saveOpenTabs(tabs: UiOpenTabs) {}

    /**
     * Compatibility details for the currently-open project, or null when it is a native project (not imported
     * from Gradle). Drives the editor's compatibility-mode notice — see [UiCompatibilityInfo].
     */
    fun compatibilityInfo(): UiCompatibilityInfo? = null

    /**
     * Re-read the open compatibility-mode project's Gradle build scripts into the model (modules, dependencies,
     * Android config), then re-resolve dependencies and re-index. Slow (parses + network resolution), so it
     * suspends off the main thread. No-op returning `ok = false` when the project isn't a Gradle import.
     */
    suspend fun syncGradle(): UiSyncResult = UiSyncResult(false, "Not a Gradle project")

    /**
     * Import the Gradle project at [sourceRootPath] into a new native workspace under the projects root and
     * open it in compatibility mode (bumps [projectEpoch]). Returns a failure result when the folder isn't an
     * importable Gradle project or no project manager is available.
     */
    suspend fun importGradleProject(sourceRootPath: String): UiProjectResult =
        UiProjectResult(false, "Gradle import not supported by this backend")

    /**
     * Export the project at [rootPath] to a shareable `.caproj` package and return its path (under the app's
     * exports dir), or null when packaging failed. The UI then hands the path to [FileActions.share] /
     * [FileActions.exportFile]. Runs off the main thread.
     */
    suspend fun exportProject(rootPath: String, options: UiExportOptions): String? = null

    /**
     * Read the `.caproj` at [archivePath] for the import preview (manifest, file peek, icon) without
     * extracting it. Returns null when the file isn't a readable package.
     */
    suspend fun previewImportPackage(archivePath: String): UiImportPreview? = null

    /**
     * Import the `.caproj` at [archivePath] into a new workspace and open it (bumps [projectEpoch]). Returns a
     * failure result when the package is invalid or its format is unsupported.
     */
    suspend fun importPackage(archivePath: String): UiProjectResult =
        UiProjectResult(false, "Project import not supported by this backend")
}

// ---------------------------------------------------------------------------
// Projects Store (the featured/searchable catalog of templates + sample projects)
// ---------------------------------------------------------------------------

/**
 * The Projects Store: a featured, searchable catalog of starter templates and sample projects, browsed from
 * the home screen's Store tab. Today a host serves the bundled project templates through this seam; the same
 * contract is what a remote (submission-backed) catalog later implements, so the UI never changes. A backend
 * that wires no store inherits [StoreService.Unsupported] (the store tab then shows an unavailable state).
 */
interface StoreService {
    /** Whether a catalog source is configured. False ⇒ the Store tab renders an unavailable placeholder. */
    fun storeAvailable(): Boolean = false

    /** The store landing payload: featured carousel + filter categories + section shelves. */
    suspend fun catalog(): UiStoreCatalog = UiStoreCatalog()

    /** Items matching [query] (blank = all), optionally narrowed to [category] (null = every category). */
    suspend fun search(query: String, category: String? = null): List<UiStoreItem> = emptyList()

    /**
     * Install the store item [id] into the workspace. A [UiStoreItemKind.Template] item is created through the
     * normal template flow (the UI passes the configure-form [args]); a sample/community item downloads its
     * ready-made project. A successful create/install bumps [ProjectService.projectEpoch].
     */
    suspend fun install(id: String, args: Map<String, String> = emptyMap()): UiStoreInstallResult =
        UiStoreInstallResult(false, "Explore is not available in this build")

    companion object {
        /** A store that advertises nothing — the default for backends that wire no catalog. */
        val Unsupported: StoreService = object : StoreService {}
    }
}

// ---------------------------------------------------------------------------
// Learn (interactive lesson tracks + auto-checked exercises)
// ---------------------------------------------------------------------------

/**
 * The interactive Learn experience: lesson tracks (Kotlin Basics, Java Basics, …), step-by-step content, and
 * exercises the app compiles + runs + auto-checks. Content is bundled today; the same contract is what a
 * remote (submission-backed) lesson catalog later implements, so the UI never changes. Progress is persisted
 * locally through this seam. A backend that wires no content inherits [LearnService.Unsupported] (the Learn
 * tab then shows only its jumping-off links).
 *
 * Exercise answers are checked here, on the backend ([check]) — they never cross to the UI. Quiz correctness
 * travels in the DTO ([UiLessonStep.Quiz.correctIndex]) and is graded client-side.
 */
interface LearnService {
    /** Whether a lesson catalog is configured. False ⇒ the Learn tab shows only its link cards. */
    fun learnAvailable(): Boolean = false

    /** The Learn landing payload: the ordered tracks with their lesson summaries. */
    suspend fun catalog(): UiLearnCatalog = UiLearnCatalog()

    /** The fully-loaded lesson [id] (its ordered steps), or null if unknown. */
    suspend fun lesson(id: String): UiLesson? = null

    /**
     * Code completion for an interactive exercise's editor: completes [code] at [offset] against the hidden
     * scratch project for [language] (`"kotlin"` | `"java"`), so a lesson buffer gets real member/keyword/
     * stdlib suggestions. Empty when no scratch engine is available.
     */
    suspend fun complete(language: String, code: String, offset: Int): UiCompletionResult =
        UiCompletionResult(emptyList(), offset, offset)

    /** Live diagnostics (errors/warnings) for an interactive exercise's [code], analyzed against the scratch
     *  project for [language] (`"kotlin"` | `"java"`). Empty when unavailable. */
    suspend fun analyze(language: String, code: String): List<UiDiagnostic> = emptyList()

    /** Inlay hints (inferred `val`/lambda types, parameter names, chained-call types) for an interactive
     *  exercise's [code] in `[startOffset, endOffset)`, computed against the scratch project for [language]
     *  (`"kotlin"` | `"java"`) — the same intelligence the project editor shows. Empty when unavailable. */
    suspend fun hints(language: String, code: String, startOffset: Int, endOffset: Int): List<UiInlayHint> = emptyList()

    /** Foldable regions for an interactive exercise's [code], computed against the scratch project for
     *  [language] — the same code-folding the project editor shows. Empty when unavailable. */
    suspend fun folds(language: String, code: String): List<UiFoldRegion> = emptyList()

    /**
     * Prepare the scratch project for [language] so a lesson's editor has real intelligence from the first
     * keystroke: create it (if needed) and wait until its index is built (bounded by a timeout). Returns true
     * once ready. Call before showing an interactive step.
     */
    suspend fun prepare(language: String): Boolean = true

    /** Whether the scratch project for [language] is still building its index (completion/diagnostics are
     *  limited until it finishes) — drives the lesson editor's "Indexing…" indicator + a re-analyze when done. */
    suspend fun indexing(language: String): Boolean = false

    /**
     * Compile + run the learner's [code] for the interactive step [stepId] of lesson [lessonId] and check it
     * against the exercise's expected result. Cold on the first call (compiler warm-up), fast afterwards.
     */
    suspend fun check(lessonId: String, stepId: String, code: String): UiExerciseResult =
        UiExerciseResult(passed = false, compiled = false, message = "Learning exercises are not available in this build")

    /** The locally-persisted progress (completed step ids per lesson). */
    fun progress(): UiLearnProgress = UiLearnProgress()

    /** Mark step [stepId] of lesson [lessonId] complete and record it as the resume point. */
    fun markStepComplete(lessonId: String, stepId: String) {}

    /** Record the learner's current place (for Resume) without marking it complete. */
    fun recordVisit(lessonId: String, stepIndex: Int) {}

    /** Where "Resume" on the Learn banner should go, or null if nothing has been started. */
    fun resume(): UiResumePoint? = null

    companion object {
        /** A Learn service with no content — the default for backends that wire none. */
        val Unsupported: LearnService = object : LearnService {}
    }
}

// ---------------------------------------------------------------------------
// SDK / toolchain manager
// ---------------------------------------------------------------------------

/** The SDK manager: download editor sources/docs for the Android SDK + JDK. */
interface SdkService {
    /** Live download queue + progress. */
    val sdkManagerState: StateFlow<UiSdkManagerState> get() = MutableStateFlow(UiSdkManagerState())

    /** The installable Android SDK source packages. */
    suspend fun sdkPackages(): List<UiSdkPackage> = emptyList()

    /** Start downloading one Android package by id; returns immediately. */
    suspend fun installSdkPackage(path: String): String = "Not supported."

    /** Cancel an in-flight SDK/JDK download by id. */
    fun cancelSdkDownload(id: String) {}

    /** Drop the finished entries from the download queue. */
    fun clearSdkDownloads() {}

    /** Current JDK + whether sources are available, or null. */
    fun jdkInfo(): UiJdkInfo? = null

    /** Start downloading a JDK [feature] for its sources; desktop only. */
    suspend fun downloadJdkSources(feature: Int): String = "Not supported."

    /** Android platform-sources status, or null when there's no Android SDK. */
    fun androidSourcesInfo(): UiAndroidSourcesInfo? = null

    /** Download the Android platform sources; returns a status message. */
    suspend fun downloadAndroidSources(): String = "Not supported."
}

// ---------------------------------------------------------------------------
// Settings / inspections / preferences
// ---------------------------------------------------------------------------

/**
 * One built-in plugin in the Plugins settings screen. [essential] plugins are shown locked (the IDE can't run
 * without them). [enabled] is the user's persisted choice; changes apply on the next launch (restart-apply).
 */
data class UiPluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val essential: Boolean,
    val enabled: Boolean,
    val dependsOn: List<String> = emptyList(),
)

/** IDE settings, the extensible settings pages, the inspection catalogue, and app preferences. */
interface SettingsService {
    /** App-global settings the editor applies live (theme, accent, font, inlay). */
    fun settings(): UiSettings = UiSettings()

    /** Every page to render in the Settings screen (built-in + plugin-contributed). */
    fun settingsPages(): List<UiSettingsPage> = emptyList()

    /** Write control [key] on page [pageId] and apply it. */
    fun setSetting(pageId: String, key: String, value: String) {}

    /** Press a settings action (e.g. "Clear caches") on page [pageId]; returns a status message. */
    suspend fun invokeSettingAction(pageId: String, key: String): String? = null

    /** The per-language code style profile for the Code Style screen ([languageId] = "java" | "kotlin"). */
    fun codeStyle(languageId: String): UiCodeStyle = UiCodeStyle()

    /** Persist the per-language code style profile (takes effect on the next reformat). */
    fun setCodeStyle(languageId: String, style: UiCodeStyle) {}

    /** Format the built-in preview sample for [languageId] with [style] (the in-progress, unsaved profile). */
    suspend fun formatStylePreview(languageId: String, style: UiCodeStyle): String = ""

    /** The per-project inspection catalogue (analyzer + enabled state + severity). */
    fun inspections(): List<UiInspection> = emptyList()

    /** Enable/disable inspection [id] and set its severity. */
    fun setInspection(id: String, enabled: Boolean, severity: UiSeverity) {}

    /** Read an app-global preference, or null if unset. */
    fun preference(key: String): String? = null

    /** Persist an app-global preference. */
    fun setPreference(key: String, value: String) {}

    /** The built-in plugins for the Plugins settings screen (all, with enabled/essential state). */
    fun pluginCatalog(): List<UiPluginInfo> = emptyList()

    /** Enable or disable built-in plugin [id]. Persisted app-globally and applied on the next launch; a no-op
     *  for an essential plugin. */
    fun setPluginEnabled(id: String, enabled: Boolean) {}
}

// ---------------------------------------------------------------------------
// UI actions (toolbar / menus / command palette)
// ---------------------------------------------------------------------------

/** The IntelliJ-style action surface: resolve/invoke contributed toolbar/menu/palette actions. */
interface ActionService {
    /** The visible actions for [ctx]'s place, ordered for display. */
    fun actionsFor(ctx: UiActionContext): List<UiActionItem> = emptyList()

    /** The resolved menu tree for [ctx]'s place (a context menu). */
    fun menuFor(ctx: UiActionContext): UiMenuGroup = UiMenuGroup()

    /** Run the action [id] and return its outcome (a message + effects to apply). */
    suspend fun invokeAction(id: String, ctx: UiActionContext): UiActionResult = UiActionResult()
}

// ---------------------------------------------------------------------------
// Diagnostics: critical errors, logs, analytics
// ---------------------------------------------------------------------------

/** The non-fatal error dialog, the in-app logs viewer, and opt-in usage analytics. */
interface DiagnosticsService {
    /** The current unexpected error to surface as a non-fatal dialog, or null. */
    val errorEvents: StateFlow<UiError?> get() = MutableStateFlow(null)

    /** Dismiss the shown error [id]; surfaces the next queued error. */
    fun dismissError(id: Int) {}

    /** A snapshot of the most recent in-memory log records, oldest first. */
    fun recentLogs(): List<UiLogEntry> = emptyList()

    /** Write the current logs to a shareable text file and return its path, or null. */
    suspend fun exportLogs(): String? = null

    /** Whether this backend actually has analytics wired (a transport is configured). */
    fun analyticsAvailable(): Boolean = false

    /** The user's analytics-consent decision: true/false/null (not yet asked). */
    fun analyticsConsent(): Boolean? = null

    /** Record the user's analytics decision. */
    fun setAnalyticsConsent(granted: Boolean) {}

    /** Record an analytics [event] (performance metrics only; never user content). */
    fun track(event: String, props: Map<String, String> = emptyMap()) {}
}
