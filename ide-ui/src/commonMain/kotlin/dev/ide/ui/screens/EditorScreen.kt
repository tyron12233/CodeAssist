package dev.ide.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.components.AddSourceRootDialog
import dev.ide.ui.components.AddSourceRootRequest
import dev.ide.ui.components.FileOpKind
import dev.ide.ui.components.FileOpRequest
import dev.ide.ui.components.FileOperationDialog
import dev.ide.ui.components.fileOpPath
import dev.ide.ui.components.IndexStatusDialog
import dev.ide.ui.components.NewEntryDialog
import dev.ide.ui.components.NewEntryKind
import dev.ide.ui.components.NewEntryRequest
import dev.ide.ui.components.NewSourceFileDialog
import dev.ide.ui.components.NewSourceLang
import dev.ide.ui.components.NewSourceRequest
import dev.ide.ui.components.NewXmlFileDialog
import dev.ide.ui.components.NewXmlTarget
import dev.ide.ui.components.xmlTargetOf
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.edscreen_project_root
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.platform.isMobilePlatform
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Width at/below which the UI reflows to the compact (phone) layout. */
internal val COMPACT_BREAKPOINT = 720.dp

/**
 * The editor screen, adaptive by window width: expanded (side rail · navigator · editor · console)
 * on wide windows, compact (single editor pane, the navigator as a left push drawer, and the bottom
 * nav doubling as the swipe-up build dock/console) when the window is narrowed past [COMPACT_BREAKPOINT].
 *
 * This top-level entry owns the screen-wide state that the layouts share: the file-creation / file-op
 * request flags (whose dialogs are hosted here so they overlay both layouts), and the system-back routing.
 * The two adaptive layouts live in `EditorLayouts.kt`; the shared editor column in `EditorCenter.kt`.
 */
@Composable
fun EditorScreen(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit = {},
    onOpenDependencies: (String?) -> Unit = {},
    onOpenModuleConfig: (String?) -> Unit = {},
    onCloseProject: () -> Unit = {},
    onOpenRun: () -> Unit = {},
    fileActions: FileActions = FileActions.None,
) {
    val indexStatus by state.backend.search.indexStatus.collectAsState()
    val buildState by state.backend.build.buildState.collectAsState()
    val scope = rememberCoroutineScope()
    // The project is open now — kick off any deferred template-dependency resolution (e.g. the Compose AAR
    // graph of a freshly-created project). Idempotent + a no-op for an opened existing project; progress
    // streams on depsState so the user can work elsewhere while it resolves.
    LaunchedEffect(state.backend) { state.backend.deps.startPendingDependencyResolution() }
    // A finished build (re)writes artifacts under `<module>/build/` — refresh the tree so the dimmed
    // "build outputs" node (and the All-Files `build/` subtree) pick up the new APK/AAB/jar.
    LaunchedEffect(buildState.status) {
        if (buildState.status == RunStatus.Succeeded || buildState.status == RunStatus.Failed) state.refreshTree()
    }
    var newEntry by remember { mutableStateOf<NewEntryRequest?>(null) }
    var newXmlTarget by remember { mutableStateOf<NewXmlTarget?>(null) }
    var newSource by remember { mutableStateOf<NewSourceRequest?>(null) }
    var fileOp by remember { mutableStateOf<FileOpRequest?>(null) }
    // New File / New Folder can target any directory; res/ folders additionally offer the templated XML flow.
    val rootPath = state.backend.project.rootPath
    val projectRootLabel = stringResource(Res.string.edscreen_project_root)
    fun dirLabel(dir: String): String =
        if (dir.startsWith(rootPath)) dir.removePrefix(rootPath).trim('/', '\\')
            .ifEmpty { projectRootLabel } else dir

    val onNewFile: (String, List<PackageSegment>) -> Unit =
        { dir, segs -> newEntry = NewEntryRequest(dir, NewEntryKind.File, dirLabel(dir), segs) }
    val onNewFolder: (String, List<PackageSegment>) -> Unit =
        { dir, segs -> newEntry = NewEntryRequest(dir, NewEntryKind.Folder, dirLabel(dir), segs) }
    val onNewResource: (TreeNode) -> Unit = { node -> xmlTargetOf(node)?.let { newXmlTarget = it } }
    val onNewSource: (String, NewSourceLang, List<PackageSegment>) -> Unit =
        { dir, lang, segs -> newSource = NewSourceRequest(dir, lang, dirLabel(dir), segs) }
    val onFileOp: (TreeNode, FileOpKind) -> Unit =
        { node, kind -> fileOp = FileOpRequest(node, kind) }

    // Back closes an open editor overlay (dialog, palette, or — on mobile — a navigator/console/search/more
    // sheet) before the app-level handler pops the screen (#997). Desktop has no system back, so this is inert
    // there; the mobile-only panes are gated on [isMobilePlatform] since on wide layouts they're docked panes.
    PlatformBackHandler(
        enabled = newEntry != null || newXmlTarget != null || newSource != null || fileOp != null || state.addSourceRootModule != null || state.indexDetailOpen || state.paletteOpen || state.sheetDest != null || state.searchOpen || state.openRightTool != null || (isMobilePlatform && (state.navOpen || state.consoleOpen)),
    ) {
        when {
            fileOp != null -> fileOp = null
            state.addSourceRootModule != null -> state.addSourceRootModule = null
            newSource != null -> newSource = null
            newEntry != null -> newEntry = null
            newXmlTarget != null -> newXmlTarget = null
            state.indexDetailOpen -> state.indexDetailOpen = false
            state.paletteOpen -> state.paletteOpen = false
            state.sheetDest != null -> state.sheetDest = null
            state.searchOpen -> state.searchOpen = false
            state.openRightTool != null -> state.openRightTool = null
            isMobilePlatform && state.consoleOpen -> state.consoleOpen = false
            isMobilePlatform && state.navOpen -> state.navOpen = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < COMPACT_BREAKPOINT) CompactLayout(
                state,
                onToggleTheme,
                onOpenHub,
                indexStatus,
                buildState,
                onNewFile,
                onNewFolder,
                onNewResource,
                onNewSource,
                onFileOp,
                onOpenDependencies,
                onOpenModuleConfig,
                onCloseProject,
                fileActions
            )
            else ExpandedLayout(
                state,
                onToggleTheme,
                onOpenHub,
                indexStatus,
                buildState,
                onNewFile,
                onNewFolder,
                onNewResource,
                onNewSource,
                onFileOp,
                onOpenDependencies,
                onOpenModuleConfig,
                onCloseProject,
                fileActions
            )
        }
        NewEntryDialog(
            request = newEntry,
            onDismiss = { newEntry = null },
            onCreate = { dir, name, kind ->
                when (kind) {
                    NewEntryKind.File -> state.createFileSmart(dir, name)
                    NewEntryKind.Folder -> state.createDirectory(dir, name)
                }
            },
        )
        NewXmlFileDialog(
            visible = newXmlTarget != null,
            target = newXmlTarget,
            onDismiss = { newXmlTarget = null },
            onCreate = { dir, fileName, content -> state.createFile(dir, fileName, content) },
            onCreateDir = { parent, dirName -> state.createDirectory(parent, dirName) },
        )
        NewSourceFileDialog(
            request = newSource,
            onDismiss = { newSource = null },
            onCreate = { dir, name, template -> state.createSourceFile(dir, name, template) },
        )
        AddSourceRootDialog(
            request = state.addSourceRootModule?.let {
                AddSourceRootRequest(
                    it, state.moduleSourceSets(it)
                )
            },
            onDismiss = { state.addSourceRootModule = null },
            onAdd = { module, set, dirName, role ->
                state.addSourceRoot(
                    module, set, dirName, role
                )
            },
        )
        FileOperationDialog(
            request = fileOp,
            rootPath = state.backend.project.rootPath,
            listDir = { state.backend.files.listDirectory(it) },
            onRename = { node, newName ->
                node.fileOpPath()?.let { p -> scope.launch { state.renamePath(p, newName) } }
            },
            onMove = { node, dest -> node.fileOpPath()?.let { state.movePath(it, dest) } },
            onCopy = { node, dest -> node.fileOpPath()?.let { state.copyPath(it, dest) } },
            onDelete = { node -> node.fileOpPath()?.let { state.deletePath(it) } },
            onDismiss = { fileOp = null },
        )
        // The index-status detail dialog, opened by tapping the top-bar index chip.
        IndexStatusDialog(
            visible = state.indexDetailOpen,
            status = indexStatus,
            onReindex = { state.backend.search.reindex() },
            onDismiss = { state.indexDetailOpen = false },
        )
        // While a console run is active but its terminal isn't on screen (the user backed out mid-run), a
        // tappable pill returns to it — the run keeps going in the background until then.
        RunningIndicator(
            state.backend,
            onClick = onOpenRun,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = if (isMobilePlatform) 88.dp else 24.dp),
        )
    }
}
