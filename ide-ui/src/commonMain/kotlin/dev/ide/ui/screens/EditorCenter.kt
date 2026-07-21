package dev.ide.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ide.ui.EditorViewMode
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.actions.dispatchAction
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.components.DepsProgressBar
import dev.ide.ui.components.EditorTopBar
import dev.ide.ui.components.NoOpenFilesView
import dev.ide.ui.components.TabsStrip
import dev.ide.ui.editor.BlockEditor
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.engine.DaemonPass
import dev.ide.ui.editor.engine.EditorEngineDaemon
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.editor.preview.ComposePreviewPane
import dev.ide.ui.editor.preview.LayoutPreviewPane
import dev.ide.ui.editor.preview.MarkdownPreviewPane
import dev.ide.ui.editor.preview.ResourcePreviewPane
import dev.ide.ui.editor.preview.isLayoutPreviewable
import dev.ide.ui.editor.preview.isMarkdownPreviewable
import dev.ide.ui.editor.preview.isPreviewable
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * Top bar + deps progress + tabs + breadcrumb row + the code canvas — the editor column shared by both
 * layouts. When no file is open it shows a placeholder; otherwise it runs the per-file analysis + breadcrumb
 * effects and renders the active [EditorViewMode] (code / blocks / preview / split).
 */
@Composable
internal fun EditorCenter(
    state: IdeUiState,
    indexStatus: IndexUiStatus,
    compact: Boolean,
    modifier: Modifier,
    /** Live navigator-open fraction from the compact layout's push drawer (gesture-accurate); null on
     *  layouts without one — the top-bar icon then eases 0↔1 off [IdeUiState.navOpen] instead. */
    navFraction: (() -> Float)? = null,
) {
    val project = state.backend.project
    val depsState by state.backend.deps.depsState.collectAsState()
    val depsScope = rememberCoroutineScope()
    // Gradle compatibility mode: non-null only for a project imported from Gradle. Drives the top-bar compat
    // chip + the details banner below the toolbar; the chip re-opens a dismissed banner.
    val compatInfo = remember(project.rootPath) { state.backend.projects.compatibilityInfo() }
    var showCompatBanner by remember(project.rootPath) { mutableStateOf(compatInfo != null) }
    // @Preview presence (enables the Design view-mode toggle + top-bar shortcut) is set by the editor daemon's
    // PREVIEWS pass below — no separate detection effect.
    var hasPreview by remember(state.active?.path) { mutableStateOf(false) }
    val active = state.active
    // Bumped by the Find button (top bar) to open the editor's in-file find bar (Ctrl/⌘-F is the keyboard path).
    var findEpoch by remember(active?.path) { mutableStateOf(0) }
    // Bumped by the Reformat button (top bar) to reformat the active file (Ctrl/⌘-Alt-L is the keyboard path).
    var formatEpoch by remember(active?.path) { mutableStateOf(0) }
    // Bumped by the Optimize Imports menu item to reorganize the active file's imports (Ctrl/⌘-Alt-O too).
    var optimizeImportsEpoch by remember(active?.path) { mutableStateOf(0) }
    // Plugin-contributed toolbar actions (empty until a plugin registers; enablement re-evaluated per file).
    val toolbarActions = remember(active?.path) {
        state.backend.actions.actionsFor(
            UiActionContext(
                place = UiActionPlaces.MAIN_TOOLBAR,
                activeFilePath = active?.path
            )
        )
    }
    // The active build variant shown in the switcher chip. It targets the Android-app module being run
    // (derived from the run-task ids), so a non-Android project leaves it null and the chip is hidden. A
    // `variantEpoch` bump recomputes the label + re-analyzes open files after a switch.
    var variantEpoch by remember { mutableStateOf(0) }
    val variantModule = remember(project.name, variantEpoch) {
        state.backend.build.runTasks().firstNotNullOfOrNull { t ->
            listOf("androidRun:", "assemble:", "bundle:").firstOrNull { t.id.startsWith(it) }
                ?.let { p -> t.id.removePrefix(p).substringBefore(":") }
        }
    }
    val activeVariant = remember(project.name, variantEpoch, variantModule) {
        variantModule?.let { state.backend.build.activeVariant(it) }
    }

    val easedNav by animateFloatAsState(
        if (state.navOpen) 1f else 0f,
        tween(Motion.BASE, easing = Motion.quiet),
        label = "navIconFraction",
    )
    Box(modifier) {
        Column(Modifier.fillMaxSize().background(Ca.colors.editorBg)) {
            EditorTopBar(
                projectName = project.name,
                indexStatus = indexStatus,
                onToggleNav = { state.navOpen = !state.navOpen },
                navFraction = navFraction ?: { easedNav },
                onOpenPalette = { state.paletteOpen = true },
                runTasks = { state.backend.build.runTasks() },
                onPickTask = { state.consoleOpen = true; state.requestRun { state.backend.build.runTask(it.id) } },
                activeVariant = activeVariant,
                variants = {
                    variantModule?.let { state.backend.build.listVariants(it) } ?: emptyList()
                },
                onPickVariant = { v ->
                    variantModule?.let { state.backend.build.setActiveVariant(it, v) }
                    variantEpoch++
                    state.reanalyzeOpenFiles()
                },
                onSave = { state.saveActive() },
                hasUnsavedChanges = active?.modified == true,
                hasActiveFile = active != null,
                canUndo = active?.session?.canUndo == true,
                canRedo = active?.session?.canRedo == true,
                onUndo = { active?.session?.undo() },
                onRedo = { active?.session?.redo() },
                onFind = { if (active != null) findEpoch++ },
                onReformat = { if (active != null) formatEpoch++ },
                onOptimizeImports = { if (active != null) optimizeImportsEpoch++ },
                onToggleConsole = { state.consoleOpen = !state.consoleOpen },
                consoleOpen = state.consoleOpen,
                inlayHintsOn = state.inlayHintsEnabled,
                onToggleInlayHints = { state.inlayHintsEnabled = !state.inlayHintsEnabled },
                showPreview = hasPreview,
                previewBusy = active?.viewMode == EditorViewMode.Preview,
                onPreview = { active?.let { it.viewMode = EditorViewMode.Preview } },
                onIndexClick = { state.indexDetailOpen = true },
                compatibilityMode = compatInfo != null,
                onCompatClick = { showCompatBanner = true },
                pluginActions = toolbarActions,
                onPluginAction = { id ->
                    depsScope.launch {
                        state.dispatchAction(
                            id,
                            UiActionContext(
                                place = UiActionPlaces.MAIN_TOOLBAR,
                                activeFilePath = active?.path
                            )
                        )
                    }
                },
                compact = compact,
            )
            DepsProgressBar(depsState) { depsScope.launch { state.backend.deps.retryDependencyResolution() } }
            if (compatInfo != null) {
                GradleCompatBanner(
                    state = state,
                    info = compatInfo,
                    visible = showCompatBanner,
                    compact = compact,
                    onDismiss = { showCompatBanner = false },
                )
            }
            TabsStrip(
                openFiles = state.openFiles,
                activeIndex = state.activeIndex,
                onSelect = { state.activeIndex = it },
                onClose = { state.close(it) },
                onCloseOthers = { state.closeOthers(it) },
                onCloseToRight = { state.closeToRight(it) },
                onCloseToLeft = { state.closeToLeft(it) },
                onCloseAll = { state.closeAll() },
            )
            if (active != null) {
                EditorDaemonEffect(state, active, indexStatus) { hasPreview = it }
                BreadcrumbBar(state, active, hasPreview)
                AndroidSourcesBanner(state)
                // The code editor and the preview, each as a Modifier-parameterized slot, so the single-pane modes
                // and the Split layout can place the SAME surfaces without duplicating their (long) wiring.
                // The editor is covered when an app-level overlay sits on top of it: the command palette or a
                // destination sheet (either layout), or — on a phone — the file-tree / build-console bottom sheets
                // (on desktop those are docked side panes that leave the editor interactive). A covered editor
                // dismisses its floating popups so they don't hang over the overlay.
                val editorObscured = state.paletteOpen || state.sheetDest != null ||
                        (compact && (state.navOpen || state.consoleOpen))
                val codeSurface: @Composable (Modifier) -> Unit = { mod ->
                    CodeEditor(
                        path = active.path,
                        session = active.session,
                        backend = state.backend,
                        modifier = mod,
                        obscured = editorObscured,
                        onSave = { state.save(active) },
                        onNavigate = { p, o -> state.openAt(p, o) },
                        onRenamed = { newPath -> state.reloadAfterRename(active.path, newPath) },
                        findEpoch = findEpoch,
                        formatEpoch = formatEpoch,
                        optimizeImportsEpoch = optimizeImportsEpoch,
                        fontScale = state.editorFontScale,
                        onFontScaleChange = { state.editorFontScale = it },
                        completionAutoPopup = state.completionAutoPopup,
                        completionDelayMs = state.completionDelayMs,
                        twoAxisScroll = state.twoAxisScrollEnabled,
                        pinchZoom = state.pinchZoomEnabled,
                        softKeyboardSuggestions = state.softKeyboardSuggestions,
                        wordWrap = state.wordWrapEnabled,
                        wrapIndent = state.wrapIndentEnabled,
                        fontLigatures = state.fontLigaturesEnabled,
                        // Tapping a @Preview gutter icon switches this tab to the Preview surface, rendering that
                        // specific composable. The editor tools (incl. the Code/Blocks/Preview switch) are pinned
                        // to the breadcrumb row, so they're already visible — making the view change easy to undo.
                        onPreview = { fn ->
                            active.previewTarget = fn
                            active.viewMode = EditorViewMode.Preview
                        },
                    )
                }
                // `split` is true only in the Split view (editor + preview together): the Compose preview then
                // hides its chrome bars and fits to width so dragging the divider doesn't rescale it.
                val previewSurface: @Composable (Modifier, Boolean) -> Unit = { mod, split ->
                    when {
                        isMarkdownPreviewable(active.path) -> MarkdownPreviewPane(
                            path = active.path,
                            text = active.text,
                            modifier = mod,
                        )

                        isLayoutPreviewable(active.path) -> LayoutPreviewPane(
                            path = active.path,
                            text = active.text,
                            backend = state.backend,
                            session = active.session,
                            modifier = mod,
                        )

                        isPreviewable(active.path) -> ResourcePreviewPane(
                            path = active.path,
                            text = active.text,
                            backend = state.backend,
                            modifier = mod,
                        )

                        else -> ComposePreviewPane(
                            path = active.path,
                            text = active.text,
                            backend = state.backend,
                            host = state.composePreviewHost,
                            modifier = mod,
                            selected = active.previewTarget,
                            split = split,
                        )
                    }
                }
                when (active.viewMode) {
                    EditorViewMode.Blocks -> BlockEditor(
                        path = active.path,
                        session = active.session,
                        backend = state.backend,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    EditorViewMode.Preview -> previewSurface(Modifier.weight(1f).fillMaxWidth(), false)
                    // Edit + watch at once: stacked on a phone (the only way both fit), side-by-side when wide.
                    EditorViewMode.Split -> SplitEditorPreview(
                        stacked = compact,
                        editor = codeSurface,
                        preview = { previewSurface(it, true) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    else -> codeSurface(Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                NoOpenFilesView(Modifier.weight(1f).fillMaxWidth())
            }
        }
        // In-file structure / outline overlay (opened from the breadcrumb tap or Ctrl-F12).
        active?.let { StructureSheet(state, it) }
    }
}

/**
 * Drives the editor's highlighting daemon ([EditorEngineDaemon], modelled on IntelliJ's `DaemonCodeAnalyzer`)
 * for [active] — ONE restartable, prioritized, cancellable pass run per settled edit (diagnostics → semantic →
 * inlay → folds → @Preview markers) with a unified preempt-retry, replacing the old per-channel debounced
 * effects scattered across this screen and [CodeEditor]. It lives HERE (not in CodeEditor) so it runs in every
 * view mode — diagnostics + dirty state keep updating while the user is in Blocks/Preview, not just code view.
 *
 * Every result lands on the session (which shifts the overlays in place between passes), so the code canvas
 * just reads them; diagnostics additionally drive the file's dirty state, and the PREVIEWS pass reports
 * @Preview presence via [onHasPreview] to gate the Design toggle.
 */
@Composable
private fun EditorDaemonEffect(
    state: IdeUiState,
    active: OpenFile,
    indexStatus: IndexUiStatus,
    onHasPreview: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val daemon = remember(active.path) { EditorEngineDaemon(scope, state.backend, active.path) }
    daemon.onDiagnostics = { active.session.applyAnalysis(it); active.recomputeDirty() }
    daemon.onSemanticTokens = { active.session.applySemanticTokens(it) }
    daemon.onInlayHints = { active.session.applyInlayHints(it) }
    daemon.onCodeFolds = { folds ->
        active.session.applyCodeFolds(folds.map {
            FoldRegion(
                it.startOffset,
                it.endOffset,
                it.placeholder,
                it.kind,
                it.collapsedByDefault
            )
        })
    }
    daemon.onComposePreviews =
        { active.session.applyComposePreviews(it); onHasPreview(it.isNotEmpty()) }
    daemon.appliesTo = { pass ->
        when (pass) {
            // Semantic coloring + folding are Java/Kotlin; @Preview markers are Kotlin-only; diagnostics + inlay
            // apply to every backend (they no-op for languages that don't provide them).
            DaemonPass.SEMANTIC, DaemonPass.FOLDS ->
                active.path.endsWith(".java") || active.path.endsWith(".kt") || active.path.endsWith(
                    ".kts"
                )

            DaemonPass.PREVIEWS -> active.path.endsWith(".kt") || active.path.endsWith(".kts")
            else -> true
        }
    }
    DisposableEffect(daemon) { onDispose { daemon.close() } }
    LaunchedEffect(
        active.path, active.session.textRevision,
        state.inlayHintsEnabled, state.semanticHighlightingEnabled, state.codeFoldingEnabled,
        state.analyzeOnTheFly, state.reparseDelayMs,
    ) {
        // Apply the user's editor/analysis prefs (Settings) to the daemon before each run.
        daemon.inlayEnabled = state.inlayHintsEnabled
        daemon.semanticEnabled = state.semanticHighlightingEnabled
        daemon.foldingEnabled = state.codeFoldingEnabled
        daemon.analyzeEnabled = state.analyzeOnTheFly
        daemon.autoReparseDelayMs = state.reparseDelayMs
        daemon.restart(active.session.doc.text) // one lazy rope materialization per settled edit
    }
    // Re-run the daemon when the workspace index finishes building. A file opened (e.g. a restored tab) while
    // the index is still building is analyzed against an incomplete classpath/symbol index and can show stale
    // "unresolved symbol" diagnostics that resolve once indexing completes. The daemon is pull-based — it only
    // re-runs on a settled edit — so nothing re-triggers it on its own when the index catches up. Fire on the
    // building true→false transition only; a file opened in the already-built steady state is already analyzed
    // against the ready index by the edit/open effect above (the initial null state never fires).
    var wasIndexing by remember(active.path) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(active.path, indexStatus.building) {
        val prev = wasIndexing
        wasIndexing = indexStatus.building
        if (prev == true && !indexStatus.building) daemon.restart(active.session.doc.text)
    }
}
