package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiDirEntry
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.fileop_copy_here
import dev.ide.ui.generated.resources.fileop_copy_title
import dev.ide.ui.generated.resources.fileop_delete_dir_body
import dev.ide.ui.generated.resources.fileop_delete_dir_title
import dev.ide.ui.generated.resources.fileop_delete_file_body
import dev.ide.ui.generated.resources.fileop_delete_file_title
import dev.ide.ui.generated.resources.fileop_empty_folder
import dev.ide.ui.generated.resources.fileop_move_here
import dev.ide.ui.generated.resources.fileop_move_title
import dev.ide.ui.generated.resources.fileop_new_name
import dev.ide.ui.generated.resources.fileop_rename_title
import dev.ide.ui.generated.resources.rename
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.theme.resolveTint
import dev.ide.ui.theme.Ca
import dev.ide.ui.generated.resources.fileop_delete_items_title
import dev.ide.ui.generated.resources.fileop_delete_items_body
import dev.ide.ui.generated.resources.fileop_delete_items_body_undo
import dev.ide.ui.generated.resources.fileop_delete_dir_body_undo
import dev.ide.ui.generated.resources.fileop_delete_file_body_undo
import dev.ide.ui.generated.resources.fileop_move_items_title
import dev.ide.ui.generated.resources.fileop_copy_items_title
import dev.ide.ui.backend.UiConflictChoice
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.undo
import dev.ide.ui.generated.resources.fileop_conflict_title
import dev.ide.ui.generated.resources.fileop_conflict_body
import dev.ide.ui.generated.resources.fileop_conflict_progress
import dev.ide.ui.generated.resources.fileop_conflict_apply_all
import dev.ide.ui.generated.resources.fileop_conflict_replace
import dev.ide.ui.generated.resources.fileop_conflict_keep_both
import dev.ide.ui.generated.resources.fileop_conflict_skip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The on-disk path a node's file operations act on: a file's own path, a package's deepest directory, or a
 *  folder's directory (Android `res/` folder, build-output, or any plain directory). Null for nodes that
 *  aren't safe to move/rename/delete (workspace, modules, source roots — those have their own management). */
fun TreeNode.fileOpPath(): String? = when (kind) {
    NodeKind.File -> filePath
    NodeKind.Package -> packageSegments.lastOrNull()?.dirPath ?: sourceRootPath
    NodeKind.Folder -> resDirPath ?: dirPath
    else -> null
}

/** Which file operation a context-menu pick requested. */
enum class FileOpKind { Rename, Move, Copy, Delete }

/** A pending file operation: the [node] it targets, the [kind] of operation, and for Move / Copy / Delete every
 *  selected [nodes] it applies to ([node] is the first). */
data class FileOpRequest(val node: TreeNode, val kind: FileOpKind, val nodes: List<TreeNode> = listOf(node))

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
    onMove: (List<TreeNode>, String) -> Unit,
    onCopy: (List<TreeNode>, String) -> Unit,
    onDelete: (List<TreeNode>) -> Unit,
    onDismiss: () -> Unit,
    /** True when a delete can be undone (the backend keeps a trash), so the confirmation says so. */
    undoableDelete: Boolean = false,
) {
    // Retain the last request so the exit animation doesn't flash empty.
    var shown by remember { mutableStateOf<FileOpRequest?>(null) }
    if (request != null) shown = request
    val visible = request != null
    DropdownOverlay(visible = visible, onDismiss = onDismiss, topPadding = 110.dp) {
        val r = shown ?: return@DropdownOverlay
        when (r.kind) {
            FileOpKind.Rename -> RenamePanel(r.node, onDismiss) { onRename(r.node, it) }
            FileOpKind.Delete -> DeletePanel(r.nodes, undoableDelete, onDismiss) { onDelete(r.nodes) }
            FileOpKind.Move, FileOpKind.Copy -> {
                val onPick: (String) -> Unit = { dest ->
                    if (r.kind == FileOpKind.Move) onMove(r.nodes, dest) else onCopy(r.nodes, dest)
                }
                DirectoryBrowserPanel(r.nodes, r.kind, rootPath, listDir, onDismiss, onPick)
            }
        }
    }
}

@Composable
private fun RenamePanel(node: TreeNode, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // Edit the leaf name on disk: a file's name, or a (possibly compacted) package's deepest directory.
    val current = node.fileOpPath()?.substringAfterLast('/')?.substringAfterLast('\\') ?: node.name
    // Open with the part you'd usually replace already selected, so typing overwrites it.
    var field by remember(node) {
        mutableStateOf(TextFieldValue(current, renameSelection(current, isFile = node.kind == NodeKind.File)))
    }
    val name = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(node) { runCatching { focus.requestFocus() } }
    val valid = name.isNotBlank() && name != current && '/' !in name && '\\' !in name
    fun submit() { if (valid) { onConfirm(name.trim()); onDismiss() } }

    DialogCard(stringResource(Res.string.fileop_rename_title, node.name)) {
        FieldLabel(stringResource(Res.string.fileop_new_name))
        DialogField(value = field, onValueChange = { field = it }, placeholder = node.name, focusRequester = focus, onSubmit = ::submit, onCancel = onDismiss)
        Spacer12()
        ButtonRow(onDismiss, stringResource(Res.string.rename), valid, ::submit)
    }
}

/**
 * The initial rename selection for [name]: a file's stem (`Example` of `Example.java`, `build.gradle` of
 * `build.gradle.kts`), or the whole name for folders/packages and dotfiles like `.gitignore` that have no stem.
 */
internal fun renameSelection(name: String, isFile: Boolean): TextRange {
    val dot = if (isFile) name.lastIndexOf('.') else -1
    return TextRange(0, if (dot > 0) dot else name.length)
}

@Composable
private fun DeletePanel(nodes: List<TreeNode>, undoable: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val node = nodes.first()
    val many = nodes.size > 1
    val isDir = node.kind != NodeKind.File
    val title = when {
        many -> pluralStringResource(Res.plurals.fileop_delete_items_title, nodes.size, nodes.size)
        else -> stringResource(if (isDir) Res.string.fileop_delete_dir_title else Res.string.fileop_delete_file_title, node.name)
    }
    val body = when {
        many && undoable -> Res.string.fileop_delete_items_body_undo
        many -> Res.string.fileop_delete_items_body
        isDir && undoable -> Res.string.fileop_delete_dir_body_undo
        isDir -> Res.string.fileop_delete_dir_body
        undoable -> Res.string.fileop_delete_file_body_undo
        else -> Res.string.fileop_delete_file_body
    }
    DialogCard(title) {
        Text(
            stringResource(body),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer12()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            DialogButton(stringResource(Res.string.cancel), primary = false, enabled = true, onClick = onDismiss)
            DangerButton(stringResource(Res.string.delete)) { onConfirm(); onDismiss() }
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
    nodes: List<TreeNode>,
    kind: FileOpKind,
    rootPath: String,
    listDir: (String) -> List<UiDirEntry>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val isMove = kind == FileOpKind.Move
    val node = nodes.first()
    val srcPaths = remember(nodes) { nodes.mapNotNull { it.fileOpPath() } }
    val srcPath = srcPaths.firstOrNull()
    val start = remember(nodes) {
        val parent = srcPath?.let(::parentDir)
        if (parent != null && isUnder(parent, rootPath)) parent else rootPath
    }
    var cur by remember(nodes) { mutableStateOf(start) }
    val entries = remember(cur) { listDir(cur) }

    // Can't land inside a moved/copied folder, or move everything back into the folder it is already in. A name
    // that is taken here is no block: the transfer asks what to do with it.
    val blocked = srcPaths.any { cur == it || isUnder(cur, it) } ||
        (isMove && srcPaths.all { parentDir(it) == cur })

    val title = if (nodes.size > 1) pluralStringResource(
        if (isMove) Res.plurals.fileop_move_items_title else Res.plurals.fileop_copy_items_title, nodes.size, nodes.size,
    ) else stringResource(if (isMove) Res.string.fileop_move_title else Res.string.fileop_copy_title, node.name)
    DialogCard(title) {
        // Breadcrumb pills.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val crumbs = crumbsFor(rootPath, cur)
            crumbs.forEachIndexed { i, c ->
                if (i > 0) Icon(CaIcons.chevronRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                val isLast = i == crumbs.lastIndex
                Box(
                    Modifier
                        .background(if (isLast) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.pill))
                        .clickable(enabled = !isLast) { cur = c.path }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(c.label, color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Spacer12()
        // The current folder's contents.
        if (entries.isEmpty()) {
            Text(stringResource(Res.string.fileop_empty_folder), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                entries.forEach { e ->
                    val intoSelf = e.isDirectory && e.path in srcPaths
                    val dimmed = !e.isDirectory || intoSelf
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                            .clickable(enabled = e.isDirectory && !intoSelf) { cur = e.path }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EntryIcon(e.iconId)
                        Spacer(Modifier.size(10.dp))
                        Text(e.name, color = if (dimmed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (e.isDirectory && !intoSelf)
                            Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        Spacer12()
        ButtonRow(onDismiss, stringResource(if (isMove) Res.string.fileop_move_here else Res.string.fileop_copy_here), !blocked) { onConfirm(cur); onDismiss() }
    }
}

/** Render a directory-entry icon from its `TreeIcons` id (folder / file-type glyph / letter badge). */
@Composable
private fun EntryIcon(iconId: String) {
    TreeIconImage(TreeIcons.resolve(iconId), 17)
}

@Composable
private fun DialogCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
            .padding(20.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Spacer12()
        content()
    }
}

@Composable
private fun ButtonRow(onCancel: () -> Unit, confirmLabel: String, enabled: Boolean, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.weight(1f))
        DialogButton(stringResource(Res.string.cancel), primary = false, enabled = true, onClick = onCancel)
        DialogButton(confirmLabel, primary = true, enabled = enabled, onClick = onConfirm)
    }
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.background(MaterialTheme.colorScheme.error, RoundedCornerShape(Ca.radius.control))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Asks what to do with each item whose name is taken in the destination ([conflicts], absolute paths; null hides
 * the dialog): Replace, Keep both, or Skip, one conflict at a time, with "do the same for the rest" for a batch.
 * [onResolve] gets a choice for every conflict; [onCancel] drops the whole transfer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferConflictDialog(
    conflicts: List<String>?,
    destName: String,
    onResolve: (Map<String, UiConflictChoice>) -> Unit,
    onCancel: () -> Unit,
) {
    var shown by remember { mutableStateOf<List<String>?>(null) }
    if (conflicts != null) shown = conflicts
    DropdownOverlay(visible = conflicts != null, onDismiss = onCancel, topPadding = 110.dp) {
        val all = shown ?: return@DropdownOverlay
        var index by remember(all) { mutableStateOf(0) }
        var applyToRest by remember(all) { mutableStateOf(false) }
        val choices = remember(all) { LinkedHashMap<String, UiConflictChoice>() }
        val current = all.getOrNull(index) ?: return@DropdownOverlay
        val rest = all.size - index - 1
        fun choose(choice: UiConflictChoice) {
            if (applyToRest) all.drop(index).forEach { choices[it] = choice } else choices[current] = choice
            if (applyToRest || index == all.lastIndex) onResolve(choices.toMap()) else index++
        }
        DialogCard(stringResource(Res.string.fileop_conflict_title, leafName(current), destName)) {
            if (all.size > 1) {
                Text(
                    stringResource(Res.string.fileop_conflict_progress, index + 1, all.size),
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                stringResource(Res.string.fileop_conflict_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
            if (rest > 0) {
                Spacer12()
                Row(
                    Modifier.clickable { applyToRest = !applyToRest }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(18.dp).then(
                            if (applyToRest) Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
                            else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (applyToRest) Icon(CaIcons.check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        pluralStringResource(Res.plurals.fileop_conflict_apply_all, rest, rest),
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer12()
            // Wraps onto two lines on a phone: the three choices first, Cancel last.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialogButton(stringResource(Res.string.cancel), primary = false, enabled = true, onClick = onCancel)
                DialogButton(stringResource(Res.string.fileop_conflict_skip), primary = false, enabled = true) { choose(UiConflictChoice.Skip) }
                DialogButton(stringResource(Res.string.fileop_conflict_keep_both), primary = false, enabled = true) { choose(UiConflictChoice.KeepBoth) }
                DialogButton(stringResource(Res.string.fileop_conflict_replace), primary = true, enabled = true) { choose(UiConflictChoice.Replace) }
            }
        }
    }
}

/**
 * The report of a finished file operation along the bottom of the editor: [text], an Undo button when [canUndo],
 * and dismiss. It goes away on its own after a few seconds; [key] restarts that when a new report replaces it.
 */
@Composable
fun FileOpSnackbar(
    text: String?,
    error: Boolean,
    canUndo: Boolean,
    key: Long,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(key, text) {
        if (text != null) { kotlinx.coroutines.delay(if (canUndo) 8000 else 5000); onDismiss() }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
        exit = androidx.compose.animation.fadeOut(),
    ) {
        var last by remember { mutableStateOf("") }
        if (text != null) last = text
        Row(
            Modifier.widthIn(max = 560.dp).padding(horizontal = 16.dp, vertical = 16.dp)
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(Ca.radius.lg))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                last,
                color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (canUndo) Box(
                Modifier.clickable(onClick = onUndo).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    stringResource(Res.string.undo),
                    color = MaterialTheme.colorScheme.inversePrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButtonCa(
                CaIcons.close, stringResource(Res.string.dismiss), onClick = onDismiss, boxSize = 36, iconSize = 16,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}
