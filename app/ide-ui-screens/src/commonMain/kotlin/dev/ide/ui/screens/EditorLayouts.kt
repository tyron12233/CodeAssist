package dev.ide.ui.screens

import dev.ide.ui.components.ToolWindowFrame
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.ui.ToolWindowIds
import dev.ide.ui.ToolWindowSide
import dev.ide.ui.components.BottomPane
import dev.ide.ui.components.LocalToolWindowDrag
import dev.ide.ui.components.ToolWindowDragGhost
import dev.ide.ui.components.ToolWindowDragState
import dev.ide.ui.components.ToolWindowDropTargets
import dev.ide.ui.components.ToolWindowPaneControls
import dev.ide.ui.platform.horizontalResizeCursor
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import dev.ide.ui.ext.UiActionHost
import dev.ide.ui.ext.UiActionRegistry
import dev.ide.ui.ext.UiHostAction
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.icons.actionIcon
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.Dp
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
import dev.ide.ui.components.FooterAdSlot
import dev.ide.ui.components.BuildConsole
import dev.ide.ui.components.BuildDock
import dev.ide.ui.components.DockBarHeight
import dev.ide.ui.components.FileNavigator
import dev.ide.ui.components.FileOpKind
import dev.ide.ui.components.fileOpPath
import dev.ide.ui.components.ToolWindowSurface
import dev.ide.ui.components.NewSourceLang
import dev.ide.ui.components.PanelContent
import dev.ide.ui.components.PushDrawer
import dev.ide.ui.components.MinPaneWidth
import dev.ide.ui.components.RailActionItem
import dev.ide.ui.components.RailDivider
import dev.ide.ui.components.RailSide
import dev.ide.ui.components.ToolWindowSwitcherHeader
import dev.ide.ui.components.rememberToolWindowHeaderSlots
import dev.ide.ui.components.SidebarPane
import dev.ide.ui.components.SidebarPanel
import dev.ide.ui.components.pluginPanels
import dev.ide.ui.components.RightToolOverlay
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.buildc_build
import dev.ide.ui.generated.resources.logs_title
import dev.ide.ui.generated.resources.run
import dev.ide.ui.generated.resources.edchrome_files
import dev.ide.ui.generated.resources.edchrome_more
import dev.ide.ui.generated.resources.sidebar_hide_tool_window_bar
import dev.ide.ui.generated.resources.edchrome_settings_and_tools
import dev.ide.ui.generated.resources.search
import dev.ide.ui.generated.resources.structure_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.hasSystemBack
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.platform.verticalResizeCursor
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** App-global preference marking the dock's one-shot swipe-up teaching bounce as already shown. */
private const val DOCK_HINT_PREF = "dock.swipeHint.seen"

/** Docked panel widths. */
/** Below this editor-area width the tool-window panes float over the editor instead of docking beside it
 *  (IntelliJ's "Undock" mode), so a phone in landscape or a narrow window keeps the editor full width. */
private val FloatingPanesBelow = 880.dp
/** The editor width docked panes must leave free; past it they stop growing. */
private val MinEditorWidth = 360.dp

/** The editor width docked panes leave free in a narrow centre column (a phone in landscape). */
private val MinEditorWidthNarrow = 240.dp
/** The bottom pane's splitter, its smallest height (a header and an input bar), and the editor lines it leaves
 *  when dragged as far as it goes. The maximize button takes the rest. */
private val BottomSplitterSize = 8.dp
private val MinBottomPaneHeight = 120.dp
private val MinEditorHeight = 64.dp
/** From this width the build console lays out with its tabs in its header, as below the editor. */
private val RoomyConsoleWidth = 480.dp

/**
 * Open a file tapped in the tree. An `.apk` the IDE built goes to the platform package installer (or reveal
 * if the host can't install, e.g. desktop); any other `.apk` and an `.aab` are revealed (see
 * [FileActions.isBuiltApk]); anything else opens in the editor via [open]. Keeps binary build artifacts out
 * of the text editor.
 */
internal fun openTreeFile(node: TreeNode, fileActions: FileActions, open: (String, String) -> Unit) {
    val path = node.filePath ?: return
    when {
        path.endsWith(".apk", ignoreCase = true) ->
            if (fileActions.canInstallApk && FileActions.isBuiltApk(path)) fileActions.installApk(path)
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
                findInFilesEpoch = state.findInFilesEpoch,
                findInFilesSeed = { state.findInFilesSeed },
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
 * Wide-window layout: the top bar across the full width, and below it left stripe · left pane · editor ·
 * right pane · right stripe, with the bottom pane under the editor and the side panes.
 *
 * Every tool window docks on a side (see [IdeUiState.sideOf]) and each side shows one at a time, IntelliJ
 * style: the left stripe lists the left windows at the top and the bottom ones at its foot, the right stripe
 * the right ones. A window moves to another side by dragging its stripe button (onto a stripe, or onto the
 * targets shown over the editor during the drag) or from "Move to" in its header. Destination sheets and the
 * command palette overlay on top.
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
    /** Opens the full-screen Run terminal, where the Appearance setting puts runs instead of a pane. */
    onOpenRun: () -> Unit = {},
) {
    val leftPanels = buildLeftPanels(
        state, fileActions, indexStatus.building,
        onNewFile, onNewFolder, onNewResource, onNewImageAsset, onNewSource, onFileOp, onOpenDependencies, onOpenModuleConfig,
        closeDrawer = {}, // desktop panes are persistent — never auto-collapse
    )
    val rightPanels = pluginPanels(ToolWindowAnchor.RIGHT, state.backend, state.active?.path)
    val moreLabel = stringResource(Res.string.edchrome_more)
    val settingsLabel = stringResource(Res.string.edchrome_settings_and_tools)
    // Only whether a console run exists, not its transcript: the transcript changes many times a second and
    // would recompose the whole layout.
    val hasRun by remember(state.backend) { state.backend.build.runConsole.map { it != null }.distinctUntilChanged() }
        .collectAsState(initial = false)
    // A run that starts while this layout is on screen opens in its pane rather than full screen, unless the
    // Appearance setting asks for full screen (the app navigates there itself). The flow's current value is
    // skipped, so coming back to the editor doesn't re-open an old run.
    LaunchedEffect(state.backend) {
        state.backend.build.runConsole.map { it?.id }.distinctUntilChanged().drop(1).collect { id ->
            if (id != null && !state.runFullScreen) state.showRunOutput()
        }
    }
    val windows = leftPanels + rightPanels + consoleWindows(state, buildState, indexStatus, fileActions, hasRun || state.runOpen)
    val leftWindows = windows.filter { state.sideOf(it.id) == ToolWindowSide.Left }
    val rightWindows = windows.filter { state.sideOf(it.id) == ToolWindowSide.Right }
    val bottomWindows = windows.filter { state.sideOf(it.id) == ToolWindowSide.Bottom }
    // A maximized pane goes back to its size once its side collapses, so it never reopens over the editor.
    val maximizedShown = state.maximizedSide?.let { state.shownOn(it) }
    LaunchedEffect(maximizedShown) { if (maximizedShown == null) state.maximizedSide = null }
    // The logs viewer forgets its source filter when it closes; Run opens full screen when the setting says so.
    val toggle: (String) -> Unit = { id ->
        when {
            id == ToolWindowIds.RUN && state.runFullScreen -> onOpenRun()
            id == ToolWindowIds.LOGS && state.logsOpen -> state.closeLogs()
            else -> state.toggleToolWindow(id)
        }
    }
    val hideSide: (ToolWindowSide) -> Unit = { side ->
        if (state.shownOn(side) == ToolWindowIds.LOGS) state.closeLogs() else state.hideSide(side)
    }
    val drag = remember(state) { ToolWindowDragState { id, side -> state.moveToolWindow(id, side) } }
    val hideBarLabel = stringResource(Res.string.sidebar_hide_tool_window_bar)
    // The screens a project is configured from (Modules, Icon Manager, Logs) sit in the stripe's lower group,
    // resolved from the registry like the phone's More sheet that lists them there.
    val stripeHost = editorActionHost(
        state, onToggleTheme, onOpenHub, onOpenIconManager, onOpenDependencies, onOpenModuleConfig, onCloseProject,
        onDone = {},
    )
    UiPluginHost.ensureLoaded()
    val stripeActions = UiActionRegistry.forPlace(UiActionPlaces.TOOL_STRIPE, stripeHost)
    // What a UI plugin put in the More menu. There is no More sheet here, so those rows open from a stripe menu.
    // Settings & Tools is left out: it is the stripe's own gear.
    val pluginMoreActions = UiActionRegistry.forPlace(UiActionPlaces.MORE_MENU, stripeHost)
        .filter { UiActionPlaces.TOOL_STRIPE !in it.places && it.id != "ui.hub" }
    CompositionLocalProvider(LocalToolWindowDrag provides drag) {
    Box(Modifier.fillMaxSize()) {
        // The top bar spans the whole window (IntelliJ's main toolbar); the stripes, panes and editor all sit
        // below it, laid out here around the editor body EditorCenter hands back.
        EditorCenter(state, indexStatus, compact = false, Modifier.fillMaxSize(), onCloseProject) { editorBody ->
            Column(Modifier.fillMaxSize()) {
                // One hairline under the bar, across the stripes too, like IntelliJ's main toolbar border. Zen mode
                // has no bar and no stripes.
                if (!state.zenMode) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (!state.zenMode) ActivityRail(
                        panels = leftWindows,
                        selectedId = state.shownOn(ToolWindowSide.Left),
                        onSelect = toggle,
                        side = RailSide.Left,
                        bottomPanels = bottomWindows,
                        bottomSelectedId = state.shownOn(ToolWindowSide.Bottom),
                        footer = {
                            RailDivider()
                            stripeActions.forEach { a ->
                                RailActionItem(actionIcon(a.iconId), localizedUiActionText(a)) { a.perform(stripeHost) }
                            }
                            if (pluginMoreActions.isNotEmpty()) StripeMoreMenu(moreLabel, pluginMoreActions, stripeHost)
                            RailActionItem(CaIcons.gear, settingsLabel, onClick = onOpenHub)
                        },
                    )
                    // Centre column spanning the width between the two stripes: the side panes and the editor, and
                    // the bottom pane under them. The stripes stay full-height, so the bottom group and the footer
                    // sit in the lower corner.
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        ToolWindowPanes(state, windows, editorBody, hideSide, maxWidth, maxHeight)
                        ToolWindowDropTargets()
                    }
                    // The right stripe shows while a window docks on the right, unless the user hid it (Settings >
                    // Appearance brings it back). During a drag it shows anyway, as a place to drop on.
                    if (!state.zenMode && ((rightWindows.isNotEmpty() && state.rightStripeVisible) || drag.dragging != null)) {
                        ActivityRail(
                            panels = rightWindows,
                            selectedId = state.shownOn(ToolWindowSide.Right),
                            onSelect = toggle,
                            side = RailSide.Right,
                            menu = { dismiss ->
                                DropdownMenuItem(
                                    text = { Text(hideBarLabel) },
                                    onClick = { dismiss(); state.setRightStripeShown(false) },
                                )
                            },
                        )
                    }
                }
            }
        }
        DestinationSheets(state, onOpenModuleConfig, onOpenDependencies, onToggleTheme, onOpenHub, onOpenIconManager, onCloseProject, fileActions, logsAsSheet = false)
        PaletteOverlay(state, onToggleTheme, onOpenHub, onOpenIconManager, onOpenDependencies, onOpenModuleConfig, onCloseProject)
        ToolWindowDragGhost()
    }
    }
}

/**
 * The three panes around the editor in the wide layout's centre column ([width] x [height]): left and right
 * beside the editor, bottom under them, each showing the window docked on that side.
 *
 * Below [FloatingPanesBelow] the side panes float over the editor (IntelliJ's "Undock" mode) so a phone in
 * landscape keeps the editor full width; one side at a time then. The console windows are the exception: they
 * stay docked beside the editor, since being watched while the code is edited is what they are for.
 */
@Composable
private fun ToolWindowPanes(
    state: IdeUiState,
    windows: List<SidebarPanel>,
    editorBody: @Composable () -> Unit,
    hideSide: (ToolWindowSide) -> Unit,
    width: Dp,
    height: Dp,
) {
    val leftId = state.shownOn(ToolWindowSide.Left)
    val rightId = state.shownOn(ToolWindowSide.Right)
    val bottomId = state.shownOn(ToolWindowSide.Bottom)
    val maximized = state.maximizedSide?.takeIf { state.shownOn(it) != null }
    val narrow = width < FloatingPanesBelow
    // Whether a side floats follows the window on it, and the last one while it animates out.
    var lastLeft by remember { mutableStateOf(leftId) }
    var lastRight by remember { mutableStateOf(rightId) }
    SideEffect {
        if (leftId != null) lastLeft = leftId
        if (rightId != null) lastRight = rightId
    }
    fun floats(id: String?, side: ToolWindowSide) = narrow && maximized != side && id !in ToolWindowIds.BOTTOM
    val leftFloats = floats(leftId ?: lastLeft, ToolWindowSide.Left)
    val rightFloats = floats(rightId ?: lastRight, ToolWindowSide.Right)
    // Floating panes overlay the editor, so only one is open at a time: whichever opened last wins, however it
    // was opened (stripe, top bar, command palette).
    LaunchedEffect(leftId) { if (leftId != null && leftFloats && rightFloats && rightId != null) hideSide(ToolWindowSide.Right) }
    LaunchedEffect(rightId) { if (rightId != null && leftFloats && rightFloats && leftId != null) hideSide(ToolWindowSide.Left) }

    val editorMin = if (narrow) MinEditorWidthNarrow else MinEditorWidth
    val leftDocked = leftId != null && !leftFloats
    val rightDocked = rightId != null && !rightFloats
    // Docked: each pane keeps its dragged width but never squeezes the editor below editorMin. Floating: a pane
    // may cover most of the editor, never all of it.
    val rightMax = if (rightFloats) (width * 0.85f).coerceAtLeast(MinPaneWidth)
        else (width - editorMin - if (leftDocked) MinPaneWidth else 0.dp).coerceAtLeast(MinPaneWidth)
    val rightUsed = if (rightDocked) state.rightPaneWidth.dp.coerceIn(MinPaneWidth, rightMax) else 0.dp
    val leftMax = if (leftFloats) (width * 0.85f).coerceAtLeast(MinPaneWidth)
        else (width - editorMin - rightUsed).coerceAtLeast(MinPaneWidth)
    val leftW = if (maximized == ToolWindowSide.Left) width else state.leftPaneWidth.dp.coerceIn(MinPaneWidth, leftMax)
    val rightW = if (maximized == ToolWindowSide.Right) width else state.rightPaneWidth.dp.coerceIn(MinPaneWidth, rightMax)
    // Below the editor: the pane leaves the editor a few lines (a phone in landscape has ~300dp here).
    val bottomMax = (height - BottomSplitterSize - MinEditorHeight).coerceAtLeast(MinBottomPaneHeight)
    val bottomH = if (maximized == ToolWindowSide.Bottom) height - BottomSplitterSize
        else state.bottomPaneHeight.dp.coerceIn(MinBottomPaneHeight, bottomMax)
    // Drag deltas accumulate onto the stored size, first pulled into range so a size saved in a larger window
    // starts moving at once instead of after the excess is dragged off.
    val onResizeLeft: ((Dp) -> Unit)? = if (maximized != null) null else ({ d ->
        state.leftPaneWidth = state.leftPaneWidth.coerceIn(MinPaneWidth.value, leftMax.value) + d.value
    })
    val onResizeRight: ((Dp) -> Unit)? = if (maximized != null) null else ({ d ->
        state.rightPaneWidth = state.rightPaneWidth.coerceIn(MinPaneWidth.value, rightMax.value) + d.value
    })
    val onResizeBottom: ((Dp) -> Unit)? = if (maximized != null) null else ({ d ->
        state.bottomPaneHeight = state.bottomPaneHeight.coerceIn(MinBottomPaneHeight.value, bottomMax.value) + d.value
    })
    fun controls(side: ToolWindowSide) = ToolWindowPaneControls(
        maximized = maximized == side,
        onToggleMaximized = { state.maximizedSide = if (maximized == side) null else side },
        side = side,
        onMove = { to -> state.shownOn(side)?.let { state.moveToolWindow(it, to) } },
    )
    @Composable
    fun sidePane(side: ToolWindowSide, floating: Boolean, modifier: Modifier = Modifier) {
        val id = state.shownOn(side)
        val rail = if (side == ToolWindowSide.Left) RailSide.Left else RailSide.Right
        SidebarPane(
            windows, id, rail, modifier,
            paneWidth = if (side == ToolWindowSide.Left) leftW else rightW,
            floating = floating,
            onResize = if (side == ToolWindowSide.Left) onResizeLeft else onResizeRight,
            onResizeEnd = { state.savePaneWidths() },
            onHide = { hideSide(side) },
            active = id != null && state.activeToolWindow == id,
            alwaysShowActions = state.alwaysShowToolWindowActions,
            onActivate = { state.activeToolWindow = state.shownOn(side) },
            // A floating pane is a narrow window's quick look over the editor: its header keeps to the window's
            // own controls (its stripe button still drags it to another side).
            controls = if (floating) null else controls(side),
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (!leftFloats && (maximized == null || maximized == ToolWindowSide.Left)) sidePane(ToolWindowSide.Left, floating = false)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize().onPress { state.activeToolWindow = null }) { editorBody() }
                if (leftFloats || rightFloats) {
                    // Tapping the dimmed editor closes the floating pane.
                    val floatingOpen = (leftFloats && leftId != null) || (rightFloats && rightId != null)
                    val scrimAlpha by animateFloatAsState(if (floatingOpen) 0.32f else 0f, tween(Motion.BASE), label = "paneScrim")
                    if (scrimAlpha > 0f) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                                .clickable(remember { MutableInteractionSource() }, indication = null) {
                                    if (leftFloats) hideSide(ToolWindowSide.Left)
                                    if (rightFloats) hideSide(ToolWindowSide.Right)
                                },
                        )
                    }
                    if (leftFloats) sidePane(ToolWindowSide.Left, floating = true, Modifier.align(Alignment.CenterStart))
                    if (rightFloats) sidePane(ToolWindowSide.Right, floating = true, Modifier.align(Alignment.CenterEnd))
                }
            }
            if (!rightFloats && (maximized == null || maximized == ToolWindowSide.Right)) sidePane(ToolWindowSide.Right, floating = false)
        }
        if (maximized == null || maximized == ToolWindowSide.Bottom) {
            BottomPane(
                windows, bottomId, bottomH,
                onResize = onResizeBottom,
                onResizeEnd = { state.savePaneWidths() },
                onHide = { hideSide(ToolWindowSide.Bottom) },
                active = bottomId != null && state.activeToolWindow == bottomId,
                alwaysShowActions = state.alwaysShowToolWindowActions,
                onActivate = { state.activeToolWindow = state.shownOn(ToolWindowSide.Bottom) },
                controls = controls(ToolWindowSide.Bottom),
            )
        }
    }
}

/**
 * The console tool windows as dockable panels: the build console, the Run terminal (once a run exists, given
 * as [withRun]) and the logs. Each draws its own header, with its hide button and the pane controls, so a pane
 * draws none above them. The build console takes its phone layout in a pane narrower than [RoomyConsoleWidth].
 */
@Composable
private fun consoleWindows(
    state: IdeUiState,
    buildState: BuildState,
    indexStatus: IndexUiStatus,
    fileActions: FileActions,
    withRun: Boolean,
): List<SidebarPanel> {
    val buildLabel = stringResource(Res.string.buildc_build)
    val runLabel = stringResource(Res.string.run)
    val logsLabel = stringResource(Res.string.logs_title)
    return buildList {
        add(SidebarPanel(ToolWindowIds.BUILD, buildLabel, CaIcons.terminal, order = 10, ownHeader = true) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val roomy = maxWidth >= RoomyConsoleWidth
                // Collected here (not threaded from the parent) so ~10/s app-log updates recompose only the
                // console, not the whole editor layout.
                val appLog by state.backend.build.appLog.collectAsState()
                BuildConsole(
                    buildState = buildState,
                    indexStatus = indexStatus,
                    onRun = { state.requestRun { state.backend.build.runBuild() } },
                    canRun = state.backend.build.supported(),
                    onStop = { state.backend.build.stopBuild() },
                    onCollapse = { state.consoleOpen = false },
                    modifier = if (roomy) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(10.dp),
                    wide = roomy,
                    onOpenDiagnostic = { d -> d.file?.let { state.openAtLine(it, d.line, d.column) } },
                    backend = state.backend,
                    activeFilePath = state.active?.path,
                    appLog = appLog,
                )
            }
        })
        if (withRun) {
            add(SidebarPanel(ToolWindowIds.RUN, runLabel, CaIcons.play, order = 20, ownHeader = true) {
                RunScreen(
                    backend = state.backend,
                    onBack = {},
                    onOpenDiagnostic = { d -> d.file?.let { state.openAtLine(it, d.line, d.column) } },
                    modifier = Modifier.fillMaxSize(),
                    onHide = { state.runOpen = false },
                    onOpenSource = { path, offset -> state.openAt(path, offset) },
                )
            })
        }
        add(SidebarPanel(ToolWindowIds.LOGS, logsLabel, CaIcons.logs, order = 30, ownHeader = true) {
            LogsScreen(
                backend = state.backend,
                fileActions = fileActions,
                modifier = Modifier.fillMaxSize(),
                initialSource = state.logsSource,
                onHide = { state.closeLogs() },
            )
        })
    }
}

/** The stripe's ⋯ button: a menu of the More-menu rows a UI plugin contributed, for the wide layout. */
@Composable
private fun StripeMoreMenu(label: String, actions: List<UiHostAction>, host: UiActionHost) {
    var open by remember { mutableStateOf(false) }
    Box {
        RailActionItem(CaIcons.ellipsis, label, active = open) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(localizedUiActionText(a)) },
                    leadingIcon = { Icon(actionIcon(a.iconId), null, Modifier.size(18.dp)) },
                    onClick = { open = false; a.perform(host) },
                )
            }
        }
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
    // The panels' saveable state (their scroll positions above all), held out here where it survives the
    // drawer: a closed drawer composes no panel at all, so this is what reopens one where it was left.
    val panelState = rememberSaveableStateHolder()
    val drawerHeaderSlots = rememberToolWindowHeaderSlots()
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
                ToolWindowSurface(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                    val drawerHeight = maxHeight
                    Column(Modifier.fillMaxSize()) {
                        ToolWindowSwitcherHeader(
                            panels = leftPanels,
                            selectedId = state.selectedLeftPanel,
                            onSelect = { state.selectLeftPanel(it) },
                            slots = drawerHeaderSlots,
                            onHide = { state.selectedLeftPanel = null },
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
                            PanelContent(
                                leftPanels.firstOrNull { it.id == id }, panelState, Modifier.fillMaxSize(),
                                headerSlot = id?.let(drawerHeaderSlots::of),
                            )
                        }
                        // A native ad pinned to the foot of the left drawer, below the tool content (mirrors the
                        // desktop SidebarPane footer). Self-collapses when ads are inactive, or the drawer is short
                        // (a small phone in landscape).
                        FooterAdSlot(AdPlacement.SIDEBAR, drawerHeight, Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
                    }
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    EditorCenter(
                        state, indexStatus, compact = true, Modifier.weight(1f).fillMaxWidth(), onCloseProject,
                        navFraction = { navProgress },
                    )
                    // While the keyboard is up: a coding-symbol accessory bar sits directly above it. Off-keyboard,
                    // the dock's collapsed bar takes the slot instead.
                    if (keyboardOpen && state.active != null) {
                        // Derived: the diagnostics list is replaced on every edit, and this scope should
                        // recompose only when the jump key appears or goes away.
                        val activeSession = state.active?.session
                        val hasDiagnostics by remember(activeSession) {
                            derivedStateOf { activeSession?.diagnostics?.isNotEmpty() == true }
                        }
                        EditorSymbolBar(
                            symbols = state.symbolKeys.ifEmpty { DEFAULT_SYMBOL_KEYS },
                            onSymbol = { sym -> state.active?.session?.commitText(sym) },
                            onAction = { id -> dispatchSymbolAction(state, id) },
                            showDiagnosticJump = hasDiagnostics,
                            // No gear here — the Symbols & Macros editor lives in Settings ▸ Symbols & Macros.
                            // The dismiss key only where the platform offers no way out of the keyboard
                            // itself; Android's back already does it. See [hasSystemBack].
                            onHideKeyboard = if (hasSystemBack) null else ({ state.active?.session?.ime?.hide() }),
                        )
                    }
                    // Reserve the dock's collapsed-bar slot so the editor column isn't hidden behind it. Zen mode hides
                    // the bar, unless the console is pulled up.
                    if (!keyboardOpen && !state.zenMode) androidx.compose.foundation.layout.Spacer(Modifier.height(DockBarHeight))
                }
                // The bottom nav is the collapsed face of the build dock: swipe it up (or tap its build
                // chip / the top-bar console toggle) and it expands into the build console.
                BuildDock(
                    open = state.consoleOpen,
                    onOpenChange = { state.consoleOpen = it },
                    buildState = buildState,
                    hidden = (keyboardOpen || state.zenMode) && !state.consoleOpen,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    hint = dockHint,
                    onHintShown = {
                        dockHint = false
                        state.backend.settings.setPreference(DOCK_HINT_PREF, "true")
                    },
                    bar = {
                        // The right-hand tool windows open from here (and the top bar's ⋯ while typing hides this
                        // bar): the first one is the tab, the overlay's switcher reaches any others.
                        val rightPrimary = ToolWindowRegistry.forAnchor(ToolWindowAnchor.RIGHT).firstOrNull()
                        BottomNav(
                            selected = state.bottomNavSelection(),
                            onSelect = { state.onBottomNav(it) },
                            showSource = leftPanels.any { it.id == LeftPanelId.SOURCE },
                            rightTool = rightPrimary?.let { tw ->
                                RightToolNavItem(actionIcon(tw.iconId), tw.title, open = state.selectedRightPanel != null) {
                                    if (state.selectedRightPanel != null) state.selectedRightPanel = null
                                    else state.selectRightPanel(tw.id)
                                }
                            },
                        )
                    },
                ) {
                    val appLog by state.backend.build.appLog.collectAsState()
                    BuildConsole(
                        buildState = buildState,
                        indexStatus = indexStatus,
                        onRun = { state.requestRun { state.backend.build.runBuild() } },
                                canRun = state.backend.build.supported(),
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

        DestinationSheets(state, onOpenModuleConfig, onOpenDependencies, onToggleTheme, onOpenHub, onOpenIconManager, onCloseProject, fileActions, logsAsSheet = true)
        PaletteOverlay(state, onToggleTheme, onOpenHub, onOpenIconManager, onOpenDependencies, onOpenModuleConfig, onCloseProject)
        // Right-edge tool-window drawer (the phone counterpart of the desktop right pane + rail). Self-gates on
        // there being a RIGHT tool window, so it lays down nothing when no plugin contributes one.
        RightToolOverlay(state)
    }
}

/** Calls [onPress] on every press inside, on the Initial pass and without consuming it, so the content below
 *  still gets the event. The editor uses it to stop a tool window from being the active one. */
private fun Modifier.onPress(onPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) onPress()
        }
    }
}
