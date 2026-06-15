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
import androidx.compose.foundation.layout.fillMaxHeight
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
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiDepKind
import dev.ide.ui.backend.UiDepModule
import dev.ide.ui.backend.UiDependencyNode
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.Chip
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
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

/** A transient confirmation/result toast. */
private data class ToastMsg(val text: String, val error: Boolean)

/** Width at/above which the screen uses the desktop two-pane layout (module list pane + content). */
private val DEPS_EXPANDED_BREAKPOINT = 860.dp

/**
 * The per-module dependency manager. Adaptive: a **two-pane desktop layout** (module list pane · content)
 * above [DEPS_EXPANDED_BREAKPOINT], a single column with module chips below it. List / Tree / Graph views
 * over the resolved picture; a live **download/resolution panel** while resolving; an Add flow (centered
 * dialog on desktop, bottom sheet on phone) that searches Maven Central and blocks incompatible artifacts;
 * a **remove confirmation**; and success/error **toasts**. Talks only to [IdeBackend].
 */
@Composable
fun DependenciesScreen(
    backend: IdeBackend,
    initialModule: String?,
    onBack: () -> Unit,
    codeFont: FontFamily = FontFamily.Monospace,
) {
    var modules by remember { mutableStateOf(backend.dependencyModules()) }
    var selected by remember { mutableStateOf(initialModule ?: modules.firstOrNull()?.name) }
    var view by remember { mutableStateOf(DepView.List) }
    var deps by remember { mutableStateOf<UiModuleDeps?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var addOpen by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<ToastMsg?>(null) }
    val resolveState by backend.depsState.collectAsState()

    LaunchedEffect(selected, reloadKey) {
        val module = selected ?: return@LaunchedEffect
        loading = true
        deps = runCatching { backend.moduleDependencies(module) }.getOrNull()
        loading = false
    }
    LaunchedEffect(reloadKey) { modules = backend.dependencyModules() }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    val onRemoveRequest: (String) -> Unit = { pendingRemove = it }
    val resolving = loading || resolveState.resolving

    BoxWithConstraints(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        val expanded = maxWidth >= DEPS_EXPANDED_BREAKPOINT
        Column(Modifier.fillMaxSize()) {
            DepsHeader(onBack, view, { view = it }, { addOpen = true }, resolving, resolveState.message)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))

            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    GlassSurface(Modifier.width(290.dp).fillMaxHeight(), GlassMaterial.Regular) {
                        ModulePane(modules, selected) { selected = it }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Ca.colors.separator))
                    DepBody(deps, loading, view, resolveState, codeFont, Modifier.weight(1f).fillMaxHeight(), onRemoveRequest)
                }
            } else {
                if (modules.size > 1) ModuleChips(modules, selected) { selected = it }
                DepBody(deps, loading, view, resolveState, codeFont, Modifier.weight(1f).fillMaxWidth(), onRemoveRequest)
            }
        }

        // ---- Add flow: a centered dialog on desktop, a bottom sheet on phone ----
        val onResult: (UiAddResult) -> Unit = { result ->
            if (result.success) { addOpen = false; reloadKey++; toast = ToastMsg(result.message, error = false) }
        }
        if (expanded) {
            DropdownOverlay(visible = addOpen, onDismiss = { addOpen = false }, topPadding = 64.dp) {
                Column(
                    Modifier.padding(horizontal = 12.dp).widthIn(max = 640.dp).fillMaxWidth()
                        .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                        .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)),
                ) {
                    AddDependencyContent(backend, selected, codeFont, onResult, Modifier.padding(20.dp).fillMaxWidth())
                }
            }
        } else {
            BottomSheet(visible = addOpen, onDismiss = { addOpen = false }, heightFraction = 0.82f) {
                AddDependencyContent(backend, selected, codeFont, onResult, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        // ---- remove confirmation ----
        ConfirmRemoveDialog(
            coordinate = pendingRemove,
            moduleName = selected,
            onDismiss = { pendingRemove = null },
            onConfirm = {
                val coord = pendingRemove; val module = selected
                if (coord != null && module != null && backend.removeDependency(module, coord)) {
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

// ---- header -------------------------------------------------------------------------------------

@Composable
private fun DepsHeader(
    onBack: () -> Unit,
    view: DepView,
    onView: (DepView) -> Unit,
    onAdd: () -> Unit,
    resolving: Boolean,
    resolveMessage: String,
) {
    GlassSurface(Modifier.fillMaxWidth(), GlassMaterial.Regular) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, "Back", onBack)
            Icon(CaIcons.layers, null, Modifier.size(20.dp), tint = Ca.colors.accent)
            Text("Dependencies", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
            AnimatedVisibility(resolving, enter = fadeIn(tween(Motion.FAST)), exit = fadeOut(tween(Motion.FAST))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 4.dp).background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
                    Text(resolveMessage.ifBlank { "Resolving…" }, color = Ca.colors.accent, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            ViewToggle(view, onView)
            Spacer(Modifier.width(4.dp))
            PrimaryButton("Add", onClick = onAdd, icon = CaIcons.plus)
        }
    }
}

// ---- module switchers ---------------------------------------------------------------------------

@Composable
private fun ModulePane(modules: List<UiDepModule>, selected: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("MODULES", color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            items(modules, key = { it.name }) { m -> ModuleRow(m, m.name == selected) { onSelect(m.name) } }
        }
    }
}

@Composable
private fun ModuleRow(module: UiDepModule, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ca.colors.accentSoft else Color.Transparent, tween(Motion.FAST), label = "moduleRowBg")
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .background(bg, RoundedCornerShape(Ca.radius.sm)).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (module.acceptsAar) CaIcons.androidLogo else CaIcons.layers, null, Modifier.size(18.dp),
            tint = if (selected) Ca.colors.accent else Ca.colors.textSecondary)
        Column(Modifier.weight(1f)) {
            Text(module.name, color = if (selected) Ca.colors.accent else Ca.colors.textPrimary, style = Ca.type.footnote,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${if (module.acceptsAar) "Android" else "Java/JVM"} · ${module.dependencyCount} dep${plural(module.dependencyCount)}",
                color = Ca.colors.textTertiary, style = Ca.type.caption2)
        }
    }
}

@Composable
private fun ModuleChips(modules: List<UiDepModule>, selected: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        modules.forEach { m -> ModuleChip(m.name, m.acceptsAar, m.name == selected) { onSelect(m.name) } }
    }
}

@Composable
private fun ModuleChip(name: String, acceptsAar: Boolean, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ca.colors.accentSoft else Ca.colors.surface2, tween(Motion.FAST), label = "moduleChipBg")
    Row(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (acceptsAar) CaIcons.androidLogo else CaIcons.layers, null, Modifier.size(14.dp),
            tint = if (selected) Ca.colors.accent else Ca.colors.textSecondary)
        Text(name, color = if (selected) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.footnote,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
                            onRemove = if (node.declared && node.kind != UiDepKind.Module) ({ onRemove(node.coordinate) }) else null)
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
            Text(coordLabel(node), color = Ca.colors.textPrimary, style = Ca.type.footnote.copy(fontFamily = codeFont),
                fontWeight = if (node.declared) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(coordLabel(node), color = if (node.compatible) Ca.colors.textPrimary else Ca.colors.error,
                style = Ca.type.footnote.copy(fontFamily = codeFont), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                !node.compatible && node.incompatibleReason != null ->
                    Text(node.incompatibleReason, color = Ca.colors.error, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                cycle -> Text("cycle — already shown above", color = Ca.colors.warning, style = Ca.type.caption2)
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
        Text(coordLabel(node), color = Ca.colors.textSecondary, style = Ca.type.caption.copy(fontFamily = codeFont),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("transitive", color = Ca.colors.textTertiary, style = Ca.type.caption2)
    }
}

// ---- Add dependency (shared by the desktop dialog + phone sheet) --------------------------------

@Composable
private fun AddDependencyContent(
    backend: IdeBackend,
    moduleName: String?,
    codeFont: FontFamily,
    onResult: (UiAddResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UiArtifactHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf("implementation") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    val resolveState by backend.depsState.collectAsState()
    val scopeOptions = listOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation")
    val coroutine = rememberCoroutineScope()

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2 || moduleName == null) { results = emptyList(); return@LaunchedEffect }
        searching = true
        delay(320)
        results = runCatching { backend.searchArtifacts(q, moduleName) }.getOrDefault(emptyList())
        searching = false
    }

    Column(modifier) {
        Text("Add dependency", color = Ca.colors.textPrimary, style = Ca.type.title3, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))

        // search field
        Row(
            Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(CaIcons.search, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search Maven Central — e.g. okhttp, com.google.guava…", color = Ca.colors.textTertiary, style = Ca.type.subhead)
                BasicTextField(query, { query = it; error = null }, singleLine = true, enabled = !busy,
                    textStyle = Ca.type.subhead.copy(color = Ca.colors.textPrimary, fontFamily = codeFont),
                    cursorBrush = SolidColor(Ca.colors.accent), modifier = Modifier.fillMaxWidth())
            }
            if (searching) CircularProgressIndicator(Modifier.size(14.dp), color = Ca.colors.textTertiary, strokeWidth = 2.dp)
        }

        // scope selector
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("scope", color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.padding(end = 4.dp))
            scopeOptions.forEach { s -> ScopeChip(s, s == scope) { if (!busy) scope = s } }
        }

        error?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Ca.colors.error.copy(alpha = 0.10f), RoundedCornerShape(Ca.radius.sm)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.warning, null, Modifier.size(15.dp), tint = Ca.colors.error)
                Text(msg, color = Ca.colors.error, style = Ca.type.caption)
            }
        }

        // While adding: a live download panel. Otherwise: the results list.
        Crossfade(targetState = busy, animationSpec = tween(Motion.BASE), label = "addBody") { isBusy ->
            if (isBusy) {
                Column(Modifier.fillMaxWidth().heightIn(min = 160.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(Modifier.size(28.dp), color = Ca.colors.accent, strokeWidth = 3.dp)
                    Text("Adding ${adding?.let(::shortCoord) ?: ""}", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
                    Text(resolveState.message.ifBlank { "Resolving transitive dependencies…" }, color = Ca.colors.textSecondary, style = Ca.type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ResolveBar(resolveState.fraction)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(results, key = { it.coordinate }) { hit ->
                        AddResultRow(hit, codeFont, Modifier.animateItem()) {
                            if (moduleName == null) return@AddResultRow
                            busy = true; error = null; adding = hit.coordinate
                            coroutine.launch {
                                val result = backend.addDependency(moduleName, hit.coordinate, scope)
                                busy = false; adding = null
                                if (result.success) onResult(result) else error = result.message
                            }
                        }
                    }
                    if (query.trim().length >= 2 && results.isEmpty() && !searching) item { EmptyRow("No results.") }
                    if (query.trim().length < 2) item { EmptyRow("Type at least 2 characters to search.") }
                }
            }
        }
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
private fun ViewToggle(view: DepView, onSelect: (DepView) -> Unit) {
    Row(Modifier.background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        DepView.entries.forEach { v -> SegItem(v.icon, v.label, v == view) { onSelect(v) } }
    }
}

@Composable
private fun SegItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Ca.colors.accentSoft else Color.Transparent, tween(Motion.FAST), label = "segBg")
    Row(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.xs)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(14.dp), tint = if (active) Ca.colors.accent else Ca.colors.textSecondary)
        Text(label, color = if (active) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
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

private fun coordLabel(node: UiDependencyNode): String = when {
    node.kind == UiDepKind.Module -> ":${node.name}"
    node.group.isEmpty() || node.version.isEmpty() -> node.name
    else -> "${node.group}:${node.name}:${node.version}"
}

private fun shortCoord(coord: String): String = coord.split(":").let { if (it.size >= 3) "${it[1]}:${it[2]}" else coord }

private fun plural(n: Int) = if (n == 1) "" else "s"
