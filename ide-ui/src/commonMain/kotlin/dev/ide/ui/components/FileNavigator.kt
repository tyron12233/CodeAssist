package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.icons.resolveTint
import dev.ide.ui.theme.Ca

/**
 * File navigator content: header + a module → source folder → package → file tree (caret toggles).
 * Surface-agnostic — it draws no background of its own so it reads correctly either as a persistent
 * pane (wrapped in a regular-glass [GlassSurface]) or as the content of a glass-thick [BottomSheet]
 * on phone. The caller sizes it via [modifier] (e.g. `fillMaxSize()` in a pane, `weight(1f)` in a
 * sheet).
 *
 * Icons come from the extensible [TreeIcons] registry keyed by [TreeNode.iconId]. Source-root and
 * package rows reveal a `+` on hover that calls [onNewFile] (a New-Class targeting that node, incl. an
 * intermediate level of a compacted package); the header `+` calls [onNewFileRoot].
 */
@Composable
fun FileNavigator(
    root: TreeNode,
    moduleCount: Int,
    activePath: String?,
    onOpen: (TreeNode) -> Unit,
    modifier: Modifier = Modifier,
    onNewFile: (TreeNode) -> Unit = {},
    onNewFileRoot: () -> Unit = {},
    onViewDependencies: (TreeNode) -> Unit = {},
    onConfigureModule: (TreeNode) -> Unit = {},
    canImport: Boolean = false,
    onImport: () -> Unit = {},
    canShare: Boolean = false,
    onShare: (TreeNode) -> Unit = {},
) {
    val expanded = remember {
        mutableSetExpandedDefaults(root)
    }
    Column(modifier) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProjectTile(root.name, size = 34.dp, radius = Ca.radius.sm)
            Column(Modifier.weight(1f)) {
                Text(root.name, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("workspace · $moduleCount modules", color = Ca.colors.textTertiary, style = Ca.type.caption2)
            }
            // Import is always visible (touch-friendly — the per-row `+` is hover-gated, desktop-only).
            if (canImport) IconButtonCa(CaIcons.download, "Import files", onClick = onImport, boxSize = 30, iconSize = 18)
            IconButtonCa(CaIcons.plus, "New class", onClick = onNewFileRoot, boxSize = 30, iconSize = 18)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            root.children.forEach { TreeRow(it, 0, expanded, activePath, onOpen, onNewFile, onViewDependencies, onConfigureModule, canShare, onShare) }
        }
    }
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

@Composable
private fun TreeRow(
    node: TreeNode,
    depth: Int,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    activePath: String?,
    onOpen: (TreeNode) -> Unit,
    onNewFile: (TreeNode) -> Unit,
    onViewDependencies: (TreeNode) -> Unit,
    onConfigureModule: (TreeNode) -> Unit,
    canShare: Boolean = false,
    onShare: (TreeNode) -> Unit = {},
) {
    val isExpandable = node.children.isNotEmpty()
    val isOpen = expanded[node.id] == true
    val isActive = node.filePath != null && node.filePath == activePath
    // A new-file target: a Java/Kotlin source root or package (New-Class), or an Android res/ folder (New XML).
    val canNewFile = (node.sourceRootPath != null &&
        (node.kind == NodeKind.SourceRoot || node.kind == NodeKind.Package)) ||
        node.resDirPath != null
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(if (isActive) Ca.colors.accentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable {
                if (node.filePath != null) onOpen(node)
                else expanded[node.id] = !isOpen
            }
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
            canNewFile && hovered ->
                IconButtonCa(CaIcons.plus, if (node.resDirPath != null) "New resource here" else "New class here", onClick = { onNewFile(node) }, boxSize = 22, iconSize = 14)
            // Share is shown for the active file (touch path) or any file on hover (desktop).
            node.filePath != null && canShare && (hovered || isActive) ->
                IconButtonCa(CaIcons.share, "Share ${node.name}", onClick = { onShare(node) }, boxSize = 22, iconSize = 14)
            node.gitStatus != null ->
                Box(Modifier.size(6.dp).background(gitColor(node.gitStatus), RoundedCornerShape(Ca.radius.pill)))
        }
    }

    if (isOpen) node.children.forEach { TreeRow(it, depth + 1, expanded, activePath, onOpen, onNewFile, onViewDependencies, onConfigureModule, canShare, onShare) }
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
