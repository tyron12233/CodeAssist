package dev.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.UiSourceRootRole
import dev.ide.ui.backend.UiNewFileTemplate
import dev.ide.ui.backend.UiOpenTabs
import dev.ide.ui.backend.UiSettings
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.languageFor
import dev.ide.ui.editor.preview.PreviewKind
import dev.ide.ui.editor.preview.previewKindOf
import dev.ide.ui.platform.ioDispatcher as platformIoDispatcher
import dev.ide.ui.platform.isMobilePlatform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level screens, ordered by depth so the transition helper can infer direction: a move to a
 * higher-ordinal screen animates "forward" (deeper), a lower one "back".
 */
enum class Screen { Projects, CreateProject, ImportProject, ExportProject, Editor, Hub, Run, ModuleConfig, SdkManager, KeystoreManager, KeystoreCreate, KeystoreImport, Settings, CodeStyle, Plugins, LessonTrack, LessonPlayer, StoreItem }

/**
 * The home screen's bottom-navigation destinations (the landing surface shown on [Screen.Projects]): the
 * project picker, the Projects Store (browse/install templates + samples), and Learn (docs + tutorials).
 */
enum class HomeTab { Projects, Store, Learn }

/**
 * Top-level editor destinations in the side rail / bottom nav. Per Apple's HIG these are peer
 * *destinations* (not actions): building/running is the Run button + the console sheet, not a tab.
 */
enum class RailDestination { Files, Search, Source, More }

/** Editor surface for a tab: the plain text editor, the projectional block editor over the same AST, a
 *  full-pane preview, or [Split] — code and its preview together (so you can edit and watch it update,
 *  the one layout that works on a phone where the panes can't otherwise share the screen). */
enum class EditorViewMode { Text, Blocks, Preview, Split }

/**
 * One open editor tab. Its buffer-of-record is the [EditorSession] (the rope-backed model both the text
 * and block editors edit in place) — there is **no** mirrored `TextFieldValue`, so a keystroke never
 * materializes the document `String`. The host observes the session's snapshot state ([EditorSession.textRevision],
 * selection) and pulls [text] lazily, only in debounced effects (analyze/breadcrumb/project/save).
 */
class OpenFile(val path: String, val name: String, initial: String) {
    val session = EditorSession(initial, languageFor(name))
    var modified by mutableStateOf(false)
        private set
    /** Which surface this tab shows — text, blocks, or resource preview (text/blocks edit the one [session]).
     *  Image resources open straight into Preview (their bytes aren't editable text). */
    var viewMode by mutableStateOf(
        if (previewKindOf(path) == PreviewKind.BITMAP)
            EditorViewMode.Preview else EditorViewMode.Text,
    )
    /** Which `@Preview` composable the Compose preview should render — set when the user taps a preview
     *  gutter icon next to a specific function. Null falls back to the file's first `@Preview`. */
    var previewTarget by mutableStateOf<String?>(null)
    /** The content this tab was opened/last-saved with — [modified] tracks divergence from it. */
    var savedText: String = initial
        private set

    init {
        // The session owns the buffer and its diagnostics (it shifts them on every edit). The tab only needs
        // the edit signal for save-state: mark dirty in O(1); the precise revert-to-saved check is deferred.
        session.onTextEdit = { modified = true }
    }

    /** The current buffer text, materialized lazily from the rope. Debounce reads — don't call per keystroke. */
    val text: String get() = session.doc.text

    /**
     * Precise dirty recompute against the saved baseline — catches a revert-to-saved. Off the hot path (call
     * on the analysis debounce): the O(1) length check short-circuits the O(n) content compare in the common
     * "lengths differ" case, and reuses the text the debounced analysis already materialized.
     */
    fun recomputeDirty() {
        modified = session.doc.length != savedText.length || text != savedText
    }

    /** Rebase the saved baseline after a successful save. */
    fun onSaved(text: String) { savedText = text; modified = false }
}

/** Placeholder root shown until the real tree finishes building off the main thread — renders as an empty pane. */
private val EMPTY_TREE = TreeNode(id = "loading", name = "", kind = NodeKind.Workspace, filePath = null, iconId = "workspace")

/**
 * App-wide UI state, hoisted so it survives screen switches. Holds the workspace tree, the open tabs,
 * and the overlay/pane toggles. Editor text lives per [OpenFile]; edits are pushed to the backend's
 * document overlay so cross-file analysis stays live.
 */
class IdeUiState(
    val backend: IdeBackend,
    val composePreviewHost: ComposePreviewHost? = null,
    // Injected so tests can drive opens synchronously (both `Unconfined`); production uses the UI thread for
    // state mutations and the JVM `Dispatchers.IO` pool for the blocking disk read.
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher,
) {
    /** Which shape the file tree takes — curated Project view vs the raw All-Files view (IntelliJ-style). */
    var treeMode by mutableStateOf(TreeViewMode.Project)
        private set
    // Starts empty (cheap) and is filled by the first [ensureTreeLoaded]; building the real tree walks the
    // filesystem recursively (both modes), so it runs off the main thread — inline it stalled project-open and
    // every refresh on device (FUSE storage) → ANR.
    var tree: TreeNode by mutableStateOf(EMPTY_TREE)
        private set
    // Monotonic token so a superseded tree build (a newer refresh/mode-flip started) can't clobber the latest;
    // and whether the real tree has been built at least once (gates the one-time initial load + default seed).
    private var treeToken = 0
    private var treeEverLoaded = false

    /**
     * Which file-tree branches are expanded, keyed by [TreeNode.id]. Held here (not inside `FileNavigator`)
     * so it survives navigating away, toggling the pane/drawer, tree refreshes, and view-mode flips — and is
     * persisted per project + view mode by the host (so the tree reopens the same way next launch). Seeded in
     * [loadTreeExpansion] from the persisted set, or the defaults when the project has none yet.
     */
    val treeExpanded: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    val openFiles = mutableStateListOf<OpenFile>()
    var activeIndex by mutableIntStateOf(-1)

    /**
     * Scope for this state's own async work — chiefly reading a tapped file off the main thread (see [open]).
     * Launched on [mainDispatcher] so Compose-state mutations stay on the UI thread; the blocking disk read
     * hops to [ioDispatcher] inside. Cancelled by [dispose] when the project/backend changes and this state is
     * replaced, so a slow read for an abandoned project can't complete against the new one.
     */
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /** Cancel in-flight async work (file opens). Call when this state leaves composition. */
    fun dispose() { scope.cancel() }

    var rail by mutableStateOf(RailDestination.Files)
    // On mobile the tree + console are space-consuming sheets — start them closed; on desktop they're
    // persistent panes, so keep them open by default.
    var navOpen by mutableStateOf(!isMobilePlatform)
    var searchOpen by mutableStateOf(false)
    var consoleOpen by mutableStateOf(!isMobilePlatform)
    var paletteOpen by mutableStateOf(false)
    /** The in-file structure / outline bottom sheet (opened from the breadcrumb tap or Ctrl-F12). */
    var structureOpen by mutableStateOf(false)

    // ---- run-conflict gate: guard a new Run while a build/program is already running ----

    /** Non-null while the "a build/program is already running" confirmation is up; holds the run the user
     *  is trying to start. `RunConflictDialog` renders it; null = no conflict pending. */
    var runConflict by mutableStateOf<PendingRun?>(null)
        private set

    /** Non-null while the first-build notification-permission gate is deciding; holds the run waiting behind
     *  it. `BuildNotificationGate` renders the prompt/explanation and resolves it via [resolveNotifGate]. */
    var notifGate by mutableStateOf<PendingRun?>(null)
        private set

    /**
     * Funnel every Run/task launch through this. On the very first build (mobile only, until
     * [NOTIF_BUILD_PROMPT_RESOLVED_PREF] is set) it defers to `BuildNotificationGate`, which asks for the
     * notification permission the isolated build process needs; the gate then re-enters via [resolveNotifGate]
     * → [proceedRun]. Otherwise it proceeds straight to [proceedRun].
     */
    fun requestRun(action: () -> Unit) {
        if (isMobilePlatform && !notifPromptResolved) { notifGate = PendingRun(action); return }
        proceedRun(action)
    }

    /**
     * Start [action] once the notification gate is clear. If nothing is running, it fires immediately. If a
     * build or program is already in progress, the user must confirm — either automatically (they earlier
     * chose "don't ask again", which remembers Stop-and-Run) or via the confirmation dialog. This guards a
     * runaway program (e.g. an infinite loop) from being silently shadowed by a second run that can never
     * start (the engine drops a run request while one is already Running).
     */
    private fun proceedRun(action: () -> Unit) {
        if (backend.build.buildState.value.status != RunStatus.Running) {
            action(); return
        }
        if (backend.settings.preference(RUN_CONFLICT_ALWAYS_STOP_PREF)?.toBooleanStrictOrNull() == true) {
            stopThenRun(action); return
        }
        runConflict = PendingRun(action)
    }

    /** Whether the one-time first-build notification prompt has already run (persisted app-globally). */
    private val notifPromptResolved: Boolean
        get() = backend.settings.preference(NOTIF_BUILD_PROMPT_RESOLVED_PREF)?.toBooleanStrictOrNull() == true

    /** `BuildNotificationGate` calls this once it has prompted (granted, denied, or dismissed): remember that
     *  the one-time prompt is done, then start the deferred run (which now runs in-process when notifications
     *  were declined — see IdeServicesBackend.separateBuildProcessEnabled). */
    fun resolveNotifGate() {
        backend.settings.setPreference(NOTIF_BUILD_PROMPT_RESOLVED_PREF, "true")
        val pending = notifGate
        notifGate = null
        pending?.let { proceedRun(it.action) }
    }

    /** The user chose "Stop and Run" in the conflict dialog. [remember] persists that choice so future runs
     *  skip the prompt and stop-and-run automatically. */
    fun confirmStopAndRun(remember: Boolean) {
        if (remember) backend.settings.setPreference(RUN_CONFLICT_ALWAYS_STOP_PREF, "true")
        val pending = runConflict
        runConflict = null
        pending?.let { stopThenRun(it.action) }
    }

    /** The user dismissed the conflict dialog — keep the current run going, start nothing new. */
    fun dismissRunConflict() {
        runConflict = null
    }

    /** Stop the in-progress build/run, then start [action]. The engine flips its status out of Running
     *  synchronously on stop, so the queued run isn't dropped — equivalent to tapping Stop then Run. */
    private fun stopThenRun(action: () -> Unit) {
        backend.build.stopBuild()
        action()
    }
    // ---- live editor preferences (seeded from persisted settings in init; the Settings screen updates them
    // via [applySettings] so open editors react immediately) ----

    /** Editor inlay hints (inferred types, parameter names); also quick-toggled from the editor top bar. */
    var inlayHintsEnabled by mutableStateOf(true)
    /** Editor text zoom (pinch / Ctrl-+ / Ctrl--), 1.0 = the theme's default code size. Shared across tabs. */
    var editorFontScale by mutableStateOf(1f)
    /** Render programming ligatures (`->`, `!=`, …) in the code editor when the font provides them (default on). */
    var fontLigaturesEnabled by mutableStateOf(true)
    /** Type-aware semantic highlighting layered over the lexer. */
    var semanticHighlightingEnabled by mutableStateOf(true)
    /** Code folding (imports, bodies, block comments). */
    var codeFoldingEnabled by mutableStateOf(true)
    /** Pop completion up automatically while typing (off = explicit Ctrl-Space only). */
    var completionAutoPopup by mutableStateOf(true)
    /** Debounce (ms) after a keystroke before the completion popup requests suggestions. */
    var completionDelayMs by mutableStateOf(110)
    /** Run diagnostics as you type (off = the highlighting daemon skips the diagnostics pass). */
    var analyzeOnTheFly by mutableStateOf(true)
    /** Quiet period (ms) after the last edit before the highlighting daemon runs. */
    var reparseDelayMs by mutableStateOf(300)
    /** Soft-wrap long lines at the viewport edge (off = one row per line + horizontal scroll). */
    var wordWrapEnabled by mutableStateOf(false)
    /** Indent wrapped continuation rows to the line's own indent (IntelliJ-style); only when wrapping. */
    var wrapIndentEnabled by mutableStateOf(true)
    /** Free (two-axis) touch scrolling: a single drag pans both axes at once (off = orientation-locked). */
    var twoAxisScrollEnabled by mutableStateOf(true)
    /** Two-finger pinch zooms the code font (Ctrl-+/-/0 always works regardless). */
    var pinchZoomEnabled by mutableStateOf(true)
    /** Allow the soft keyboard's autocorrect / suggestions / auto-space (off = raw code input). */
    var softKeyboardSuggestions by mutableStateOf(true)

    init {
        applySettings(backend.settings.settings())
        loadTreeExpansion()
    }

    /**
     * (Re)seed [treeExpanded] for the current [treeMode]: the persisted expanded set if this project has one,
     * otherwise the defaults (each module / source root / the workspace expanded). Called on creation and on a
     * view-mode flip (the two modes shape the tree differently, so each remembers its own expansion).
     */
    private fun loadTreeExpansion() {
        // Apply only the persisted set here — the tree isn't built yet at construction, so the default seed
        // (which reads [tree]) happens after the first off-thread build (see [loadTree]/[ensureTreeLoaded]).
        treeExpanded.clear()
        backend.files.expandedTreeState(treeMode)?.forEach { treeExpanded[it] = true }
    }

    /** Expand modules / source roots / the workspace by default. Additive (doesn't clear user toggles), so it's
     *  safe to (re)run once the real tree lands. Reads [tree], so it must run after a tree build. */
    private fun seedDefaultExpansion() {
        fun seed(n: TreeNode) {
            if (n.kind == NodeKind.Module || n.kind == NodeKind.SourceRoot || n.kind == NodeKind.Workspace)
                treeExpanded[n.id] = true
            n.children.forEach(::seed)
        }
        seed(tree)
    }

    /** The currently-expanded tree-node ids — the host persists this (debounced) whenever it changes. */
    fun expandedTreeSnapshot(): Set<String> = treeExpanded.filterValues { it }.keys.toSet()

    /** Push persisted IDE settings into the live editor-pref fields (called on creation + on each settings change). */
    fun applySettings(s: UiSettings) {
        inlayHintsEnabled = s.inlayHints
        editorFontScale = s.editorFontScale
        fontLigaturesEnabled = s.fontLigatures
        semanticHighlightingEnabled = s.semanticHighlighting
        codeFoldingEnabled = s.codeFolding
        completionAutoPopup = s.completionAutoPopup
        completionDelayMs = s.completionDelayMs
        analyzeOnTheFly = s.analyzeOnTheFly
        reparseDelayMs = s.reparseDelayMs
        wordWrapEnabled = s.wordWrap
        wrapIndentEnabled = s.wrapIndent
        twoAxisScrollEnabled = s.twoAxisScroll
        pinchZoomEnabled = s.pinchZoom
        softKeyboardSuggestions = s.softKeyboardSuggestions
    }

    /** A transient destination shown as a sheet/overlay (Source, More) — null when none is open. */
    var sheetDest by mutableStateOf<RailDestination?>(null)

    /** Whether the Logs viewer sheet (editor & analysis logs, opened from the More menu) is showing. */
    var logsOpen by mutableStateOf(false)

    /** Whether the indexing-status detail dialog (opened by tapping the top-bar index chip) is showing. */
    var indexDetailOpen by mutableStateOf(false)

    /** The module whose Add-Source-Root dialog is open, or null when closed. */
    var addSourceRootModule by mutableStateOf<String?>(null)

    /** Route to a rail destination: Files/Search toggle their side panes; Source/More open as a sheet. */
    fun selectRail(dest: RailDestination) {
        rail = dest
        when (dest) {
            RailDestination.Files -> { navOpen = !navOpen; searchOpen = false; sheetDest = null }
            RailDestination.Search -> { searchOpen = !searchOpen; navOpen = false; sheetDest = null }
            RailDestination.Source, RailDestination.More -> { sheetDest = dest }
        }
    }

    val active: OpenFile? get() = openFiles.getOrNull(activeIndex)

    /** Fire-and-forget open for UI callbacks — the file is read off the main thread ([openSuspend]). */
    fun open(path: String, name: String) {
        scope.launch { openSuspend(path, name) }
    }

    /**
     * Open [path] as a tab: focus an already-open tab, else read the file **off the main thread** and add it.
     * The disk read is the ANR risk (a tap on a tree row must never block the UI thread on device), so it runs
     * on [ioDispatcher]; the resulting Compose-state mutations resume on the launching (main) dispatcher.
     */
    suspend fun openSuspend(path: String, name: String) {
        if (focusOpenTab(path)) return
        val text = withContext(ioDispatcher) { backend.files.readFile(path) }
        // A second tap on the same row may have opened it while we were reading — focus it, don't duplicate.
        if (focusOpenTab(path)) return
        backend.editor.updateDocument(path, text)
        openFiles.add(OpenFile(path, name, text))
        activeIndex = openFiles.lastIndex
    }

    /** Focus the already-open tab for [path] if there is one; returns true when it existed. */
    private fun focusOpenTab(path: String): Boolean {
        val existing = openFiles.indexOfFirst { it.path == path }
        if (existing >= 0) { activeIndex = existing; return true }
        return false
    }

    /** Open [path] and move the caret to [offset] (go-to-symbol). */
    fun openAt(path: String, offset: Int) {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        scope.launch {
            openSuspend(path, name)
            active?.session?.setCaret(offset) // setCaret coerces into the buffer
        }
    }

    /** Open [path] and move the caret to 1-based [line]/[column] — the build console's jump-to-diagnostic. */
    fun openAtLine(path: String, line: Int, column: Int) {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        scope.launch {
            openSuspend(path, name)
            val session = active?.session ?: return@launch
            val base = session.doc.lineStart((line - 1).coerceAtLeast(0))
            session.setCaret(base + (column - 1).coerceAtLeast(0)) // setCaret coerces into the buffer
        }
    }

    fun close(file: OpenFile) {
        val idx = openFiles.indexOf(file)
        if (idx < 0) return
        openFiles.removeAt(idx)
        activeIndex = activeIndex.coerceAtMost(openFiles.lastIndex)
    }

    /** Close every tab except [keep] (tab context menu). Iterates a copy, so mutating [openFiles] is safe. */
    fun closeOthers(keep: OpenFile) = openFiles.filter { it !== keep }.forEach(::close)

    /** Close all open tabs. */
    fun closeAll() = openFiles.toList().forEach(::close)

    /** Close the tabs positioned after [file] in the strip. `drop`/`take` copy, so the loop can mutate safely. */
    fun closeToRight(file: OpenFile) {
        val i = openFiles.indexOf(file)
        if (i < 0) return
        openFiles.drop(i + 1).forEach(::close)
    }

    /** Close the tabs positioned before [file] in the strip. */
    fun closeToLeft(file: OpenFile) {
        val i = openFiles.indexOf(file)
        if (i <= 0) return
        openFiles.take(i).forEach(::close)
    }

    /** Persist [file]'s buffer to disk, rebase its saved baseline, and clear the dirty flag. No-op if clean. */
    fun save(file: OpenFile) {
        if (!file.modified) return
        val text = file.text // one lazy materialization, on save (not per keystroke)
        backend.editor.saveFile(file.path, text)
        file.onSaved(text)
    }

    /** Save the active tab (Cmd/Ctrl-S, toolbar). */
    fun saveActive() { active?.let(::save) }

    /**
     * Reopen the tabs persisted from a previous session with this project (paths in tab order + the active
     * tab). Files that no longer exist on disk are skipped. Returns true if at least one tab was restored, so
     * the caller can fall back to [defaultFile] when there was no remembered session.
     */
    suspend fun restoreTabs(): Boolean {
        val saved = backend.projects.openTabs()
        if (saved.paths.isEmpty()) return false
        for (path in saved.paths) {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            runCatching { openSuspend(path, name) } // a deleted file throws in readFile — skip it
        }
        if (openFiles.isEmpty()) return false
        activeIndex = saved.activeIndex.coerceIn(0, openFiles.lastIndex)
        return true
    }

    /** The current open tabs as a persistable snapshot (paths in tab order + the active index). */
    fun tabsSnapshot(): UiOpenTabs =
        UiOpenTabs(openFiles.map { it.path }, activeIndex)

    /** Pick a sensible first file: a `Main.java`, else the first source file in the tree. */
    fun defaultFile(): TreeNode? {
        val all = ArrayList<TreeNode>()
        fun walk(n: TreeNode) { if (n.filePath != null) all.add(n); n.children.forEach(::walk) }
        walk(tree)
        return all.firstOrNull { it.name == "Main.java" }
            ?: all.firstOrNull { it.name.endsWith(".java") }
            ?: all.firstOrNull()
    }

    /** Re-read the workspace tree from the backend (after a file is created/removed), off the main thread. */
    fun refreshTree() { scope.launch { loadTree() } }

    /**
     * Build the current-mode tree **off the main thread** and publish it on the UI thread. A build that a newer
     * refresh/mode-flip superseded is dropped ([treeToken]). When [seedExpansionIfDefault] and this mode has no
     * persisted expansion, expands the modules/roots once the tree is in place.
     */
    private suspend fun loadTree(seedExpansionIfDefault: Boolean = false) {
        val mode = treeMode
        val token = ++treeToken
        val built = withContext(ioDispatcher) { backend.files.fileTree(mode) }
        if (token != treeToken || mode != treeMode) return // superseded by a later build
        tree = built
        treeEverLoaded = true
        if (seedExpansionIfDefault && backend.files.expandedTreeState(mode) == null) seedDefaultExpansion()
    }

    /** Build the real tree the first time it's needed (project open). No-op once loaded — refreshes go via [refreshTree]. */
    suspend fun ensureTreeLoaded() {
        if (!treeEverLoaded) loadTree(seedExpansionIfDefault = true)
    }

    /** Switch the tree view mode (Project ↔ All Files), rebuild the tree, and restore that mode's expansion. */
    fun selectTreeMode(mode: TreeViewMode) {
        if (mode == treeMode) return
        treeMode = mode
        // Apply the persisted expansion for the new mode now; the default seed happens after the tree lands.
        treeExpanded.clear()
        backend.files.expandedTreeState(mode)?.forEach { treeExpanded[it] = true }
        scope.launch { loadTree(seedExpansionIfDefault = true) }
    }

    /**
     * Reflect a completed project-wide rename in the open tabs. The rename wrote every reference site to
     * disk, so each clean tab is re-read (a no-op for files it didn't touch — those keep their session, undo
     * and caret). The active tab follows the backing-file rename to [newPath] when the file itself was
     * renamed. Tabs with unsaved edits are left untouched so a rename never clobbers in-progress work.
     */
    fun reloadAfterRename(activePath: String?, newPath: String?) {
        scope.launch {
            for (i in openFiles.indices) {
                val f = openFiles[i]
                val followsFileRename = newPath != null && f.path == activePath
                if (!followsFileRename && f.modified) continue
                val diskPath = if (followsFileRename) newPath!! else f.path
                val text = readTabText(diskPath) ?: continue
                if (!followsFileRename && text == f.savedText) continue // untouched → preserve session/undo/caret
                val name = diskPath.substringAfterLast('/').substringAfterLast('\\')
                openFiles[i] = OpenFile(diskPath, name, text)
                backend.editor.updateDocument(diskPath, text)
            }
            loadTree()
        }
    }

    /** Read [path]'s disk text off the main thread; null on any I/O error (a deleted/renamed file). */
    private suspend fun readTabText(path: String): String? =
        withContext(ioDispatcher) { runCatching { backend.files.readFile(path) }.getOrNull() }

    /** Re-push every open buffer to the editor backend so it re-analyzes against the current classpath — used
     *  after switching the active build variant (the engine has already invalidated the per-module analyzers). */
    fun reanalyzeOpenFiles() {
        for (f in openFiles) backend.editor.updateDocument(f.path, f.text)
    }

    /** Create a new file through the backend (off the main thread), refresh the tree, and open it in the editor. */
    fun createFile(dirPath: String, fileName: String, content: String) {
        scope.launch {
            val path = withContext(ioDispatcher) { backend.files.createFile(dirPath, fileName, content) } ?: return@launch
            loadTree()
            openSuspend(path, fileName)
        }
    }

    /** Create a new directory through the backend (off the main thread) and refresh the tree (nothing to open). */
    fun createDirectory(parentPath: String, name: String) {
        scope.launch {
            if (withContext(ioDispatcher) { backend.files.createDirectory(parentPath, name) } != null) loadTree()
        }
    }

    // ---- file & package operations (delete / rename / move / copy) ----

    /** Delete a file or directory/package (off the main thread): close any open tabs under it, refresh the tree. */
    fun deletePath(path: String) {
        scope.launch {
            if (!withContext(ioDispatcher) { backend.files.deletePath(path) }) return@launch
            closeTabsUnder(path)
            loadTree()
        }
    }

    /** Rename a file/directory to [newName]; rebase open tabs onto the new path + refresh. Returns the result. */
    suspend fun renamePath(path: String, newName: String): UiRenameResult {
        val r = backend.files.renamePath(path, newName)
        if (r.success) { rebaseTabs(path, r.newPath ?: path); refreshCleanTabs(); loadTree() }
        return r
    }

    /** Move a file/directory into [destDir] (off the main thread); rebase open tabs + refresh. */
    fun movePath(path: String, destDir: String) {
        scope.launch {
            val newPath = withContext(ioDispatcher) { backend.files.movePath(path, destDir) } ?: return@launch
            rebaseTabs(path, newPath) // re-read moved tabs off the main thread
            loadTree()
        }
    }

    /** Copy a file/directory into [destDir] (off the main thread); refresh the tree. */
    fun copyPath(path: String, destDir: String) {
        scope.launch {
            if (withContext(ioDispatcher) { backend.files.copyPath(path, destDir) } != null) loadTree()
        }
    }

    /** True if [p] is [root] or lives under it (matching on either path separator). */
    private fun underPath(p: String, root: String): Boolean =
        p == root || p.startsWith("$root/") || p.startsWith("$root\\")

    /** Close every open tab at [path] or under it (after a delete). */
    private fun closeTabsUnder(path: String) {
        openFiles.filter { underPath(it.path, path) }.forEach(::close)
    }

    /** Re-point open tabs at [oldPath] (or under it, for a directory) to [newPath], re-reading from disk. */
    private suspend fun rebaseTabs(oldPath: String, newPath: String) {
        for (i in openFiles.indices) {
            val p = openFiles[i].path
            val rebased = when {
                p == oldPath -> newPath
                underPath(p, oldPath) -> newPath + p.substring(oldPath.length)
                else -> continue
            }
            val text = readTabText(rebased) ?: continue
            val name = rebased.substringAfterLast('/').substringAfterLast('\\')
            openFiles[i] = OpenFile(rebased, name, text)
            backend.editor.updateDocument(rebased, text)
        }
    }

    /** Re-read clean (unmodified) tabs whose disk content changed — e.g. references rewritten by a rename. */
    private suspend fun refreshCleanTabs() {
        for (i in openFiles.indices) {
            val f = openFiles[i]
            if (f.modified) continue
            val text = readTabText(f.path) ?: continue
            if (text == f.savedText) continue
            openFiles[i] = OpenFile(f.path, f.name, text)
            backend.editor.updateDocument(f.path, text)
        }
    }

    /** Create a smart-scaffolded, nested-path-aware file under [dirPath] (off the main thread), refresh, and open. */
    fun createFileSmart(dirPath: String, name: String) {
        scope.launch {
            val path = withContext(ioDispatcher) { backend.files.createFileSmart(dirPath, name) } ?: return@launch
            loadTree()
            openSuspend(path, name.substringAfterLast('/').substringAfterLast('\\'))
        }
    }

    /** Create a typed source file ([template]) named [name] under [dirPath] (off the main thread), refresh, open. */
    fun createSourceFile(dirPath: String, name: String, template: UiNewFileTemplate) {
        scope.launch {
            val path = withContext(ioDispatcher) { backend.files.createSourceFile(dirPath, name, template) } ?: return@launch
            loadTree()
            openSuspend(path, path.substringAfterLast('/').substringAfterLast('\\'))
        }
    }

    // ---- source-set / content-root management ----

    /** Source-set names declared on [moduleName] (for the Add-Source-Root selector). */
    fun moduleSourceSets(moduleName: String): List<String> = backend.modules.moduleSourceSets(moduleName)

    /** Add a typed source root to [moduleName] and refresh the tree. Returns true on success. */
    fun addSourceRoot(moduleName: String, sourceSetName: String, dirName: String, role: UiSourceRootRole): Boolean {
        val created = backend.modules.addSourceRoot(moduleName, sourceSetName, dirName, role) != null
        if (created) refreshTree()
        return created
    }

    /** Unmark a content root (model-only) and refresh the tree. Returns true on success. */
    fun removeSourceRoot(moduleName: String, sourceSetName: String, rootPath: String): Boolean {
        val ok = backend.modules.removeSourceRoot(moduleName, sourceSetName, rootPath)
        if (ok) refreshTree()
        return ok
    }

    /** Create an empty source set on [moduleName] and refresh the tree. Returns true on success. */
    fun addSourceSet(moduleName: String, name: String): Boolean {
        val ok = backend.modules.addSourceSet(moduleName, name)
        if (ok) refreshTree()
        return ok
    }

    companion object {
        /** App preference: "true" once the user checks "don't ask again" on the run-conflict dialog — future
         *  runs then stop the current build/program and start automatically, without prompting. */
        const val RUN_CONFLICT_ALWAYS_STOP_PREF = "run.conflict.alwaysStop"

        /** App preference: "true" once the first-build notification-permission prompt has been shown (see
         *  `BuildNotificationGate`), so later builds don't re-prompt. Re-request from Settings → Build Runtime. */
        const val NOTIF_BUILD_PROMPT_RESOLVED_PREF = "notif.buildPromptResolved"
    }
}

/** A run the user is trying to start while a build/program is already running (see [IdeUiState.requestRun]). */
class PendingRun(val action: () -> Unit)
