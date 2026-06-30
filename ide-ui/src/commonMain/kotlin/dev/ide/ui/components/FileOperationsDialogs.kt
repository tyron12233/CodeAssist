package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiDirEntry
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.icons.resolveTint
import dev.ide.ui.theme.Ca

/** The on-disk path a node's file operations act on: a file's own path, a package's deepest directory, or a
 *  folder's directory (Android `res/` folder, build-output, or any plain directory). Null for nodes that
 *  aren't safe to move/rename/delete (workspace, modules, source roots — those have their own management). */
internal fun TreeNode.fileOpPath(): String? = when (kind) {
    NodeKind.File -> filePath
    NodeKind.Package -> packageSegments.lastOrNull()?.dirPath ?: sourceRootPath
    NodeKind.Folder -> resDirPath ?: dirPath
    else -> null
}

/** Which file operation a context-menu pick requested. */
enum class FileOpKind { Rename, Move, Copy, Delete }

/** A pending file operation: the [node] it targets and the [kind] of operation. */
data class FileOpRequest(val node: TreeNode, val kind: FileOpKind)

/**
 * Host the rename / move / copy / delete dialogs for [request]; [onDismiss] clears it.
 *
 * Move/Copy uses a file-manager-style **directory browser** on every platform: tap folders to descend,
 * breadcrumb pills to jump back, and "Move/Copy here" lands in the current folder — driven by [listDir]
 * from [rootPath].
 */
@Composable
fun FileOperationDialog(
    request: FileOpRequest?,
    rootPath: String,
    listDir: (String) -> List<UiDirEntry>,
    onRename: (TreeNode, String) -> Unit,
    onMove: (TreeNode, String) -> Unit,
    onCopy: (TreeNode, String) -> Unit,
    onDelete: (TreeNode) -> Unit,
    onDismiss: () -> Unit,
) {
    // Retain the last request so the exit animation doesn't flash empty.
    var shown by remember { mutableStateOf<FileOpRequest?>(null) }
    if (request != null) shown = request
    val visible = request != null
    DropdownOverlay(visible = visible, onDismiss = onDismiss, topPadding = 110.dp) {
        val r = shown ?: return@DropdownOverlay
        when (r.kind) {
            FileOpKind.Rename -> RenamePanel(r.node, onDismiss) { onRename(r.node, it) }
            FileOpKind.Delete -> DeletePanel(r.node, onDismiss) { onDelete(r.node) }
            FileOpKind.Move, FileOpKind.Copy -> {
                val onPick: (String) -> Unit = { dest ->
                    if (r.kind == FileOpKind.Move) onMove(r.node, dest) else onCopy(r.node, dest)
                }
                DirectoryBrowserPanel(r.node, r.kind, rootPath, listDir, onDismiss, onPick)
            }
        }
    }
}

@Composable
private fun RenamePanel(node: TreeNode, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // Edit the leaf name on disk: a file's name, or a (possibly compacted) package's deepest directory.
    val current = node.fileOpPath()?.substringAfterLast('/')?.substringAfterLast('\\') ?: node.name
    var name by remember(node) { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(node) { runCatching { focus.requestFocus() } }
    val valid = name.isNotBlank() && name != current && '/' !in name && '\\' !in name
    fun submit() { if (valid) { onConfirm(name.trim()); onDismiss() } }

    DialogCard("Rename '${node.name}'") {
        FieldLabel("New name")
        DialogField(value = name, onValueChange = { name = it }, placeholder = node.name, focusRequester = focus, onSubmit = ::submit, onCancel = onDismiss)
        Spacer12()
        ButtonRow(onDismiss, "Rename", valid, ::submit)
    }
}

@Composable
private fun DeletePanel(node: TreeNode, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val isDir = node.kind != NodeKind.File
    DialogCard(if (isDir) "Delete '${node.name}' and its contents?" else "Delete '${node.name}'?") {
        Text(
            if (isDir) "This permanently removes the folder and everything inside it. This can't be undone."
            else "This permanently removes the file. This can't be undone.",
            color = Ca.colors.textSecondary, style = Ca.type.footnote,
        )
        Spacer12()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            DialogButton("Cancel", primary = false, enabled = true, onClick = onDismiss)
            DangerButton("Delete") { onConfirm(); onDismiss() }
        }
    }
}

/** A path's parent directory (works for either separator); the whole string if it has no separator. */
private fun parentDir(path: String): String {
    val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (cut <= 0) path else path.substring(0, cut)
}

/** The leaf (file/dir name) of a path. */
private fun leafName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

/** True if [p] is [root] or lives under it (matching on either path separator). */
private fun isUnder(p: String, root: String): Boolean =
    p == root || p.startsWith("$root/") || p.startsWith("$root\\")

/** A breadcrumb crumb: its display [label] and the absolute directory [path] it jumps to. */
private data class Crumb(val label: String, val path: String)

/** Crumbs from the workspace [root] down to [cur] (inclusive). Falls back to just [cur] when it's outside [root]. */
private fun crumbsFor(root: String, cur: String): List<Crumb> {
    val rootLabel = leafName(root).ifBlank { root }
    if (cur == root || !isUnder(cur, root)) {
        return if (cur == root) listOf(Crumb(rootLabel, root)) else listOf(Crumb(leafName(cur), cur))
    }
    val out = mutableListOf(Crumb(rootLabel, root))
    val tail = cur.substring(root.length).trimStart('/', '\\')
    var acc = root
    for (seg in tail.split('/', '\\').filter { it.isNotEmpty() }) {
        acc = "$acc/$seg"
        out += Crumb(seg, acc)
    }
    return out
}

/**
 * The move/copy picker: a file-manager-style directory browser. Tapping a folder descends into it;
 * the breadcrumb pills jump back to any ancestor; the confirm button lands the moved/copied item in the
 * currently-shown folder. Opens at the item's current parent for context.
 */
@Composable
private fun DirectoryBrowserPanel(
    node: TreeNode,
    kind: FileOpKind,
    rootPath: String,
    listDir: (String) -> List<UiDirEntry>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val verb = if (kind == FileOpKind.Move) "Move" else "Copy"
    val srcPath = node.fileOpPath()
    val srcLeaf = srcPath?.let(::leafName)
    val start = remember(node) {
        val parent = srcPath?.let(::parentDir)
        if (parent != null && isUnder(parent, rootPath)) parent else rootPath
    }
    var cur by remember(node) { mutableStateOf(start) }
    val entries = remember(cur) { listDir(cur) }
    val names = remember(entries) { entries.map { it.name }.toSet() }

    // Can't land here when it's the source's own dir (self-move), inside the moved folder, or a name clash.
    val blocked = (srcPath != null && (cur == srcPath || isUnder(cur, srcPath))) ||
        (srcLeaf != null && srcLeaf in names)

    DialogCard("$verb '${node.name}' to…") {
        // Breadcrumb pills.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val crumbs = crumbsFor(rootPath, cur)
            crumbs.forEachIndexed { i, c ->
                if (i > 0) Icon(CaIcons.chevronRight, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
                val isLast = i == crumbs.lastIndex
                Box(
                    Modifier
                        .background(if (isLast) Ca.colors.accentSoft else Ca.colors.surface2, RoundedCornerShape(Ca.radius.pill))
                        .clickable(enabled = !isLast) { cur = c.path }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(c.label, color = if (isLast) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.caption,
                        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Spacer12()
        // The current folder's contents.
        if (entries.isEmpty()) {
            Text("This folder is empty.", color = Ca.colors.textSecondary, style = Ca.type.footnote)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                entries.forEach { e ->
                    val intoSelf = e.isDirectory && e.path == srcPath
                    val dimmed = !e.isDirectory || intoSelf
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                            .clickable(enabled = e.isDirectory && !intoSelf) { cur = e.path }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EntryIcon(e.iconId)
                        Spacer(Modifier.size(10.dp))
                        Text(e.name, color = if (dimmed) Ca.colors.textTertiary else Ca.colors.textPrimary,
                            style = Ca.type.footnote, modifier = Modifier.weight(1f))
                        if (e.isDirectory && !intoSelf)
                            Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = Ca.colors.textTertiary)
                    }
                }
            }
        }
        Spacer12()
        ButtonRow(onDismiss, "$verb here", !blocked) { onConfirm(cur); onDismiss() }
    }
}

/** Render a directory-entry icon from its `TreeIcons` id (folder / file-type glyph / letter badge). */
@Composable
private fun EntryIcon(iconId: String) {
    when (val ic = TreeIcons.resolve(iconId)) {
        is TreeIcon.Glyph -> Icon(ic.image, null, Modifier.size(17.dp), tint = resolveTint(ic.tint))
        is TreeIcon.Folder -> Icon(ic.closed, null, Modifier.size(17.dp), tint = resolveTint(ic.tint))
        is TreeIcon.Badge -> LetterBadge(ic.text, ic.color, 17)
    }
}

@Composable
private fun DialogCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 12.dp)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
            .padding(20.dp),
    ) {
        Text(title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Spacer12()
        content()
    }
}

@Composable
private fun ButtonRow(onCancel: () -> Unit, confirmLabel: String, enabled: Boolean, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.weight(1f))
        DialogButton("Cancel", primary = false, enabled = true, onClick = onCancel)
        DialogButton(confirmLabel, primary = true, enabled = enabled, onClick = onConfirm)
    }
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.background(Ca.colors.error, RoundedCornerShape(Ca.radius.control))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, color = Ca.colors.textOnAccent, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
    }
}
