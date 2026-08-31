package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.LeftPanelId
import dev.ide.ui.LocalPluginNavigator
import dev.ide.ui.actions.dispatchAction
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.CustomizationActions
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.components.ActivityRail
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.BuildConsole
import dev.ide.ui.components.BuildDock
import dev.ide.ui.components.DockBarHeight
import dev.ide.ui.components.FileNavigator
import dev.ide.ui.components.FileOpKind
import dev.ide.ui.components.fileOpPath
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.NewSourceLang
import dev.ide.ui.components.ProjectTile
import dev.ide.ui.components.PushDrawer
import dev.ide.ui.components.RailActionItem
import dev.ide.ui.components.RailSide
import dev.ide.ui.components.SegmentedPanelSwitcher
import dev.ide.ui.components.SidebarPane
import dev.ide.ui.components.SidebarPanel
import dev.ide.ui.components.pluginPanels
import dev.ide.ui.components.RightToolOverlay
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.buildc_build
import dev.ide.ui.generated.resources.edchrome_files
import dev.ide.ui.generated.resources.edchrome_more
import dev.ide.ui.generated.resources.edchrome_settings_and_tools
import dev.ide.ui.generated.resources.search
import dev.ide.ui.generated.resources.structure_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.platform.verticalResizeCursor
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** App-global preference marking the dock's one-shot swipe-up teaching bounce as already shown. */
private const val DOCK_HINT_PREF = "dock.swipeHint.seen"

/** Docked panel widths. */
private val LeftPaneWidth = 320.dp
private val RightPaneWidth = 420.dp

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
 * Build the LEFT sidebar's panel list: the built-in panes (Files · Search · Structure · Source) plus every
 * plugin-contributed LEFT tool window, merged and sorted by order. Both layouts share this so the rail, the
 * desktop pane, and the mobile drawer all show the same panels. [closeDrawer] fires after a navigating action
 * (compact closes the drawer; desktop no-ops).
 */
@Composable
internal fun buildLeftPanels(
    state: IdeUiState,
    fileActions: FileActions,
    indexBuilding: Boolean,
    onNewFile: (String, List<PackageSegment>) -> Unit,
    onNewFolder: (String, List<PackageSegment>) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewImageAsset: (TreeNode) -> Unit,
    onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit,
    onFileOp: (TreeNode, FileOpKind) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    closeDrawer: () -> Unit,
): List<SidebarPanel> {
    val filesTitle = stringResource(Res.string.edchrome_files)
    val searchTitle = stringResource(Res.string.search)
    val structureTitle = stringResource(Res.string.structure_title)

    // Remembered HERE (the panel host stays composed while the drawer/left panel is swapped) rather than inside
    // SearchScreen, so a search survives navigating to a result and reopening Search for the next occurrence.
    val searchState = remember { SearchState() }

    val builtIns = listOf(
        SidebarPanel(LeftPanelId.FILES, filesTitle, CaIcons.docText, order = 10) {
            FilesPanelContent(
                state, fileActions, onNewFile, onNewFolder, onNewResource, onNewImageAsset, onNewSource,
                onFileOp, onOpenDependencies, onOpenModuleConfig, closeDrawer,
            )
        },
        SidebarPanel(LeftPanelId.SEARCH, searchTitle, CaIcons.search, order = 20) {
            SearchScreen(
                backend = state.backend,
                indexing = indexBuilding,
                onOpenAt = { p, o -> state.openAt(p, o); closeDrawer() },
                modifier = Modifier.fillMaxSize(),
                searchState = searchState,
            )
        },
        SidebarPanel(LeftPanelId.STRUCTURE, structureTitle, CaIcons.code, order = 30) {
            StructureOutline(state, onNavigated = closeDrawer, modifier = Modifier.fillMaxSize())
        },
    )
    // The source-control panel is contributed by the version-control plugin (it registers under
    // LeftPanelId.SOURCE, so it takes this rail slot and the phone bottom-nav slot that maps to it). With the
    // plugin disabled there is simply no such panel, rather than a placeholder promising one.
    val plugins = pluginPanels(ToolWindowAnchor.LEFT, state.backend, state.active?.path)
    return (builtIns + plugins).sortedWith(compareBy({ it.order }, { it.title }))
}

/** Dispatches a symbol-bar action key ([CustomizationActions]) against the active editor session. Tab commits
 *  the highlighted completion when the popup is up (like a hardware Tab), else indents — the bar has no physical
 *  key event to route through `onPreviewKey`. */
private fun dispatchSymbolAction(state: IdeUiState, action: String) {
    val s = state.active?.session ?: return
    when (action) {
        CustomizationActions.TAB -> if (s.acceptCompletionIfShowing?.invoke() != true) s.indent()
        CustomizationActions.COMMENT -> s.toggleComment()
        CustomizationActions.MOVE_LINE_UP -> s.moveLines(-1)
        CustomizationActions.MOVE_LINE_DOWN -> s.moveLines(1)
        CustomizationActions.DUPLICATE_LINE -> s.duplicateSelection()
        CustomizationActions.NEXT_PROBLEM -> s.goToDiagnostic(forward = true)
    }
}

/** The Files panel body — the full [FileNavigator] wiring, shared by both layouts. [closeDrawer] closes the
 *  compact push drawer after opening a file / navigating to a module action (a no-op on desktop). */
@Composable
private fun FilesPanelContent(
    state: IdeUiState,
    fileActions: FileActions,
    onNewFile: (String, List<PackageSegment>) -> Unit,
    onNewFolder: (String, List<PackageSegment>) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewImageAsset: (TreeNode) -> Unit,
    onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit,
    onFileOp: (TreeNode, FileOpKind) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    closeDrawer: () -> Unit,
) {
    val project = state.backend.project
    val fileCtxScope = rememberCoroutineScope()
    val pluginNavigator = LocalPluginNavigator.current
    FileNavigator(
        root = state.tree,
        moduleCount = project.moduleCount,
        activePath = state.active?.path,
        onOpen = { node -> openTreeFile(node, fileActions) { p, n -> state.open(p, n); closeDrawer() } },
        modifier = Modifier.fillMaxSize(),
        onNewFile = onNewFile,
        onNewFolder = onNewFolder,
        onNewResource = onNewResource,
        onNewImageAsset = onNewImageAsset,
        onNewSource = onNewSource,
        onViewDependencies = { node -> closeDrawer(); onOpenDependencies(node.moduleConfigName ?: node.name) },
        onConfigureModule = { node -> closeDrawer(); onOpenModuleConfig(node.moduleConfigName ?: node.name) },
        onAddSourceRoot = { node -> closeDrawer(); state.addSourceRootModule = node.moduleConfigName ?: node.name },
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
                state.dispatchAction(
                    id,
                    UiActionContext(place = UiActionPlaces.FILE_CONTEXT, contextPath = node.filePath ?: node.dirPath),
                    navigate = pluginNavigator,
                )
            }
        },
        onOpenInFiles = if (fileActions.canReveal) ({ (state.tree.dirPath ?: state.backend.projects.storageRootPath())?.let { fileActions.reveal(it) } }) else null,
        onRefreshTree = { state.refreshTree() },
        mode = state.treeMode,
        onModeChange = { state.selectTreeMode(it) },
        expandedState = state.treeExpanded,
    )
}

/**
 * Wide-window layout: left activity rail · left pane · editor · (console) · right pane · right activity rail.
 * Both rails are data-driven (built-in + plugin tool windows); the right rail lays down nothing when no
 * plugin contributes a RIGHT tool window. Destination sheets + command palette overlay on top.
 */
@Composable
internal fun ExpandedLayout(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenIconManager: () -> Unit,
    indexStatus: IndexUiStatus,
    buildState: BuildState,
    onNewFile: (String, List<PackageSegment>) -> Unit,
    onNewFolder: (String, List<PackageSegment>) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewImageAsset: (TreeNode) -> Unit,
    onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit,
    onFileOp: (TreeNode, FileOpKind) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    val project = state.backend.project
    val leftPanels = buildLeftPanels(
        state, fileActions, indexStatus.building,
        onNewFile, onNewFolder, onNewResource, onNewImageAsset, onNewSource, onFileOp, onOpenDependencies, onOpenModuleConfig,
        closeDrawer = {}, // desktop panes are persistent — never auto-collapse
    )
    val rightPanels = pluginPanels(ToolWindowAnchor.RIGHT, state.backend, state.active?.path)
    val moreLabel = stringResource(Res.string.edchrome_more)
    val settingsLabel = stringResource(Res.string.edchrome_settings_and_tools)
    val buildConsoleLabel = stringResource(Res.string.buildc_build)
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            ActivityRail(
                panels = leftPanels,
                selectedId = state.selectedLeftPanel,
                onSelect = { state.toggleLeftPanel(it) },
                header = {
                    ProjectTile(project.name, size = 42.dp)
                    Box(Modifier.padding(vertical = 2.dp).width(32.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                },
                footer = {
                    // The build console is a BOTTOM tool window (docked below the editor, see the centre column);
                    // IntelliJ-style its toggle lives at the lower-left of the rail, tinted while it's open.
                    RailActionItem(CaIcons.terminal, buildConsoleLabel, active = state.consoleOpen) {
                        state.consoleOpen = !state.consoleOpen
                    }
                    RailActionItem(CaIcons.ellipsis, moreLabel) { state.moreOpen = true }
                    RailActionItem(CaIcons.gear, settingsLabel, onClick = onOpenHub)
                },
            )
            // Centre column spanning the width between the two activity rails: the editor (with the left/right
            // tool panes) on top, and the build console docked along the BOTTOM (IntelliJ bottom tool window)
            // rather than as a right-edge pane. The rails stay full-height, so their footer buttons — including
            // the console toggle — sit in the lower corners. The divider above the console drags to resize it.
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val density = LocalDensity.current
                val minConsole = 140.dp
                val maxConsole = (maxHeight - 200.dp).coerceAtLeast(minConsole)
                var consoleHeight by remember { mutableStateOf(300.dp) }
                val consoleH = consoleHeight.coerceIn(minConsole, maxConsole)
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        SidebarPane(leftPanels, state.selectedLeftPanel, RailSide.Left, paneWidth = LeftPaneWidth)
                        EditorCenter(state, indexStatus, compact = false, Modifier.weight(1f).fillMaxHeight())
                        // Right-edge tool-window pane. Fully plugin-derived: nothing lays down when no plugin
                        // contributes a RIGHT tool window (the AI chat is one such plugin).
                        SidebarPane(rightPanels, state.selectedRightPanel, RailSide.Right, paneWidth = RightPaneWidth)
                    }
                    if (state.consoleOpen) {
                        // Resize grip: a thin 1dp splitter line with a slightly taller invisible grab strip and a
                        // vertical-resize cursor on hover (desktop). The console is bottom-anchored, so dragging
                        // this top edge UP grows it.
                        Box(
                            Modifier.fillMaxWidth().height(5.dp)
                                .verticalResizeCursor()
                                .draggable(
                                    orientation = Orientation.Vertical,
                                    state = rememberDraggableState { dy ->
                                        consoleHeight = (consoleH - with(density) { dy.toDp() })
                                            .coerceIn(minConsole, maxConsole)
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        }
                        GlassSurface(Modifier.fillMaxWidth().height(consoleH), GlassMaterial.Regular) {
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
                }
            }
            if (rightPanels.isNotEmpty()) {
                ActivityRail(
                    panels = rightPanels,
                    selectedId = state.selectedRightPanel,
                    onSelect = { state.toggleRightPanel(it) },
                )
            }
        }
        DestinationSheets(state, compact = false, onOpenModuleConfig, onToggleTheme, onOpenHub, onOpenIconManager, onCloseProject, fileActions)
        PaletteOverlay(state, onToggleTheme, onOpenHub, onOpenIconManager, onOpenDependencies)
    }
}

/**
 * Phone layout: a single editor pane with a bottom nav. The left sidebar is a **push drawer** hosting the
 * selected panel with a segmented switcher on top (the whole editor slides right to reveal it — edge swipe /
 * editor-at-scroll-start swipe / top-bar toggle); the bottom nav doubles as the collapsed build dock (swipe up
 * for the console); the RIGHT tool windows live in a swipe-in overlay ([RightToolOverlay]).
 */
@Composable
internal fun CompactLayout(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenIconManager: () -> Unit,
    indexStatus: IndexUiStatus,
    buildState: BuildState,
    onNewFile: (String, List<PackageSegment>) -> Unit,
    onNewFolder: (String, List<PackageSegment>) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewImageAsset: (TreeNode) -> Unit,
    onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit,
    onFileOp: (TreeNode, FileOpKind) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    // Hide the bottom nav while the soft keyboard is up, so the editor gets the full height and the user can
    // focus on the code being typed (the nav is one swipe/back away). The IME inset is read raw — directly,
    // not via a consuming modifier — so the app's `safeDrawing` padding doesn't zero it. Always 0 on desktop.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // The drawer's live open fraction, mirrored by the top bar's sidebar icon (its divider tracks the
    // drawer edge through a swipe). Float state written per frame; read deferred in the icon's draw.
    var navProgress by remember { mutableFloatStateOf(0f) }
    // One-shot swipe-affordance hint: the first build activity that happens with the dock collapsed peeks
    // the bar up and back so the drag is discoverable; persisted so it never repeats.
    var dockHint by remember { mutableStateOf(false) }
    LaunchedEffect(buildState.status) {
        if (isMobilePlatform && buildState.status != RunStatus.Idle && !state.consoleOpen &&
            state.backend.settings.preference(DOCK_HINT_PREF) != "true"
        ) dockHint = true
    }
    val leftPanels = buildLeftPanels(
        state, fileActions, indexStatus.building,
        onNewFile, onNewFolder, onNewResource, onNewImageAsset, onNewSource, onFileOp, onOpenDependencies, onOpenModuleConfig,
        closeDrawer = { state.selectedLeftPanel = null }, // a navigating action closes the drawer on phone
    )
    Box(Modifier.fillMaxSize()) {
        // The push drawer hosts the selected left panel; a segmented switcher on top flips between panels
        // (built-in + plugin). Opens by edge swipe, by a rightward swipe once the editor is at its horizontal
        // start (nested-scroll aware), or from the top-bar toggle.
        PushDrawer(
            open = state.leftOpen,
            onOpenChange = { open -> if (open) state.openLeftSidebar() else { state.selectedLeftPanel = null } },
            gesturesEnabled = isMobilePlatform,
            onProgress = { navProgress = it },
            drawerContent = {
                GlassSurface(Modifier.fillMaxSize(), GlassMaterial.Regular) {
                    Column(Modifier.fillMaxSize()) {
                        SegmentedPanelSwitcher(
                            panels = leftPanels,
                            selectedId = state.selectedLeftPanel,
                            onSelect = { state.selectLeftPanel(it) },
                        )
                        // Key on the stable id, not the panel object (rebuilt every recomposition) — otherwise
                        // the crossfade restarts each frame while the IME inset animates, which stutters.
                        val selectedId = state.selectedLeftPanel ?: leftPanels.firstOrNull()?.id
                        AnimatedContent(
                            targetState = selectedId,
                            transitionSpec = { fadeIn(tween(Motion.BASE)) togetherWith fadeOut(tween(Motion.FAST)) },
                            label = "drawerPanelSwitch",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { id ->
                            val panel = leftPanels.firstOrNull { it.id == id }
                            Box(Modifier.fillMaxSize()) { panel?.content?.invoke() }
                        }
                        // A native ad pinned to the foot of the left drawer, below the tool content (mirrors the
                        // desktop SidebarPane footer). Self-collapses when ads are inactive.
                        AdSlot(AdPlacement.SIDEBAR, Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    EditorCenter(
                        state, indexStatus, compact = true, Modifier.weight(1f).fillMaxWidth(),
                        navFraction = { navProgress },
                    )
                    // While the keyboard is up: a coding-symbol accessory bar sits directly above it. Off-keyboard,
                    // the dock's collapsed bar takes the slot instead.
                    if (keyboardOpen && state.active != null) {
                        EditorSymbolBar(
                            symbols = state.symbolKeys.ifEmpty { DEFAULT_SYMBOL_KEYS },
                            onSymbol = { sym -> state.active?.session?.commitText(sym) },
                            onAction = { id -> dispatchSymbolAction(state, id) },
                            showDiagnosticJump = state.active?.session?.diagnostics?.isNotEmpty() == true,
                            // No gear here — the Symbols & Macros editor lives in Settings ▸ Symbols & Macros.
                        )
                    }
                    // Reserve the dock's collapsed-bar slot so the editor column isn't hidden behind it.
                    if (!keyboardOpen) androidx.compose.foundation.layout.Spacer(Modifier.height(DockBarHeight))
                }
                // The bottom nav is the collapsed face of the build dock: swipe it up (or tap its build
                // chip / the top-bar console toggle) and it expands into the build console.
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
                    bar = {
                        BottomNav(
                            selected = state.bottomNavSelection(),
                            onSelect = { state.onBottomNav(it) },
                            showSource = leftPanels.any { it.id == LeftPanelId.SOURCE },
                        )
                    },
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

        DestinationSheets(state, compact = true, onOpenModuleConfig, onToggleTheme, onOpenHub, onOpenIconManager, onCloseProject, fileActions)
        PaletteOverlay(state, onToggleTheme, onOpenHub, onOpenIconManager, onOpenDependencies)
        // Right-edge tool-window drawer (the phone counterpart of the desktop right pane + rail). Self-gates on
        // there being a RIGHT tool window, so it lays down nothing when no plugin contributes one.
        RightToolOverlay(state)
    }
}
