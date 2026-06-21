package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.DepsResolveState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiDepKind
import dev.ide.ui.backend.UiDependencyNode
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.Chip
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DepView(val label: String, val icon: ImageVector) {
    List("List", CaIcons.resources), Tree("Tree", CaIcons.layers), Graph("Graph", CaIcons.gitBranch)
}

/** The Add flow can add a library/AAR, import a BOM as a platform (Gradle `platform(...)`), depend on
 *  another module, or attach a local jar/aar file. */
private enum class AddMode(val label: String) { Library("Library"), Platform("Platform (BOM)"), Module("Module"), Local("Local file") }

/** A typed string is treated as a direct coordinate when it carries a `:` — `group:name[:version]`. */
private fun looksLikeCoordinate(s: String): Boolean =
    s.split(":").let { it.size in 2..3 && it.all { p -> p.isNotBlank() } }

/** A transient confirmation/result toast. */
private data class ToastMsg(val text: String, val error: Boolean)

/** Width at/above which the screen uses the desktop two-pane layout (module list pane + content). */
private val DEPS_EXPANDED_BREAKPOINT = 860.dp

/**
 * The per-module dependency manager, **embedded in a module's detail screen** (the host owns the module
 * header / back / tab chrome). A toolbar (List/Tree/Graph toggle · Repositories · Add) over the resolved
 * picture; a live **download/resolution panel** while resolving; an Add flow (centered dialog on desktop,
 * bottom sheet on phone) that adds a **library/AAR**, imports a **BOM platform**, or depends on **another
 * module**; a **Repositories** manager for custom Maven repos; a remove confirmation; and toasts. Talks
 * only to [IdeBackend].
 */
@Composable
fun DependenciesPane(
    backend: IdeBackend,
    moduleName: String,
    codeFont: FontFamily = FontFamily.Monospace,
    fileActions: FileActions = FileActions.None,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf(DepView.List) }
    var deps by remember { mutableStateOf<UiModuleDeps?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember(moduleName) { mutableStateOf(0) }
    var addOpen by remember { mutableStateOf(false) }
    var reposOpen by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<ToastMsg?>(null) }
    val resolveState by backend.depsState.collectAsState()

    LaunchedEffect(moduleName, reloadKey) {
        loading = true
        deps = runCatching { backend.moduleDependencies(moduleName) }.getOrNull()
        loading = false
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    val resolving = loading || resolveState.resolving
    BoxWithConstraints(modifier.fillMaxSize().background(Ca.colors.bg)) {
        val expanded = maxWidth >= DEPS_EXPANDED_BREAKPOINT
        Column(Modifier.fillMaxSize()) {
            DepPaneToolbar(view, { view = it }, { addOpen = true }, { reposOpen = true }, resolving, resolveState.message, compact = !expanded)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            DepBody(deps, loading, view, resolveState, codeFont, Modifier.weight(1f).fillMaxWidth()) { pendingRemove = it }
        }

        // ---- Add flow + Repositories: centered dialogs on desktop, bottom sheets on phone ----
        val onResult: (UiAddResult) -> Unit = { result ->
            if (result.success) { addOpen = false; reloadKey++; toast = ToastMsg(result.message, error = false) }
        }
        if (expanded) {
            DropdownOverlay(visible = addOpen, onDismiss = { addOpen = false }, topPadding = 64.dp) {
                OverlayCard(maxWidth = 640.dp) {
                    AddDependencyContent(backend, moduleName, codeFont, fileActions, onResult, Modifier.padding(20.dp).fillMaxWidth())
                }
            }
            DropdownOverlay(visible = reposOpen, onDismiss = { reposOpen = false }, topPadding = 64.dp) {
                OverlayCard(maxWidth = 560.dp) {
                    RepositoriesContent(backend, codeFont, Modifier.padding(20.dp).fillMaxWidth())
                }
            }
        } else {
            BottomSheet(visible = addOpen, onDismiss = { addOpen = false }, heightFraction = 0.82f) {
                AddDependencyContent(backend, moduleName, codeFont, fileActions, onResult, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            BottomSheet(visible = reposOpen, onDismiss = { reposOpen = false }, heightFraction = 0.7f) {
                RepositoriesContent(backend, codeFont, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        // ---- remove confirmation ----
        ConfirmRemoveDialog(
            coordinate = pendingRemove,
            moduleName = moduleName,
            onDismiss = { pendingRemove = null },
            onConfirm = {
                val coord = pendingRemove
                if (coord != null && backend.removeDependency(moduleName, coord)) {
                    toast = ToastMsg("Removed ${shortCoord(coord)}", error = false)
                    reloadKey++
                }
                pendingRemove = null
            },
        )

        // ---- toast ----
        ToastHost(toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun OverlayCard(maxWidth: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 12.dp).widthIn(max = maxWidth).fillMaxWidth()
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)),
    ) { content() }
}

// ---- toolbar ------------------------------------------------------------------------------------

@Composable
private fun DepPaneToolbar(
    view: DepView,
    onView: (DepView) -> Unit,
    onAdd: () -> Unit,
    onRepos: () -> Unit,
    resolving: Boolean,
    resolveMessage: String,
    compact: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ViewToggle(view, onView, compact = compact)
        if (resolving) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
            if (!compact) Text(resolveMessage.ifBlank { "Resolving…" }, color = Ca.colors.accent, style = Ca.type.caption2,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.weight(1f))
        IconButtonCa(CaIcons.pkg, "Repositories", onClick = onRepos)
        PrimaryButton("Add", onClick = onAdd, icon = CaIcons.plus, iconOnly = compact)
    }
}

// ---- repositories manager -----------------------------------------------------------------------

@Composable
private fun RepositoriesContent(backend: IdeBackend, codeFont: FontFamily, modifier: Modifier = Modifier) {
    var repos by remember { mutableStateOf(backend.repositories()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val add = {
        if (backend.addRepository(name, url)) { repos = backend.repositories(); name = ""; url = ""; error = null }
        else error = "Enter a valid http(s) URL that isn't already added."
    }

    Column(modifier) {
        Text("Repositories", color = Ca.colors.textPrimary, style = Ca.type.title3, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Where libraries resolve from. Built-in repos can't be removed.", color = Ca.colors.textTertiary, style = Ca.type.caption)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(repos, key = { it.url }) { r -> RepoRow(r) { if (backend.removeRepository(r.url)) repos = backend.repositories() } }
        }
        Spacer(Modifier.height(12.dp))
        // add a custom repository
        RepoField("Name (optional)", name, codeFont) { name = it; error = null }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { RepoField("https://repo.example.com/maven", url, codeFont) { url = it; error = null } }
            PrimaryButton("Add", onClick = add, icon = CaIcons.plus)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Ca.colors.error, style = Ca.type.caption2)
        }
    }
}

@Composable
private fun RepoRow(repo: dev.ide.ui.backend.UiRepository, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LetterBox(if (repo.builtin) "•" else "+", if (repo.builtin) Ca.colors.textTertiary else Ca.colors.accent)
        Column(Modifier.weight(1f)) {
            Text(repo.name, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(repo.url, color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!repo.builtin) IconButtonCa(CaIcons.close, "Remove ${repo.name}", onClick = onRemove, boxSize = 28, iconSize = 16, tint = Ca.colors.textTertiary)
        else Text("built-in", color = Ca.colors.textTertiary, style = Ca.type.caption2)
    }
}

@Composable
private fun RepoField(hint: String, value: String, codeFont: FontFamily, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Ca.colors.textTertiary, style = Ca.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true,
                textStyle = Ca.type.subhead.copy(color = Ca.colors.textPrimary, fontFamily = codeFont),
                cursorBrush = SolidColor(Ca.colors.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- body: loading panel / content --------------------------------------------------------------

@Composable
private fun DepBody(
    deps: UiModuleDeps?,
    loading: Boolean,
    view: DepView,
    resolveState: DepsResolveState,
    codeFont: FontFamily,
    modifier: Modifier,
    onRemove: (String) -> Unit,
) {
    Crossfade(targetState = loading, animationSpec = tween(Motion.BASE), label = "depBody", modifier = modifier) { isLoading ->
        when {
            isLoading -> ResolvingPanel(resolveState)
            deps == null -> Empty("Couldn't load dependencies.")
            else -> DepContent(deps, view, codeFont, onRemove)
        }
    }
}

/** The download/resolution experience: a centered card with a spinner, the live step message, and a bar. */
@Composable
private fun ResolvingPanel(state: DepsResolveState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(24.dp)
                .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg))
                .padding(24.dp)
                .entranceSlideUp(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(30.dp), color = Ca.colors.accent, strokeWidth = 3.dp)
            Text("Resolving dependencies", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            Text(state.message.ifBlank { "Downloading POMs & artifacts…" }, color = Ca.colors.textSecondary,
                style = Ca.type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ResolveBar(state.fraction)
        }
    }
}

/** Determinate when [fraction] is in 0..1, otherwise an indeterminate sweep — the spec's progress recipe. */
@Composable
private fun ResolveBar(fraction: Double) {
    if (fraction in 0.0..1.0) {
        LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Ca.colors.accent, trackColor = Ca.colors.surface2)
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp), color = Ca.colors.accent, trackColor = Ca.colors.surface2)
    }
}

@Composable
private fun DepContent(deps: UiModuleDeps, view: DepView, codeFont: FontFamily, onRemove: (String) -> Unit) {
    val nodesByCoord = remember(deps) { deps.nodes.associateBy { it.coordinate } }
    val expanded = remember(deps) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (deps.conflicts.isNotEmpty()) item("conflicts") {
            BannerCard(CaIcons.warning, Ca.colors.warning, "${deps.conflicts.size} version conflict${plural(deps.conflicts.size)} resolved", Modifier.animateItem()) {
                deps.conflicts.forEach { c ->
                    Text("${c.artifact}: ${c.requested.joinToString(", ")} → ${c.chosen}",
                        color = Ca.colors.textSecondary, style = Ca.type.caption.copy(fontFamily = codeFont))
                }
            }
        }
        if (deps.cycles.isNotEmpty()) item("cycles") {
            BannerCard(CaIcons.refresh, Ca.colors.error, "${deps.cycles.size} dependency cycle${plural(deps.cycles.size)}", Modifier.animateItem()) {
                deps.cycles.forEach { cycle -> Text(cycle.joinToString(" → ") { it.substringBeforeLast(':') }, color = Ca.colors.textSecondary, style = Ca.type.caption.copy(fontFamily = codeFont)) }
            }
        }
        if (deps.unresolved.isNotEmpty()) item("unresolved") {
            BannerCard(CaIcons.error, Ca.colors.error, "${deps.unresolved.size} unresolved", Modifier.animateItem()) {
                deps.unresolved.forEach { Text(it, color = Ca.colors.textSecondary, style = Ca.type.caption.copy(fontFamily = codeFont)) }
            }
        }

        when (view) {
            DepView.List -> {
                if (deps.declared.isEmpty()) item("empty") { EmptyRow("No dependencies declared. Tap Add to download one.") }
                items(deps.declared, key = { "list:${it.coordinate}" }) { node ->
                    val open = expanded["list:${node.coordinate}"] == true
                    Column(Modifier.fillMaxWidth().animateItem()) {
                        DependencyRow(node, codeFont, depth = 0, expandable = node.children.isNotEmpty(), expanded = open,
                            onToggle = { expanded["list:${node.coordinate}"] = !open },
                            onRemove = if (node.declared) ({ onRemove(node.coordinate) }) else null)
                        AnimatedVisibility(open, enter = expandVertically(tween(Motion.FAST)) + fadeIn(), exit = shrinkVertically(tween(Motion.FAST)) + fadeOut()) {
                            Column {
                                node.children.forEach { childCoord -> nodesByCoord[childCoord]?.let { TransitiveRow(it, codeFont, depth = 1) } }
                            }
                        }
                    }
                }
            }
            DepView.Tree -> {
                if (deps.declared.isEmpty()) item("empty") { EmptyRow("No dependencies declared.") }
                deps.declared.forEach { root -> treeRows(root, nodesByCoord, 0, emptyList(), expanded, codeFont) { onRemove(root.coordinate) } }
            }
            DepView.Graph -> {
                val sorted = deps.nodes.sortedWith(compareByDescending<UiDependencyNode> { it.declared }.thenBy { it.coordinate })
                items(sorted, key = { "graph:${it.coordinate}" }) { node -> Box(Modifier.animateItem()) { GraphRow(node, nodesByCoord, codeFont) } }
            }
        }
    }
}

private fun LazyListScope.treeRows(
    node: UiDependencyNode,
    nodesByCoord: Map<String, UiDependencyNode>,
    depth: Int,
    ancestors: List<String>,
    expanded: SnapshotStateMap<String, Boolean>,
    codeFont: FontFamily,
    onRemove: (() -> Unit)?,
) {
    val key = (ancestors + node.coordinate).joinToString(">")
    val cycle = node.coordinate in ancestors
    val children = if (cycle) emptyList() else node.children.mapNotNull { nodesByCoord[it] }
    val isOpen = expanded[key] == true
    item(key) {
        Box(Modifier.animateItem()) {
            DependencyRow(node, codeFont, depth = depth, expandable = children.isNotEmpty(), expanded = isOpen,
                onToggle = { expanded[key] = !isOpen }, onRemove = onRemove, cycle = cycle)
        }
    }
    if (isOpen) children.forEach { child -> treeRows(child, nodesByCoord, depth + 1, ancestors + node.coordinate, expanded, codeFont, null) }
}

@Composable
private fun GraphRow(node: UiDependencyNode, nodesByCoord: Map<String, UiDependencyNode>, codeFont: FontFamily) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DepBadge(node)
            Box(Modifier.weight(1f, fill = false)) { Column { DepPrimary(node, codeFont, dimmed = !node.declared); DepSubtitle(node) } }
            if (node.declared) Chip(node.scope ?: "declared", fill = Ca.colors.accentSoft, textColor = Ca.colors.accent)
            if (node.inConflict) Chip("conflict", fill = Ca.colors.warning.copy(alpha = 0.16f), textColor = Ca.colors.warning)
        }
        if (node.children.isNotEmpty()) {
            Row(Modifier.padding(start = 30.dp, top = 3.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CaIcons.arrowRight, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
                node.children.forEach { c ->
                    val child = nodesByCoord[c]
                    Chip(child?.let { "${it.name}:${it.version}" } ?: c.substringBeforeLast(':'), fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun DependencyRow(
    node: UiDependencyNode,
    codeFont: FontFamily,
    depth: Int,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)?,
    cycle: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clickable(enabled = expandable, onClick = onToggle)
            .padding(start = (16 + depth * 18).dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (expandable) Icon(if (expanded) CaIcons.caretDown else CaIcons.caretRight, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
        else Spacer(Modifier.width(14.dp))
        DepBadge(node)
        Column(Modifier.weight(1f)) {
            DepPrimary(node, codeFont)
            when {
                !node.compatible && node.incompatibleReason != null ->
                    Text(node.incompatibleReason, color = Ca.colors.error, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                cycle -> Text("cycle — already shown above", color = Ca.colors.warning, style = Ca.type.caption2)
                else -> DepSubtitle(node)
            }
        }
        node.scope?.let { Chip(it, fill = Ca.colors.accentSoft, textColor = Ca.colors.accent) }
        if (node.inConflict) Chip("conflict", fill = Ca.colors.warning.copy(alpha = 0.16f), textColor = Ca.colors.warning)
        if (!node.compatible) Icon(CaIcons.warning, "Incompatible", Modifier.size(16.dp), tint = Ca.colors.error)
        if (onRemove != null) IconButtonCa(CaIcons.close, "Remove ${node.name}", onClick = onRemove, boxSize = 28, iconSize = 16, tint = Ca.colors.textTertiary)
    }
}

@Composable
private fun TransitiveRow(node: UiDependencyNode, codeFont: FontFamily, depth: Int) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(start = (16 + depth * 18 + 14).dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DepBadge(node, small = true)
        Column(Modifier.weight(1f)) {
            DepPrimary(node, codeFont, dimmed = true)
            DepSubtitle(node)
        }
        Text("transitive", color = Ca.colors.textTertiary, style = Ca.type.caption2)
    }
}

// ---- Add dependency (shared by the desktop dialog + phone sheet) --------------------------------

@Composable
private fun AddDependencyContent(
    backend: IdeBackend,
    moduleName: String,
    codeFont: FontFamily,
    fileActions: FileActions,
    onResult: (UiAddResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(AddMode.Library) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UiArtifactHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf("implementation") }
    var moduleTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var localCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    val resolveState by backend.depsState.collectAsState()
    val scopeOptions = listOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation")
    val coroutine = rememberCoroutineScope()

    LaunchedEffect(query, mode) {
        val q = query.trim()
        if (q.length < 2 || mode == AddMode.Module || mode == AddMode.Local) { results = emptyList(); return@LaunchedEffect }
        searching = true
        delay(320)
        // distinctBy coordinate: the same GAV can come back from more than one repo; duplicate keys crash the list.
        results = runCatching { backend.searchArtifacts(q, moduleName) }.getOrDefault(emptyList()).distinctBy { it.coordinate }
        searching = false
    }
    // Load candidate modules / project-local jars when their tab is selected.
    LaunchedEffect(mode) {
        if (mode == AddMode.Module) moduleTargets = runCatching { backend.moduleDependencyTargets(moduleName) }.getOrDefault(emptyList())
        if (mode == AddMode.Local) localCandidates = runCatching { backend.localLibraryCandidates(moduleName) }.getOrDefault(emptyList())
    }

    // Add a versioned library/AAR, a BOM platform, or (Module mode) a module-on-module dependency.
    val performAdd: (String) -> Unit = { coordinate ->
        busy = true; error = null; adding = coordinate
        coroutine.launch {
            val result = when (mode) {
                AddMode.Platform -> backend.addPlatform(moduleName, coordinate)
                AddMode.Module -> backend.addModuleDependency(moduleName, coordinate, scope)
                AddMode.Local -> backend.addLocalLibrary(moduleName, coordinate, scope)
                AddMode.Library -> backend.addDependency(moduleName, coordinate, scope)
            }
            busy = false; adding = null
            if (result.success) onResult(result) else error = result.message
        }
    }

    // Pick a jar/aar via the platform file picker; it's copied into the module's libs/ then attached.
    val pickLocalFile = {
        val dropDir = backend.localLibraryDropDir(moduleName)
        if (dropDir != null) fileActions.importInto(dropDir) { imported ->
            if (imported.isNotEmpty()) {
                busy = true; error = null; adding = imported.first().substringAfterLast('/').substringAfterLast('\\')
                coroutine.launch {
                    var last: UiAddResult? = null
                    for (p in imported) { last = backend.addLocalLibrary(moduleName, p, scope); if (last?.success != true) break }
                    busy = false; adding = null
                    last?.let { if (it.success) onResult(it) else error = it.message }
                }
            }
        }
    }

    Column(modifier) {
        Text("Add dependency", color = Ca.colors.textPrimary, style = Ca.type.title3, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))

        // Library / Platform (BOM) / Module toggle
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddMode.entries.forEach { m -> ModeChip(m.label, m == mode) { if (!busy) { mode = m; error = null } } }
        }

        // search field — library/platform only (Module picks project modules; Local picks files)
        if (mode != AddMode.Module && mode != AddMode.Local) Row(
            Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(CaIcons.search, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            Box(Modifier.weight(1f)) {
                val hint = if (mode == AddMode.Platform) "Search a BOM, or type group:name:version — e.g. androidx.compose:compose-bom:…"
                    else "Search Maven Central, or type group:name[:version]…"
                if (query.isEmpty()) Text(hint, color = Ca.colors.textTertiary, style = Ca.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(query, { query = it; error = null }, singleLine = true, enabled = !busy,
                    textStyle = Ca.type.subhead.copy(color = Ca.colors.textPrimary, fontFamily = codeFont),
                    cursorBrush = SolidColor(Ca.colors.accent), modifier = Modifier.fillMaxWidth())
            }
            if (searching) CircularProgressIndicator(Modifier.size(14.dp), color = Ca.colors.textTertiary, strokeWidth = 2.dp)
        }

        // scope selector — libraries + module deps (a platform carries no scope)
        if (mode != AddMode.Platform) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("scope", color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.padding(end = 4.dp))
            scopeOptions.forEach { s -> ScopeChip(s, s == scope) { if (!busy) scope = s } }
        } else Spacer(Modifier.height(10.dp))

        error?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Ca.colors.error.copy(alpha = 0.10f), RoundedCornerShape(Ca.radius.sm)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.warning, null, Modifier.size(15.dp), tint = Ca.colors.error)
                Text(msg, color = Ca.colors.error, style = Ca.type.caption)
            }
        }

        // While adding: a live download panel. Otherwise: the results / module list.
        Crossfade(targetState = busy, animationSpec = tween(Motion.BASE), label = "addBody") { isBusy ->
            if (isBusy) {
                Column(Modifier.fillMaxWidth().heightIn(min = 160.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(Modifier.size(28.dp), color = Ca.colors.accent, strokeWidth = 3.dp)
                    Text("Adding ${adding?.let(::shortCoord) ?: ""}", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
                    Text(resolveState.message.ifBlank { "Resolving transitive dependencies…" }, color = Ca.colors.textSecondary, style = Ca.type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ResolveBar(resolveState.fraction)
                }
            } else if (mode == AddMode.Module) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (moduleTargets.isEmpty()) item { EmptyRow("No other modules available to depend on.") }
                    items(moduleTargets, key = { it }) { target ->
                        ModuleTargetRow(target, Modifier.animateItem()) { performAdd(target) }
                    }
                }
            } else if (mode == AddMode.Local) {
                LocalLibraryBody(
                    candidates = localCandidates,
                    canPick = fileActions.canImport && backend.localLibraryDropDir(moduleName) != null,
                    codeFont = codeFont,
                    onPick = pickLocalFile,
                    onAttach = { path -> performAdd(path) },
                )
            } else {
                val typed = query.trim()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    // Direct add of a typed coordinate — the only way to add a versionless `group:name`
                    // (resolved against the module's imported platforms) or a coordinate not in the index.
                    if (looksLikeCoordinate(typed)) item("direct:$typed") {
                        DirectAddRow(typed, mode, codeFont, Modifier.animateItem()) { performAdd(typed) }
                    }
                    items(results, key = { it.coordinate }) { hit ->
                        AddResultRow(hit, codeFont, Modifier.animateItem()) { performAdd(hit.coordinate) }
                    }
                    if (typed.length >= 2 && results.isEmpty() && !searching && !looksLikeCoordinate(typed)) item { EmptyRow("No results.") }
                    if (typed.length < 2) item { EmptyRow("Type at least 2 characters to search, or a full group:name[:version].") }
                }
            }
        }
    }
}

/** A pickable module to depend on (Module mode). */
@Composable
private fun ModuleTargetRow(name: String, modifier: Modifier, onAdd: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(48.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onAdd).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LetterBox("M", Ca.colors.accent)
        Text(":$name", color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButtonCa(CaIcons.plus, "Add $name", onClick = onAdd, active = true, boxSize = 32, iconSize = 18)
    }
}

/** A row offering to add the literally-typed coordinate (handles versionless `group:name` + BOMs). */
@Composable
private fun DirectAddRow(coordinate: String, mode: AddMode, codeFont: FontFamily, modifier: Modifier, onAdd: () -> Unit) {
    val versionless = coordinate.count { it == ':' } == 1
    val color = if (mode == AddMode.Platform) Ca.colors.info else Ca.colors.accent
    Row(
        modifier.fillMaxWidth().height(52.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onAdd).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LetterBox(if (mode == AddMode.Platform) "B" else "+", color)
        Column(Modifier.weight(1f)) {
            Text(coordinate, color = Ca.colors.textPrimary, style = Ca.type.footnote.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    mode == AddMode.Platform -> "Import as a platform (BOM)"
                    versionless -> "Add versionless — version from a platform"
                    else -> "Add this exact coordinate"
                },
                color = Ca.colors.textTertiary, style = Ca.type.caption2,
            )
        }
        IconButtonCa(CaIcons.plus, "Add $coordinate", onClick = onAdd, active = true, boxSize = 32, iconSize = 18)
    }
}

/**
 * The Local-file add mode: pick a `.jar`/`.aar` from the device (copied into the module's `libs/`), or
 * attach one already in the project tree (e.g. imported earlier). AAR compatibility is enforced by the
 * backend; rejected picks surface as the inline error.
 */
@Composable
private fun LocalLibraryBody(
    candidates: List<String>,
    canPick: Boolean,
    codeFont: FontFamily,
    onPick: () -> Unit,
    onAttach: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (canPick) item("pick") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.md))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onPick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(CaIcons.plus, null, Modifier.size(18.dp), tint = Ca.colors.accent)
                Column(Modifier.weight(1f)) {
                    Text("Choose a .jar or .aar file", color = Ca.colors.accent, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
                    Text("Copied into the module's libs/ folder", color = Ca.colors.textTertiary, style = Ca.type.caption2)
                }
            }
        }
        if (candidates.isNotEmpty()) {
            item("from-project") {
                Text("Already in the project", color = Ca.colors.textTertiary, style = Ca.type.caption,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            }
            items(candidates, key = { "local:$it" }) { path ->
                LocalCandidateRow(path, codeFont, Modifier.animateItem()) { onAttach(path) }
            }
        }
        if (!canPick && candidates.isEmpty()) item("empty") {
            EmptyRow("No local jars/aars found. Import one into the project, or open this on a device to pick a file.")
        }
    }
}

/** A `.jar`/`.aar` file already in the project tree, offered for one-tap attach as a local library. */
@Composable
private fun LocalCandidateRow(path: String, codeFont: FontFamily, modifier: Modifier, onAttach: () -> Unit) {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    val isAar = fileName.endsWith(".aar", ignoreCase = true)
    Row(
        modifier.fillMaxWidth().height(52.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onAttach).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LetterBox(if (isAar) "A" else "J", if (isAar) Ca.colors.run else Ca.colors.warning)
        Column(Modifier.weight(1f)) {
            Text(fileName, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, color = Ca.colors.textTertiary, style = Ca.type.caption2.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButtonCa(CaIcons.plus, "Attach $fileName", onClick = onAttach, active = true, boxSize = 32, iconSize = 18)
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ca.colors.accentSoft else Ca.colors.surface2, tween(Motion.FAST), label = "modeBg")
    Box(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.pill)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AddResultRow(hit: UiArtifactHit, codeFont: FontFamily, modifier: Modifier, onAdd: () -> Unit) {
    val isAar = hit.packaging.equals("aar", ignoreCase = true)
    Row(
        modifier.fillMaxWidth().height(52.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LetterBox(if (isAar) "A" else "J", if (hit.compatible) (if (isAar) Ca.colors.run else Ca.colors.warning) else Ca.colors.error)
        Column(Modifier.weight(1f)) {
            Text(hit.coordinate, color = if (hit.compatible) Ca.colors.textPrimary else Ca.colors.textTertiary,
                style = Ca.type.footnote.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!hit.compatible && hit.incompatibleReason != null)
                Text(hit.incompatibleReason, color = Ca.colors.error, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(hit.packaging, color = Ca.colors.textTertiary, style = Ca.type.caption2)
        }
        if (hit.compatible) IconButtonCa(CaIcons.plus, "Add ${hit.coordinate}", onClick = onAdd, active = true, boxSize = 32, iconSize = 18)
        else Icon(CaIcons.warning, "Incompatible", Modifier.size(18.dp), tint = Ca.colors.error)
    }
}

// ---- confirm dialog + toast ---------------------------------------------------------------------

@Composable
private fun ConfirmRemoveDialog(coordinate: String?, moduleName: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    // Retain the last coordinate so the exit animation doesn't flash empty.
    var shown by remember { mutableStateOf<String?>(null) }
    if (coordinate != null) shown = coordinate
    DropdownOverlay(visible = coordinate != null, onDismiss = onDismiss, topPadding = 140.dp) {
        Column(
            Modifier.padding(horizontal = 12.dp).widthIn(max = 460.dp).fillMaxWidth()
                .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)).padding(20.dp),
        ) {
            Text("Remove dependency", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Remove ${shown?.let(::shortCoord) ?: ""} from ${moduleName ?: ""}? Its resolved classpath will be detached from the module.",
                color = Ca.colors.textSecondary, style = Ca.type.footnote)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                DialogButton("Cancel", destructive = false, onClick = onDismiss)
                DialogButton("Remove", destructive = true, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, destructive: Boolean, onClick: () -> Unit) {
    val fill = if (destructive) Ca.colors.error else Ca.colors.surface3
    val fg = if (destructive) Ca.colors.textOnAccent else Ca.colors.textSecondary
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.background(fill, RoundedCornerShape(Ca.radius.control)).clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, color = fg, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ToastHost(toast: ToastMsg?, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(bottom = 28.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically(tween(Motion.BASE, easing = Motion.spring)) { it } + fadeIn(tween(Motion.BASE)),
            exit = slideOutVertically(tween(Motion.FAST)) { it } + fadeOut(tween(Motion.FAST)),
        ) {
            val t = toast
            Row(
                Modifier.background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.pill))
                    .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (t?.error == true) CaIcons.warning else CaIcons.check, null, Modifier.size(16.dp), tint = if (t?.error == true) Ca.colors.error else Ca.colors.run)
                Text(t?.text ?: "", color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---- small shared pieces ------------------------------------------------------------------------

@Composable
private fun ViewToggle(view: DepView, onSelect: (DepView) -> Unit, compact: Boolean = false) {
    Row(Modifier.background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        DepView.entries.forEach { v -> SegItem(v.icon, v.label, v == view, compact) { onSelect(v) } }
    }
}

@Composable
private fun SegItem(icon: ImageVector, label: String, active: Boolean, compact: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Ca.colors.accentSoft else Color.Transparent, tween(Motion.FAST), label = "segBg")
    Row(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.xs)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(14.dp), tint = if (active) Ca.colors.accent else Ca.colors.textSecondary)
        if (!compact) Text(label, color = if (active) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ca.colors.accent else Ca.colors.surface2, tween(Motion.FAST), label = "scopeBg")
    Box(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.pill)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) Ca.colors.textOnAccent else Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DepBadge(node: UiDependencyNode, small: Boolean = false) {
    val (letter, color) = when (node.kind) {
        UiDepKind.Jar -> "J" to Ca.colors.warning
        UiDepKind.Aar -> "A" to Ca.colors.run
        UiDepKind.Module -> "M" to Ca.colors.accent
        UiDepKind.Sdk -> "S" to Ca.colors.info
        UiDepKind.Platform -> "B" to Ca.colors.info   // a BOM (bill of materials) — version source, no artifact
    }
    LetterBox(letter, if (node.compatible) color else Ca.colors.error, size = if (small) 16 else 20)
}

@Composable
private fun LetterBox(letter: String, color: Color, size: Int = 20) {
    Box(Modifier.size(size.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(Ca.radius.xs)), contentAlignment = Alignment.Center) {
        Text(letter, color = color, style = Ca.type.caption2, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BannerCard(icon: ImageVector, color: Color, title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(Ca.radius.md))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(Ca.radius.md)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = color)
            Text(title, color = color, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Ca.colors.textTertiary, style = Ca.type.subhead)
    }
}

@Composable
private fun EmptyRow(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ca.colors.textTertiary, style = Ca.type.footnote)
    }
}

/**
 * A dependency's identity, laid out for readability instead of one run-on `group:name:version` string:
 * the artifact **name** reads first (bold for a declared root, dimmed for a transitive), the **version**
 * sits beside it as a subtle monospace tag, and the **group** (or a "module"/"local"/"BOM" descriptor)
 * is the dimmed subtitle on the line below ([DepSubtitle]).
 */
@Composable
private fun DepPrimary(node: UiDependencyNode, codeFont: FontFamily, dimmed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            primaryName(node),
            color = if (!node.compatible) Ca.colors.error else if (dimmed) Ca.colors.textSecondary else Ca.colors.textPrimary,
            style = Ca.type.footnote.copy(fontFamily = codeFont),
            fontWeight = if (node.declared) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (node.version.isNotEmpty()) VersionTag(node.version, codeFont)
    }
}

@Composable
private fun DepSubtitle(node: UiDependencyNode) {
    depSubtitle(node)?.let { Text(it, color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A small, dimmed monospace tag for a dependency's version, kept visually separate from its name. */
@Composable
private fun VersionTag(version: String, codeFont: FontFamily) {
    Box(Modifier.background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.xs)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(version, color = Ca.colors.textSecondary, style = Ca.type.caption2.copy(fontFamily = codeFont))
    }
}

/** The primary label: a module reads as `:name`; everything else by its artifact/file name. */
private fun primaryName(node: UiDependencyNode): String =
    if (node.kind == UiDepKind.Module) ":${node.name}" else node.name

/** The dimmed subtitle: the Maven group, or a kind descriptor when there's no group. */
private fun depSubtitle(node: UiDependencyNode): String? = when {
    node.kind == UiDepKind.Module -> "module"
    node.kind == UiDepKind.Platform -> node.group.ifEmpty { "platform (BOM)" }
    node.local -> if (node.kind == UiDepKind.Aar) "local aar" else "local jar"
    node.group.isNotEmpty() -> node.group
    else -> null
}

private fun shortCoord(coord: String): String = coord.split(":").let { if (it.size >= 3) "${it[1]}:${it[2]}" else coord }

private fun plural(n: Int) = if (n == 1) "" else "s"
