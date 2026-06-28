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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.actions.dispatchAction
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionPlaces
import kotlinx.coroutines.launch
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.BuildConsole
import dev.ide.ui.components.FileNavigator
import dev.ide.ui.components.FileOpKind
import dev.ide.ui.components.fileOpPath
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.NewSourceLang
import dev.ide.ui.components.SideRail
import dev.ide.ui.theme.Ca

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
    onOpenSettings: () -> Unit,
    indexStatus: IndexUiStatus,
    buildState: BuildState,
    onNewFile: (String, List<PackageSegment>) -> Unit,
    onNewFolder: (String, List<PackageSegment>) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit,
    onFileOp: (TreeNode, FileOpKind) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onOpenSdkManager: () -> Unit,
    onOpenKeystoreManager: () -> Unit,
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
                onSettings = onOpenSettings,
                onOpenSdkManager = onOpenSdkManager,
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
                        mode = state.treeMode,
                        onModeChange = { state.selectTreeMode(it) },
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
                    BuildConsole(
                        buildState = buildState,
                        indexStatus = indexStatus,
                        onRun = { state.backend.build.runBuild() },
                        onStop = { state.backend.build.stopBuild() },
                        onCollapse = { state.consoleOpen = false },
                        modifier = Modifier.fillMaxSize().padding(14.dp),
                        onOpenDiagnostic = { d -> d.file?.let { state.openAtLine(it, d.line, d.column) } },
                        backend = state.backend,
                        activeFilePath = state.active?.path,
                    )
                }
            }
        }
        DestinationSheets(state, compact = false, onOpenModuleConfig, onToggleTheme, onOpenSettings, onOpenSdkManager, onOpenKeystoreManager, onCloseProject, fileActions)
        PaletteOverlay(state, onToggleTheme, onOpenSettings, onOpenDependencies, onOpenSdkManager, onOpenKeystoreManager)
    }
}

/**
 * Phone layout: a single editor pane with a bottom nav, and the navigator / console / destinations as
 * bottom sheets rather than docked panes.
 */
@Composable
internal fun CompactLayout(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    indexStatus: IndexUiStatus,
    buildState: BuildState,
    onNewFile: (String, List<PackageSegment>) -> Unit,
    onNewFolder: (String, List<PackageSegment>) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit,
    onFileOp: (TreeNode, FileOpKind) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onOpenSdkManager: () -> Unit,
    onOpenKeystoreManager: () -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    val project = state.backend.project
    val fileCtxScope = rememberCoroutineScope()
    // Hide the bottom nav while the soft keyboard is up, so the editor gets the full height and the user can
    // focus on the code being typed (the nav is one swipe/back away). The IME inset is read raw — directly,
    // not via a consuming modifier — so the app's `safeDrawing` padding doesn't zero it. Always 0 on desktop.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EditorCenter(state, indexStatus, compact = true, Modifier.weight(1f).fillMaxWidth())
            // While the keyboard is up: a coding-symbol accessory bar sits directly above it (the root's
            // safeDrawing padding lifts this Column above the IME). It inserts into the active editor; off-
            // keyboard, the bottom nav takes the slot instead.
            // No enter/exit animation for now — it read as clunky; the bar just appears with the keyboard.
            if (keyboardOpen && state.active != null) {
                EditorSymbolBar(
                    onTab = { state.active?.session?.indent() },
                    onSymbol = { sym -> state.active?.session?.commitText(sym) },
                )
            }
            if (!keyboardOpen) {
                BottomNav(
                    selected = state.rail,
                    onSelect = state::selectRail,
                )
            }
        }

        // File navigator as a bottom sheet — the compact reflow (on phone the navigator is a
        // sheet, not a side pane). Slides up over a fading scrim; tapping a file opens it and dismisses.
        BottomSheet(
            visible = state.navOpen,
            onDismiss = { state.navOpen = false },
            heightFraction = 0.7f,
        ) {
            FileNavigator(
                root = state.tree,
                moduleCount = project.moduleCount,
                activePath = state.active?.path,
                onOpen = { node -> openTreeFile(node, fileActions) { p, n -> state.open(p, n); state.navOpen = false } },
                modifier = Modifier.fillMaxWidth().weight(1f),
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
                mode = state.treeMode,
                onModeChange = { state.selectTreeMode(it) },
            )
        }
        // Build console as a bottom sheet; a taller detent when a problem is present (design: 0.6 / 0.72).
        BottomSheet(
            visible = state.consoleOpen,
            onDismiss = { state.consoleOpen = false },
            heightFraction = if (buildState.status == RunStatus.Failed) 0.72f else 0.6f,
        ) {
            BuildConsole(
                buildState = buildState,
                indexStatus = indexStatus,
                onRun = { state.backend.build.runBuild() },
                onStop = { state.backend.build.stopBuild() },
                onCollapse = { state.consoleOpen = false },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(14.dp),
                // On phone the console is a sheet over the editor; jump to the file and dismiss it.
                onOpenDiagnostic = { d -> d.file?.let { state.openAtLine(it, d.line, d.column); state.consoleOpen = false } },
                backend = state.backend,
                activeFilePath = state.active?.path,
            )
        }
        DestinationSheets(state, compact = true, onOpenModuleConfig, onToggleTheme, onOpenSettings, onOpenSdkManager, onOpenKeystoreManager, onCloseProject, fileActions)
        PaletteOverlay(state, onToggleTheme, onOpenSettings, onOpenDependencies, onOpenSdkManager, onOpenKeystoreManager)
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Ca.colors.separator))
}
