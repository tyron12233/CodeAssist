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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.verticalScroll
import dev.ide.ui.backend.UiVersionConflict
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
import dev.ide.ui.components.CaDropdownMenu
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Top-level split: what the module DECLARES (the roots you added) vs the RESOLVED transitive closure. The
 *  Declared tab is the place a declared-but-unresolved dependency stays visible (with a red badge) instead of
 *  silently vanishing from the resolved graph. */
private enum class DepTab(val label: String, val icon: ImageVector) {
    Declared("Declared", CaIcons.resources), Resolved("Resolved", CaIcons.gitBranch)
}

/** Sub-views of the Resolved tab: the transitive closure as an expandable tree or a flat listing. */
private enum class DepView(val label: String, val icon: ImageVector) {
    Tree("Tree", CaIcons.layers), Graph("Graph", CaIcons.gitBranch)
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
 * header / back / tab chrome). A toolbar (Declared/Resolved tabs, a Tree/Graph sub-toggle on Resolved ·
 * Repositories · Add); a live **download/resolution panel** while resolving; an Add flow (centered dialog on desktop,
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
    var tab by remember { mutableStateOf(DepTab.Declared) }
    var resolvedView by remember { mutableStateOf(DepView.Tree) }
    var deps by remember { mutableStateOf<UiModuleDeps?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember(moduleName) { mutableStateOf(0) }
    var addOpen by remember { mutableStateOf(false) }
    var reposOpen by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    var pendingEdit by remember { mutableStateOf<UiDependencyNode?>(null) }
    var toast by remember { mutableStateOf<ToastMsg?>(null) }
    val resolveState by backend.deps.depsState.collectAsState()
    val coroutine = rememberCoroutineScope()

    LaunchedEffect(moduleName, reloadKey) {
        loading = true
        deps = runCatching { backend.deps.moduleDependencies(moduleName) }.getOrNull()
        loading = false
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    // Exclude a transitive dependency: append its group:name to the exclusions of the direct dependency it
    // came from, then re-resolve. Reuses the same exclusion mechanism as the per-dependency editor.
    val onExcludeTransitive: (UiDependencyNode, UiDependencyNode) -> Unit = { root, transitive ->
        coroutine.launch {
            val gn = "${transitive.group}:${transitive.name}"
            val result = backend.deps.setDependencyExclusions(moduleName, root.coordinate, (root.exclusions + gn).distinct())
            toast = ToastMsg(if (result.success) "Excluded $gn from ${root.name}" else result.message, error = !result.success)
            if (result.success) reloadKey++
        }
    }
    // Re-include a previously-excluded entry: drop it from the direct dependency's exclusions and re-resolve.
    val onRemoveExclusion: (UiDependencyNode, String) -> Unit = { root, excl ->
        coroutine.launch {
            val result = backend.deps.setDependencyExclusions(moduleName, root.coordinate, root.exclusions - excl)
            toast = ToastMsg(if (result.success) "Re-included $excl" else result.message, error = !result.success)
            if (result.success) reloadKey++
        }
    }

    val resolving = loading || resolveState.resolving
    BoxWithConstraints(modifier.fillMaxSize().background(Ca.colors.bg)) {
        val expanded = maxWidth >= DEPS_EXPANDED_BREAKPOINT
        Column(Modifier.fillMaxSize()) {
            DepPaneToolbar(tab, { tab = it }, resolvedView, { resolvedView = it }, { addOpen = true }, { reposOpen = true },
                { coroutine.launch { backend.deps.retryDependencyResolution(); reloadKey++ } }, resolving, resolveState.message, compact = !expanded)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            DepBody(deps, loading, tab, resolvedView, resolveState, codeFont, Modifier.weight(1f).fillMaxWidth(), { pendingRemove = it }, { pendingEdit = it }, onExcludeTransitive, onRemoveExclusion)
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
            // Opens near-full (the content is dense) and the sheet still drags up to true full screen; the
            // content fills the sheet (weight + fillHeight) so the results list uses the room, not empty space.
            BottomSheet(visible = addOpen, onDismiss = { addOpen = false }, heightFraction = 0.94f) {
                AddDependencyContent(backend, moduleName, codeFont, fileActions, onResult, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp), fillHeight = true)
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
                if (coord != null && backend.deps.removeDependency(moduleName, coord)) {
                    toast = ToastMsg("Removed ${shortCoord(coord)}", error = false)
                    reloadKey++
                }
                pendingRemove = null
            },
        )

        // ---- edit dependency (version · scope · exclusions) ----
        pendingEdit?.let { node ->
            EditDependencySheet(
                backend = backend,
                moduleName = moduleName,
                node = node,
                codeFont = codeFont,
                expanded = expanded,
                onDismiss = { pendingEdit = null },
                onSave = { version, scope, exclusions ->
                    coroutine.launch {
                        val result = backend.deps.updateDependency(moduleName, node.coordinate, version, scope, exclusions)
                        toast = ToastMsg(result.message, error = !result.success)
                        if (result.success) reloadKey++
                    }
                    pendingEdit = null
                },
            )
        }

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
    tab: DepTab,
    onTab: (DepTab) -> Unit,
    resolvedView: DepView,
    onView: (DepView) -> Unit,
    onAdd: () -> Unit,
    onRepos: () -> Unit,
    onResolve: () -> Unit,
    resolving: Boolean,
    resolveMessage: String,
    compact: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabToggle(tab, onTab, compact = compact)
        if (tab == DepTab.Resolved) ViewToggle(resolvedView, onView, compact = true)
        if (resolving) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
            if (!compact) Text(resolveMessage.ifBlank { "Resolving…" }, color = Ca.colors.accent, style = Ca.type.caption2,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.weight(1f))
        // Force a fresh resolve of the declared deps — clears the reconcile marker so resolver changes
        // (e.g. new variant/constraint handling) actually re-apply to a project whose deps are unchanged.
        IconButtonCa(CaIcons.refresh, "Re-resolve dependencies", onClick = { if (!resolving) onResolve() })
        IconButtonCa(CaIcons.pkg, "Repositories", onClick = onRepos)
        PrimaryButton("Add", onClick = onAdd, icon = CaIcons.plus, iconOnly = compact)
    }
}

// ---- repositories manager -----------------------------------------------------------------------

@Composable
private fun RepositoriesContent(backend: IdeBackend, codeFont: FontFamily, modifier: Modifier = Modifier) {
    var repos by remember { mutableStateOf(backend.deps.repositories()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val add = {
        if (backend.deps.addRepository(name, url)) { repos = backend.deps.repositories(); name = ""; url = ""; error = null }
        else error = "Enter a valid http(s) URL that isn't already added."
    }

    Column(modifier) {
        Text("Repositories", color = Ca.colors.textPrimary, style = Ca.type.title3, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Where libraries resolve from. Built-in repos can't be removed.", color = Ca.colors.textTertiary, style = Ca.type.caption)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(repos, key = { it.url }) { r -> RepoRow(r) { if (backend.deps.removeRepository(r.url)) repos = backend.deps.repositories() } }
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
    tab: DepTab,
    resolvedView: DepView,
    resolveState: DepsResolveState,
    codeFont: FontFamily,
    modifier: Modifier,
    onRemove: (String) -> Unit,
    onEdit: (UiDependencyNode) -> Unit,
    onExcludeTransitive: (root: UiDependencyNode, transitive: UiDependencyNode) -> Unit,
    onRemoveExclusion: (root: UiDependencyNode, exclusion: String) -> Unit,
) {
    Crossfade(targetState = loading, animationSpec = tween(Motion.BASE), label = "depBody", modifier = modifier) { isLoading ->
        when {
            isLoading -> ResolvingPanel(resolveState)
            deps == null -> Empty("Couldn't load dependencies.")
            // The persistent error state carries the (heuristic) why per coordinate — surface it here too.
            else -> DepContent(deps, tab, resolvedView, codeFont, resolveState.unresolved.associate { it.coordinate to it.reason }, onRemove, onEdit, onExcludeTransitive, onRemoveExclusion)
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
private fun DepContent(deps: UiModuleDeps, tab: DepTab, resolvedView: DepView, codeFont: FontFamily, reasons: Map<String, String>, onRemove: (String) -> Unit, onEdit: (UiDependencyNode) -> Unit, onExcludeTransitive: (root: UiDependencyNode, transitive: UiDependencyNode) -> Unit, onRemoveExclusion: (root: UiDependencyNode, exclusion: String) -> Unit) {
    val nodesByCoord = remember(deps) { deps.nodes.associateBy { it.coordinate } }
    val expanded = remember(deps) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val unresolvedSet = remember(deps) { deps.unresolved.toSet() }
    // Only a major-version clash (semver-incompatible) is flagged on a row; benign newest-wins differences
    // are counted in the summary, not painted on every node. Keyed by `group:name`.
    val realConflicts = remember(deps) { deps.conflicts.filter(::isRealConflict).associateBy { it.artifact } }
    fun conflictFor(node: UiDependencyNode): UiVersionConflict? = realConflicts["${node.group}:${node.name}"]

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        // A quiet, collapsible conflict summary: real (major-version) clashes are listed for review; benign
        // newest-wins differences are just counted (the per-row warning glyph flags the real ones in place).
        if (deps.conflicts.isNotEmpty()) item("conflicts") {
            ConflictSummaryBanner(deps.conflicts, realConflicts.keys, codeFont, Modifier.animateItem())
        }
        if (deps.cycles.isNotEmpty()) item("cycles") {
            BannerCard(CaIcons.refresh, Ca.colors.error, "${deps.cycles.size} dependency cycle${plural(deps.cycles.size)}", Modifier.animateItem()) {
                deps.cycles.forEach { cycle -> Text(cycle.joinToString(" → ") { it.substringBeforeLast(':') }, color = Ca.colors.textSecondary, style = Ca.type.caption.copy(fontFamily = codeFont)) }
            }
        }
        if (deps.unresolved.isNotEmpty()) item("unresolved") {
            BannerCard(CaIcons.error, Ca.colors.error, "${deps.unresolved.size} unresolved", Modifier.animateItem()) {
                deps.unresolved.forEach { coord ->
                    Text(coord, color = Ca.colors.textSecondary, style = Ca.type.caption.copy(fontFamily = codeFont))
                    reasons[coord]?.let { Text(it, color = Ca.colors.textTertiary, style = Ca.type.caption2) }
                }
            }
        }

        when (tab) {
            // What the module declares: the roots you added, each showing its resolved version + scope, or a
            // red "unresolved" badge when resolution couldn't satisfy it. Expand a row to peek at its
            // (resolved) transitive children.
            DepTab.Declared -> {
                if (deps.declared.isEmpty()) item("empty") { EmptyRow("No dependencies declared. Tap Add to download one.") }
                items(deps.declared, key = { "decl:${it.coordinate}" }) { node ->
                    val open = expanded["decl:${node.coordinate}"] == true
                    Column(Modifier.fillMaxWidth().animateItem()) {
                        DependencyRow(node, codeFont, depth = 0, expandable = node.children.isNotEmpty(), expanded = open,
                            onToggle = { expanded["decl:${node.coordinate}"] = !open },
                            onRemove = { onRemove(node.coordinate) }, unresolved = node.coordinate in unresolvedSet,
                            conflict = conflictFor(node),
                            onEdit = if (node.kind == UiDepKind.Jar || node.kind == UiDepKind.Aar) {
                                if (!node.local) ({ onEdit(node) }) else null
                            } else null)
                        AnimatedVisibility(open, enter = expandVertically(tween(Motion.FAST)) + fadeIn(), exit = shrinkVertically(tween(Motion.FAST)) + fadeOut()) {
                            Column {
                                // The transitive's overflow menu excludes it from this (declared) root.
                                val onExclude: ((UiDependencyNode) -> Unit)? = if (node.excludable()) ({ t -> onExcludeTransitive(node, t) }) else null
                                node.children.forEach { childCoord -> nodesByCoord[childCoord]?.let { TransitiveRow(it, codeFont, depth = 1, onExclude = onExclude) } }
                                // Excluded entries (group:name) shown as their own rows with an "excluded" pill,
                                // each re-includable via its overflow menu.
                                node.exclusions.forEach { excl -> ExcludedRow(excl, codeFont) { onRemoveExclusion(node, excl) } }
                            }
                        }
                    }
                }
            }
            // The resolved transitive closure (declared + everything pulled in), as a tree rooted at the
            // declared deps or a flat listing.
            DepTab.Resolved -> when (resolvedView) {
                DepView.Tree -> {
                    if (deps.declared.isEmpty()) item("empty") { EmptyRow("Nothing resolved yet.") }
                    deps.declared.forEach { root ->
                        treeRows(root, root, nodesByCoord, 0, emptyList(), expanded, codeFont, realConflicts, { onRemove(root.coordinate) }, onExcludeTransitive)
                    }
                }
                DepView.Graph -> {
                    if (deps.nodes.isEmpty()) item("empty") { EmptyRow("Nothing resolved yet.") }
                    val sorted = deps.nodes.sortedWith(compareByDescending<UiDependencyNode> { it.declared }.thenBy { it.coordinate })
                    items(sorted, key = { "graph:${it.coordinate}" }) { node -> Box(Modifier.animateItem()) { GraphRow(node, nodesByCoord, codeFont, conflictFor(node)) } }
                }
            }
        }
    }
}

private fun LazyListScope.treeRows(
    root: UiDependencyNode,
    node: UiDependencyNode,
    nodesByCoord: Map<String, UiDependencyNode>,
    depth: Int,
    ancestors: List<String>,
    expanded: SnapshotStateMap<String, Boolean>,
    codeFont: FontFamily,
    realConflicts: Map<String, UiVersionConflict>,
    onRemove: (() -> Unit)?,
    onExcludeTransitive: (root: UiDependencyNode, transitive: UiDependencyNode) -> Unit,
) {
    val key = (ancestors + node.coordinate).joinToString(">")
    val cycle = node.coordinate in ancestors
    val children = if (cycle) emptyList() else node.children.mapNotNull { nodesByCoord[it] }
    val isOpen = expanded[key] == true
    // A transitive (depth > 0) row can be excluded from its declared root; the root itself uses remove instead.
    val onExclude: (() -> Unit)? = if (depth > 0 && root.excludable()) ({ onExcludeTransitive(root, node) }) else null
    item(key) {
        Box(Modifier.animateItem()) {
            DependencyRow(node, codeFont, depth = depth, expandable = children.isNotEmpty(), expanded = isOpen,
                onToggle = { expanded[key] = !isOpen }, onRemove = onRemove, cycle = cycle,
                conflict = realConflicts["${node.group}:${node.name}"], onExclude = onExclude)
        }
    }
    if (isOpen) children.forEach { child -> treeRows(root, child, nodesByCoord, depth + 1, ancestors + node.coordinate, expanded, codeFont, realConflicts, null, onExcludeTransitive) }
}

@Composable
private fun GraphRow(node: UiDependencyNode, nodesByCoord: Map<String, UiDependencyNode>, codeFont: FontFamily, conflict: UiVersionConflict?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DepBadge(node)
            Box(Modifier.weight(1f, fill = false)) { Column { DepPrimary(node, codeFont, dimmed = !node.declared); DepSubtitle(node) } }
            if (node.declared) node.variant?.let { VariantBadge(it) }
            if (node.declared) node.scope?.takeIf { it != "platform" }?.let { ScopeBadge(it) }
            conflict?.let { ConflictBadge(it) }
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
    unresolved: Boolean = false,
    conflict: UiVersionConflict? = null,
    onEdit: (() -> Unit)? = null,
    onExclude: (() -> Unit)? = null,
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
            DepPrimary(node, codeFont, dimmed = unresolved)
            when {
                unresolved -> Text("not resolved — check the version/repository or re-resolve", color = Ca.colors.error, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                !node.compatible && node.incompatibleReason != null ->
                    Text(node.incompatibleReason, color = Ca.colors.error, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                cycle -> Text("cycle — already shown above", color = Ca.colors.warning, style = Ca.type.caption2)
                else -> DepSubtitle(node)
            }
        }
        node.variant?.let { VariantBadge(it) }
        node.scope?.takeIf { it != "platform" }?.let { ScopeBadge(it) }
        // No "excludes N" summary chip here — excluded entries show as their own rows (with an "excluded"
        // pill) when the dependency is expanded.
        if (unresolved) WithTooltip("Couldn't resolve — check the version/repository") { Icon(CaIcons.error, "Unresolved", Modifier.size(16.dp), tint = Ca.colors.error) }
        conflict?.let { ConflictBadge(it) }
        if (!node.compatible) Icon(CaIcons.warning, "Incompatible", Modifier.size(16.dp), tint = Ca.colors.error)
        if (onEdit != null) IconButtonCa(CaIcons.gear, "Edit ${node.name}", onClick = onEdit, boxSize = 28, iconSize = 16, tint = if (node.exclusions.isNotEmpty()) Ca.colors.accent else Ca.colors.textTertiary)
        if (onExclude != null) RowActionMenu("More actions for ${node.name}", "Exclude ${node.name}", CaIcons.close, onExclude)
        if (onRemove != null) IconButtonCa(CaIcons.close, "Remove ${node.name}", onClick = onRemove, boxSize = 28, iconSize = 16, tint = Ca.colors.textTertiary)
    }
}

@Composable
private fun TransitiveRow(node: UiDependencyNode, codeFont: FontFamily, depth: Int, onExclude: ((UiDependencyNode) -> Unit)? = null) {
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
        if (onExclude != null) RowActionMenu("More actions for ${node.name}", "Exclude ${node.name}", CaIcons.close) { onExclude(node) }
    }
}

/** A directly-declared Maven library (jar/aar) whose transitives can carry exclusions. */
private fun UiDependencyNode.excludable(): Boolean = (kind == UiDepKind.Jar || kind == UiDepKind.Aar) && !local

/** An excluded entry (group:name) under a declared dependency: dimmed, tagged "excluded", re-includable. */
@Composable
private fun ExcludedRow(exclusion: String, codeFont: FontFamily, onRemoveExclusion: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(start = (16 + 18 + 14).dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CaIcons.close, null, Modifier.size(13.dp), tint = Ca.colors.textTertiary)
        Text(exclusion, color = Ca.colors.textTertiary, style = Ca.type.subhead.copy(fontFamily = codeFont),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Chip("excluded", fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary)
        RowActionMenu("Options for excluded $exclusion", "Remove exclusion", CaIcons.plus, onRemoveExclusion)
    }
}

/** A row's overflow (⋮) menu with a single action item (e.g. "Exclude" / "Remove exclusion"). */
@Composable
private fun RowActionMenu(contentDesc: String, itemLabel: String, itemIcon: ImageVector, onClick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButtonCa(CaIcons.ellipsis, contentDesc, onClick = { open = true }, boxSize = 28, iconSize = 16, tint = Ca.colors.textTertiary)
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(itemLabel, color = Ca.colors.textPrimary, style = Ca.type.subhead) },
                leadingIcon = { Icon(itemIcon, null, Modifier.size(16.dp), tint = Ca.colors.textSecondary) },
                onClick = { open = false; onClick() },
            )
        }
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
    // True in the (mobile) bottom sheet: the results area fills the sheet height instead of capping at 360dp,
    // so a near-/full-screen sheet shows more results rather than empty space. False in the desktop dialog.
    fillHeight: Boolean = false,
) {
    var mode by remember { mutableStateOf(AddMode.Library) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UiArtifactHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf("implementation") }
    // The build variant this declaration is scoped to (null = shared / all variants → a plain `implementation`).
    var variant by remember { mutableStateOf<String?>(null) }
    var variants by remember { mutableStateOf<List<String>>(emptyList()) }
    var moduleTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var localCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    val resolveState by backend.deps.depsState.collectAsState()
    val scopeOptions = listOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation")
    val coroutine = rememberCoroutineScope()
    LaunchedEffect(moduleName) { variants = runCatching { backend.build.listVariants(moduleName) }.getOrDefault(emptyList()) }

    LaunchedEffect(query, mode) {
        val q = query.trim()
        if (q.length < 2 || mode == AddMode.Module || mode == AddMode.Local) { results = emptyList(); return@LaunchedEffect }
        searching = true
        delay(320)
        // distinctBy coordinate: the same GAV can come back from more than one repo; duplicate keys crash the list.
        results = runCatching { backend.deps.searchArtifacts(q, moduleName) }.getOrDefault(emptyList()).distinctBy { it.coordinate }
        searching = false
    }
    // Load candidate modules / project-local jars when their tab is selected.
    LaunchedEffect(mode) {
        if (mode == AddMode.Module) moduleTargets = runCatching { backend.deps.moduleDependencyTargets(moduleName) }.getOrDefault(emptyList())
        if (mode == AddMode.Local) localCandidates = runCatching { backend.deps.localLibraryCandidates(moduleName) }.getOrDefault(emptyList())
    }

    // Add a versioned library/AAR, a BOM platform, or (Module mode) a module-on-module dependency.
    val performAdd: (String) -> Unit = { coordinate ->
        busy = true; error = null; adding = coordinate
        coroutine.launch {
            val result = when (mode) {
                AddMode.Platform -> backend.deps.addPlatform(moduleName, coordinate, variant = variant)
                AddMode.Module -> backend.deps.addModuleDependency(moduleName, coordinate, scope, variant = variant)
                AddMode.Local -> backend.deps.addLocalLibrary(moduleName, coordinate, scope)
                AddMode.Library -> backend.deps.addDependency(moduleName, coordinate, scope, variant = variant)
            }
            busy = false; adding = null
            if (result.success) onResult(result) else error = result.message
        }
    }

    // Pick a jar/aar via the platform file picker; it's copied into the module's libs/ then attached.
    val pickLocalFile = {
        val dropDir = backend.deps.localLibraryDropDir(moduleName)
        if (dropDir != null) fileActions.importInto(dropDir) { imported ->
            if (imported.isNotEmpty()) {
                busy = true; error = null; adding = imported.first().substringAfterLast('/').substringAfterLast('\\')
                coroutine.launch {
                    var last: UiAddResult? = null
                    for (p in imported) { last = backend.deps.addLocalLibrary(moduleName, p, scope); if (last?.success != true) break }
                    busy = false; adding = null
                    last?.let { if (it.success) onResult(it) else error = it.message }
                }
            }
        }
    }

    Column(modifier) {
        Text("Add dependency", color = Ca.colors.textPrimary, style = Ca.type.title3, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))

        // Library / Platform (BOM) / Module / Local toggle — scrolls horizontally so chips never squish.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        // variant selector — library/module/platform deps on an Android module: scope the dependency to a
        // build variant (e.g. `debug` → `debugImplementation`). "All variants" (null) is the shared default.
        // (Local file libraries aren't variant-scoped.)
        if (mode != AddMode.Local && variants.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("variant", color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.padding(end = 4.dp))
            ScopeChip("All variants", variant == null) { if (!busy) variant = null }
            variants.forEach { v -> ScopeChip(v, v == variant) { if (!busy) variant = v } }
        }

        // Transitive exclusions aren't set here anymore — add the dependency, then exclude any transitive
        // from the dependency tree (its ⋮ menu) or the per-dependency "Edit exclusions" editor.

        // One-click quick-add for common Google libraries (Library mode). Firebase imports the BoM +
        // firebase-analytics (and reminds about google-services.json); Play Services adds the named artifact.
        // Each reuses the busy/error/result flow; the backend rejects them on a non-Android module.
        if (mode == AddMode.Library) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("suggested", color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.padding(end = 4.dp))
            val quickAdd: (String, suspend () -> UiAddResult) -> Unit = { label, action ->
                if (!busy) {
                    busy = true; error = null; adding = label
                    coroutine.launch {
                        val r = action(); busy = false; adding = null
                        if (r.success) onResult(r) else error = r.message
                    }
                }
            }
            ModeChip("Firebase", false) { quickAdd("Firebase") { backend.deps.addFirebase(moduleName) } }
            ModeChip("Play Services Auth", false) { quickAdd("Play Services Auth") { backend.deps.addGooglePlayServices(moduleName, listOf("com.google.android.gms:play-services-auth:21.2.0")) } }
            ModeChip("Maps", false) { quickAdd("Maps") { backend.deps.addGooglePlayServices(moduleName, listOf("com.google.android.gms:play-services-maps:19.0.0")) } }
            ModeChip("Location", false) { quickAdd("Location") { backend.deps.addGooglePlayServices(moduleName, listOf("com.google.android.gms:play-services-location:21.3.0")) } }
        }

        error?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Ca.colors.error.copy(alpha = 0.10f), RoundedCornerShape(Ca.radius.sm)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.warning, null, Modifier.size(15.dp), tint = Ca.colors.error)
                Text(msg, color = Ca.colors.error, style = Ca.type.caption)
            }
        }

        // The results area: fills the sheet height on mobile (fillHeight), or caps at 360dp in the dialog.
        val listModifier = if (fillHeight) Modifier.fillMaxWidth().fillMaxHeight() else Modifier.fillMaxWidth().heightIn(max = 360.dp)

        // While adding: a live download panel. Otherwise: the results / module list.
        Crossfade(targetState = busy, animationSpec = tween(Motion.BASE), label = "addBody",
            modifier = if (fillHeight) Modifier.weight(1f) else Modifier) { isBusy ->
            if (isBusy) {
                Column(Modifier.fillMaxWidth().heightIn(min = 160.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(Modifier.size(28.dp), color = Ca.colors.accent, strokeWidth = 3.dp)
                    Text("Adding ${adding?.let(::shortCoord) ?: ""}", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
                    Text(resolveState.message.ifBlank { "Resolving transitive dependencies…" }, color = Ca.colors.textSecondary, style = Ca.type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ResolveBar(resolveState.fraction)
                }
            } else if (mode == AddMode.Module) {
                LazyColumn(listModifier) {
                    if (moduleTargets.isEmpty()) item { EmptyRow("No other modules available to depend on.") }
                    items(moduleTargets, key = { it }) { target ->
                        ModuleTargetRow(target, Modifier.animateItem()) { performAdd(target) }
                    }
                }
            } else if (mode == AddMode.Local) {
                LocalLibraryBody(
                    candidates = localCandidates,
                    canPick = fileActions.canImport && backend.deps.localLibraryDropDir(moduleName) != null,
                    codeFont = codeFont,
                    onPick = pickLocalFile,
                    onAttach = { path -> performAdd(path) },
                )
            } else {
                val typed = query.trim()
                LazyColumn(listModifier) {
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

/**
 * Edit one declared library: change its **version** (picked from a live repository list, or typed),
 * its **scope**, and its transitive **exclusions**, applied in one re-resolve. Prefilled with the
 * current values; the version list streams in from the repositories (newest-first) with an
 * "update available" hint when a newer release exists. Saves via [onSave] (`version`, `scope`, exclusions).
 */
@Composable
private fun EditDependencySheet(
    backend: IdeBackend,
    moduleName: String,
    node: UiDependencyNode,
    codeFont: FontFamily,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSave: (version: String, scope: String, exclusions: List<String>) -> Unit,
) {
    var versionText by remember(node.coordinate) { mutableStateOf(node.version) }
    var scope by remember(node.coordinate) { mutableStateOf(node.scope ?: "implementation") }
    var exclText by remember(node.coordinate) { mutableStateOf(node.exclusions.joinToString(", ")) }
    var versions by remember(node.coordinate) { mutableStateOf<List<String>>(emptyList()) }
    var loadingVersions by remember(node.coordinate) { mutableStateOf(true) }
    LaunchedEffect(node.coordinate) {
        loadingVersions = true
        versions = runCatching { backend.deps.availableVersions(moduleName, node.coordinate) }.getOrDefault(emptyList())
        loadingVersions = false
    }
    // The list is newest-first, so a newer release exists when the current version isn't at the top of it.
    val newest = versions.firstOrNull()
    val updateAvailable = newest != null && node.version in versions && newest != node.version
    val scopeOptions = listOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation")

    val card: @Composable () -> Unit = {
        Column(
            Modifier.padding(horizontal = if (expanded) 12.dp else 0.dp).widthIn(max = 540.dp).fillMaxWidth()
                .then(if (expanded) Modifier.background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl)).border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)) else Modifier)
                .padding(if (expanded) 20.dp else 4.dp),
        ) {
            Text("Edit dependency", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(shortCoord(node.coordinate), color = Ca.colors.textSecondary, style = Ca.type.caption.copy(fontFamily = codeFont))

            // ---- version ----
            SheetSection("Version")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { SheetField(versionText, "version", codeFont, leading = CaIcons.pkg) { versionText = it } }
                if (loadingVersions) CircularProgressIndicator(Modifier.size(16.dp), color = Ca.colors.textTertiary, strokeWidth = 2.dp)
                else if (updateAvailable && newest != null) UpdateHintChip(newest) { versionText = newest }
            }
            VersionList(versions, selected = versionText, loading = loadingVersions, codeFont = codeFont) { versionText = it }

            // ---- scope ----
            SheetSection("Scope")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                scopeOptions.forEach { s -> ScopeChip(s, s == scope) { scope = s } }
            }

            // ---- exclusions ----
            SheetSection("Exclusions")
            Text("Transitive group:name entries to drop (either side may be *), comma/space separated.",
                color = Ca.colors.textTertiary, style = Ca.type.caption2)
            Spacer(Modifier.height(8.dp))
            SheetField(exclText, "e.g. com.google.guava:guava, org.json:*", codeFont, leading = CaIcons.close, singleLine = false) { exclText = it }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                DialogButton("Cancel", destructive = false, onClick = onDismiss)
                DialogButton("Save", destructive = false, onClick = {
                    onSave(versionText.trim(), scope, exclText.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() })
                })
            }
        }
    }
    if (expanded) DropdownOverlay(visible = true, onDismiss = onDismiss, topPadding = 80.dp) { card() }
    else BottomSheet(visible = true, onDismiss = onDismiss, heightFraction = 0.9f) { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { card() } }
}

/** A small section header inside the edit sheet. */
@Composable
private fun SheetSection(label: String) {
    Spacer(Modifier.height(14.dp))
    Text(label.uppercase(), color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

/** A boxed text field with a leading icon, matching the sheet's other inputs. */
@Composable
private fun SheetField(value: String, hint: String, codeFont: FontFamily, leading: ImageVector, singleLine: Boolean = true, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(leading, null, Modifier.size(16.dp), tint = Ca.colors.textTertiary)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Ca.colors.textTertiary, style = Ca.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = singleLine,
                textStyle = Ca.type.caption.copy(color = Ca.colors.textPrimary, fontFamily = codeFont),
                cursorBrush = SolidColor(Ca.colors.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The "↑ newest X" affordance shown when a newer release than the current version is published. */
@Composable
private fun UpdateHintChip(newest: String, onClick: () -> Unit) {
    Row(
        Modifier.background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(CaIcons.chevronUp, null, Modifier.size(13.dp), tint = Ca.colors.accent)
        Text(newest, color = Ca.colors.accent, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The scrollable list of published versions (newest-first); the current selection carries a check. */
@Composable
private fun VersionList(versions: List<String>, selected: String, loading: Boolean, codeFont: FontFamily, onSelect: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    when {
        loading -> Text("Loading versions…", color = Ca.colors.textTertiary, style = Ca.type.caption2, modifier = Modifier.padding(vertical = 6.dp))
        versions.isEmpty() -> Text("Couldn't load versions — type one above.", color = Ca.colors.textTertiary, style = Ca.type.caption2, modifier = Modifier.padding(vertical = 6.dp))
        else -> Column(
            Modifier.fillMaxWidth().heightIn(max = 188.dp).verticalScroll(rememberScrollState())
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)),
        ) {
            versions.forEach { v ->
                val isSel = v == selected
                Row(
                    Modifier.fillMaxWidth().height(34.dp).clickable(remember(v) { MutableInteractionSource() }, null) { onSelect(v) }
                        .background(if (isSel) Ca.colors.accentSoft else Color.Transparent)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(v, color = if (isSel) Ca.colors.accent else Ca.colors.textPrimary,
                        style = Ca.type.caption.copy(fontFamily = codeFont), fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isSel) Icon(CaIcons.check, "Selected", Modifier.size(15.dp), tint = Ca.colors.accent)
                }
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
private fun TabToggle(tab: DepTab, onSelect: (DepTab) -> Unit, compact: Boolean = false) {
    Row(Modifier.background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        DepTab.entries.forEach { t -> SegItem(t.icon, t.label, t == tab, compact) { onSelect(t) } }
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

// ---- scope + conflict indicators (compact, icon-first; full text on hover/long-press) -----------

/** The compact representation of a dependency scope: a single letter + the full name (for the tooltip). */
private enum class ScopeStyle(val letter: String, val full: String) {
    IMPLEMENTATION("I", "implementation"), API("A", "api"), COMPILE_ONLY("C", "compileOnly"),
    RUNTIME_ONLY("R", "runtimeOnly"), TEST("T", "testImplementation"), OTHER("·", "")
}

private fun scopeStyle(scope: String): ScopeStyle = when (scope.lowercase().replace("_", "").replace("-", "")) {
    "api" -> ScopeStyle.API
    "compileonly" -> ScopeStyle.COMPILE_ONLY
    "runtimeonly" -> ScopeStyle.RUNTIME_ONLY
    "testimplementation", "test" -> ScopeStyle.TEST
    "implementation" -> ScopeStyle.IMPLEMENTATION
    else -> ScopeStyle.OTHER
}

/** A scope shown as a color-coded letter badge (`I`/`A`/`C`/`R`/`T`) — the full name is the tooltip. Round
 *  (vs. the square kind badge) so the two never read as the same thing on one row. */
@Composable
private fun ScopeBadge(scope: String) {
    val style = scopeStyle(scope)
    val color = when (style) {
        ScopeStyle.API -> Ca.colors.run                 // exported (api) — like a module's compile surface
        ScopeStyle.IMPLEMENTATION -> Ca.colors.accent
        ScopeStyle.COMPILE_ONLY -> Ca.colors.info
        ScopeStyle.RUNTIME_ONLY -> Ca.colors.warning
        ScopeStyle.TEST -> Ca.colors.textTertiary
        ScopeStyle.OTHER -> Ca.colors.textTertiary
    }
    WithTooltip(style.full.ifEmpty { scope }) {
        Box(Modifier.size(18.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(Ca.radius.pill)), contentAlignment = Alignment.Center) {
            Text(style.letter, color = color, style = Ca.type.caption2, fontWeight = FontWeight.Bold)
        }
    }
}

/** A small pill marking a declared dependency as scoped to one build variant (e.g. `debug`). */
@Composable
private fun VariantBadge(variant: String) {
    WithTooltip("Only in the '$variant' variant") {
        Box(
            Modifier.background(Ca.colors.textTertiary.copy(alpha = 0.14f), RoundedCornerShape(Ca.radius.pill))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(variant, color = Ca.colors.textSecondary, style = Ca.type.caption2, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** A conflict that's worth flagging on the row: a warning glyph; the requested→chosen detail is the tooltip. */
@Composable
private fun ConflictBadge(conflict: UiVersionConflict) {
    WithTooltip("Version conflict: ${conflict.requested.joinToString(" vs ")} → using ${conflict.chosen}") {
        Icon(CaIcons.warning, "Version conflict", Modifier.size(16.dp), tint = Ca.colors.warning)
    }
}

/** A version conflict worth surfacing per-row: the requested versions span more than one MAJOR version
 *  (semver-incompatible). A pure patch/minor difference is resolved newest-wins with no risk, so it's only
 *  counted in the summary, never painted on the row. */
private fun isRealConflict(c: UiVersionConflict): Boolean =
    c.requested.mapNotNull(::majorOf).toSet().size > 1

/** The leading numeric (major) component of a Maven version, or null when it doesn't start with a number. */
private fun majorOf(v: String): Int? =
    v.trimStart('v', 'V', '[', '(', ' ').takeWhile { it.isDigit() }.toIntOrNull()

/** Wrap [content] with a hover (desktop) / long-press (touch) tooltip showing [text]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text, style = Ca.type.caption2) } },
        state = rememberTooltipState(),
    ) { content() }
}

/**
 * A quiet, collapsible summary of version conflicts. Real (major-version) clashes are listed for review
 * and the banner opens by default; when every clash is a benign newest-wins difference it stays a single
 * muted, collapsed line so it never floods the list. [realArtifacts] are the `group:name`s of real clashes.
 */
@Composable
private fun ConflictSummaryBanner(conflicts: List<UiVersionConflict>, realArtifacts: Set<String>, codeFont: FontFamily, modifier: Modifier = Modifier) {
    val real = conflicts.filter { it.artifact in realArtifacts }
    val benign = conflicts.filterNot { it.artifact in realArtifacts }
    var open by remember(conflicts) { mutableStateOf(real.isNotEmpty()) }
    val color = if (real.isNotEmpty()) Ca.colors.warning else Ca.colors.textTertiary
    val title = if (real.isNotEmpty()) "${real.size} version conflict${plural(real.size)} to review"
        else "${benign.size} version${plural(benign.size)} auto-resolved (newest wins)"
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(Ca.radius.md))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(Ca.radius.md)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (real.isNotEmpty()) CaIcons.warning else CaIcons.info, null, Modifier.size(16.dp), tint = color)
            Text(title, color = color, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (real.isNotEmpty() && benign.isNotEmpty()) Text("+${benign.size} auto-resolved", color = Ca.colors.textTertiary, style = Ca.type.caption2)
            Icon(if (open) CaIcons.chevronUp else CaIcons.chevronDown, null, Modifier.size(16.dp), tint = Ca.colors.textTertiary)
        }
        AnimatedVisibility(open, enter = expandVertically(tween(Motion.FAST)) + fadeIn(), exit = shrinkVertically(tween(Motion.FAST)) + fadeOut()) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                real.forEach { ConflictLine(it, codeFont, highlight = true) }
                benign.forEach { ConflictLine(it, codeFont, highlight = false) }
            }
        }
    }
}

@Composable
private fun ConflictLine(c: UiVersionConflict, codeFont: FontFamily, highlight: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (highlight) Icon(CaIcons.warning, null, Modifier.size(12.dp).padding(top = 2.dp), tint = Ca.colors.warning)
        Column {
            Text(c.artifact, color = Ca.colors.textSecondary, style = Ca.type.caption.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.requested.joinToString(", ")} → ${c.chosen}", color = if (highlight) Ca.colors.warning else Ca.colors.textTertiary,
                style = Ca.type.caption2.copy(fontFamily = codeFont), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
        Text(version, color = Ca.colors.textSecondary, style = Ca.type.caption2.copy(fontFamily = codeFont),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
