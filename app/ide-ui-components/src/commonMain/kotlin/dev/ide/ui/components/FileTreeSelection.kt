package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.pathLeafName
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.filetree_clear_clipboard
import dev.ide.ui.generated.resources.filetree_clear_selection
import dev.ide.ui.generated.resources.filetree_clip_copy
import dev.ide.ui.generated.resources.filetree_clip_cut
import dev.ide.ui.generated.resources.filetree_copy
import dev.ide.ui.generated.resources.filetree_cut
import dev.ide.ui.generated.resources.filetree_delete
import dev.ide.ui.generated.resources.filetree_drag_items
import dev.ide.ui.generated.resources.filetree_drop_copy
import dev.ide.ui.generated.resources.filetree_drop_move
import dev.ide.ui.generated.resources.filetree_drop_nowhere
import dev.ide.ui.generated.resources.filetree_more_selection
import dev.ide.ui.generated.resources.filetree_paste_into
import dev.ide.ui.generated.resources.filetree_paste
import dev.ide.ui.generated.resources.filetree_selected_count
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMouse
import dev.ide.ui.platform.usesCommandKey
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What Cut or Copy put on the tree's clipboard: [paths], to be moved ([cut]) or copied by the next paste. */
data class TreeClipboard(val paths: List<String>, val cut: Boolean)

/**
 * The file tree's batch operations, all over absolute paths: the selection's Cut / Copy / Paste, Duplicate, Move
 * to / Copy to (the folder browser), Delete (asks first), a drop from drag and drop, Copy Path (to the system
 * clipboard), and undoing the last operation. Giving [FileNavigator] one turns on multi-select, the clipboard and
 * drag and drop; without it the tree keeps its one-row menus.
 */
class TreeFileOps(
    val clipboard: TreeClipboard? = null,
    val onCut: (List<String>) -> Unit = {},
    val onCopy: (List<String>) -> Unit = {},
    val onPaste: (destDir: String) -> Unit = {},
    val onClearClipboard: () -> Unit = {},
    val onDuplicate: (List<String>) -> Unit = {},
    val onMoveTo: (List<TreeNode>) -> Unit = {},
    val onCopyTo: (List<TreeNode>) -> Unit = {},
    val onDelete: (List<TreeNode>) -> Unit = {},
    val onDrop: (paths: List<String>, destDir: String, copy: Boolean) -> Unit = { _, _, _ -> },
    val onCopyText: (String) -> Unit = {},
    val onUndo: (() -> Unit)? = null,
)

/** The path a tree row's file operations and selection act on, or null for a row that can't be selected
 *  (the workspace, modules, source roots). */
internal fun TreeNode.selectablePath(): String? =
    if (kind == NodeKind.File || kind == NodeKind.Package || kind == NodeKind.Folder) fileOpPath() else null

/** True if [p] is [root] or lives under it (either separator). */
internal fun isUnderPath(p: String, root: String): Boolean =
    p == root || p.startsWith("$root/") || p.startsWith("$root\\")

internal fun parentDirOf(path: String): String {
    val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (cut <= 0) path else path.substring(0, cut)
}

/**
 * The tree's selection, by [TreeNode.id]. [anchorId] is where a Shift range starts, [focusId] the row the keyboard
 * moves from (and where a paste lands). [mode] is touch selection mode: on, a tap toggles a row instead of opening
 * it. [pressModifiers] holds the keyboard modifiers of the latest press, read by the row's click (a click callback
 * gets no event of its own).
 */
@Stable
internal class TreeSelection {
    val ids = mutableStateMapOf<String, Boolean>()
    var anchorId by mutableStateOf<String?>(null)
    var focusId by mutableStateOf<String?>(null)
    var mode by mutableStateOf(false)
    var pressModifiers: PointerKeyboardModifiers? = null

    val size: Int get() = ids.size
    fun isSelected(id: String) = ids[id] == true

    fun selectOnly(id: String) {
        ids.clear(); ids[id] = true
        anchorId = id; focusId = id
    }

    fun toggle(id: String) {
        if (isSelected(id)) ids.remove(id) else ids[id] = true
        anchorId = id; focusId = id
        if (ids.isEmpty()) mode = false
    }

    fun clear() {
        ids.clear(); mode = false
    }

    fun hasSelection(): Boolean = ids.isNotEmpty() || mode

    /** Select every selectable row from the anchor to [toIndex] (in [rows]' order), the way Shift+click does. */
    fun selectRange(rows: List<TreeNode>, toIndex: Int) {
        val from = rows.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 } ?: toIndex
        ids.clear()
        for (i in minOf(from, toIndex)..maxOf(from, toIndex)) {
            val n = rows[i]
            if (n.selectablePath() != null) ids[n.id] = true
        }
        focusId = rows[toIndex].id
    }

    /** The selected nodes, in [order] (the visible rows) first and then any selected row a collapse hid. */
    fun nodes(order: List<TreeNode>, byId: Map<String, TreeNode>): List<TreeNode> {
        val visible = order.filter { isSelected(it.id) }
        val hidden = ids.keys.filter { id -> visible.none { it.id == id } }.mapNotNull(byId::get)
        return visible + hidden
    }
}

/** A drag in progress: the [paths] carried, where the pointer is, and the folder row under it. */
@Stable
internal class TreeDrag {
    var paths by mutableStateOf<List<String>>(emptyList())
    var pointer by mutableStateOf(Offset.Zero)
    var targetId by mutableStateOf<String?>(null)
    var targetDir by mutableStateOf<String?>(null)
    var copy by mutableStateOf(false)
    var hoverStartMillis = 0L
    val active: Boolean get() = paths.isNotEmpty()

    /** Whether the carried items may land in [targetDir]: not into themselves, and not a move to where they are. */
    val canDrop: Boolean
        get() {
            val dir = targetDir ?: return false
            if (paths.any { isUnderPath(dir, it) }) return false
            return copy || paths.any { parentDirOf(it) != dir }
        }

    fun end() {
        paths = emptyList(); targetId = null; targetDir = null; copy = false
    }
}

/** Ctrl, or Command on a Mac: what makes a click add to the selection and a key a shortcut. */
internal fun PointerKeyboardModifiers.isCommandLike(): Boolean = if (usesCommandKey) isMetaPressed else isCtrlPressed
private fun KeyEvent.isCommandLike(): Boolean = if (usesCommandKey) isMetaPressed else isCtrlPressed

/**
 * Starts a drag from a selectable row and tracks it until the pointer lifts: a mouse after it moves past the
 * touch slop, a finger after a long press (which also selected the row) and then a move. The gesture is watched
 * on the list's Initial pass and only consumed once a drag is on, so taps, long presses and scrolling still reach
 * the rows and the list. [rowAt] maps a point in the list to its row; [onStart] returns the paths the drag carries
 * (the selection when the row is in it); [onDrop] runs when it lifts over a folder it may land in.
 */
internal fun Modifier.treeDragGestures(
    enabled: Boolean,
    selection: TreeSelection,
    drag: TreeDrag,
    rowAt: (Offset) -> TreeNode?,
    onStart: (TreeNode) -> List<String>,
    onMove: (Offset, Boolean) -> Unit,
    onDrop: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        selection.pressModifiers = currentEvent.keyboardModifiers
        val node = rowAt(down.position) ?: return@awaitEachGesture
        if (node.selectablePath() == null) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val mouse = down.isMouse
        if (mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        if (!mouse) {
            // A finger has to hold still through a long press first; a move before that is a scroll.
            var stillDown = true
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed || (c.position - down.position).getDistance() > slop) {
                        stillDown = false; break
                    }
                }
            }
            if (held != null || !stillDown) return@awaitEachGesture
        }
        // Then a move past the slop starts the drag; lifting before that is a click (or the long press alone).
        var start: Offset
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                ?: return@awaitEachGesture
            if (!c.pressed) return@awaitEachGesture
            if ((c.position - down.position).getDistance() > slop) { start = c.position; c.consume(); break }
        }
        val carried = onStart(node)
        if (carried.isEmpty()) return@awaitEachGesture
        drag.paths = carried
        try {
            onMove(start, false)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                event.changes.forEach { it.consume() }
                if (!c.pressed) { onDrop(); break }
                val mods = event.keyboardModifiers
                onMove(c.position, mouse && (mods.isCommandLike() || mods.isAltPressed))
            }
        } finally {
            drag.end()
        }
    }
}

/** The chip that follows the pointer while dragging: what is carried, and where it would land. */
@Composable
internal fun DragChip(drag: TreeDrag, targetName: String?) {
    val count = drag.paths.size
    val p = drag.pointer
    Column(
        Modifier
            .offset { IntOffset(p.x.toInt() + 24, p.y.toInt() + 12) }
            .widthIn(max = 260.dp)
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            if (count == 1) pathLeafName(drag.paths.first()) else pluralStringResource(Res.plurals.filetree_drag_items, count, count),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        val canDrop = drag.canDrop
        Text(
            when {
                !canDrop || targetName == null -> stringResource(Res.string.filetree_drop_nowhere)
                drag.copy -> stringResource(Res.string.filetree_drop_copy, targetName)
                else -> stringResource(Res.string.filetree_drop_move, targetName)
            },
            color = if (canDrop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The bar over the tree while rows are selected: clear, the count, and the actions that apply to all of them.
 * [moreMenu] is the overflow's content (the full row menu when one row is selected).
 */
@Composable
internal fun SelectionBar(
    modifier: Modifier = Modifier,
    count: Int,
    onClear: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    moreMenu: @Composable (close: () -> Unit) -> Unit,
) {
    var moreOpen by androidx.compose.runtime.remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonCa(CaIcons.close, stringResource(Res.string.filetree_clear_selection), onClick = onClear, boxSize = 36, iconSize = 18)
        Text(
            pluralStringResource(Res.plurals.filetree_selected_count, count, count),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 2.dp),
        )
        IconButtonCa(CaIcons.cut, stringResource(Res.string.filetree_cut), onClick = onCut, boxSize = 36, iconSize = 18)
        IconButtonCa(CaIcons.copy, stringResource(Res.string.filetree_copy), onClick = onCopy, boxSize = 36, iconSize = 18)
        IconButtonCa(CaIcons.trash, stringResource(Res.string.filetree_delete), onClick = onDelete, boxSize = 36, iconSize = 18)
        Box {
            IconButtonCa(CaIcons.ellipsis, stringResource(Res.string.filetree_more_selection), onClick = { moreOpen = true }, boxSize = 36, iconSize = 18)
            CaDropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                moreMenu { moreOpen = false }
            }
        }
    }
}

/** The strip under the tree while the clipboard holds items: what is on it, Paste into [targetName], and clear. */
@Composable
internal fun ClipboardBanner(clipboard: TreeClipboard, targetName: String?, onPaste: () -> Unit, onClear: () -> Unit) {
    val n = clipboard.paths.size
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (clipboard.cut) CaIcons.cut else CaIcons.copy, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        Column(Modifier.weight(1f)) {
            Text(
                if (clipboard.cut) pluralStringResource(Res.plurals.filetree_clip_cut, n, n)
                else pluralStringResource(Res.plurals.filetree_clip_copy, n, n),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (targetName != null) Text(
                stringResource(Res.string.filetree_paste_into, targetName),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        if (targetName != null) Box(
            Modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                .clickable(onClick = onPaste)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                stringResource(Res.string.filetree_paste),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        IconButtonCa(CaIcons.close, stringResource(Res.string.filetree_clear_clipboard), onClick = onClear, boxSize = 32, iconSize = 16)
    }
}

/** The keyboard commands of a focused tree. Each returns true when it handled the key. */
internal class TreeKeyCommands(
    val move: (delta: Int, extend: Boolean) -> Unit,
    val collapseOrParent: () -> Unit,
    val expandOrChild: () -> Unit,
    val activate: () -> Unit,
    val delete: () -> Unit,
    val rename: () -> Unit,
    val cut: () -> Unit,
    val copy: () -> Unit,
    val paste: () -> Unit,
    val duplicate: () -> Unit,
    val selectAll: () -> Unit,
    val copyPath: () -> Unit,
    val moveTo: () -> Unit,
    val copyTo: () -> Unit,
    val undo: (() -> Unit)?,
    val escape: () -> Boolean,
)

/** Route a key press in the focused tree to [cmd]; false lets it bubble (to the app-wide shortcuts). */
internal fun handleTreeKey(e: KeyEvent, cmd: TreeKeyCommands): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val command = e.isCommandLike()
    when {
        e.key == Key.DirectionUp && !command -> cmd.move(-1, e.isShiftPressed)
        e.key == Key.DirectionDown && !command -> cmd.move(1, e.isShiftPressed)
        e.key == Key.DirectionLeft && !command -> cmd.collapseOrParent()
        e.key == Key.DirectionRight && !command -> cmd.expandOrChild()
        e.key == Key.Enter || e.key == Key.NumPadEnter -> cmd.activate()
        e.key == Key.Delete || (e.key == Key.Backspace && command) -> cmd.delete()
        e.key == Key.F2 -> cmd.rename()
        e.key == Key.F5 -> cmd.copyTo()
        e.key == Key.F6 -> cmd.moveTo()
        command && e.isShiftPressed && e.key == Key.C -> cmd.copyPath()
        command && e.key == Key.C -> cmd.copy()
        command && e.key == Key.X -> cmd.cut()
        command && e.key == Key.V -> cmd.paste()
        command && e.key == Key.D -> cmd.duplicate()
        command && e.key == Key.A -> cmd.selectAll()
        command && e.key == Key.Z && !e.isShiftPressed -> cmd.undo?.invoke() ?: return false
        e.key == Key.Escape -> return cmd.escape()
        else -> return false
    }
    return true
}

/** Selection and drag state handed to a [FileNavigator] from outside, so a snapshot can show a given moment. */
internal class TreeStateSeed(val selection: TreeSelection, val drag: TreeDrag)

internal val LocalTreeStateSeed = androidx.compose.runtime.staticCompositionLocalOf<TreeStateSeed?> { null }
