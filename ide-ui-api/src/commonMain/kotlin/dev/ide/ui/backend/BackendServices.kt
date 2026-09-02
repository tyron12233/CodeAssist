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

    /** Code actions at the selection `[selStart, selEnd)`: analysis quick-fixes and intentions merged with
     *  the plugin actions placed on [UiActionPlaces.EDITOR] (those carry a [UiAction.actionId]). */
    suspend fun actionsAt(path: String, text: String, selStart: Int, selEnd: Int): List<UiAction> = emptyList()

    /**
     * What the caret is on at [offset] in [path]'s live buffer: the flat snapshot an editor action is
     * resolved against. Null when the file has no language backend or is not parseable.
     *
     * The UI needs this to build a [UiActionContext] for the editor places, since it has no parse tree of
     * its own. Cheap (syntax-only), but it is a backend round-trip, so fetch it when a surface that needs
     * it opens rather than on every caret move.
     */
    suspend fun caretContext(path: String, text: String, offset: Int): UiCaretContext? = null

    /** Compute the edits for the code action [actionId] from [actionsAt] over the same buffer + selection. */
    suspend fun applyAction(path: String, text: String, selStart: Int, selEnd: Int, actionId: Int): List<UiTextEdit> = emptyList()

    /** Reformat the whole buffer to the active code style. Empty if unsupported / already formatted. */
    suspend fun formatDocument(path: String, text: String): List<UiTextEdit> = emptyList()

    /** Reformat only the text overlapping the selection `[selStart, selEnd)`. */
    suspend fun formatRange(path: String, text: String, selStart: Int, selEnd: Int): List<UiTextEdit> = emptyList()

    /** Reorder + de-duplicate + wildcard-collapse + drop-unused the file's imports ("Optimize Imports").
     *  Empty if unsupported / already optimal. */
    suspend fun optimizeImports(path: String, text: String): List<UiTextEdit> = emptyList()

    /** Go-to-definition for the symbol/reference at [offset], or null. */
    suspend fun definitionAt(path: String, text: String, offset: Int): UiDefinition? = null

    /** Source go-to targets for the symbol at [offset] ([kind]: declaration / implementation / type / super).
     *  0 → nothing found; 1 → navigate; >1 → the caller shows a picker. Kotlin source only for now. */
    suspend fun navigationTargets(path: String, text: String, offset: Int, kind: UiNavKind): List<UiNavTarget> = emptyList()

    /** The navigation actions APPLICABLE at [offset] (each with its resolved targets) — so the Go-to menu shows
     *  only usable actions. Empty when nothing applies. Kotlin source only for now. */
    suspend fun navigationOptions(path: String, text: String, offset: Int): List<UiNavOption> = emptyList()

    /** The read-only content for a `library://<fqn>` target: attached source, else a decompiled view (full-body
     *  Java, or a Kotlin declaration stub). [forceJava] runs the Java decompiler on any class ("Decompile to
     *  Java"). Resolved against [contextPath]'s module classpath. Null when the class isn't found. */
    suspend fun libraryContent(contextPath: String, fqn: String, forceJava: Boolean = false): UiLibraryContent? = null

    /** Gutter inheritor ("implementations") markers for [text] — one per inheritable type with direct subtypes.
     *  Empty for languages/files without the subtype relation indexed. */
    suspend fun inheritorMarkers(path: String, text: String): List<UiInheritorMarker> = emptyList()

    /** Resolve an inheritor [fqn] (from an [inheritorMarkers] target) to its source location for
     *  go-to-implementation, relative to [contextPath]'s module. Null when it's classpath-only (no source). */
    suspend fun implementationLocationOf(contextPath: String, fqn: String): UiDefinition? = null

    /** Quick documentation (signature + doc comment) for the symbol at [offset], or null. */
    suspend fun quickDocAt(path: String, text: String, offset: Int): UiQuickDoc? = null

    /** Expand the selection `[selStart, selEnd)` to the smallest enclosing structural node — one step of a walk
     *  UP the tolerant DOM (word → expression → statement → block → method → class …). Returns that node's
     *  range, or null when nothing larger encloses the selection (or the backend can't parse [path]). Because it
     *  re-derives from the passed selection, repeated calls climb the tree one level at a time. Drives the
     *  editor's "expand selection" gesture (multi-click / triple-tap). */
    suspend fun expandSelection(path: String, text: String, selStart: Int, selEnd: Int): UiTextRange? = null

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
    /**
     * Whether the block editor is available at all — false when the `blocks` plugin is disabled (no block
     * mapping is registered). The shell reads this once to decide whether to offer the Blocks view-mode
     * segment; when false the toggle omits it and a persisted `blocks` tab restores as plain text. Defaults to
     * false so a backend that wires no block editor never shows the toggle.
     */
    fun blocksEnabled(): Boolean = false

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

    /** Whether [path]'s module can resolve library composables yet (the workspace index has finished building).
     *  The preview pane gates rendering on this: interpreting a preview while the index is still building resolves
     *  library calls (e.g. material3's `lightColorScheme`) to zero candidates and would latch a permanent
     *  "unresolved call" failure that never self-heals. Defaults to true so stub/non-indexing backends render
     *  immediately (unchanged behavior). */
    suspend fun composePreviewReady(path: String): Boolean = true

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

    /** Forward a pointer event into a windowed program's UI; [x] and [y] are in the frame's pixel space and
     *  [action] is a `RunPointer` constant. No-op for a console run, which has no window. */
    fun sendRunPointer(action: Int, x: Float, y: Float) {}

    /** Forward a key event into a windowed program's UI. No-op for a console run. */
    fun sendRunKey(action: Int, keyCode: Int, keyChar: Char) {}

    /** Forward a scroll into a windowed program's UI; [notches] is positive when the content should move
     *  down. No-op for a console run. */
    fun sendRunScroll(x: Float, y: Float, notches: Int) {}

    /** Tell a windowed program the pixel size its window is drawn at, so it paints at that size rather than
     *  being scaled to fit. No-op for a console run. */
    fun setRunSurfaceSize(widthPx: Int, heightPx: Int) {}

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

    /** Versions of the library [group]:[name] currently downloaded to the shared cache, each with its size
     *  on disk (newest-first) — the Dependencies editor's downloaded-versions cleanup list. */
    suspend fun cachedVersions(group: String, name: String): List<UiCachedVersion> = emptyList()

    /** Delete the cached [version] of [group]:[name] from the shared download store to reclaim disk; a later
     *  build re-downloads it if needed. Returns true when the version was present. */
    suspend fun deleteCachedVersion(group: String, name: String, version: String): Boolean = false

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

    /**
     * Toolchain problems that will break the build, across EVERY module of the open project (a bundled KSP
     * processor whose generated code needs a newer runtime than the module declares). Drives the editor banner.
     *
     * Project-wide, not per file: the problem belongs to a module's configuration, so it is knowable the moment
     * the project opens and should not wait for someone to open a file from the offending module (typically a
     * `di/` module nobody edits). Empty for a healthy project, so the banner costs nothing when there is nothing
     * to say. The probe short-circuits on modules that declare none of the bundled processors.
     */
    suspend fun toolchainWarnings(): List<UiToolchainWarning> = emptyList()

    /** Apply [warningId]'s fix on [moduleName]: set the declared runtime to the version the IDE bundles. */
    suspend fun fixToolchainWarning(moduleName: String, warningId: String): UiConfigResult =
        UiConfigResult(false, "Toolchain warnings not supported by this backend")

    /**
     * Record that the user accepts [warningId] on [moduleName]: source generation stops refusing to run and the
     * problem is reported once per build instead. Persisted on the module, and NOT a fix (the compile is still
     * expected to fail on the generated code).
     */
    suspend fun acceptToolchainWarning(moduleName: String, warningId: String): UiConfigResult =
        UiConfigResult(false, "Toolchain warnings not supported by this backend")

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

    /**
     * A breakdown of what's using disk under the app storage root — total plus per-category sizes and
     * per-project sizes — for the Storage screen's usage graph. Walks the managed storage, so it suspends
     * off the main thread. Null when the backend has no project manager.
     */
    suspend fun storageReport(): UiStorageReport? = null

    /**
     * Delete the regenerable storage the category [id] owns (see [UiStorageCategory.id]) — caches, or the
     * app-owned SDK/toolchain dirs. A no-op returning false for a read-only category (project source, other
     * files) or an unknown id; never removes source, config, or keystores. Suspends off the main thread.
     */
    suspend fun clearStorageCategory(id: String): Boolean = false

    /** The editor tabs open the last time the active project was used. */
    fun openTabs(): UiOpenTabs = UiOpenTabs()

    /** Whether a tab session was ever persisted for the active project (the open-tabs file exists) — true even
     *  when it recorded ZERO tabs. Lets the UI tell a genuine first open (auto-open a default file) from a
     *  project the user deliberately left with no tabs open (respect the empty editor on return). */
    fun hasSavedSession(): Boolean = false

    /** Persist the open editor tabs for the active project. */
    fun saveOpenTabs(tabs: UiOpenTabs) {}

    /**
     * Compatibility details for the currently-open project, or null when it is a native project (one whose
     * model the IDE owns). Drives the editor's compatibility-mode notice, see [UiCompatibilityInfo].
     */
    fun compatibilityInfo(): UiCompatibilityInfo? = null

    /**
     * Details for the currently-open project when it was adopted from a folder nothing recognized, or null
     * for any project the IDE or an importer authored. Drives the editor's "opened for editing only" notice,
     * see [UiUnrecognizedProject].
     */
    fun unrecognizedProjectInfo(): UiUnrecognizedProject? = null

    /**
     * Re-read the open project's build files into the model (modules, dependencies, Android config), then
     * re-resolve dependencies and re-index. Slow (parses plus network resolution), so it suspends off the main
     * thread. No-op returning `ok = false` for a project whose model the IDE itself owns.
     */
    suspend fun syncProject(): UiSyncResult = UiSyncResult(false, "This project has no build files to sync from.")

    /**
     * Convert the open Gradle compatibility-mode project to a native CodeAssist project: the leftover Gradle
     * build files are MOVED to a backup folder and the compatibility marker is dropped, so `module.toml`
     * becomes the sole source of truth (Re-sync no longer applies). The model is unchanged — no re-resolve or
     * re-index needed. No-op returning `ok = false` when the project isn't a compatibility-mode import.
     */
    suspend fun convertToNative(): UiConvertResult = UiConvertResult(false, "Not a Gradle project")

    /** Undo a [convertToNative]: restore the backed-up Gradle build files and re-enter compatibility mode. */
    suspend fun revertToGradle(): UiConvertResult = UiConvertResult(false, "Nothing to revert")

    /**
     * What kind of project, if any, the folder at [path] holds — so the picker can ask the questions that
     * actually apply to it. A CodeAssist workspace is adopted as-is and has no compatibility/convert choice
     * to make; only a foreign build system does.
     */
    suspend fun inspectProjectFolder(path: String): UiProjectFolderKind = UiProjectFolderKind.UNKNOWN

    /**
     * Import the project at [sourceRootPath] into a new workspace under the projects root and open it (bumps
     * [projectEpoch]). A CodeAssist workspace is copied verbatim; a foreign build system (Gradle today) is
     * read statically and opened in compatibility mode. Returns a failure result when the folder is neither,
     * or when no project manager is available.
     */
    suspend fun importExternalProject(sourceRootPath: String): UiProjectResult =
        UiProjectResult(false, "Project import not supported by this backend")

    /**
     * Export the project at [rootPath] to a shareable `.caproj` package and return its path (under the app's
     * exports dir), or null when packaging failed. The UI then hands the path to [FileActions.share] /
     * [FileActions.exportFile]. Runs off the main thread.
     */
    suspend fun exportProject(rootPath: String, options: UiExportOptions): String? = null

    /**
     * Export the project at [rootPath] as a Gradle project (sources plus generated build scripts, zipped
     * under the app exports dir) so it can be opened in Android Studio or built with `gradle`. Best effort:
     * the scripts are derived from the project model, and whatever has no Gradle equivalent comes back in
     * [UiGradleExport.notes]. Null when the export failed. Runs off the main thread.
     */
    suspend fun exportGradleProject(rootPath: String): UiGradleExport? = null

    /**
     * What the export screen can offer for the project at [rootPath]: its modules (with the share of the
     * package each accounts for) and what bundling the resolved dependencies would cost. Walks the project
     * tree, so it suspends. Null when [rootPath] holds no readable project.
     */
    suspend fun exportPlan(rootPath: String): UiExportPlan? = null

    /**
     * Read the `.caproj` at [archivePath] for the import preview (manifest, contents, icon) without
     * extracting it. Returns null when the file isn't a readable package.
     */
    suspend fun previewImportPackage(archivePath: String): UiImportPreview? = null

    /**
     * Import the `.caproj` at [archivePath] into a new workspace and open it (bumps [projectEpoch]). A
     * non-blank [projectName] overrides the name in the package, for both the project and the directory it
     * lands in. Returns a failure result when the package is invalid or its format is unsupported.
     */
    suspend fun importPackage(archivePath: String, projectName: String? = null): UiProjectResult =
        UiProjectResult(false, "Project import not supported by this backend")

    /** Where [importPackage] would put a project imported under [projectName], so the import preview can show
     *  the destination before committing. Checks the projects directory for name collisions, so it suspends.
     *  Null when this backend has no projects directory. */
    suspend fun importDestination(projectName: String): String? = null

    /** Raw bytes of the image at [path], for previewing a file the user picked outside any project (the
     *  export screen's screenshots). Null when it isn't a readable image of a sane size. */
    suspend fun imageBytes(path: String): ByteArray? = null
}

/**
 * The Storage screen's usage report: a total plus per-category and per-project sizes, all in bytes so the
 * UI can draw proportional segments and format the numbers itself. Titles/descriptions are NOT carried here
 * — the UI resolves them (and each category's color) from [UiStorageCategory.id], keeping user-facing text
 * in the localized resource bundle. [openProjectRootPath] is the currently-open project (so the screen can
 * refuse to delete it), or null when none is open.
 */
data class UiStorageReport(
    val storageRootPath: String,
    val totalBytes: Long,
    val categories: List<UiStorageCategory>,
    val projects: List<UiStorageProject>,
    val openProjectRootPath: String?,
)

/**
 * One slice of storage. [id] is a stable key (e.g. `"dependencies"`, `"sdk"`, `"projects"`) the UI maps to a
 * title, description, and color. [clearable] shows a Clear action; [destructive] means it needs a
 * confirmation first (the SDK/toolchain). Read-only categories (project source, other files) have both false.
 */
data class UiStorageCategory(
    val id: String,
    val bytes: Long,
    val colorId: String,
    val clearable: Boolean,
    val destructive: Boolean,
)

/** One managed project in the Storage screen's delete list. [bytes] is the full size freed by deleting it. */
data class UiStorageProject(
    val name: String,
    val rootPath: String,
    val bytes: Long,
    val isAndroid: Boolean,
)

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

    /**
     * The server-driven Explore feed: the mode, the store's state, and the ordered sections.
     *
     * Null means no feed is available at all (no remote store configured, and nothing cached), which is
     * different from an empty store — the caller falls back to [catalog]'s bundled shelves rather than
     * rendering the zero-data screen, because "we cannot reach the store" and "nobody has published
     * anything" are opposite claims.
     *
     * [seedItemId] is the locally most-recently-installed item, supplied by the caller because under the
     * anonymous personalization model only the device knows its own install history.
     *
     * The app's build number is NOT a parameter: it belongs to the backend's store source, which knows
     * the installation it is part of. The UI has no way to know it and should not have to pass it.
     */
    suspend fun feed(seedItemId: String? = null): UiStoreFeed? = null

    /**
     * Live install progress, keyed by item id.
     *
     * Keyed rather than singular so concurrent installs each report their own progress; the UI must not
     * share one value across rows.
     */
    fun installProgress(): kotlinx.coroutines.flow.StateFlow<Map<String, UiInstallProgress>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

    /**
     * Count one install of [id].
     *
     * Called by the engine **after** an install actually succeeds, never by the UI on a button press —
     * counting on intent rather than completion inflates the very numbers the charts rank on. [install]
     * already does it, so no UI code should need this; it also blocks briefly on the network, so a caller
     * would have to be off the main thread.
     */
    fun recordInstall(id: String) {}

    // ---- accounts (publishing only; browsing and installing never need one) ----

    /**
     * Which providers this build can sign in with, as their wire names ("github").
     *
     * Empty means sign-in is unavailable and the UI must not offer it: a provider needs an OAuth app
     * registered on the backend, and a button that cannot succeed is worse than no button.
     */
    fun authProviders(): List<String> = emptyList()

    /**
     * Ask the backend which providers to offer, and return the result.
     *
     * [authProviders] answers from cache so it can be read during composition; this is the call that
     * refreshes it. A provider turned on server-side therefore appears the next time a sign-in surface
     * opens, with no app release — which is how a provider awaiting external approval ships disabled.
     */
    suspend fun refreshAuthProviders(): List<String> = authProviders()

    /** The live sign-in state. Safe to collect during composition. */
    fun authState(): kotlinx.coroutines.flow.StateFlow<UiStoreAuthState> =
        kotlinx.coroutines.flow.MutableStateFlow(UiStoreAuthState())

    /**
     * Start a sign-in and return the URL the caller must open in a browser, or null if it cannot start.
     *
     * The engine does not open the browser itself: on Android that is a custom tab owned by the activity,
     * and on desktop it is the system handler, neither of which the engine can reach.
     */
    fun beginSignIn(provider: String): String? = null

    /**
     * Finish a sign-in from the redirect the provider sent back to the app.
     *
     * Called by the host that receives the deep link, not by a screen. Returns immediately and reports
     * through [authState], because the token exchange is a network call and the redirect can arrive while
     * no store screen is on top, or before the UI exists at all.
     */
    fun completeSignIn(redirect: String) {}

    fun signOut() {}

    // ---- submitting ----

    /**
     * Whether this build can publish at all. False hides every publish entry point.
     *
     * Separate from [authProviders] because they fail differently: no provider means nobody can sign in,
     * while no submission service means a signed-in user still cannot upload.
     */
    fun submissionsAvailable(): Boolean = false

    /**
     * Zip a project for submission, without uploading anything.
     *
     * Deliberately its own step: packaging is local and cheap to redo, and its result is what the submit
     * screen shows before the user commits to publishing. That is what makes "here is exactly what will be
     * uploaded, and here is what was left out" possible.
     */
    suspend fun packProject(rootPath: String): UiPackagedProject? = null

    /**
     * Why the last [packProject] of [rootPath] failed, or null if it succeeded.
     *
     * Separate from [packProject]'s null so the screen can explain an empty result. Packaging fails for
     * specific, actionable reasons ("too large", "every file was excluded") and dropping them would leave
     * the user with a blank panel.
     */
    suspend fun packFailure(rootPath: String): String? = null

    /**
     * The categories a submission may declare, as (slug, title).
     *
     * Read from the backend rather than hardcoded: the slug is what it stores, and the list is content the
     * store owns. Not taken from the Explore feed, because an empty store has no category shelf and an
     * empty store is exactly when the first submission happens.
     */
    suspend fun submitCategories(): List<Pair<String, String>> = emptyList()

    /** Upload a packaged project and create the pending submission. Requires a signed-in account. */
    suspend fun submit(draft: UiSubmissionDraft, packaged: UiPackagedProject): UiSubmitResult =
        UiSubmitResult(false, "Publishing is not available in this build")

    /** The signed-in account's own submissions, newest first. Empty when signed out. */
    suspend fun mySubmissions(): List<UiStoreSubmission> = emptyList()

    /** Withdraw a still-pending submission. */
    suspend fun withdrawSubmission(itemId: String, version: String): Boolean = false

    // ---- ratings and reviews ----

    // ---- launch notification ----

    /**
     * Whether this install asked to be told when projects arrive in the store.
     *
     * Local, so the switch renders correctly offline and on first paint; the server side is a broadcast
     * topic on the device row.
     */
    fun launchNotificationEnabled(): Boolean = false

    /**
     * Turn the "tell me when projects arrive" subscription on or off.
     *
     * Returns null on success, or a message to show. It can fail for a mundane reason — no network, or push
     * never configured — and a switch that silently flips back is worse than one that says why.
     */
    suspend fun setLaunchNotification(enabled: Boolean): String? =
        "Notifications are not available in this build"

    /** Whether reviews can be read at all. False hides the tab rather than showing an empty one. */
    fun reviewsAvailable(): Boolean = false

    /**
     * The reviews panel for an item.
     *
     * Signed out still returns a page: reviews are part of the catalog, and only [UiReviewPage.mine] and
     * the per-review `votedByMe` flags need a session.
     */
    suspend fun reviews(
        itemId: String,
        sort: UiReviewSort = UiReviewSort.HELPFUL,
        limit: Int = 20,
    ): UiReviewPage = UiReviewPage()

    /**
     * Leave or replace the reader's review. One per account per project, so this edits rather than adds.
     *
     * Returns null on success, or a message to show. A signed-out caller gets the sign-in message rather
     * than silence, because the UI's next move is to offer sign-in.
     */
    suspend fun rate(itemId: String, stars: Int, review: String? = null): String? =
        "Reviews are not available in this build"

    suspend fun deleteMyReview(itemId: String): Boolean = false

    /** Mark a review useful, or take it back. Returns null on success or a message to show. */
    suspend fun voteReview(itemId: String, authorId: String, helpful: Boolean): String? =
        "Reviews are not available in this build"

    /**
     * Answer a review as the project's publisher. Returns null on success, or a message to show.
     *
     * Only offered when [UiReviewPage.canReply]; the backend refuses anyone else and says why.
     */
    suspend fun replyToReview(itemId: String, authorId: String, body: String): String? =
        "Reviews are not available in this build"

    suspend fun deleteReply(itemId: String, authorId: String): String? =
        "Reviews are not available in this build"

    /**
     * Flag a review for a moderator. Returns null on success, or a message to show.
     *
     * Reporting the same thing twice succeeds quietly: a reporter cannot read the queue, so telling them it
     * was already reported would only invite them to try again.
     */
    suspend fun reportReview(
        itemId: String,
        authorId: String,
        reason: UiReportReason,
        detail: String? = null,
    ): String? = "Reviews are not available in this build"

    /** Flag a whole project rather than one of its reviews. */
    suspend fun reportItem(
        itemId: String,
        reason: UiReportReason,
        detail: String? = null,
    ): String? = "Reviews are not available in this build"

    /** Hide or restore a review. Only offered when [UiReviewPage.canModerate]. */
    suspend fun setReviewHidden(itemId: String, authorId: String, hidden: Boolean): String? =
        "Reviews are not available in this build"

    // ---- likes ----

    /**
     * Like a project, or take it back. Returns null on success, or a message to show.
     *
     * The like and the saved-for-later bookmark are one thing: saving a project is the signal that you rate
     * it, so the store counts saves publicly rather than asking twice for one opinion.
     */
    suspend fun setLike(itemId: String, liked: Boolean): String? =
        "Likes are not available in this build"

    /**
     * Every project the reader has liked.
     *
     * Answers from the device first so the Saved shelf paints offline, then reconciles with the account —
     * likes made on another device belong here too.
     */
    suspend fun likedItems(): Set<String> = emptySet()

    /** Whether [itemId] is liked, from the local list. Safe to read during composition. */
    fun isLiked(itemId: String): Boolean = false

    /**
     * A remote screenshot as a local file path, downloading and caching it on first use.
     *
     * The galleries decode files rather than URLs, so this is what turns a published screenshot into
     * something they can render. Null when it could not be fetched, and the gallery shows what it has.
     */
    suspend fun screenshotFile(storagePath: String): String? = null

    // ---- publisher profiles ----

    /** A publisher's page, or null when there is no such publisher. */
    suspend fun publisherProfile(handle: String): UiPublisherProfile? = null

    /** Follow or unfollow a publisher. Returns null on success, or a message to show. */
    suspend fun setFollowing(handle: String, following: Boolean): String? =
        "Following is not available in this build"

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
    /** True for a plugin shipped inside the IDE, false for one the user installed separately. The Plugins
     *  screen lists the two under separate tabs. */
    val builtIn: Boolean = true,
    /** Where an installed plugin came from (its package name), shown on its row. Empty for a built-in. */
    val origin: String = "",
    /** Why an installed plugin did not load this launch, or null if it loaded. */
    val error: String? = null,
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
// Editor customizations: the keyboard symbol bar (+ macros, recorded macros)
// ---------------------------------------------------------------------------

/** One key on the editor's keyboard symbol bar: [label] shows on the key; [insert] is committed at the caret
 *  (a single-char insert goes through the editor's smart-insert, so brackets/quotes still auto-close). [pinned]
 *  keeps it in the fixed left group (not the scroll); [action] (a [CustomizationActions] id) makes it invoke a
 *  built-in editor op — Tab / comment / move-line — instead of inserting [insert]. */
data class UiSymbolKey(
    val label: String,
    val insert: String,
    val pinned: Boolean = false,
    val action: String? = null,
)

/** A user (or built-in) live-template macro: type [abbreviation] and accept in completion to expand [template]
 *  (`$1`/`${1:default}` tab stops, `$END$` final caret, `$FILE$`/`$DATE$`/`$USER$` variables). [languages] limits
 *  where it fires (LanguageId values; empty = all); [builtIn] marks a shipped, editable template. */
data class UiMacro(
    val abbreviation: String,
    val template: String,
    val description: String = "",
    val languages: List<String> = emptyList(),
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    /** When set (an FQN like `java.lang.String`), the macro is type-scoped: offered only at `receiver.abbrev`
     *  where the receiver matches this type. Null/blank = a plain statement macro. */
    val receiverType: String? = null,
    /** Type-scoped only: match a static reference to the type (`String.abbrev`) instead of an instance. */
    val static: Boolean = false,
)

/** Built-in editor-action ids a symbol-bar key can carry (mirror of ide-core's `SymbolActions`), so the UI can
 *  render/pick action keys and the layout can dispatch them without depending on ide-core. */
object CustomizationActions {
    const val TAB = "tab"
    const val COMMENT = "comment"
    const val MOVE_LINE_UP = "moveLineUp"
    const val MOVE_LINE_DOWN = "moveLineDown"
    const val DUPLICATE_LINE = "duplicateLine"
    const val NEXT_PROBLEM = "nextProblem"
    val ALL: List<String> = listOf(TAB, COMMENT, MOVE_LINE_UP, MOVE_LINE_DOWN, DUPLICATE_LINE, NEXT_PROBLEM)
}

/**
 * User customization of the editor — the keyboard symbol bar today, live-template macros and recorded macros
 * later. Two scopes: "global" ([CustomizationService.GLOBAL], per-user, every project) and "project"
 * ([CustomizationService.PROJECT], checked into the open project, shareable). The symbol bar renders
 * [symbolKeys]; the editor screen reads/writes a scope's list and can reset / import / export / suggest.
 * Optional — a backend that wires no store inherits [Unsupported] (every method no-ops).
 */
interface CustomizationService {
    /** The effective symbol-bar keys (project ▸ global ▸ shipped defaults). Empty only if the user blanked it. */
    fun symbolKeys(): List<UiSymbolKey> = emptyList()

    /** The symbols DEFINED at [scope], or null when that scope defines none (so it falls through to the next). */
    fun scopedSymbols(scope: String): List<UiSymbolKey>? = null

    /** Replace [scope]'s symbol list (an empty list = an intentionally blank bar; see [clearScopedSymbols]). */
    fun setScopedSymbols(scope: String, symbols: List<UiSymbolKey>) {}

    /** Unset [scope]'s symbols so it falls through to the next scope / the shipped defaults. */
    fun clearScopedSymbols(scope: String) {}

    /** The shipped default symbol keys — the "Reset to defaults" target. */
    fun defaultSymbols(): List<UiSymbolKey> = emptyList()

    /** True when [scope] is writable ("project" needs a project open; "global" needs a shared config dir). */
    fun scopeAvailable(scope: String): Boolean = false

    /** Export [scope]'s whole customization set as shareable JSON. */
    fun exportScope(scope: String): String = ""

    /** Replace [scope]'s set from [json]; returns false if the payload was malformed (the set is left as-is). */
    fun importScope(scope: String, json: String): Boolean = false

    /** Symbols frequently typed in [filePath]'s current text that aren't already in [existing], most-frequent
     *  first — the "suggest from file" helper. */
    fun suggestSymbols(filePath: String, existing: List<UiSymbolKey>): List<UiSymbolKey> = emptyList()

    /** The live-template macros DEFINED at [scope] (not the built-ins; a scope's own additions/overrides). */
    fun scopedMacros(scope: String): List<UiMacro> = emptyList()

    /** The shipped built-in macros (`builtIn = true`), so the editor can list them as editable/disable-able
     *  alongside the user's own. Editing/disabling one writes an override into a scope. */
    fun defaultMacros(): List<UiMacro> = emptyList()

    /** Replace [scope]'s macros. */
    fun setScopedMacros(scope: String, macros: List<UiMacro>) {}

    /** Expand [template] with sample values (`$FILE$`→`Example.kt`, `$DATE$`→today, …) for the editor's live
     *  preview — placeholders render as their defaults; returns "" if the template is unparseable. */
    fun previewMacro(template: String): String = ""

    /** The macro-template variable names offered in the editor (inserted as `$NAME$`, resolved at expansion):
     *  FILE, CLASS, EXPR, DATE, USER, UUID, … */
    fun macroVariables(): List<String> = emptyList()

    /** No-op service for a backend that wires no customization store. */
    object Unsupported : CustomizationService

    companion object {
        const val GLOBAL = "global"
        const val PROJECT = "project"
    }
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

/**
 * The AI coding agent: a streamed chat transcript over a tool-using agent (see docs/agentic-coding.md). The
 * UI observes [chatState] and [permissionRequest], sends user turns, and answers write-permission prompts.
 * A backend that wires no agent inherits [Unsupported].
 */
interface AgentService {
    /** The live chat transcript (messages, streaming reasoning, per-tool-call status). */
    val chatState: StateFlow<UiAgentChatState>

    /** A pending write-permission prompt in ASK_EACH mode, or null. */
    val permissionRequest: StateFlow<UiAgentPermissionRequest?>

    /** Providers, the current selection/model, whether a key is configured, and the permission mode. */
    fun config(): UiAgentConfig

    /** Models available from the current provider (fetched via [refreshModels]); falls back to the provider's
     *  known models until a live list arrives. */
    val models: StateFlow<List<UiAgentModel>>

    /** Fetch the current provider's model list with the configured key. */
    fun refreshModels()

    /** Set the active model (persisted). */
    fun setModel(model: String)

    /** Choose the active provider (a built-in id, or "gateway" for the custom OpenAI-compatible entry). */
    fun selectProvider(id: String)

    /** Store the API key for [providerId] (a built-in id or "gateway"). Blank clears it. */
    fun setProviderKey(providerId: String, key: String)

    /** Configure the custom OpenAI-compatible gateway (base URL + model name, and an optional extra CA
     *  certificate PEM to trust for an endpoint behind a private/regional CA — blank = system trust only). */
    fun setGateway(baseUrl: String, model: String, caCert: String)

    /** Send a user message; streams the agent's response into [chatState]. */
    fun send(text: String)

    /** Re-run the last turn after a failure (rate limit, network). No-op if there's nothing to retry. */
    fun retry()

    /** Cancel the in-flight response. */
    fun stop()

    /** Clear the transcript and start a fresh conversation. */
    fun newSession()

    /** Set the write-permission mode (persisted). */
    fun setPermissionMode(mode: UiAgentPermissionMode)

    /** Answer a pending [permissionRequest]. */
    fun answerPermission(id: Int, decision: UiAgentPermissionDecision)

    /** Whether the backend hosts the local FTP asset server (the More-menu toggle + `ftp_server` tool). */
    fun ftpServerSupported(): Boolean = false

    /** Whether the local FTP asset server is currently running. */
    fun ftpServerEnabled(): Boolean = false

    /** Start or stop the local FTP asset server (persisted under `settings.ai.ftpServer`). */
    fun setFtpServerEnabled(enabled: Boolean) {}

    /** A no-op agent for backends that wire none. */
    object Unsupported : AgentService {
        override val chatState: StateFlow<UiAgentChatState> =
            kotlinx.coroutines.flow.MutableStateFlow(UiAgentChatState())
        override val permissionRequest: StateFlow<UiAgentPermissionRequest?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)
        override fun config(): UiAgentConfig = UiAgentConfig()
        override val models: StateFlow<List<UiAgentModel>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        override fun refreshModels() {}
        override fun setModel(model: String) {}
        override fun selectProvider(id: String) {}
        override fun setProviderKey(providerId: String, key: String) {}
        override fun setGateway(baseUrl: String, model: String, caCert: String) {}
        override fun send(text: String) {}
        override fun retry() {}
        override fun stop() {}
        override fun newSession() {}
        override fun setPermissionMode(mode: UiAgentPermissionMode) {}
        override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {}
    }
}
