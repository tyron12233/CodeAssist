package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ide.ui.EditorViewMode
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.components.DepsProgressBar
import dev.ide.ui.components.EditorTopBar
import dev.ide.ui.components.TabsStrip
import dev.ide.ui.editor.BlockEditor
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.engine.DaemonPass
import dev.ide.ui.editor.engine.EditorEngineDaemon
import dev.ide.ui.editor.preview.ComposePreviewPane
import dev.ide.ui.editor.preview.LayoutPreviewPane
import dev.ide.ui.editor.preview.ResourcePreviewPane
import dev.ide.ui.editor.preview.isLayoutPreviewable
import dev.ide.ui.editor.preview.isPreviewable
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch

/**
 * Top bar + deps progress + tabs + breadcrumb row + the code canvas — the editor column shared by both
 * layouts. When no file is open it shows a placeholder; otherwise it runs the per-file analysis + breadcrumb
 * effects and renders the active [EditorViewMode] (code / blocks / preview / split).
 */
@Composable
internal fun EditorCenter(state: IdeUiState, indexStatus: IndexUiStatus, compact: Boolean, modifier: Modifier) {
    val project = state.backend.project
    val depsState by state.backend.depsState.collectAsState()
    val depsScope = rememberCoroutineScope()
    // @Preview presence (enables the Design view-mode toggle + top-bar shortcut) is set by the editor daemon's
    // PREVIEWS pass below — no separate detection effect.
    var hasPreview by remember(state.active?.path) { mutableStateOf(false) }
    val active = state.active
    // Bumped by the Find button (top bar) to open the editor's in-file find bar (Ctrl/⌘-F is the keyboard path).
    var findEpoch by remember(active?.path) { mutableStateOf(0) }
    Column(modifier) {
        EditorTopBar(
            projectName = project.name,
            indexStatus = indexStatus,
            onToggleNav = { state.navOpen = !state.navOpen },
            onOpenPalette = { state.paletteOpen = true },
            onRun = { state.consoleOpen = true; state.backend.runBuild() },
            runTasks = { state.backend.runTasks() },
            onPickTask = { state.consoleOpen = true; state.backend.runTask(it.id) },
            onSave = { state.saveActive() },
            hasUnsavedChanges = active?.modified == true,
            hasActiveFile = active != null,
            canUndo = active?.session?.canUndo == true,
            canRedo = active?.session?.canRedo == true,
            onUndo = { active?.session?.undo() },
            onRedo = { active?.session?.redo() },
            onFind = { if (active != null) findEpoch++ },
            onToggleConsole = { state.consoleOpen = !state.consoleOpen },
            consoleOpen = state.consoleOpen,
            inlayHintsOn = state.inlayHintsEnabled,
            onToggleInlayHints = { state.inlayHintsEnabled = !state.inlayHintsEnabled },
            showPreview = hasPreview,
            previewBusy = active?.viewMode == EditorViewMode.Preview,
            onPreview = { active?.let { it.viewMode = EditorViewMode.Preview } },
            onIndexClick = { state.indexDetailOpen = true },
            compact = compact,
        )
        DepsProgressBar(depsState) { depsScope.launch { state.backend.retryDependencyResolution() } }
        TabsStrip(
            openFiles = state.openFiles,
            activeIndex = state.activeIndex,
            onSelect = { state.activeIndex = it },
            onClose = { state.close(it) },
        )
        if (active != null) {
            EditorDaemonEffect(state, active) { hasPreview = it }
            BreadcrumbBar(state, active, hasPreview)
            AndroidSourcesBanner(state)
            // The code editor and the preview, each as a Modifier-parameterized slot, so the single-pane modes
            // and the Split layout can place the SAME surfaces without duplicating their (long) wiring.
            val codeSurface: @Composable (Modifier) -> Unit = { mod ->
                CodeEditor(
                    path = active.path,
                    session = active.session,
                    backend = state.backend,
                    modifier = mod,
                    onSave = { state.save(active) },
                    onNavigate = { p, o -> state.openAt(p, o) },
                    onRenamed = { newPath -> state.reloadAfterRename(active.path, newPath) },
                    findEpoch = findEpoch,
                    fontScale = state.editorFontScale,
                    onFontScaleChange = { state.editorFontScale = it },
                    // Tapping a @Preview gutter icon switches this tab to the Preview surface, rendering that
                    // specific composable. The editor tools (incl. the Code/Blocks/Preview switch) are pinned
                    // to the breadcrumb row, so they're already visible — making the view change easy to undo.
                    onPreview = { fn ->
                        active.previewTarget = fn
                        active.viewMode = EditorViewMode.Preview
                    },
                )
            }
            val previewSurface: @Composable (Modifier) -> Unit = { mod ->
                when {
                    isLayoutPreviewable(active.path) -> LayoutPreviewPane(
                        path = active.path, text = active.text, backend = state.backend, modifier = mod,
                    )
                    isPreviewable(active.path) -> ResourcePreviewPane(
                        path = active.path, text = active.text, backend = state.backend, modifier = mod,
                    )
                    else -> ComposePreviewPane(
                        path = active.path, text = active.text, backend = state.backend,
                        host = state.composePreviewHost, modifier = mod, selected = active.previewTarget,
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
                EditorViewMode.Preview -> previewSurface(Modifier.weight(1f).fillMaxWidth())
                // Edit + watch at once: stacked on a phone (the only way both fit), side-by-side when wide.
                EditorViewMode.Split -> SplitEditorPreview(
                    stacked = compact,
                    editor = codeSurface,
                    preview = previewSurface,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                else -> codeSurface(Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().background(Ca.colors.editorBg), contentAlignment = Alignment.Center) {
                Text("Open a file from the navigator", color = Ca.colors.textTertiary, style = Ca.type.subhead)
            }
        }
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
private fun EditorDaemonEffect(state: IdeUiState, active: OpenFile, onHasPreview: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val daemon = remember(active.path) { EditorEngineDaemon(scope, state.backend, active.path) }
    daemon.onDiagnostics = { active.session.applyAnalysis(it); active.recomputeDirty() }
    daemon.onSemanticTokens = { active.session.applySemanticTokens(it) }
    daemon.onInlayHints = { active.session.applyInlayHints(it) }
    daemon.onCodeFolds = { folds ->
        active.session.applyCodeFolds(folds.map {
            dev.ide.ui.editor.folding.FoldRegion(it.startOffset, it.endOffset, it.placeholder, it.kind, it.collapsedByDefault)
        })
    }
    daemon.onComposePreviews = { active.session.applyComposePreviews(it); onHasPreview(it.isNotEmpty()) }
    daemon.appliesTo = { pass ->
        when (pass) {
            // Semantic coloring + folding are Java/Kotlin; @Preview markers are Kotlin-only; diagnostics + inlay
            // apply to every backend (they no-op for languages that don't provide them).
            DaemonPass.SEMANTIC, DaemonPass.FOLDS ->
                active.path.endsWith(".java") || active.path.endsWith(".kt") || active.path.endsWith(".kts")
            DaemonPass.PREVIEWS -> active.path.endsWith(".kt") || active.path.endsWith(".kts")
            else -> true
        }
    }
    DisposableEffect(daemon) { onDispose { daemon.close() } }
    LaunchedEffect(active.path, active.session.textRevision, state.inlayHintsEnabled) {
        daemon.inlayEnabled = state.inlayHintsEnabled
        daemon.restart(active.session.doc.text) // one lazy rope materialization per settled edit
    }
}
