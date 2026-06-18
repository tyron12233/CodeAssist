package dev.ide.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.icons.resolveTint
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca

/**
 * File navigator content: header + a module → source folder → package → file tree (caret toggles).
 * Surface-agnostic — it draws no background of its own so it reads correctly either as a persistent
 * pane (wrapped in a regular-glass [GlassSurface]) or as the content of a glass-thick [BottomSheet]
 * on phone. The caller sizes it via [modifier] (e.g. `fillMaxSize()` in a pane, `weight(1f)` in a
 * sheet).
 *
 * Icons come from the extensible [TreeIcons] registry keyed by [TreeNode.iconId]. Any directory row reveals
 * a `+` on hover (desktop) that creates a file there via [onNewFile]; on touch (and right-click) the row's
 * long-press menu carries a context-aware **New ▸** submenu — New File ([onNewFile]), New Directory
 * ([onNewFolder]), New Java Class / Kotlin File ([onNewSource], on Java/Kotlin source roots & packages), and
 * New Resource File ([onNewResource], on `res/`) — so you can create anything anywhere. The header carries the IntelliJ-style scope
 * dropdown (Project ⇄ All files, [mode]/[onModeChange]) and an overflow `⋮` menu with tree-wide actions
 * (New file/folder at the workspace root, Expand/Collapse all, Sort by name/type).
 */
@Composable
fun FileNavigator(
    root: TreeNode,
    moduleCount: Int,
    activePath: String?,
    onOpen: (TreeNode) -> Unit,
    modifier: Modifier = Modifier,
    /** Create a new file in the given directory (smart-scaffolded by extension; the name may be a nested path). */
    onNewFile: (dirPath: String) -> Unit = {},
    /** Create a new folder in the given directory (the name may be a nested path). */
    onNewFolder: (dirPath: String) -> Unit = {},
    /** Create a new Android XML resource for a `res/` node (the templated dialog). */
    onNewResource: (TreeNode) -> Unit = {},
    /** Create a new typed source file (Java class / Kotlin file) in the given directory. */
    onNewSource: (dirPath: String, lang: NewSourceLang) -> Unit = { _, _ -> },
    onViewDependencies: (TreeNode) -> Unit = {},
    onConfigureModule: (TreeNode) -> Unit = {},
    /** Open the Add-Source-Root dialog for a module node. */
    onAddSourceRoot: (TreeNode) -> Unit = {},
    canImport: Boolean = false,
    onImport: () -> Unit = {},
    canShare: Boolean = false,
    onShare: (TreeNode) -> Unit = {},
    canModify: Boolean = false,
    onRename: (TreeNode) -> Unit = {},
    onMove: (TreeNode) -> Unit = {},
    onCopy: (TreeNode) -> Unit = {},
    onDelete: (TreeNode) -> Unit = {},
    canReveal: Boolean = false,
    onReveal: (TreeNode) -> Unit = {},
    /** Open the whole project folder in the system file manager (the DocumentsProvider root). Null hides the button. */
    onOpenInFiles: (() -> Unit)? = null,
    mode: TreeViewMode = TreeViewMode.Project,
    onModeChange: (TreeViewMode) -> Unit = {},
) {
    // The tree is rebuilt with new node ids when the view mode flips, so re-seed the default-expanded set.
    val expanded = remember(mode) {
        mutableSetExpandedDefaults(root)
    }
    var sort by remember { mutableStateOf(TreeSort.Name) }
    val ctx = FileRowActions(canModify, onRename, onMove, onCopy, onDelete, canReveal, onReveal)
    Column(modifier) {
        // header — project identity + the IntelliJ-style scope dropdown, with import + an overflow ⋮ menu.
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProjectTile(root.name, size = 32.dp, radius = Ca.radius.sm)
            Column(Modifier.weight(1f)) {
                Text(root.name, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$moduleCount ${if (moduleCount == 1) "module" else "modules"}", color = Ca.colors.textTertiary, style = Ca.type.caption2)
            }
            if (onOpenInFiles != null) IconButtonCa(CaIcons.folderOpen, "Open in file manager", onClick = onOpenInFiles, boxSize = 34, iconSize = 18)
            if (canImport) IconButtonCa(CaIcons.download, "Import files", onClick = onImport, boxSize = 34, iconSize = 18)
            val rootDir = root.dirPath
            HeaderOverflowMenu(
                onNewFile = { rootDir?.let(onNewFile) },
                onNewFolder = { rootDir?.let(onNewFolder) },
                onExpandAll = { expandAll(root, expanded) },
                onCollapseAll = { expanded.clear() },
                sort = sort,
                onSort = { sort = it },
            )
        }
        // The scope selector (Project ⇄ All files) — a dropdown button, like IntelliJ's view chooser.
        ScopeDropdown(mode, onModeChange, Modifier.padding(start = 12.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            root.children.sortedForTree(sort).forEach { TreeRow(it, 0, expanded, sort, activePath, onOpen, onNewFile, onNewFolder, onNewResource, onNewSource, onViewDependencies, onConfigureModule, onAddSourceRoot, canShare, onShare, ctx) }
        }
    }
}

/** The directory a New action targets for this node: a directory node's own dir, or a file's parent. */
private fun TreeNode.newTargetDir(): String? =
    dirPath ?: filePath?.substringBeforeLast('/')?.takeIf { it.isNotEmpty() && it != filePath }

/** Recursively mark every node with children as expanded (the "Expand all" action). */
private fun expandAll(root: TreeNode, expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>) {
    fun walk(n: TreeNode) {
        if (n.children.isNotEmpty()) expanded[n.id] = true
        n.children.forEach(::walk)
    }
    root.children.forEach(::walk)
}

/** How the tree orders siblings: by name (folders first, alphabetical — the backend default) or grouped by file type. */
enum class TreeSort { Name, Type }

/** Apply the chosen [sort] to a sibling list. Name keeps the backend order (folders first, alpha); Type groups
 *  folders first, then files bucketed by extension. */
private fun List<TreeNode>.sortedForTree(sort: TreeSort): List<TreeNode> = when (sort) {
    TreeSort.Name -> this
    TreeSort.Type -> sortedWith(
        compareBy(
            { if (it.kind == NodeKind.File) 1 else 0 },
            { if (it.kind == NodeKind.File) it.name.substringAfterLast('.', "").lowercase() else "" },
            { it.name.lowercase() },
        ),
    )
}

/** The delete/rename/move/copy callbacks the per-row context menu invokes (bundled to avoid threading five
 *  parameters through the recursive [TreeRow]). [enabled] gates whether the menu is offered at all. */
private class FileRowActions(
    val enabled: Boolean,
    val onRename: (TreeNode) -> Unit,
    val onMove: (TreeNode) -> Unit,
    val onCopy: (TreeNode) -> Unit,
    val onDelete: (TreeNode) -> Unit,
    val canReveal: Boolean,
    val onReveal: (TreeNode) -> Unit,
)

/** The label shown in the scope dropdown for a view mode. */
private fun TreeViewMode.label(): String = when (this) {
    TreeViewMode.Project -> "Project"
    TreeViewMode.AllFiles -> "All files"
}

/** IntelliJ-style scope chooser: a small button showing the current view, opening a menu to switch. */
@Composable
private fun ScopeDropdown(mode: TreeViewMode, onModeChange: (TreeViewMode) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        Row(
            Modifier
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .clickable(interaction, indication = null) { open = true }
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(mode.label(), color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
            Icon(CaIcons.chevronDown, "Change view", Modifier.size(15.dp), tint = Ca.colors.textTertiary)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TreeViewMode.entries.forEach { m ->
                CheckableMenuItem(m.label(), checked = m == mode) { open = false; onModeChange(m) }
            }
        }
    }
}

/** The header's overflow (⋮) menu: tree-wide actions that don't warrant a permanent button. */
@Composable
private fun HeaderOverflowMenu(
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    sort: TreeSort,
    onSort: (TreeSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButtonCa(CaIcons.ellipsis, "More actions", onClick = { open = true }, boxSize = 34, iconSize = 18)
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FileActionItem(CaIcons.plus, "New file…") { open = false; onNewFile() }
            FileActionItem(CaIcons.folder, "New folder…") { open = false; onNewFolder() }
            FileActionItem(CaIcons.chevronDown, "Expand all") { open = false; onExpandAll() }
            FileActionItem(CaIcons.chevronUp, "Collapse all") { open = false; onCollapseAll() }
            Box(Modifier.fillMaxWidth().height(1.dp).padding(vertical = 4.dp).background(Ca.colors.separator))
            MenuSectionLabel("Sort by")
            CheckableMenuItem("Name", checked = sort == TreeSort.Name) { open = false; onSort(TreeSort.Name) }
            CheckableMenuItem("Type", checked = sort == TreeSort.Type) { open = false; onSort(TreeSort.Type) }
        }
    }
}

/** A small muted header inside a dropdown (e.g. "Sort by"). */
@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        color = Ca.colors.textTertiary,
        style = Ca.type.caption2,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
    )
}

/** A dropdown item with a trailing check when [checked] (radio/toggle semantics). */
@Composable
private fun CheckableMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = Ca.colors.textPrimary, style = Ca.type.footnote) },
        trailingIcon = { if (checked) Icon(CaIcons.check, null, Modifier.size(15.dp), tint = Ca.colors.accent) },
        onClick = onClick,
    )
}


private fun mutableSetExpandedDefaults(root: TreeNode): androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean> {
    val map = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    fun walk(n: TreeNode) {
        if (n.kind == NodeKind.Module || n.kind == NodeKind.SourceRoot || n.kind == NodeKind.Workspace) map[n.id] = true
        n.children.forEach(::walk)
    }
    walk(root)
    return map
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    node: TreeNode,
    depth: Int,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    sort: TreeSort,
    activePath: String?,
    onOpen: (TreeNode) -> Unit,
    onNewFile: (dirPath: String) -> Unit,
    onNewFolder: (dirPath: String) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewSource: (dirPath: String, lang: NewSourceLang) -> Unit,
    onViewDependencies: (TreeNode) -> Unit,
    onConfigureModule: (TreeNode) -> Unit,
    onAddSourceRoot: (TreeNode) -> Unit,
    canShare: Boolean = false,
    onShare: (TreeNode) -> Unit = {},
    ctx: FileRowActions,
) {
    val isExpandable = node.children.isNotEmpty()
    val isOpen = expanded[node.id] == true
    val isActive = node.filePath != null && node.filePath == activePath
    // New File / New Folder can target ANY directory (or a file's parent) — create anything, anywhere.
    val targetDir = node.newTargetDir()
    // Android res nodes additionally offer the templated "New resource…" flow.
    val canNewResource = node.resDirPath != null
    // A Java/Kotlin source root or package additionally offers typed "New Java class / Kotlin file".
    val isSourceContext = targetDir != null && node.sourceRootPath != null
    // The "New ▸" submenu appears whenever there's anything to create here.
    val canNew = targetDir != null || canNewResource
    // Long-press / right-click opens delete/rename/move/copy for files, packages, and res/ folders.
    val canContext = ctx.enabled && node.fileOpPath() != null &&
        (node.kind == NodeKind.File || node.kind == NodeKind.Package || node.kind == NodeKind.Folder)
    // The menu is the only touch path to these (the hover `+`/gear/layers are invisible on a phone): New
    // File/Folder for any dir, New resource for res/, module settings/deps, reveal, and the file ops.
    val canModuleMenu = node.kind == NodeKind.Module
    val canRevealHere = ctx.canReveal && node.fileOpPath() != null
    val hasMenu = canNew || canContext || canModuleMenu || canRevealHere
    var menuOpen by remember { mutableStateOf(false) }
    // The context menu is two-level: the root actions, and a "New ▸" page of context-aware create options.
    var inNewPage by remember(menuOpen) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Taller, finger-friendly rows on touch; denser on desktop where pointer precision is higher.
    val rowHeight = if (isMobilePlatform) 40.dp else 30.dp
    Box {
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (isActive) Ca.colors.accentSoft else Color.Transparent)
            .hoverable(interaction)
            .combinedClickable(
                onClick = {
                    when {
                        // A module.toml (filePath set + moduleConfigName) opens the Module Settings editor.
                        node.filePath != null && node.moduleConfigName != null -> onConfigureModule(node)
                        node.filePath != null -> onOpen(node)
                        else -> expanded[node.id] = !isOpen
                    }
                },
                onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
            )
            .padding(start = (8 + depth * 16).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isExpandable) {
            Icon(
                if (isOpen) CaIcons.caretDown else CaIcons.caretRight,
                null,
                Modifier.size(14.dp),
                tint = Ca.colors.textTertiary,
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(18.dp))
        }
        NodeIcon(node, isOpen)
        Spacer(Modifier.width(8.dp))
        Text(
            node.name,
            color = if (isActive) Ca.colors.accent else Ca.colors.textPrimary,
            style = Ca.type.footnote,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            node.kind == NodeKind.Module && hovered ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButtonCa(CaIcons.gear, "Settings of ${node.name}", onClick = { onConfigureModule(node) }, boxSize = 22, iconSize = 14)
                    IconButtonCa(CaIcons.layers, "Dependencies of ${node.name}", onClick = { onViewDependencies(node) }, boxSize = 22, iconSize = 14)
                }
            canNew && hovered ->
                NewHoverButton(node, targetDir, isSourceContext, canNewResource, onNewFile, onNewFolder, onNewResource, onNewSource)
            // Share is shown for the active file (touch path) or any file on hover (desktop).
            node.filePath != null && canShare && (hovered || isActive) ->
                IconButtonCa(CaIcons.share, "Share ${node.name}", onClick = { onShare(node) }, boxSize = 22, iconSize = 14)
            node.gitStatus != null ->
                Box(Modifier.size(6.dp).background(gitColor(node.gitStatus), RoundedCornerShape(Ca.radius.pill)))
        }
    }
        if (hasMenu) CaDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            if (inNewPage) {
                // The "New ▸" submenu page: context-aware create options, with a way back to the root menu.
                FileActionItem(CaIcons.chevronLeft, "Back") { inNewPage = false }
                MenuDivider()
                NewActionItems(node, targetDir, isSourceContext, canNewResource, onNewFile, onNewFolder, onNewResource, onNewSource) { menuOpen = false }
            } else {
                if (canModuleMenu) {
                    FileActionItem(CaIcons.gear, "Module settings…") { menuOpen = false; onConfigureModule(node) }
                    FileActionItem(CaIcons.layers, "Dependencies…") { menuOpen = false; onViewDependencies(node) }
                    FileActionItem(CaIcons.plus, "Add source root…") { menuOpen = false; onAddSourceRoot(node) }
                }
                if (canNew) FileActionItem(CaIcons.plus, "New", trailing = CaIcons.caretRight) { inNewPage = true }
                if (canRevealHere) FileActionItem(CaIcons.share, "Reveal in file manager") { menuOpen = false; ctx.onReveal(node) }
                if (canContext) {
                    FileActionItem(CaIcons.docText, "Rename…") { menuOpen = false; ctx.onRename(node) }
                    FileActionItem(CaIcons.arrowRight, "Move…") { menuOpen = false; ctx.onMove(node) }
                    FileActionItem(CaIcons.copy, "Copy…") { menuOpen = false; ctx.onCopy(node) }
                    FileActionItem(CaIcons.close, "Delete", danger = true) { menuOpen = false; ctx.onDelete(node) }
                }
            }
        }
    }

    if (isOpen) node.children.sortedForTree(sort).forEach { TreeRow(it, depth + 1, expanded, sort, activePath, onOpen, onNewFile, onNewFolder, onNewResource, onNewSource, onViewDependencies, onConfigureModule, onAddSourceRoot, canShare, onShare, ctx) }
}

@Composable
private fun FileActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val tint = if (danger) Ca.colors.error else Ca.colors.textSecondary
    DropdownMenuItem(
        text = { Text(label, color = if (danger) Ca.colors.error else Ca.colors.textPrimary, style = Ca.type.footnote) },
        leadingIcon = { Icon(icon, null, Modifier.size(15.dp), tint = tint) },
        trailingIcon = trailing?.let { { Icon(it, null, Modifier.size(15.dp), tint = Ca.colors.textTertiary) } },
        onClick = onClick,
    )
}

/** A thin separator inside a dropdown menu. */
@Composable
private fun MenuDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).padding(vertical = 4.dp).background(Ca.colors.separator))
}

/** The context-aware create options, shared by the long-press "New ▸" page and the desktop hover `+` menu:
 *  typed Java/Kotlin source files (in a source context), an Android resource (in `res/`), and a plain
 *  File/Directory. [close] dismisses the hosting menu after an action fires. */
@Composable
private fun NewActionItems(
    node: TreeNode,
    targetDir: String?,
    isSourceContext: Boolean,
    canNewResource: Boolean,
    onNewFile: (dirPath: String) -> Unit,
    onNewFolder: (dirPath: String) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewSource: (dirPath: String, lang: NewSourceLang) -> Unit,
    close: () -> Unit,
) {
    if (isSourceContext && targetDir != null) {
        FileActionItem(CaIcons.code, "Java Class…") { close(); onNewSource(targetDir, NewSourceLang.Java) }
        FileActionItem(CaIcons.code, "Kotlin File…") { close(); onNewSource(targetDir, NewSourceLang.Kotlin) }
    }
    if (canNewResource) FileActionItem(CaIcons.image, "Resource File…") { close(); onNewResource(node) }
    if (targetDir != null) {
        FileActionItem(CaIcons.plus, "File…") { close(); onNewFile(targetDir) }
        FileActionItem(CaIcons.folder, "Directory…") { close(); onNewFolder(targetDir) }
    }
}

/** The desktop hover `+` affordance: a button that drops the same context-aware [NewActionItems] menu, so
 *  the typed Java/Kotlin/resource templates are one click away (not just behind the long-press menu). */
@Composable
private fun NewHoverButton(
    node: TreeNode,
    targetDir: String?,
    isSourceContext: Boolean,
    canNewResource: Boolean,
    onNewFile: (dirPath: String) -> Unit,
    onNewFolder: (dirPath: String) -> Unit,
    onNewResource: (TreeNode) -> Unit,
    onNewSource: (dirPath: String, lang: NewSourceLang) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButtonCa(CaIcons.plus, "New…", onClick = { open = true }, boxSize = 22, iconSize = 14)
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NewActionItems(node, targetDir, isSourceContext, canNewResource, onNewFile, onNewFolder, onNewResource, onNewSource) { open = false }
        }
    }
}

@Composable
private fun NodeIcon(node: TreeNode, open: Boolean) {
    when (val ic = TreeIcons.resolve(node.iconId)) {
        is TreeIcon.Glyph -> Icon(ic.image, null, Modifier.size(17.dp), tint = resolveTint(ic.tint))
        is TreeIcon.Folder -> Icon(if (open) ic.open else ic.closed, null, Modifier.size(17.dp), tint = resolveTint(ic.tint))
        is TreeIcon.Badge -> LetterBadge(ic.text, ic.color, 17)
    }
}

@Composable
private fun gitColor(status: dev.ide.ui.backend.GitStatus): Color = when (status) {
    dev.ide.ui.backend.GitStatus.Added -> Ca.colors.gitAdded
    dev.ide.ui.backend.GitStatus.Modified -> Ca.colors.gitModified
    dev.ide.ui.backend.GitStatus.Deleted -> Ca.colors.gitDeleted
    dev.ide.ui.backend.GitStatus.Untracked -> Ca.colors.gitUntracked
}
