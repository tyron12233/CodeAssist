package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.theme.Ca

/** The on-disk path a node's file operations act on: a file's own path, a package's deepest directory, or an
 *  Android `res/` folder. Null for nodes that aren't safe to move/rename/delete (modules, source roots). */
internal fun TreeNode.fileOpPath(): String? = when (kind) {
    NodeKind.File -> filePath
    NodeKind.Package -> packageSegments.lastOrNull()?.dirPath ?: sourceRootPath
    NodeKind.Folder -> resDirPath
    else -> null
}

/** Which file operation a context-menu pick requested. */
enum class FileOpKind { Rename, Move, Copy, Delete }

/** A pending file operation: the [node] it targets and the [kind] of operation. */
data class FileOpRequest(val node: TreeNode, val kind: FileOpKind)

/** A destination directory offered by the move/copy picker (a human label + the absolute path). */
data class DirChoice(val label: String, val path: String)

/**
 * Candidate destination directories for move/copy: every source root and package directory in the tree
 * (each compacted-package level is offered separately) plus Android `res/` folders. Sorted by label.
 */
fun collectDirChoices(root: TreeNode): List<DirChoice> {
    val out = LinkedHashMap<String, DirChoice>() // de-dupe by path; keep first label
    fun add(path: String, label: String) { if (path !in out) out[path] = DirChoice(label, path) }
    fun walk(node: TreeNode) {
        when (node.kind) {
            NodeKind.SourceRoot -> node.sourceRootPath?.let { add(it, node.name) }
            NodeKind.Package -> for (seg in node.packageSegments) add(seg.dirPath, seg.packageName)
            NodeKind.Folder -> node.resDirPath?.let { add(it, node.name) }
            else -> {}
        }
        node.children.forEach(::walk)
    }
    walk(root)
    return out.values.sortedBy { it.label.lowercase() }
}

/** Host the rename / move / copy / delete dialogs for [request]; [onDismiss] clears it. */
@Composable
fun FileOperationDialog(
    request: FileOpRequest?,
    tree: TreeNode,
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
            FileOpKind.Move, FileOpKind.Copy ->
                DestinationPanel(r.node, r.kind, collectDirChoices(tree), onDismiss) { dest ->
                    if (r.kind == FileOpKind.Move) onMove(r.node, dest) else onCopy(r.node, dest)
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
            if (isDir) "This permanently removes the folder and everything inside it." else "This permanently removes the file.",
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

@Composable
private fun DestinationPanel(node: TreeNode, kind: FileOpKind, choices: List<DirChoice>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by remember(node) { mutableStateOf<String?>(null) }
    val verb = if (kind == FileOpKind.Move) "Move" else "Copy"
    DialogCard("$verb '${node.name}' to…") {
        if (choices.isEmpty()) {
            Text("No destination folders found.", color = Ca.colors.textSecondary, style = Ca.type.footnote)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                choices.forEach { c ->
                    val isSel = c.path == selected
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isSel) Ca.colors.accentSoft else Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                            .clickable { selected = c.path }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(c.label, color = if (isSel) Ca.colors.accent else Ca.colors.textPrimary, style = Ca.type.footnote,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        Spacer12()
        ButtonRow(onDismiss, verb, selected != null) { selected?.let { onConfirm(it); onDismiss() } }
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
