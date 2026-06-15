package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.UiAndroidSourcesInfo
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.RailDestination
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.Breadcrumb
import dev.ide.ui.components.BuildConsole
import dev.ide.ui.components.ComingSoon
import dev.ide.ui.components.CommandPalette
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.components.EditorTopBar
import dev.ide.ui.components.FileNavigator
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.editor.BlockEditor
import dev.ide.ui.components.NewFileDialog
import dev.ide.ui.components.NewXmlFileDialog
import dev.ide.ui.components.NewXmlTarget
import dev.ide.ui.components.xmlTargetOf
import dev.ide.ui.components.NewFileTarget
import dev.ide.ui.components.SideRail
import dev.ide.ui.components.TabsStrip
import dev.ide.ui.components.newFileTargetOf
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay

/** Width at/below which the UI reflows to the compact (phone) layout. */
private val COMPACT_BREAKPOINT = 720.dp

/**
 * The editor screen, adaptive by window width: expanded (side rail · navigator · editor · console)
 * on wide windows, compact (single editor pane, bottom nav, navigator/console as sheets) when the
 * window is narrowed past [COMPACT_BREAKPOINT].
 */
@Composable
fun EditorScreen(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenDependencies: (String?) -> Unit = {},
    onOpenModuleConfig: (String?) -> Unit = {},
    onOpenSdkManager: () -> Unit = {},
    onCloseProject: () -> Unit = {},
    fileActions: FileActions = FileActions.None,
) {
    val indexStatus by state.backend.indexStatus.collectAsState()
    val buildState by state.backend.buildState.collectAsState()
    var newFileTarget by remember { mutableStateOf<NewFileTarget?>(null) }
    var newXmlTarget by remember { mutableStateOf<NewXmlTarget?>(null) }
    // A res/ folder node opens the XML resource dialog; a Java source/package node opens New-Class.
    val onNewFile: (TreeNode) -> Unit = { node ->
        val xml = xmlTargetOf(node)
        if (xml != null) newXmlTarget = xml else newFileTargetOf(node)?.let { newFileTarget = it }
    }
    val onNewFileRoot: () -> Unit = { state.defaultNewFileTarget()?.let { newFileTarget = it } }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < COMPACT_BREAKPOINT) CompactLayout(state, onToggleTheme, indexStatus, buildState, onNewFile, onNewFileRoot, onOpenDependencies, onOpenModuleConfig, onOpenSdkManager, onCloseProject, fileActions)
            else ExpandedLayout(state, onToggleTheme, indexStatus, buildState, onNewFile, onNewFileRoot, onOpenDependencies, onOpenModuleConfig, onOpenSdkManager, onCloseProject, fileActions)
        }
        NewFileDialog(
            visible = newFileTarget != null,
            target = newFileTarget,
            onDismiss = { newFileTarget = null },
            onCreate = { dir, fileName, content -> state.createFile(dir, fileName, content) },
        )
        NewXmlFileDialog(
            visible = newXmlTarget != null,
            target = newXmlTarget,
            onDismiss = { newXmlTarget = null },
            onCreate = { dir, fileName, content -> state.createFile(dir, fileName, content) },
            onCreateDir = { parent, dirName -> state.createDirectory(parent, dirName) },
        )
    }
}

@Composable
private fun ExpandedLayout(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    indexStatus: IndexUiStatus,
    buildState: BuildState,
    onNewFile: (TreeNode) -> Unit,
    onNewFileRoot: () -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onOpenSdkManager: () -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    val project = state.backend.project
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            SideRail(
                selected = state.rail,
                onSelect = state::selectRail,
                projectInitial = project.name,
                onSettings = onToggleTheme,
                onOpenSdkManager = onOpenSdkManager,
            )
            if (state.navOpen) {
                GlassSurface(Modifier.width(300.dp).fillMaxHeight(), GlassMaterial.Regular) {
                    FileNavigator(
                        root = state.tree,
                        moduleCount = project.moduleCount,
                        activePath = state.active?.path,
                        onOpen = { node -> node.filePath?.let { state.open(it, node.name) } },
                        modifier = Modifier.fillMaxSize(),
                        onNewFile = onNewFile,
                        onNewFileRoot = onNewFileRoot,
                        onViewDependencies = { node -> onOpenDependencies(node.name) },
                        onConfigureModule = { node -> onOpenModuleConfig(node.name) },
                        canImport = fileActions.canImport,
                        onImport = { doImport(state, fileActions) },
                        canShare = fileActions.canShare,
                        onShare = { node -> node.filePath?.let { fileActions.share(it) } },
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
                        onRun = { state.backend.runBuild() },
                        onStop = { state.backend.stopBuild() },
                        onCollapse = { state.consoleOpen = false },
                        modifier = Modifier.fillMaxSize().padding(14.dp),
                    )
                }
            }
        }
        DestinationSheets(state, compact = false, onOpenDependencies, onOpenModuleConfig, onToggleTheme, onOpenSdkManager, onCloseProject)
        PaletteOverlay(state, onToggleTheme, onOpenDependencies, onOpenSdkManager)
    }
}

@Composable
private fun CompactLayout(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    indexStatus: IndexUiStatus,
    buildState: BuildState,
    onNewFile: (TreeNode) -> Unit,
    onNewFileRoot: () -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onOpenSdkManager: () -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    val project = state.backend.project
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EditorCenter(state, indexStatus, compact = true, Modifier.weight(1f).fillMaxWidth())
            BottomNav(
                selected = state.rail,
                onSelect = state::selectRail,
            )
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
                onOpen = { node -> node.filePath?.let { state.open(it, node.name); state.navOpen = false } },
                modifier = Modifier.fillMaxWidth().weight(1f),
                onNewFile = onNewFile,
                onNewFileRoot = onNewFileRoot,
                onViewDependencies = { node -> state.navOpen = false; onOpenDependencies(node.name) },
                onConfigureModule = { node -> state.navOpen = false; onOpenModuleConfig(node.name) },
                canImport = fileActions.canImport,
                onImport = { doImport(state, fileActions) },
                canShare = fileActions.canShare,
                onShare = { node -> node.filePath?.let { fileActions.share(it) } },
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
                onRun = { state.backend.runBuild() },
                onStop = { state.backend.stopBuild() },
                onCollapse = { state.consoleOpen = false },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(14.dp),
            )
        }
        DestinationSheets(state, compact = true, onOpenDependencies, onOpenModuleConfig, onToggleTheme, onOpenSdkManager, onCloseProject)
        PaletteOverlay(state, onToggleTheme, onOpenDependencies, onOpenSdkManager)
    }
}

/** Top bar + tabs + breadcrumb + the code canvas — shared by both layouts. */
@Composable
private fun EditorCenter(state: IdeUiState, indexStatus: IndexUiStatus, compact: Boolean, modifier: Modifier) {
    val project = state.backend.project
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
            hasUnsavedChanges = state.active?.modified == true,
            onToggleConsole = { state.consoleOpen = !state.consoleOpen },
            consoleOpen = state.consoleOpen,
            inlayHintsOn = state.inlayHintsEnabled,
            onToggleInlayHints = { state.inlayHintsEnabled = !state.inlayHintsEnabled },
            compact = compact,
        )
        TabsStrip(
            openFiles = state.openFiles,
            activeIndex = state.activeIndex,
            onSelect = { state.activeIndex = it },
            onClose = { state.close(it) },
        )
        val active = state.active
        if (active != null) {
            // Diagnostics live on the session and shift themselves on every edit (like the line index / token
            // spans), so the screen does no shifting. This runs the authoritative analysis, debounced and
            // keyed on the session's revision (an Int, not the text): Compose compares effect keys every
            // recomposition, so an O(1) Int compare per keystroke beats an O(n) String equals. The full text
            // materializes once here, after typing settles.
            LaunchedEffect(active.path, active.session.textRevision) {
                delay(350)
                val text = active.text // one lazy rope materialization per pause
                state.backend.updateDocument(active.path, text)
                active.session.applyAnalysis(runCatching { state.backend.analyze(active.path, text) }.getOrDefault(emptyList()))
                active.recomputeDirty() // precise dirty state (catches revert-to-saved); reuses the cached text
            }
            // Breadcrumb tracks the caret: module › enclosing type(s) › method. Debounced (a reparse), and
            // falls back to the file path when the caret is outside any declaration (imports, blank lines).
            var crumbs by remember(active.path) { mutableStateOf(breadcrumbFor(state, active)) }
            val caretOffset = active.session.selection.start
            LaunchedEffect(active.path, active.session.textRevision, caretOffset) {
                delay(200)
                val structure = runCatching { state.backend.breadcrumbAt(active.path, active.text, caretOffset) }.getOrDefault(emptyList())
                crumbs = if (structure.isEmpty()) breadcrumbFor(state, active)
                else listOfNotNull(state.backend.moduleNameForFile(active.path)) + structure
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { Breadcrumb(crumbs) }
                ViewModeToggle(active.viewMode, dev.ide.ui.editor.preview.isPreviewable(active.path)) { active.viewMode = it }
            }
            AndroidSourcesBanner(state)
            when (active.viewMode) {
                dev.ide.ui.EditorViewMode.Blocks -> BlockEditor(
                    path = active.path,
                    session = active.session,
                    backend = state.backend,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                dev.ide.ui.EditorViewMode.Preview -> dev.ide.ui.editor.preview.ResourcePreviewPane(
                    path = active.path,
                    text = active.text,
                    backend = state.backend,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                else -> CodeEditor(
                    path = active.path,
                    session = active.session,
                    backend = state.backend,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onSave = { state.save(active) },
                    onNavigate = { p, o -> state.openAt(p, o) },
                    showInlayHints = state.inlayHintsEnabled,
                )
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().background(Ca.colors.editorBg), contentAlignment = Alignment.Center) {
                Text("Open a file from the navigator", color = Ca.colors.textTertiary, style = Ca.type.subhead)
            }
        }
    }
}

/**
 * A thin one-time banner offering to download the Android platform sources (so `android.*` APIs get
 * parameter names + javadoc). Shown only when an Android SDK is present, the sources aren't installed, and an
 * `sdkmanager` is available. Dismisses itself once a download is attempted.
 */
@Composable
private fun AndroidSourcesBanner(state: IdeUiState) {
    var info by remember { mutableStateOf<UiAndroidSourcesInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { info = runCatching { state.backend.androidSourcesInfo() }.getOrNull() }

    val show = status != null || (info?.let { !it.installed && it.downloadable } == true)
    if (!show) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .background(Ca.colors.accent.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status ?: "Android platform sources (${info?.platform}) aren't installed — needed for android.* parameter names & docs.",
            color = Ca.colors.textSecondary, style = Ca.type.footnote, modifier = Modifier.weight(1f),
        )
        if (status == null) {
            Text(
                if (busy) "Downloading…" else "Download",
                color = if (busy) Ca.colors.textTertiary else Ca.colors.accent,
                style = Ca.type.footnote,
                modifier = Modifier.then(
                    if (busy) Modifier else Modifier.clickable {
                        busy = true
                        scope.launch { status = runCatching { state.backend.downloadAndroidSources() }.getOrElse { "Download failed: ${it.message}" } }
                    },
                ),
            )
        }
    }
}

@Composable
private fun PaletteOverlay(state: IdeUiState, onToggleTheme: () -> Unit, onOpenDependencies: (String?) -> Unit, onOpenSdkManager: () -> Unit) {
    DropdownOverlay(
        visible = state.paletteOpen,
        onDismiss = { state.paletteOpen = false },
    ) {
        CommandPalette(
            files = openableFiles(state.tree),
            backend = state.backend,
            onOpenFile = { node -> node.filePath?.let { state.open(it, node.name); state.paletteOpen = false } },
            onOpenAt = { path, offset -> state.openAt(path, offset); state.paletteOpen = false },
            onToggleTheme = onToggleTheme,
            onReindex = { state.backend.reindex(); state.paletteOpen = false },
            onOpenDependencies = { state.paletteOpen = false; onOpenDependencies(null) },
            onManageSdk = { state.paletteOpen = false; onOpenSdkManager() },
            onClose = { state.paletteOpen = false },
        )
    }
}

/**
 * The destinations that present as sheets rather than panes: Search (phone only — it's a side pane on
 * desktop), the Source-control "coming soon" placeholder, and the More menu of secondary actions.
 */
@Composable
private fun DestinationSheets(
    state: IdeUiState,
    compact: Boolean,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSdkManager: () -> Unit,
    onCloseProject: () -> Unit,
) {
    val indexStatus by state.backend.indexStatus.collectAsState()
    if (compact) {
        BottomSheet(visible = state.searchOpen, onDismiss = { state.searchOpen = false }, heightFraction = 0.85f) {
            SearchScreen(
                backend = state.backend,
                indexing = indexStatus.building,
                onOpenAt = { p, o -> state.openAt(p, o); state.searchOpen = false },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
    BottomSheet(visible = state.sheetDest == RailDestination.Source, onDismiss = { state.sheetDest = null }, heightFraction = 0.55f) {
        ComingSoon(
            icon = CaIcons.gitBranch,
            title = "Source Control",
            description = "Git integration — staging, diffs, commit & push — is on the roadmap.",
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    BottomSheet(visible = state.sheetDest == RailDestination.More, onDismiss = { state.sheetDest = null }, heightFraction = 0.52f) {
        MoreSheetContent(
            onModuleSettings = { state.sheetDest = null; onOpenModuleConfig(null) },
            onDependencies = { state.sheetDest = null; onOpenDependencies(null) },
            onReindex = { state.sheetDest = null; state.backend.reindex() },
            onToggleTheme = { state.sheetDest = null; onToggleTheme() },
            onManageSdk = { state.sheetDest = null; onOpenSdkManager() },
            onCloseProject = { state.sheetDest = null; onCloseProject() },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** The "More" menu: secondary actions that don't warrant a top-level destination. */
@Composable
private fun MoreSheetContent(
    onModuleSettings: () -> Unit,
    onDependencies: () -> Unit,
    onReindex: () -> Unit,
    onToggleTheme: () -> Unit,
    onManageSdk: () -> Unit,
    onCloseProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("More", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 10.dp))
        MoreRow(CaIcons.gear, "Module Settings", "Java version, Android config, source sets", onModuleSettings)
        MoreRow(CaIcons.layers, "Dependencies", "Add or remove libraries", onDependencies)
        MoreRow(CaIcons.pkg, "SDK Manager", "Download Android SDK packages & JDK sources", onManageSdk)
        MoreRow(CaIcons.refresh, "Re-index project", "Rebuild symbol & completion indexes", onReindex)
        MoreRow(CaIcons.eye, "Toggle theme", "Switch between light and dark", onToggleTheme)
        MoreRow(CaIcons.close, "Close project", "Back to all projects", onCloseProject)
    }
}

@Composable
private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.md))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(38.dp).background(Ca.colors.accentSoft, androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.sm)),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(icon, null, Modifier.size(19.dp), tint = Ca.colors.accent) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Ca.colors.textTertiary, style = Ca.type.caption2)
        }
        androidx.compose.material3.Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = Ca.colors.textTertiary)
    }
}

@Composable
private fun BottomNav(selected: RailDestination, onSelect: (RailDestination) -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth().height(60.dp), material = GlassMaterial.Thick) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavItem(CaIcons.docText, "Files", selected == RailDestination.Files) { onSelect(RailDestination.Files) }
            BottomNavItem(CaIcons.search, "Search", selected == RailDestination.Search) { onSelect(RailDestination.Search) }
            BottomNavItem(CaIcons.gitBranch, "Source", selected == RailDestination.Source) { onSelect(RailDestination.Source) }
            BottomNavItem(CaIcons.ellipsis, "More", selected == RailDestination.More) { onSelect(RailDestination.More) }
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box {
            IconButtonCa(icon, label, onClick, active = active, iconSize = 22, boxSize = 40)
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .height(15.dp).width(15.dp)
                        .background(Ca.colors.error, androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.pill)),
                    contentAlignment = Alignment.Center,
                ) { Text("$badge", color = Ca.colors.textOnAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text(label, color = if (active) Ca.colors.accent else Ca.colors.textTertiary, fontSize = 10.5f.sp)
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Ca.colors.separator))
}

/** A compact `Code / Blocks` segmented control switching the active tab between the two editor surfaces. */
@Composable
private fun ViewModeToggle(mode: dev.ide.ui.EditorViewMode, canPreview: Boolean, onSelect: (dev.ide.ui.EditorViewMode) -> Unit) {
    Row(
        Modifier.background(Ca.colors.surface2, androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.sm)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SegmentItem(CaIcons.code, "Code", mode == dev.ide.ui.EditorViewMode.Text) { onSelect(dev.ide.ui.EditorViewMode.Text) }
        SegmentItem(CaIcons.layers, "Blocks", mode == dev.ide.ui.EditorViewMode.Blocks) { onSelect(dev.ide.ui.EditorViewMode.Blocks) }
        if (canPreview) {
            SegmentItem(CaIcons.image, "Preview", mode == dev.ide.ui.EditorViewMode.Preview) { onSelect(dev.ide.ui.EditorViewMode.Preview) }
        }
    }
}

@Composable
private fun SegmentItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.xs))
            .background(if (active) Ca.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.xs))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, label, Modifier.size(15.dp), tint = if (active) Ca.colors.accent else Ca.colors.textSecondary)
        Text(label, color = if (active) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

private fun breadcrumbFor(state: IdeUiState, file: OpenFile): List<String> {
    val module = state.backend.moduleNameForFile(file.path)
    val segs = file.path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    val tail = segs.takeLast(3)
    return (listOfNotNull(module) + tail).distinct()
}

/**
 * Launch the host's file picker to import external file(s) into the first source root, then refresh the
 * tree and open the first imported file. No-op when there's no source root to target.
 */
private fun doImport(state: IdeUiState, fileActions: FileActions) {
    val dir = state.defaultNewFileTarget()?.sourceRootPath ?: return
    fileActions.importInto(dir) { paths ->
        state.refreshTree()
        paths.firstOrNull()?.let { p -> state.open(p, p.substringAfterLast('/').substringAfterLast('\\')) }
    }
}

private fun openableFiles(root: TreeNode): List<TreeNode> {
    val out = ArrayList<TreeNode>()
    fun walk(n: TreeNode) {
        if (n.filePath != null) out.add(n)
        n.children.forEach(::walk)
    }
    walk(root)
    return out
}
