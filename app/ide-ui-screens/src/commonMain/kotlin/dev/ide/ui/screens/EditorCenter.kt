package dev.ide.ui.screens

import dev.ide.ui.LocalPluginFileOpener
import dev.ide.ui.LocalPluginNavigator
import dev.ide.ui.theme.Ide
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextRange
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
import org.jetbrains.compose.resources.stringResource
import dev.ide.ui.generated.resources.toolchain_warning_many
import dev.ide.ui.generated.resources.show_details
import dev.ide.ui.generated.resources.gradle_mode_title
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.components.NoOpenFilesView
import dev.ide.ui.components.TabsStrip
import dev.ide.ui.editor.BlockEditor
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.engine.DaemonPass
import dev.ide.ui.editor.core.isLarge
import dev.ide.ui.editor.engine.EditorEngineDaemon
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.editor.preview.ComposePreviewPane
import dev.ide.ui.editor.preview.LayoutPreviewPane
import dev.ide.ui.editor.preview.MarkdownPreviewPane
import dev.ide.ui.editor.preview.PluginPreviewPane
import dev.ide.ui.editor.preview.ResourcePreviewPane
import dev.ide.ui.ext.EditorPreviewRegistry
import dev.ide.ui.ext.ViewModeContext
import dev.ide.ui.ext.ViewModeRegistry
import dev.ide.ui.editor.preview.isLayoutPreviewable
import dev.ide.ui.editor.preview.isMarkdownPreviewable
import dev.ide.ui.editor.preview.isPreviewable
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch
import dev.ide.ui.actions.applyWorkspaceEdits

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
     *  layouts without one — the top-bar icon then eases 0↔1 off [IdeUiState.leftOpen] instead. */
    navFraction: (() -> Float)? = null,
) {
    val project = state.backend.project
    val depsState by state.backend.deps.depsState.collectAsState()
    val depsScope = rememberCoroutineScope()
    val pluginNavigator = LocalPluginNavigator.current
    val pluginFileOpener = LocalPluginFileOpener.current
    // Gradle compatibility mode: non-null only for a project imported from Gradle. Drives the top-bar compat
    // chip + the details banner below the toolbar; the chip re-opens a dismissed banner. `compatEpoch` re-keys
    // the disk read so a convert/revert (which drops/re-adds the marker without changing rootPath) refreshes it
    // — otherwise the cached non-null value would leave a ghost banner/chip after converting.
    var compatEpoch by remember(project.rootPath) { mutableStateOf(0) }
    val compatInfo = remember(project.rootPath, compatEpoch) { state.backend.projects.compatibilityInfo() }
    var showCompatBanner by remember(project.rootPath) { mutableStateOf(compatInfo != null) }
    var showConvertDialog by remember(project.rootPath) { mutableStateOf(false) }
    // A project adopted from a folder nothing recognized (a clone). Read once per project: the marker only
    // changes when the project gains a module, which re-opens the editor anyway.
    val unrecognizedInfo = remember(project.rootPath) { state.backend.projects.unrecognizedProjectInfo() }
    var showUnrecognizedBanner by remember(project.rootPath) { mutableStateOf(unrecognizedInfo != null) }
    // One-shot: an import where "Convert to CodeAssist project" was chosen at the picker. Now that the editor
    // is open (so the reader's notes are known), run the convert flow — the confirm dialog is the gate when
    // `gradle.convert.warnUnresolved` is on (default); otherwise convert straight away.
    LaunchedEffect(project.rootPath) {
        if (!state.pendingGradleConvertPrompt) return@LaunchedEffect
        state.pendingGradleConvertPrompt = false
        if (compatInfo == null) return@LaunchedEffect
        val warn = state.backend.settings.preference("gradle.convert.warnUnresolved")?.toBooleanStrictOrNull() != false
        if (warn) showConvertDialog = true
        else if (state.backend.projects.convertToNative().ok) { compatEpoch++; showCompatBanner = false }
    }
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
        if (state.leftOpen) 1f else 0f,
        tween(Motion.BASE, easing = Motion.quiet),
        label = "navIconFraction",
    )
    // The primary RIGHT tool window (if any). On a phone the top bar shows one button to open its swipe-in
    // drawer (the desktop uses the right activity rail instead); the compact branch of EditorTopBar renders it.
    UiPluginHost.ensureLoaded()
    val rightPrimary = ToolWindowRegistry.forAnchor(ToolWindowAnchor.RIGHT).firstOrNull()
    Box(modifier) {
        Column(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
            EditorTopBar(
                projectName = project.name,
                indexStatus = indexStatus,
                onToggleNav = { state.toggleLeftSidebar() },
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
                rightToolIconId = rightPrimary?.iconId,
                rightToolTitle = rightPrimary?.title ?: "",
                rightToolOpen = state.selectedRightPanel != null,
                onToggleRightTool = { rightPrimary?.let { state.toggleRightPanel(it.id) } },
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
                            ),
                            navigate = pluginNavigator,
                        )
                    }
                },
                compact = compact,
            )
            DepsProgressBar(depsState) { depsScope.launch { state.backend.deps.retryDependencyResolution() } }
            // Hoisted so the notice strip can report the count and the banner can render the cards from the
            // same state; two holders would disagree about which warnings had been dismissed.
            val toolchain = rememberToolchainWarningState(state)
            // ONE bar for every project notice.
            //
            // Collected here rather than each rendering its own strip: these are independent conditions that
            // happen to co-occur, and ten of them stacked is half a phone screen of chrome above the code.
            // The three that are a single sentence live in the strip; the three that need a card of their own
            // contribute a summary line whose action reveals that card, so at most one card is ever open.
            val notices = buildList {
                if (compatInfo != null && !showCompatBanner) {
                    add(
                        EditorNotice(
                            id = "gradle-compat",
                            level = NoticeLevel.Warning,
                            summary = stringResource(Res.string.gradle_mode_title),
                            actionLabel = stringResource(Res.string.show_details),
                            onAction = { showCompatBanner = true },
                        ),
                    )
                }
                if (unrecognizedInfo != null && !showUnrecognizedBanner) {
                    add(
                        EditorNotice(
                            id = "unrecognized",
                            level = NoticeLevel.Warning,
                            summary = unrecognizedInfo.summary,
                            actionLabel = stringResource(Res.string.show_details),
                            onAction = { showUnrecognizedBanner = true },
                        ),
                    )
                }
                // Only a RUN of warnings becomes a count here; a lone one shows its card directly, because
                // that card carries the fix and accept actions and a one-line summary cannot.
                if (toolchain.shown.size > 1 && !toolchain.listOpen) {
                    add(
                        EditorNotice(
                            id = "toolchain",
                            level = NoticeLevel.Warning,
                            summary = stringResource(Res.string.toolchain_warning_many, toolchain.shown.size),
                            actionLabel = stringResource(Res.string.show_details),
                            onAction = toolchain::toggleList,
                            onDismiss = toolchain::dismissAll,
                        ),
                    )
                }
                androidSourcesNotice(state)?.let(::add)
                active?.let { file ->
                    readOnlyNotice(state, file)?.let(::add)
                    largeFileNotice(file)?.let(::add)
                }
            }
            EditorNoticeStrip(notices)
            if (compatInfo != null) {
                GradleCompatBanner(
                    state = state,
                    info = compatInfo,
                    visible = showCompatBanner,
                    compact = compact,
                    onDismiss = { showCompatBanner = false },
                    onConvert = { showConvertDialog = true },
                )
            }
            // Hosted outside the compatInfo guard so it survives the marker being dropped on convert (which
            // nulls compatInfo) and can still show its Undo/Done result step.
            if (showConvertDialog) {
                ConvertToNativeDialog(
                    notes = compatInfo?.notes ?: emptyList(),
                    backend = state.backend,
                    onConverted = { compatEpoch++; showCompatBanner = false },
                    onReverted = { compatEpoch++; showCompatBanner = true },
                    onClose = { showConvertDialog = false },
                )
            }
            if (unrecognizedInfo != null) {
                UnrecognizedProjectBanner(
                    info = unrecognizedInfo,
                    visible = showUnrecognizedBanner,
                    onDismiss = { showUnrecognizedBanner = false },
                )
            }
            // A toolchain problem that will break a module's build (a bundled KSP processor whose generated code
            // needs a newer runtime than the module declares): said here, with its fix, rather than left to
            // appear as unresolved symbols in generated code after a build. Project-scoped and above the tabs,
            // so it shows on open even with no file open, and even when the offending module (typically a `di/`
            // one) is never opened at all.
            ToolchainWarningBanner(toolchain, compact)
            TabsStrip(
                openFiles = state.openFiles,
                activeIndex = state.activeIndex,
                onSelect = { state.activeIndex = it },
                onClose = { state.close(it) },
                onCloseOthers = { state.closeOthers(it) },
                onCloseToRight = { state.closeToRight(it) },
                onCloseToLeft = { state.closeToLeft(it) },
                onCloseAll = { state.closeAll() },
                backend = state.backend,
            )
            if (active != null) {
                EditorDaemonEffect(state, active, indexStatus) { hasPreview = it }
                BreadcrumbBar(state, active, hasPreview)
                // The code editor and the preview, each as a Modifier-parameterized slot, so the single-pane modes
                // and the Split layout can place the SAME surfaces without duplicating their (long) wiring.
                // The editor is covered when an app-level overlay sits on top of it: the command palette or a
                // destination sheet (either layout), or — on a phone — the file-tree / build-console bottom sheets
                // (on desktop those are docked side panes that leave the editor interactive). A covered editor
                // dismisses its floating popups so they don't hang over the overlay.
                val editorObscured = state.paletteOpen || state.moreOpen ||
                        (compact && (state.leftOpen || state.consoleOpen))
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
                        // scrollable2D has no wheel handling, so the free-pan mode is touch-only.
                        twoAxisScroll = state.twoAxisScrollEnabled && isMobilePlatform,
                        pinchZoom = state.pinchZoomEnabled,
                        softKeyboardSuggestions = state.softKeyboardSuggestions,
                        wordWrap = state.wordWrapEnabled,
                        wrapIndent = state.wrapIndentEnabled,
                        horizontalScrollbar = state.horizontalScrollbarEnabled,
                        fontLigatures = state.fontLigaturesEnabled,
                        // Tapping a @Preview gutter icon switches this tab to the Preview surface, rendering that
                        // specific composable. The editor tools (incl. the Code/Blocks/Preview switch) are pinned
                        // to the breadcrumb row, so they're already visible — making the view change easy to undo.
                        onPreview = { fn ->
                            active.previewTarget = fn
                            active.viewMode = EditorViewMode.Preview
                        },
                        // A plugin editor action goes through the same dispatcher as the toolbar's, so its
                        // effects (edit, move the caret, open a file, navigate) behave identically. The caret
                        // snapshot is fetched at invoke time rather than kept on hand: it is only needed when
                        // an action actually runs, and refetching it guarantees it matches this buffer.
                        // A fix that reaches past this buffer (an import added in another file, a
                        // declaration moved) goes through the multi-file writer, which edits an open tab in
                        // place and writes a closed file through. Dropped here until it was wired up.
                        onOtherFileEdits = { edits -> state.applyWorkspaceEdits(edits) },
                        onEditorAction = { actionId, selStart, selEnd ->
                            state.dispatchAction(
                                actionId,
                                UiActionContext(
                                    place = UiActionPlaces.EDITOR,
                                    activeFilePath = active.path,
                                    selectionStart = selStart,
                                    selectionEnd = selEnd,
                                    caret = runCatching {
                                        state.backend.editor.caretContext(
                                            active.path, active.session.doc.text, selStart,
                                        )
                                    }.getOrNull(),
                                    documentText = active.session.doc.text,
                                ),
                                navigate = pluginNavigator,
                            )
                        },
                    )
                }
                // The plugin-contributed pane claiming this file, if any (see PluginPreviewPane).
                val pluginPreview = EditorPreviewRegistry.forPath(active.path)
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

                        // A plugin's own pane, for a file kind the four built-ins do not cover (a game
                        // scene, a shader, a diagram). Consulted AFTER them on purpose: a plugin cannot take
                        // `.xml` away from the layout preview by claiming it.
                        pluginPreview != null -> PluginPreviewPane(
                            preview = pluginPreview,
                            path = active.path,
                            text = active.text,
                            backend = state.backend,
                            dark = Ide.colors.isDark,
                            onOpenFile = pluginFileOpener,
                            onOpenScreen = pluginNavigator,
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
                    // Guard: only render blocks while the `blocks` plugin is enabled; otherwise fall through to
                    // the code surface (the toggle omits Blocks when disabled, so this is defence-in-depth).
                    EditorViewMode.Blocks -> if (state.blocksEnabled) BlockEditor(
                        path = active.path,
                        session = active.session,
                        backend = state.backend,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) else codeSurface(Modifier.weight(1f).fillMaxWidth())

                    EditorViewMode.Preview -> previewSurface(Modifier.weight(1f).fillMaxWidth(), false)
                    // Edit + watch at once: stacked on a phone (the only way both fit), side-by-side when wide.
                    EditorViewMode.Split -> SplitEditorPreview(
                        stacked = compact,
                        editor = codeSurface,
                        preview = { previewSurface(it, true) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    // A mode a plugin contributed for this file. Resolved by id, so a tab restored into a
                    // mode whose plugin is no longer installed falls through to the code editor below rather
                    // than showing an empty pane.
                    else -> {
                        val contributed = ViewModeRegistry.find(active.viewMode.id)
                            ?.takeIf { runCatching { it.appliesTo(active.path) }.getOrDefault(false) }
                        if (contributed == null) {
                            codeSurface(Modifier.weight(1f).fillMaxWidth())
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                contributed.content(rememberViewModeContext(state, active))
                            }
                        }
                    }
                }
            } else {
                NoOpenFilesView(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * The [ViewModeContext] a contributed pane renders against.
 *
 * It is a view OF the tab, not a copy: [ViewModeContext.text] reads the same [EditorSession] the code editor
 * edits, and [ViewModeContext.replaceText] writes through it, so a pane's edit is undoable, is analysed, and
 * marks the tab dirty exactly as typing does. Remembered on what a pane can observe rather than on the tab,
 * since the text changes on every keystroke.
 */
@Composable
private fun rememberViewModeContext(state: IdeUiState, active: OpenFile): ViewModeContext {
    val text = active.text
    val caret = active.session.selection.min
    return remember(active.path, text, caret, state.backend) {
        object : ViewModeContext {
            override val backend = state.backend
            override val filePath = active.path
            override val text = text
            override val caretOffset = caret

            override fun replaceText(start: Int, end: Int, newText: String) {
                val length = active.session.doc.length
                val from = start.coerceIn(0, length)
                val to = end.coerceIn(from, length)
                active.session.replaceRange(from, to, newText, TextRange(from + newText.length))
            }

            override fun openFile(path: String, offset: Int) {
                state.openAt(path, offset)
            }
        }
    }
}

/**
 * Drives the editor's highlighting daemon ([EditorEngineDaemon], modelled on IntelliJ's `DaemonCodeAnalyzer`)
 * for [active] — ONE restartable, prioritized, cancellable pass run per settled edit (diagnostics → semantic →
 * inlay → folds → @Preview markers → plugin decorations) with a unified preempt-retry, replacing the old per-channel debounced
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
    daemon.onDecorations = { active.session.applyDecorations(it.ranges, it.gutter, it.inlays) }
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
        // Apply the user's editor/analysis prefs (Settings) to the daemon before each run. Above the large-file
        // threshold the four heavy passes are forced off regardless of the prefs: parsing + resolving a very
        // large file builds an AST and symbol index many times the source size, which OOMs a low-heap device
        // (a recurring on-device crash on budget hardware). Cheap line-based syntax highlighting is not a pass,
        // so it keeps running; a disabled pass clears its overlay via the daemon's applyEmpty.
        val large = active.session.doc.isLarge()
        daemon.inlayEnabled = state.inlayHintsEnabled && !large
        daemon.semanticEnabled = state.semanticHighlightingEnabled && !large
        daemon.foldingEnabled = state.codeFoldingEnabled && !large
        daemon.analyzeEnabled = state.analyzeOnTheFly && !large
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
