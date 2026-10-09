package dev.ide.ui.screens

import dev.ide.ui.components.ToolWindowFrame
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
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
import dev.ide.ui.components.AdSlot
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
 * (console) · right pane · right stripe.
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
    val leftPanels = buildLeftPanels(
        state, fileActions, indexStatus.building,
        onNewFile, onNewFolder, onNewResource, onNewImageAsset, onNewSource, onFileOp, onOpenDependencies, onOpenModuleConfig,
        closeDrawer = {}, // desktop panes are persistent — never auto-collapse
    )
    val rightPanels = pluginPanels(ToolWindowAnchor.RIGHT, state.backend, state.active?.path)
    val moreLabel = stringResource(Res.string.edchrome_more)
    val settingsLabel = stringResource(Res.string.edchrome_settings_and_tools)
    val buildConsoleLabel = stringResource(Res.string.buildc_build)
    val logsLabel = stringResource(Res.string.logs_title)
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
    // Floating panes overlay the editor, so only one side is open at a time; set by the centre column below.
    var floatPanes by remember { mutableStateOf(false) }
    // Whichever side opened last wins, however it was opened (stripe, top bar, command palette).
    LaunchedEffect(floatPanes, state.selectedLeftPanel) {
        if (floatPanes && state.leftOpen) state.selectedRightPanel = null
    }
    LaunchedEffect(floatPanes, state.selectedRightPanel) {
        if (floatPanes && state.selectedRightPanel != null) state.selectedLeftPanel = null
    }
    Box(Modifier.fillMaxSize()) {
        // The top bar spans the whole window (IntelliJ's main toolbar); the stripes, panes, editor and console
        // all sit below it, laid out here around the editor body EditorCenter hands back.
        EditorCenter(state, indexStatus, compact = false, Modifier.fillMaxSize(), onCloseProject) { editorBody ->
            Column(Modifier.fillMaxSize()) {
                // One hairline under the bar, across the stripes too, like IntelliJ's main toolbar border.
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    ActivityRail(
                        panels = leftPanels,
                        selectedId = state.selectedLeftPanel,
                        onSelect = { state.toggleLeftPanel(it) },
                        side = RailSide.Left,
                        footer = {
                            // The build console is a BOTTOM tool window (docked below the editor, see the centre column);
                            // IntelliJ-style its toggle lives at the lower-left of the stripe, lit while it's open.
                            // The bottom tool windows: the build console and the logs share the bottom pane, one at
                            // a time; picking the one on show hides the pane.
                            RailActionItem(CaIcons.terminal, buildConsoleLabel, active = state.consoleOpen && !state.logsOpen) {
                                if (state.consoleOpen && !state.logsOpen) {
                                    state.consoleOpen = false
                                } else {
                                    state.consoleOpen = true
                                    state.closeLogs()
                                }
                            }
                            RailActionItem(CaIcons.logs, logsLabel, active = state.logsOpen) {
                                if (state.logsOpen) {
                                    state.closeLogs()
                                    state.consoleOpen = false
                                } else {
                                    state.logsOpen = true
                                }
                            }
                            RailDivider()
                            stripeActions.forEach { a ->
                                RailActionItem(actionIcon(a.iconId), localizedUiActionText(a)) { a.perform(stripeHost) }
                            }
                            if (pluginMoreActions.isNotEmpty()) StripeMoreMenu(moreLabel, pluginMoreActions, stripeHost)
                            RailActionItem(CaIcons.gear, settingsLabel, onClick = onOpenHub)
                        },
                    )
                    // Centre column spanning the width between the two stripes: the editor (with the left/right tool panes)
                    // on top, and the build console docked along the BOTTOM (IntelliJ bottom tool window) rather than as a
                    // right-edge pane. The stripes stay full-height, so their footer buttons — including the console toggle
                    // — sit in the lower corners. The divider above the console drags to resize it.
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val density = LocalDensity.current
                        val minConsole = 140.dp
                        val maxConsole = (maxHeight - 200.dp).coerceAtLeast(minConsole)
                        var consoleHeight by remember { mutableStateOf(300.dp) }
                        val consoleH = consoleHeight.coerceIn(minConsole, maxConsole)
                        val floating = maxWidth < FloatingPanesBelow
                        SideEffect { floatPanes = floating }
                        val leftOpen = state.leftOpen
                        val rightOpen = state.selectedRightPanel != null && rightPanels.isNotEmpty()
                        // Docked: each pane keeps its dragged width but never squeezes the editor below MinEditorWidth.
                        // Floating: a pane may cover most of the editor, never all of it.
                        val rightMax: Dp
                        val leftMax: Dp
                        if (floating) {
                            rightMax = (maxWidth * 0.85f).coerceAtLeast(MinPaneWidth)
                            leftMax = rightMax
                        } else {
                            rightMax = (maxWidth - MinEditorWidth - if (leftOpen) MinPaneWidth else 0.dp).coerceAtLeast(MinPaneWidth)
                            val rightUsed = if (rightOpen) state.rightPaneWidth.dp.coerceIn(MinPaneWidth, rightMax) else 0.dp
                            leftMax = (maxWidth - MinEditorWidth - rightUsed).coerceAtLeast(MinPaneWidth)
                        }
                        val leftW = state.leftPaneWidth.dp.coerceIn(MinPaneWidth, leftMax)
                        val rightW = state.rightPaneWidth.dp.coerceIn(MinPaneWidth, rightMax)
                        // Drag deltas accumulate onto the stored width, first pulled into range so a width saved in a
                        // larger window starts moving at once instead of after the excess is dragged off.
                        val onResizeLeft: (Dp) -> Unit = { d ->
                            state.leftPaneWidth = state.leftPaneWidth.coerceIn(MinPaneWidth.value, leftMax.value) + d.value
                        }
                        val onResizeRight: (Dp) -> Unit = { d ->
                            state.rightPaneWidth = state.rightPaneWidth.coerceIn(MinPaneWidth.value, rightMax.value) + d.value
                        }
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                Row(Modifier.fillMaxSize()) {
                                    if (!floating) {
                                        SidebarPane(
                                            leftPanels, state.selectedLeftPanel, RailSide.Left, paneWidth = leftW,
                                            onResize = onResizeLeft, onResizeEnd = { state.savePaneWidths() },
                                            onHide = { state.selectedLeftPanel = null },
                                            active = state.activeToolWindow != null && state.activeToolWindow == state.selectedLeftPanel,
                                            alwaysShowActions = state.alwaysShowToolWindowActions,
                                            onActivate = { state.activeToolWindow = state.selectedLeftPanel },
                                        )
                                    }
                                    Box(Modifier.weight(1f).fillMaxHeight().onPress { state.activeToolWindow = null }) { editorBody() }
                                    // Right-edge tool-window pane. Fully plugin-derived: nothing lays down when no plugin
                                    // contributes a RIGHT tool window (the AI chat is one such plugin).
                                    if (!floating) {
                                        SidebarPane(
                                            rightPanels, state.selectedRightPanel, RailSide.Right, paneWidth = rightW,
                                            onResize = onResizeRight, onResizeEnd = { state.savePaneWidths() },
                                            onHide = { state.selectedRightPanel = null },
                                            active = state.activeToolWindow != null && state.activeToolWindow == state.selectedRightPanel,
                                            alwaysShowActions = state.alwaysShowToolWindowActions,
                                            onActivate = { state.activeToolWindow = state.selectedRightPanel },
                                        )
                                    }
                                }
                                if (floating) {
                                    // Tapping the dimmed editor closes the floating pane.
                                    val scrimAlpha by animateFloatAsState(
                                        if (leftOpen || rightOpen) 0.32f else 0f,
                                        tween(Motion.BASE),
                                        label = "paneScrim",
                                    )
                                    if (scrimAlpha > 0f) {
                                        Box(
                                            Modifier.fillMaxSize()
                                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                                                .clickable(remember { MutableInteractionSource() }, indication = null) {
                                                    state.selectedLeftPanel = null
                                                    state.selectedRightPanel = null
                                                },
                                        )
                                    }
                                    SidebarPane(
                                        leftPanels, state.selectedLeftPanel, RailSide.Left, Modifier.align(Alignment.CenterStart),
                                        paneWidth = leftW, floating = true,
                                        onResize = onResizeLeft, onResizeEnd = { state.savePaneWidths() },
                                        onHide = { state.selectedLeftPanel = null },
                                        active = state.activeToolWindow != null && state.activeToolWindow == state.selectedLeftPanel,
                                        alwaysShowActions = state.alwaysShowToolWindowActions,
                                        onActivate = { state.activeToolWindow = state.selectedLeftPanel },
                                    )
                                    SidebarPane(
                                        rightPanels, state.selectedRightPanel, RailSide.Right, Modifier.align(Alignment.CenterEnd),
                                        paneWidth = rightW, floating = true,
                                        onResize = onResizeRight, onResizeEnd = { state.savePaneWidths() },
                                        onHide = { state.selectedRightPanel = null },
                                        active = state.activeToolWindow != null && state.activeToolWindow == state.selectedRightPanel,
                                        alwaysShowActions = state.alwaysShowToolWindowActions,
                                        onActivate = { state.activeToolWindow = state.selectedRightPanel },
                                    )
                                }
                            }
                            // The bottom tool window slides up from, and back down into, the bottom edge, with
                            // the same motion as the side panes.
                            AnimatedVisibility(
                                visible = state.consoleOpen || state.logsOpen,
                                enter = expandVertically(tween(Motion.BASE, easing = Motion.quiet), expandFrom = Alignment.Bottom) +
                                    fadeIn(tween(Motion.BASE)),
                                exit = shrinkVertically(tween(Motion.BASE, easing = Motion.quiet), shrinkTowards = Alignment.Bottom) +
                                    fadeOut(tween(Motion.BASE / 2)),
                            ) {
                                Column {
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
                                    ToolWindowFrame(
                                        active = state.activeToolWindow == IdeUiState.CONSOLE_TOOL_WINDOW,
                                        alwaysShowActions = state.alwaysShowToolWindowActions,
                                        onActivate = { state.activeToolWindow = IdeUiState.CONSOLE_TOOL_WINDOW },
                                    ) {
                                    ToolWindowSurface(Modifier.fillMaxWidth().height(consoleH)) {
                                        if (state.logsOpen) {
                                            LogsScreen(
                                                backend = state.backend,
                                                fileActions = fileActions,
                                                modifier = Modifier.fillMaxSize(),
                                                initialSource = state.logsSource,
                                                onHide = { state.closeLogs(); state.consoleOpen = false },
                                            )
                                        } else {
                                        // Collected here (not threaded from the parent) so ~10/s app-log updates recompose only
                                        // the console subtree, not the whole editor layout.
                                        val appLog by state.backend.build.appLog.collectAsState()
                                        BuildConsole(
                                            buildState = buildState,
                                            indexStatus = indexStatus,
                                            onRun = { state.requestRun { state.backend.build.runBuild() } },
                                            canRun = state.backend.build.supported(),
                                            onStop = { state.backend.build.stopBuild() },
                                            onCollapse = { state.consoleOpen = false },
                                            modifier = Modifier.fillMaxSize(),
                                            wide = true,
                                            onOpenDiagnostic = { d -> d.file?.let { state.openAtLine(it, d.line, d.column) } },
                                            backend = state.backend,
                                            activeFilePath = state.active?.path,
                                            appLog = appLog,
                                        )
                                        }
                                    }
                                    }
                                }
                            }
                        }
                    }
                    // The right stripe shows whenever a plugin contributes a RIGHT tool window, unless the user hid it;
                    // the top bar then carries a toggle for the primary one, and Settings > Appearance brings the stripe back.
                    if (rightPanels.isNotEmpty() && state.rightStripeVisible) {
                        ActivityRail(
                            panels = rightPanels,
                            selectedId = state.selectedRightPanel,
                            onSelect = { state.toggleRightPanel(it) },
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
                        // desktop SidebarPane footer). Self-collapses when ads are inactive.
                        AdSlot(AdPlacement.SIDEBAR, Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
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
