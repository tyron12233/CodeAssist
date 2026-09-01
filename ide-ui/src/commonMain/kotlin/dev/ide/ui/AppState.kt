package dev.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.UiSymbolKey
import dev.ide.ui.backend.UiSourceRootRole
import dev.ide.ui.backend.UiNewFileTemplate
import dev.ide.ui.backend.UiOpenTab
import dev.ide.ui.backend.UiOpenTabs
import dev.ide.ui.backend.UiSettings
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.editor.core.mapOffsetThroughEdits
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Top-level screens, ordered by depth so the transition helper can infer direction: a move to a
 * higher-ordinal screen animates "forward" (deeper), a lower one "back".
 */
enum class Screen { Projects, CreateProject, ImportProject, ExportProject, Editor, Hub, Run, ModuleConfig, SdkManager, KeystoreManager, KeystoreCreate, KeystoreImport, IconManager, AppIconStudio, Settings, CodeStyle, EditorSymbols, Plugins, Storage, LessonTrack, LessonPlayer, StoreItem, SubmitProject, PublishingGuide, PluginScreen }

/**
 * The home screen's bottom-navigation destinations (the landing surface shown on [Screen.Projects]): the
 * project picker, the Projects Store (browse/install templates + samples), and Learn (docs + tutorials).
 */
enum class HomeTab { Projects, Store, Learn }

/**
 * The phone bottom-nav slots (the compact counterpart to the desktop activity rail). Each maps to opening a
 * left sidebar panel (Files/Search/Source) or the More menu — see [IdeUiState.onBottomNav]. The desktop rail
 * itself is now data-driven (a `SidebarPanel` list of built-in + plugin tool windows), so it no longer uses
 * this enum. Kept as the fixed mobile slot model.
 */
enum class RailDestination { Files, Search, Source, More }

/** Stable ids of the built-in LEFT sidebar panels (peers of plugin-contributed LEFT tool windows). These
 *  double as the persisted "which panel is open" key and the bottom-nav → panel mapping. */
object LeftPanelId {
    const val FILES = "files"
    const val SEARCH = "search"
    const val STRUCTURE = "structure"
    const val SOURCE = "source"
}

/** Editor surface for a tab: the plain text editor, the projectional block editor over the same AST, a
 *  full-pane preview, or [Split] — code and its preview together (so you can edit and watch it update,
 *  the one layout that works on a phone where the panes can't otherwise share the screen). */
enum class EditorViewMode { Text, Blocks, Preview, Split }

/** Stable persisted id for a tab's [EditorViewMode] (see `UiOpenTab.viewMode`). */
internal fun EditorViewMode.persistId(): String = when (this) {
    EditorViewMode.Text -> "text"
    EditorViewMode.Blocks -> "blocks"
    EditorViewMode.Preview -> "preview"
    EditorViewMode.Split -> "split"
}

/** Parse a persisted [EditorViewMode] id, or null when unknown (so the tab keeps its default surface). */
internal fun editorViewModeOf(id: String?): EditorViewMode? = when (id) {
    "text" -> EditorViewMode.Text
    "blocks" -> EditorViewMode.Blocks
    "preview" -> EditorViewMode.Preview
    "split" -> EditorViewMode.Split
    else -> null
}

/**
 * One open editor tab. Its buffer-of-record is the [EditorSession] (the rope-backed model both the text
 * and block editors edit in place) — there is **no** mirrored `TextFieldValue`, so a keystroke never
 * materializes the document `String`. The host observes the session's snapshot state ([EditorSession.textRevision],
 * selection) and pulls [text] lazily, only in debounced effects (analyze/breadcrumb/project/save).
 */
/** The synthetic tab-path scheme for a compiled library class opened read-only (decompiled / attached source).
 *  A path with this prefix has no disk file — it never participates in save / disk-sync / persistence. */
internal const val LIBRARY_SCHEME = "library://"

class OpenFile(
    val path: String,
    val name: String,
    initial: String,
    val readOnly: Boolean = false,
    /** For a `library://…` tab: how its text was obtained (`source`/`decompiled_java`/`decompiled_kotlin`),
     *  driving the read-only banner + the "Decompile to Java" affordance. Null for a normal disk file. */
    val libraryKind: String? = null,
    /** Stable, unique tab id — the editor tab strip keys its `LazyRow` on this, NOT on [path]. Two tabs can
     *  momentarily hold the same path (a re-point after a rename/move, or a concurrent open on a slow device),
     *  and a duplicate LazyList key hard-crashes the measure pass (`measureLazyList` precondition). A per-tab
     *  id makes that impossible by construction; [AppState.dedupeTabsByPath] still reconciles the logical
     *  duplicate. Defaults to a fresh id; re-point sites that replace a tab's OpenFile in place for the SAME
     *  logical tab pass the old id so the tab keeps its strip identity (no crossfade) across the swap. Assigned
     *  on the UI thread (every OpenFile is built inside a Main-dispatched launch), so the counter needs no sync. */
    val tabId: Long = nextTabId(),
) {
    val session = EditorSession(initial, languageFor(name), editable = !readOnly)
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

    companion object {
        // Monotonic tab-id source. Bumped only from OpenFile construction, which always happens on the UI
        // thread (see [tabId]), so a plain counter is race-free without atomics.
        private var tabIdSeq = 0L
        private fun nextTabId(): Long = ++tabIdSeq
    }
}

/** The open-tab session bits that, when any change, should reschedule the debounced tab save (so the persisted
 *  session tracks the caret / scroll / view surface, not just which files are open). */
private data class TabSessionKey(val path: String, val caret: Int, val scrollLine: Int, val viewMode: EditorViewMode)

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
    initialGradleConvertPrompt: Boolean = false,
) {
    /**
     * One-shot: the project was just imported with "Convert to CodeAssist project" chosen at the picker, so
     * the editor should open the convert-confirmation dialog once. Consumed (set false) by `EditorCenter` on
     * first show. Held here (not a param through EditorScreen) so it rides the per-project state rebuild.
     */
    var pendingGradleConvertPrompt by mutableStateOf(initialGradleConvertPrompt)
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

    /**
     * The per-project bridges between this state and the backend, running for as long as the caller's
     * coroutine lives (the host launches one per project, so they all stop together when the project is
     * swapped out): restore the last tab session and keep it persisted, publish the editor-lifecycle events
     * plugins listen for, persist the file-tree expansion, and re-read files written outside the editor.
     */
    suspend fun runSessionEffects(): Unit = coroutineScope {
        launch { restoreAndPersistTabs() }
        launch { publishActiveEditor() }
        launch { publishSelection() }
        launch { persistTreeExpansion() }
        launch { syncExternalWrites() }
    }

    // Reopen the tabs from the last session with this project; if there were none, land on a sensible first
    // file so entering the editor shows real code. Then persist tab changes (debounced) so they reopen next
    // launch: `drop(1)` skips the just-restored state, and `collectLatest` cancels the pending write when
    // another tab change lands within the debounce window.
    private suspend fun restoreAndPersistTabs() {
        ensureTreeLoaded() // build the real file tree off the main thread before it's shown / walked
        if (!restoreTabs()) {
            defaultFile()?.let { node -> node.filePath?.let { open(it, node.name) } }
        }
        snapshotFlow {
            // Re-emit when the tab set, the active tab, OR any tab's caret / scroll / view mode changes, so the
            // persisted session records where the user is, not only which files are open.
            openFiles.map {
                TabSessionKey(it.path, it.session.selection.start, it.session.viewportTopLine, it.viewMode)
            } to activeIndex
        }.drop(1).collectLatest {
            delay(600.milliseconds)
            val snapshot = tabsSnapshot() // read the Compose session state on the main thread,
            withContext(ioDispatcher) { backend.projects.saveOpenTabs(snapshot) } // then write off it
        }
    }

    // Editor-lifecycle events for plugins: the engine republishes these on the message bus
    // (IdeEventTopics.EDITOR) and they are no-ops when nothing subscribes. The focused file, whenever it
    // changes (null once the last tab closes):
    private suspend fun publishActiveEditor() {
        snapshotFlow { active?.path }
            .distinctUntilChanged()
            .collect { backend.editor.onActiveEditorChanged(it) }
    }

    // The caret/selection, debounced so it fires on settle rather than on every keystroke (collectLatest
    // cancels the pending delay when the selection moves again).
    private suspend fun publishSelection() {
        snapshotFlow {
            active?.let { Triple(it.path, it.session.selection.start, it.session.selection.end) }
        }.distinctUntilChanged().collectLatest { sel ->
            if (sel == null) return@collectLatest
            delay(150.milliseconds)
            backend.editor.onSelectionChanged(sel.first, sel.second, sel.third)
        }
    }

    // Persist the file-tree expansion (debounced) so the tree reopens the same way next launch, keyed per
    // project + view mode. `drop(1)` skips the seeded initial state; `collectLatest` coalesces rapid toggles.
    private suspend fun persistTreeExpansion() {
        snapshotFlow { treeMode to expandedTreeSnapshot() }.drop(1).collectLatest { (mode, ids) ->
            delay(300.milliseconds)
            backend.files.saveExpandedTreeState(mode, ids.toList())
        }
    }

    // External file writes (e.g. an agent edit, or an "Open with" import the UI didn't drive) re-read the tree
    // AND re-sync any clean open editor tab whose file changed on disk.
    private suspend fun syncExternalWrites() {
        backend.files.fileSystemEpoch.collect { fsEpoch ->
            if (fsEpoch > 0) {
                refreshTree()
                syncOpenTabsFromDisk()
            }
        }
    }

    // ---- sidebar panels (the LEFT + RIGHT activity rails) ----
    // Both sides are a list of `SidebarPanel`s (built-in + plugin tool windows); the rail shows one icon per
    // panel and selects which one is docked. `null` = that side is collapsed.

    /** Which LEFT panel is open (a [LeftPanelId] or a plugin tool-window id), or null when the left sidebar is
     *  collapsed. On desktop this is the docked pane; on mobile it's the panel shown in the push drawer.
     *  Seeded from the persisted last panel in [init] (desktop opens it; mobile starts collapsed). */
    var selectedLeftPanel by mutableStateOf<String?>(null)
    /** The panel to reopen when the left sidebar is toggled back on (survives a collapse). */
    private var lastLeftPanel: String = LeftPanelId.FILES
    /** Which RIGHT panel is open (a plugin tool-window id — e.g. the AI chat), or null when collapsed. Right
     *  panels are fully plugin-derived; a disabled plugin contributes nothing and leaves no rail icon. */
    var selectedRightPanel by mutableStateOf<String?>(null)

    /** True while the left sidebar is showing a panel (drives the top-bar toggle glyph, the compact push
     *  drawer's open state, and the back handler). */
    val leftOpen: Boolean get() = selectedLeftPanel != null

    /** Desktop rail: tap an icon to open/switch to it; tap the already-selected one to collapse the side. */
    fun toggleLeftPanel(id: String) {
        if (selectedLeftPanel == id) selectedLeftPanel = null else rememberAndSelectLeft(id)
    }
    /** Open (and switch to) a LEFT panel — the mobile segmented switcher, bottom nav, and breadcrumb use this. */
    fun selectLeftPanel(id: String) = rememberAndSelectLeft(id)
    /** The top-bar sidebar button: reopen the last panel, or collapse if one is already open. */
    fun toggleLeftSidebar() { if (leftOpen) selectedLeftPanel = null else rememberAndSelectLeft(lastLeftPanel) }
    /** Open the left sidebar to its last panel (the compact push-drawer's swipe-open). No-op if already open. */
    fun openLeftSidebar() { if (!leftOpen) rememberAndSelectLeft(lastLeftPanel) }

    private fun rememberAndSelectLeft(id: String) {
        selectedLeftPanel = id
        lastLeftPanel = id
        backend.settings.setPreference(LEFT_PANEL_PREF, id)
    }

    /** RIGHT rail: tap an icon to open/switch; tap the selected one to collapse (the phone swipe routes here too). */
    fun toggleRightPanel(id: String) { selectedRightPanel = if (selectedRightPanel == id) null else id }
    /** Open (and switch to) a RIGHT panel — the mobile right-overlay switcher uses this. */
    fun selectRightPanel(id: String) { selectedRightPanel = id }

    /** The bottom-nav slot that reflects the current state (or null when none of its slots is active). */
    fun bottomNavSelection(): RailDestination? = when {
        moreOpen -> RailDestination.More
        selectedLeftPanel == LeftPanelId.SEARCH -> RailDestination.Search
        selectedLeftPanel == LeftPanelId.SOURCE -> RailDestination.Source
        else -> null
    }
    /** Route a bottom-nav tap: open the left drawer to the matching panel, or open the More menu. */
    fun onBottomNav(dest: RailDestination) = when (dest) {
        RailDestination.Files -> selectLeftPanel(LeftPanelId.FILES)
        RailDestination.Search -> selectLeftPanel(LeftPanelId.SEARCH)
        RailDestination.Source -> selectLeftPanel(LeftPanelId.SOURCE)
        RailDestination.More -> { moreOpen = true }
    }

    // On mobile the console is a space-consuming sheet — start it closed; on desktop it's a persistent pane.
    var consoleOpen by mutableStateOf(!isMobilePlatform)

    var paletteOpen by mutableStateOf(false)
    /** Whether the "More" actions sheet is showing (rail footer / bottom-nav More). */
    var moreOpen by mutableStateOf(false)

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
    /** Draggable bar along the editor's bottom edge while a line runs past the view; none while wrapping. */
    var horizontalScrollbarEnabled by mutableStateOf(true)
    /** Free (two-axis) touch scrolling: a single drag pans both axes at once (off = orientation-locked). */
    var twoAxisScrollEnabled by mutableStateOf(true)
    /** Two-finger pinch zooms the code font (Ctrl-+/-/0 always works regardless). */
    var pinchZoomEnabled by mutableStateOf(true)
    /** Allow the soft keyboard's autocorrect / suggestions / auto-space (off = raw code input). */
    var softKeyboardSuggestions by mutableStateOf(true)

    /** The keyboard symbol-bar keys, from the effective customization set (project ▸ global ▸ shipped defaults).
     *  Seeded in [init]; re-read via [refreshSymbolKeys] after the Symbols editor saves. */
    var symbolKeys by mutableStateOf<List<UiSymbolKey>>(emptyList())
        private set

    /** The Symbols &amp; Macros editor overlay (hosted in EditorScreen); opened from the symbol bar's gear key. */
    var symbolEditorOpen by mutableStateOf(false)

    /** Re-read the effective symbol-bar keys from the backend (after an edit, or on project change). */
    fun refreshSymbolKeys() { symbolKeys = backend.customize.symbolKeys() }

    init {
        applySettings(backend.settings.settings())
        refreshSymbolKeys()
        loadTreeExpansion()
        // Restore the last-open left panel: desktop reopens it as a docked pane; mobile starts collapsed but
        // remembers it for the next time the drawer is toggled on.
        backend.settings.preference(LEFT_PANEL_PREF)?.let { lastLeftPanel = it }
        if (!isMobilePlatform) selectedLeftPanel = lastLeftPanel
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
        horizontalScrollbarEnabled = s.horizontalScrollbar
        twoAxisScrollEnabled = s.twoAxisScroll
        pinchZoomEnabled = s.pinchZoom
        softKeyboardSuggestions = s.softKeyboardSuggestions
    }

    /**
     * Whether the projectional block editor is available (the `blocks` plugin is enabled). Read once from the
     * backend at construction — the plugin set is app-global and restart-applied, so it can't change within a
     * session. Gates the Code/Blocks view-mode toggle and the restore of a persisted `blocks` tab.
     */
    val blocksEnabled: Boolean = backend.blocks.blocksEnabled()

    /** Whether the Logs viewer sheet (editor & analysis logs, opened from the More menu) is showing. */
    var logsOpen by mutableStateOf(false)

    /** Whether the indexing-status detail dialog (opened by tapping the top-bar index chip) is showing. */
    var indexDetailOpen by mutableStateOf(false)

    /** The module whose Add-Source-Root dialog is open, or null when closed. */
    var addSourceRootModule by mutableStateOf<String?>(null)

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
        backend.editor.onFileOpened(path) // a genuinely new tab (focus path returned above), for plugin events
    }

    /** Focus the already-open tab for [path] if there is one; returns true when it existed. */
    private fun focusOpenTab(path: String): Boolean {
        val existing = openFiles.indexOfFirst { it.path == path }
        if (existing >= 0) { activeIndex = existing; return true }
        return false
    }

    /** Open [path] and move the caret to [offset] (go-to-symbol). A `library://…` path routes through
     *  [openLibrary] (fetch decompiled/attached-source content, open read-only) instead of a disk read. */
    fun openAt(path: String, offset: Int) {
        if (path.startsWith(LIBRARY_SCHEME)) { openLibrary(path); return }
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        scope.launch {
            openSuspend(path, name)
            active?.session?.setCaret(offset) // setCaret coerces into the buffer
        }
    }

    /**
     * Open a compiled LIBRARY class in a read-only tab. [libPath] is `library://<fqn>[#member]`; the content
     * (attached source, else a decompiled view) is fetched from the backend off the main thread against the
     * active tab's module, then shown read-only with the caret on [member] (searched in the fetched text), or
     * the top. [forceJava] runs the Java decompiler on any class ("Decompile to Java"). Re-opening focuses the
     * existing tab (keyed by the canonical `library://<fqn>[?java]` path the backend returns).
     */
    fun openLibrary(libPath: String, forceJava: Boolean = false) {
        val raw = libPath.removePrefix(LIBRARY_SCHEME).substringBefore("?")
        val fqn = raw.substringBefore('#')
        val member = raw.substringAfter('#', "").ifEmpty { null }
        val contextPath = active?.path?.takeUnless { it.startsWith(LIBRARY_SCHEME) }
        scope.launch {
            val content = withContext(ioDispatcher) {
                runCatching { backend.editor.libraryContent(contextPath ?: fqn, fqn, forceJava) }.getOrNull()
            } ?: return@launch
            if (focusOpenTab(content.path)) {
                active?.session?.let { placeLibraryCaret(it, member ?: fqn.substringAfterLast('.')) }
                return@launch
            }
            val tab = OpenFile(content.path, content.name, content.text, readOnly = true, libraryKind = content.kind)
            openFiles.add(tab)
            activeIndex = openFiles.lastIndex
            placeLibraryCaret(tab.session, member ?: fqn.substringAfterLast('.'))
            backend.editor.onFileOpened(content.path)
        }
    }

    /** Move [session]'s caret to the first occurrence of [name] as a whole word (a best-effort landing on the
     *  declaration in decompiled/library text), else leave it at the top. */
    private fun placeLibraryCaret(session: EditorSession, name: String) {
        val text = session.doc.text
        var from = 0
        while (true) {
            val i = text.indexOf(name, from)
            if (i < 0) break
            val before = if (i > 0) text[i - 1] else ' '
            val after = if (i + name.length < text.length) text[i + name.length] else ' '
            if (!before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_') {
                session.setCaret(i); return
            }
            from = i + name.length
        }
    }

    /** Open [path] and move the caret to 1-based [line]/[column] — the build console's jump-to-diagnostic. */
    /**
     * Apply [edits] to the active tab, returning false when there is no editable tab. This is how a
     * full-screen tool (the Icon Manager) contributes source to the editor: it drives the same session the
     * editor is showing, so undo and the caret behave as if the text had been typed.
     *
     * Edits land back to front so an earlier one cannot shift a later one's offsets, and the caret ends up
     * after the highest-offset insertion (the snippet at the cursor) rather than after whichever import was
     * written last.
     */
    fun applyEdits(edits: List<UiTextEdit>): Boolean {
        val session = active?.session ?: return false
        val ordered = edits.filter { it.newText.isNotEmpty() || it.end > it.start }.sortedByDescending { it.start }
        if (ordered.isEmpty()) return false

        var caret = ordered.first().let { it.start + it.newText.length }
        ordered.forEachIndexed { index, edit ->
            val end = edit.end.coerceAtLeast(edit.start)
            session.replaceRange(edit.start, end, edit.newText, TextRange(edit.start + edit.newText.length))
            // Every edit after the first sits earlier in the file, so it shifts the caret by its own delta.
            if (index > 0) caret += edit.newText.length - (end - edit.start)
        }
        session.setCaret(caret)
        return true
    }

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
        backend.editor.onFileClosed(file.path)
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

    /** Persist [file]'s buffer to disk, rebase its saved baseline, and clear the dirty flag. No-op if clean.
     *  When "Reformat on save" (Settings ▸ Code Style) is on, the buffer is reformatted first — for EVERY save
     *  path (toolbar button, Cmd/Ctrl-S, autosave), not just the editor's key handler, so it works on-device
     *  too and for every language with a formatter (Kotlin included). Formatting is a suspend backend call, so
     *  that path saves asynchronously; the plain path stays synchronous. */
    fun save(file: OpenFile) {
        if (file.readOnly || !file.modified) return
        if (runCatching { backend.settings.settings().formatOnSave }.getOrDefault(false)) {
            scope.launch {
                reformatBuffer(file)
                writeToDisk(file)
            }
        } else {
            writeToDisk(file)
        }
    }

    private fun writeToDisk(file: OpenFile) {
        if (file.readOnly || !file.modified) return
        val text = file.text // one lazy materialization, on save (not per keystroke)
        backend.editor.saveFile(file.path, text)
        file.onSaved(text)
    }

    /** Reformat [file]'s live buffer in place (whole-document) before a save. Works with or without a mounted
     *  editor, so a non-active tab formats too. Best-effort: a formatter failure or an already-formatted buffer
     *  leaves it untouched. Mirrors CodeEditor.applyBufferEdits' caret shift; the editor (if mounted) brings the
     *  caret back into view via its editCount effect. */
    private suspend fun reformatBuffer(file: OpenFile) {
        val session = file.session
        val text = session.doc.text
        val caretBefore = session.selection.start.coerceIn(0, session.doc.length)
        val raw = runCatching { backend.editor.formatDocument(file.path, text) }.getOrNull().orEmpty()
        if (raw.isEmpty()) return
        val len = session.doc.length
        val edits = raw.map { e ->
            val st = e.start.coerceIn(0, len)
            RangeEdit(st, e.end.coerceIn(st, len), e.newText, st + e.newText.length)
        }
        session.applyEdits(edits, TextRange(mapOffsetThroughEdits(caretBefore, edits)))
    }

    /** Save the active tab (Cmd/Ctrl-S, toolbar). */
    fun saveActive() { active?.let(::save) }

    /**
     * Reopen the tabs persisted from a previous session with this project (in tab order + the active tab),
     * each restored to where the user left it: its view mode, caret, and scroll position. Files that no longer
     * exist on disk are skipped. Returns true when the previous session was HANDLED — either tabs were
     * restored, or the project was opened before and deliberately left with no tabs (a persisted-but-empty
     * session) — so the caller only falls back to [defaultFile] on a genuine first open (no session yet).
     */
    suspend fun restoreTabs(): Boolean {
        val saved = backend.projects.openTabs()
        // No tabs to restore: respect an intentionally-empty reopen (session file exists → stay empty), and
        // only fall through to a default file on a first open (no session file yet).
        if (saved.tabs.isEmpty()) return backend.projects.hasSavedSession()
        for (tab in saved.tabs) {
            val name = tab.path.substringAfterLast('/').substringAfterLast('\\')
            runCatching {
                openSuspend(tab.path, name) // a deleted file throws in readFile — skip it
                // Reapply the saved per-tab view state to the tab we just opened. setCaret + the scroll anchor
                // coerce into the (possibly changed) buffer, so a shrunken file can't strand the caret/scroll.
                openFiles.firstOrNull { it.path == tab.path }?.let { f ->
                    // Drop a persisted Blocks mode if the block editor is disabled (plugin off) — restore as text.
                    editorViewModeOf(tab.viewMode)
                        ?.takeUnless { it == EditorViewMode.Blocks && !blocksEnabled }
                        ?.let { f.viewMode = it }
                    f.session.setCaret(tab.caret)
                    f.session.viewportTopLine = tab.scrollLine.coerceAtLeast(0)
                }
            }
        }
        // Every saved tab's file is gone now, but the project WAS opened before — treat it as handled (leave
        // the editor empty) rather than surprising the user with an unrelated default file.
        if (openFiles.isEmpty()) return true
        activeIndex = saved.activeIndex.coerceIn(0, openFiles.lastIndex)
        return true
    }

    /** The current open tabs as a persistable snapshot: each tab's path, caret, scroll line, and view mode,
     *  plus the active index. */
    fun tabsSnapshot(): UiOpenTabs {
        // Read-only library tabs (`library://…`) have no disk file — never persist them (they'd fail to reopen).
        val persistable = openFiles.filter { !it.readOnly }
        val newActive = active?.takeUnless { it.readOnly }?.let { persistable.indexOf(it) } ?: 0
        return UiOpenTabs(
            persistable.map { f ->
                UiOpenTab(
                    path = f.path,
                    caret = f.session.selection.start,
                    scrollLine = f.session.viewportTopLine.coerceAtLeast(0),
                    viewMode = f.viewMode.persistId(),
                )
            },
            newActive.coerceAtLeast(0),
        )
    }

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
                if (f.readOnly) continue
                val followsFileRename = newPath != null && f.path == activePath
                if (!followsFileRename && f.modified) continue
                val diskPath = if (followsFileRename) newPath!! else f.path
                val text = readTabText(diskPath) ?: continue
                if (!followsFileRename && text == f.savedText) continue // untouched → preserve session/undo/caret
                val name = diskPath.substringAfterLast('/').substringAfterLast('\\')
                openFiles[i] = OpenFile(diskPath, name, text, tabId = f.tabId)
                backend.editor.updateDocument(diskPath, text)
            }
            dedupeTabsByPath()
            loadTree()
        }
    }

    /**
     * Re-read any clean open tab whose backing file changed on disk since it was loaded (e.g. the agent, or
     * any external tool, wrote it). Runs on every file-system-epoch bump. Modified tabs are left untouched so
     * an external write never clobbers in-progress user edits; unchanged tabs keep their session/undo/caret.
     */
    fun syncOpenTabsFromDisk() {
        scope.launch {
            // openFiles can be mutated (a tab opened/closed) while this suspends on disk I/O, so a captured
            // index goes stale — re-indexing it threw IndexOutOfBounds in the field. Snapshot the targets, then
            // write each result back at that exact tab's CURRENT index, skipping it if the tab is gone or has
            // since been edited (so an external write never clobbers in-progress user edits).
            for (f in openFiles.toList()) {
                if (f.readOnly || f.modified) continue
                val text = readTabText(f.path) ?: continue
                if (text == f.savedText) continue // untouched → preserve session/undo/caret
                val i = openFiles.indexOf(f)
                if (i < 0 || openFiles[i].modified) continue
                val name = f.path.substringAfterLast('/').substringAfterLast('\\')
                openFiles[i] = OpenFile(f.path, name, text, tabId = f.tabId)
                backend.editor.updateDocument(f.path, text)
            }
        }
    }

    /** Read [path]'s disk text off the main thread; null on any I/O error (a deleted/renamed file). */
    private suspend fun readTabText(path: String): String? =
        withContext(ioDispatcher) { runCatching { backend.files.readFile(path) }.getOrNull() }

    /** Re-push every open buffer to the editor backend so it re-analyzes against the current classpath — used
     *  after switching the active build variant (the engine has already invalidated the per-module analyzers). */
    fun reanalyzeOpenFiles() {
        for (f in openFiles) if (!f.readOnly) backend.editor.updateDocument(f.path, f.text)
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
            refreshCleanTabs() // a move rewrites importers' package/import lines — reflect that in open tabs
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
            openFiles[i] = OpenFile(rebased, name, text, tabId = openFiles[i].tabId)
            backend.editor.updateDocument(rebased, text)
        }
        dedupeTabsByPath()
    }

    /**
     * Collapse tabs that have ended up pointing at the same file, keeping the first.
     *
     * Re-pointing a tab after a rename or move ([rebaseTabs], [reloadAfterRename]) can land it on a path that
     * another tab already holds: renaming `a/Foo.kt` over an open `a/Bar.kt`, or moving a directory into one
     * whose files are already open. Two tabs for one path are wrong (edits/saves would fight over one file), so
     * they are collapsed here. The tab strip itself no longer crashes on the transient duplicate — it keys its
     * lazy row by [OpenFile.tabId], not path — but the logical duplicate must still be reconciled. The backend
     * is NOT told the file closed, because the kept tab still has it open.
     */
    private fun dedupeTabsByPath() {
        val seen = HashSet<String>()
        val duplicates = openFiles.indices.filter { !seen.add(openFiles[it].path) }
        if (duplicates.isEmpty()) return
        val activePath = openFiles.getOrNull(activeIndex)?.path
        // Remove from the end so the lower indices stay valid as the list shrinks.
        duplicates.asReversed().forEach { openFiles.removeAt(it) }
        // The active tab may have been one of the removed duplicates; follow its path to the tab that kept it.
        activeIndex = openFiles.indexOfFirst { it.path == activePath }
            .takeIf { it >= 0 } ?: activeIndex.coerceIn(0, openFiles.lastIndex.coerceAtLeast(0))
    }

    /** Re-read clean (unmodified) tabs whose disk content changed — e.g. references rewritten by a rename. */
    private suspend fun refreshCleanTabs() {
        for (i in openFiles.indices) {
            val f = openFiles[i]
            if (f.modified) continue
            val text = readTabText(f.path) ?: continue
            if (text == f.savedText) continue
            openFiles[i] = OpenFile(f.path, f.name, text, tabId = f.tabId)
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

        /** App preference: the last-open LEFT sidebar panel id ([LeftPanelId] or a plugin tool-window id), so
         *  the activity rail reopens to the same panel next launch. */
        const val LEFT_PANEL_PREF = "sidebar.leftPanel"
    }
}

/** A run the user is trying to start while a build/program is already running (see [IdeUiState.requestRun]). */
class PendingRun(val action: () -> Unit)
