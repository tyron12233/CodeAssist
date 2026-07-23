package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.RunStatus
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.actions.dispatchAction
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionPlaces
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import dev.ide.ui.components.BuildConsole
import dev.ide.ui.components.ChatOverlay
import dev.ide.ui.components.BuildDock
import dev.ide.ui.components.DockBarHeight
import dev.ide.ui.components.FileNavigator
import dev.ide.ui.components.FileOpKind
import dev.ide.ui.components.fileOpPath
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.theme.Motion
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContext
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.components.NewSourceLang
import dev.ide.ui.components.PushDrawer
import dev.ide.ui.components.SideRail
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca

/** App-global preference marking the dock's one-shot swipe-up teaching bounce as already shown. */
private const val DOCK_HINT_PREF = "dock.swipeHint.seen"

/**
 * Open a file tapped in the tree. A built `.apk` goes to the platform package installer (or reveal if the
 * host can't install, e.g. desktop); an `.aab` is revealed (it can't be installed directly); anything else
 * opens in the editor via [open]. Keeps binary build artifacts out of the text editor.
 */
internal fun openTreeFile(node: TreeNode, fileActions: FileActions, open: (String, String) -> Unit) {
    val path = node.filePath ?: return
    when {
        path.endsWith(".apk", ignoreCase = true) ->
            if (fileActions.canInstallApk) fileActions.installApk(path)
            else if (fileActions.canReveal) fileActions.reveal(path)
            else open(path, node.name)
        path.endsWith(".aab", ignoreCase = true) ->
            if (fileActions.canReveal) fileActions.reveal(path) else open(path, node.name)
        else -> open(path, node.name)
    }
}

/**
 * Wide-window layout: side rail · (navigator) · (search) · editor · (console) as docked panes, with the
 * destination sheets and command palette overlaid on top.
 */
@Composable
internal fun ExpandedLayout(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit,
    indexStatus: IndexUiStatus,
    buildState: BuildState,
    onNewFile: (String, List<PackageSegment>) -> Unit,
    onNewFolder: (String, List<PackageSegment>) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit,
    onFileOp: (TreeNode, FileOpKind) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    val project = state.backend.project
    val fileCtxScope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            SideRail(
                selected = state.rail,
                onSelect = state::selectRail,
                projectInitial = project.name,
                onOpenHub = onOpenHub,
            )
            if (state.navOpen) {
                GlassSurface(Modifier.width(300.dp).fillMaxHeight(), GlassMaterial.Regular) {
                    FileNavigator(
                        root = state.tree,
                        moduleCount = project.moduleCount,
                        activePath = state.active?.path,
                        onOpen = { node -> openTreeFile(node, fileActions) { p, n -> state.open(p, n) } },
                        modifier = Modifier.fillMaxSize(),
                        onNewFile = onNewFile,
                        onNewFolder = onNewFolder,
                        onNewResource = onNewResource,
                        onNewSource = onNewSource,
                        onViewDependencies = { node -> onOpenDependencies(node.moduleConfigName ?: node.name) },
                        onConfigureModule = { node -> onOpenModuleConfig(node.moduleConfigName ?: node.name) },
                        onAddSourceRoot = { node -> state.addSourceRootModule = node.moduleConfigName ?: node.name },
                        canImport = fileActions.canImport,
                        onImport = { doImport(state, fileActions) },
                        onImportInto = { dir -> doImportInto(state, fileActions, dir) },
                        canShare = fileActions.canShare,
                        onShare = { node -> node.filePath?.let { fileActions.share(it) } },
                        canExport = fileActions.canExport,
                        onExport = { node -> node.filePath?.let { fileActions.exportFile(it) } },
                        canModify = true,
                        onRename = { onFileOp(it, FileOpKind.Rename) },
                        onMove = { onFileOp(it, FileOpKind.Move) },
                        onCopy = { onFileOp(it, FileOpKind.Copy) },
                        onDelete = { onFileOp(it, FileOpKind.Delete) },
                        canReveal = fileActions.canReveal,
                        onReveal = { node -> node.fileOpPath()?.let { fileActions.reveal(it) } },
                        onOpenInFiles = if (fileActions.canReveal) ({ (state.tree.dirPath ?: state.backend.projects.storageRootPath())?.let { fileActions.reveal(it) } }) else null,
                        onRefreshTree = { state.refreshTree() },
                        mode = state.treeMode,
                        onModeChange = { state.selectTreeMode(it) },
                        expandedState = state.treeExpanded,
                    )
                }
                VerticalDivider()
            }
            if (state.searchOpen) {
                GlassSurface(Modifier.width(340.dp).fillMaxHeight(), GlassMaterial.Regular) {
                    SearchScreen(
                        backend = state.backend,
                        indexing = indexStatus.building,
                        onOpenAt = { p, o -> state.openAt(p, o) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                VerticalDivider()
            }
            EditorCenter(state, indexStatus, compact = false, Modifier.weight(1f).fillMaxHeight())
            if (state.consoleOpen) {
                VerticalDivider()
                GlassSurface(Modifier.width(380.dp).fillMaxHeight(), GlassMaterial.Regular) {
                    // Collected here (not threaded from the parent) so ~10/s app-log updates recompose only
                    // the console subtree, not the whole editor layout.
                    val appLog by state.backend.build.appLog.collectAsState()
                    BuildConsole(
                        buildState = buildState,
                        indexStatus = indexStatus,
                        onRun = { state.requestRun { state.backend.build.runBuild() } },
                        onStop = { state.backend.build.stopBuild() },
                        onCollapse = { state.consoleOpen = false },
                        modifier = Modifier.fillMaxSize().padding(14.dp),
                        onOpenDiagnostic = { d -> d.file?.let { state.openAtLine(it, d.line, d.column) } },
                        backend = state.backend,
                        activeFilePath = state.active?.path,
                        appLog = appLog,
                    )
                }
            }
            // The AI agent chat drawer, right edge. Plugin-based: rendered from the RIGHT tool-window anchor,
            // so any UI plugin can contribute a right-edge panel (the chat is AgentUiPlugin's contribution).
            // Slides in/out from the end so opening/closing the drawer is animated, matching the mobile overlay.
            if (state.chatOpen) UiPluginHost.ensureLoaded()
            val rightTool = ToolWindowRegistry.forAnchor(ToolWindowAnchor.RIGHT).firstOrNull()
            AnimatedVisibility(
                visible = state.chatOpen && rightTool != null,
                enter = expandHorizontally(tween(Motion.BASE, easing = Motion.quiet), expandFrom = Alignment.End) +
                    fadeIn(tween(Motion.BASE)),
                exit = shrinkHorizontally(tween(Motion.BASE, easing = Motion.quiet), shrinkTowards = Alignment.End) +
                    fadeOut(tween(Motion.BASE / 2)),
            ) {
                Row(Modifier.fillMaxHeight()) {
                    VerticalDivider()
                    GlassSurface(Modifier.width(420.dp).fillMaxHeight(), GlassMaterial.Regular) {
                        val twBackend = state.backend
                        val twActive = state.active?.path
                        val twCtx = remember(twBackend, twActive) {
                            object : ToolWindowContext {
                                override val backend = twBackend
                                override val activeFilePath = twActive
                            }
                        }
                        rightTool?.content(twCtx)
                    }
                }
            }
        }
        DestinationSheets(state, compact = false, onOpenModuleConfig, onToggleTheme, onOpenHub, onCloseProject, fileActions)
        PaletteOverlay(state, onToggleTheme, onOpenHub, onOpenDependencies)
    }
}

/**
 * Phone layout: a single editor pane with a bottom nav. The file navigator is a **push drawer** on the
 * left (the whole pane slides right to reveal it — edge swipe / editor-at-scroll-start swipe / top-bar
 * toggle); the bottom nav doubles as the collapsed build dock (swipe up for the console); the
 * destinations stay bottom sheets.
 */
@Composable
internal fun CompactLayout(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit,
    indexStatus: IndexUiStatus,
    buildState: BuildState,
    onNewFile: (String, List<PackageSegment>) -> Unit,
    onNewFolder: (String, List<PackageSegment>) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit,
    onFileOp: (TreeNode, FileOpKind) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    val project = state.backend.project
    val fileCtxScope = rememberCoroutineScope()
    // Hide the bottom nav while the soft keyboard is up, so the editor gets the full height and the user can
    // focus on the code being typed (the nav is one swipe/back away). The IME inset is read raw — directly,
    // not via a consuming modifier — so the app's `safeDrawing` padding doesn't zero it. Always 0 on desktop.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // The drawer's live open fraction, mirrored by the top bar's sidebar icon (its divider tracks the
    // drawer edge through a swipe). Float state written per frame; read deferred in the icon's draw.
    var navProgress by remember { mutableFloatStateOf(0f) }
    // One-shot swipe-affordance hint: the first build activity that happens with the dock collapsed peeks
    // the bar up and back so the drag is discoverable; persisted so it never repeats. (Run usually
    // auto-expands the console, so in practice this fires the first time a build runs or finishes with
    // the bar down — exactly when there's something under it worth swiping up for.)
    var dockHint by remember { mutableStateOf(false) }
    LaunchedEffect(buildState.status) {
        if (isMobilePlatform && buildState.status != RunStatus.Idle && !state.consoleOpen &&
            state.backend.settings.preference(DOCK_HINT_PREF) != "true"
        ) dockHint = true
    }
    Box(Modifier.fillMaxSize()) {
        // The navigator is a push drawer: the editor pane (incl. the bottom nav) slides right in place of
        // the tree rather than being covered by a sheet. Opens by edge swipe, by a rightward swipe once the
        // editor is at its horizontal start (nested-scroll aware), or from the top-bar toggle.
        PushDrawer(
            open = state.navOpen,
            onOpenChange = { state.navOpen = it },
            gesturesEnabled = isMobilePlatform,
            onProgress = { navProgress = it },
            drawerContent = {
                FileNavigator(
                    root = state.tree,
                    moduleCount = project.moduleCount,
                    activePath = state.active?.path,
                    onOpen = { node -> openTreeFile(node, fileActions) { p, n -> state.open(p, n); state.navOpen = false } },
                    modifier = Modifier.fillMaxSize(),
                    onNewFile = onNewFile,
                    onNewFolder = onNewFolder,
                    onNewResource = onNewResource,
                    onNewSource = onNewSource,
                    onViewDependencies = { node -> state.navOpen = false; onOpenDependencies(node.moduleConfigName ?: node.name) },
                    onConfigureModule = { node -> state.navOpen = false; onOpenModuleConfig(node.moduleConfigName ?: node.name) },
                    onAddSourceRoot = { node -> state.navOpen = false; state.addSourceRootModule = node.moduleConfigName ?: node.name },
                    canImport = fileActions.canImport,
                    onImport = { doImport(state, fileActions) },
                    onImportInto = { dir -> doImportInto(state, fileActions, dir) },
                    canShare = fileActions.canShare,
                    onShare = { node -> node.filePath?.let { fileActions.share(it) } },
                    canExport = fileActions.canExport,
                    onExport = { node -> node.filePath?.let { fileActions.exportFile(it) } },
                    canModify = true,
                    onRename = { onFileOp(it, FileOpKind.Rename) },
                    onMove = { onFileOp(it, FileOpKind.Move) },
                    onCopy = { onFileOp(it, FileOpKind.Copy) },
                    onDelete = { onFileOp(it, FileOpKind.Delete) },
                    canReveal = fileActions.canReveal,
                    onReveal = { node -> node.fileOpPath()?.let { fileActions.reveal(it) } },
                    contextMenuFor = { node ->
                        state.backend.actions.menuFor(UiActionContext(place = UiActionPlaces.FILE_CONTEXT, contextPath = node.filePath ?: node.dirPath))
                    },
                    onContextAction = { id, node ->
                        fileCtxScope.launch {
                            state.dispatchAction(id, UiActionContext(place = UiActionPlaces.FILE_CONTEXT, contextPath = node.filePath ?: node.dirPath))
                        }
                    },
                    onOpenInFiles = if (fileActions.canReveal) ({ (state.tree.dirPath ?: state.backend.projects.storageRootPath())?.let { fileActions.reveal(it) } }) else null,
                    onRefreshTree = { state.refreshTree() },
                    mode = state.treeMode,
                    onModeChange = { state.selectTreeMode(it) },
                    expandedState = state.treeExpanded,
                )
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    EditorCenter(
                        state, indexStatus, compact = true, Modifier.weight(1f).fillMaxWidth(),
                        navFraction = { navProgress },
                    )
                    // While the keyboard is up: a coding-symbol accessory bar sits directly above it (the root's
                    // safeDrawing padding lifts this Column above the IME). It inserts into the active editor; off-
                    // keyboard, the dock's collapsed bar takes the slot instead.
                    // No enter/exit animation for now — it read as clunky; the bar just appears with the keyboard.
                    if (keyboardOpen && state.active != null) {
                        EditorSymbolBar(
                            onTab = { state.active?.session?.indent() },
                            onSymbol = { sym -> state.active?.session?.commitText(sym) },
                            onComment = { state.active?.session?.toggleComment() },
                            onMoveLineUp = { state.active?.session?.moveLines(-1) },
                            onMoveLineDown = { state.active?.session?.moveLines(1) },
                            onDuplicateLine = { state.active?.session?.duplicateSelection() },
                            showDiagnosticJump = state.active?.session?.diagnostics?.isNotEmpty() == true,
                            onNextDiagnostic = { state.active?.session?.goToDiagnostic(forward = true) },
                        )
                    }
                    // Reserve the dock's collapsed-bar slot so the editor column isn't hidden behind it —
                    // the dock itself is an overlay (below) so its expansion never relayouts the editor.
                    if (!keyboardOpen) Spacer(Modifier.height(DockBarHeight))
                }
                // The bottom nav is the collapsed face of the build dock: swipe it up (or tap its build
                // chip / the top-bar console toggle) and it expands into the build console — bar → 60% →
                // full detents, nav items fading out as the console fades in. Replaces the console sheet.
                BuildDock(
                    open = state.consoleOpen,
                    onOpenChange = { state.consoleOpen = it },
                    buildState = buildState,
                    hidden = keyboardOpen && !state.consoleOpen,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    hint = dockHint,
                    onHintShown = {
                        dockHint = false
                        state.backend.settings.setPreference(DOCK_HINT_PREF, "true")
                    },
                    bar = { BottomNav(selected = state.rail, onSelect = state::selectRail) },
                ) {
                    val appLog by state.backend.build.appLog.collectAsState()
                    BuildConsole(
                        buildState = buildState,
                        indexStatus = indexStatus,
                        onRun = { state.requestRun { state.backend.build.runBuild() } },
                        onStop = { state.backend.build.stopBuild() },
                        onCollapse = { state.consoleOpen = false },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(14.dp),
                        // On phone the console covers the editor; jump to the file and collapse the dock.
                        onOpenDiagnostic = { d -> d.file?.let { state.openAtLine(it, d.line, d.column); state.consoleOpen = false } },
                        backend = state.backend,
                        activeFilePath = state.active?.path,
                        appLog = appLog,
                    )
                }
            }
        }

        DestinationSheets(state, compact = true, onOpenModuleConfig, onToggleTheme, onOpenHub, onCloseProject, fileActions)
        PaletteOverlay(state, onToggleTheme, onOpenHub, onOpenDependencies)
        // The AI agent chat: a right-edge drawer overlay (the phone counterpart of the desktop pane). It owns
        // its own continuous right-edge-swipe-to-open gesture, so the finger drags the panel in directly.
        ChatOverlay(state)
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Ca.colors.separator))
}
