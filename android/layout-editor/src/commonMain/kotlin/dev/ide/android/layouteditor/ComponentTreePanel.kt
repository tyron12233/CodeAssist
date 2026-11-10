package dev.ide.android.layouteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.le_close
import dev.ide.android.layouteditor.generated.resources.le_tree_collapse_all
import dev.ide.android.layouteditor.generated.resources.le_tree_empty
import dev.ide.android.layouteditor.generated.resources.le_tree_expand
import dev.ide.android.layouteditor.generated.resources.le_tree_search
import dev.ide.android.layouteditor.generated.resources.le_tree_search_hint
import dev.ide.android.layouteditor.generated.resources.le_tree_title
import dev.ide.android.layouteditor.generated.resources.le_tree_root
import dev.ide.android.layouteditor.generated.resources.le_tree_drag
import org.jetbrains.compose.resources.stringResource

/**
 * The component tree: the layout's elements as written (not the framework's inflated hierarchy, which adds
 * window decor and views libraries create internally), indented by depth, collapsible, searchable by tag or id.
 * A row selects its element on the canvas.
 */
@Composable
internal fun ComponentTreePanel(
    document: LayoutDocument,
    selected: LayoutElement?,
    onSelect: (LayoutElement) -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier,
    drag: DragState? = null,
    isViewGroup: (LayoutElement) -> Boolean = { it.children.isNotEmpty() },
    onDrop: (DragPayload, DropTarget?) -> Unit = { _, _ -> },
) {
    // Each row's bounds in root coordinates, for resolving a drop over the tree to a row.
    val rowBounds = remember { HashMap<LayoutElement, androidx.compose.ui.geometry.Rect>() }
    if (drag != null) {
        val docNow = document
        drag.treeTarget = { p -> treeDropTarget(docNow, rowBounds, p, isViewGroup, (drag.payload as? DragPayload.Move)?.element) }
        androidx.compose.runtime.DisposableEffect(drag) { onDispose { drag.treeTarget = null } }
    }
    val hover = drag?.payload?.let { drag.treeTarget?.invoke(drag.position) }
    // Collapsed elements by their selection key, so collapsing survives the re-parse every edit makes.
    var collapsed by remember { mutableStateOf(emptySet<SelectionKey>()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val rows = remember(document, collapsed, query) {
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            document.elements().filter { it.tag.lowercase().contains(q) || it.id?.lowercase()?.contains(q) == true }
                .map { TreeRow(it, 0, false) }.toList()
        } else buildList {
            fun walk(el: LayoutElement, depth: Int) {
                val key = SelectionKey.of(el)
                val isCollapsed = key in collapsed
                add(TreeRow(el, depth, isCollapsed))
                if (!isCollapsed) for (c in el.children) walk(c, depth + 1)
            }
            document.root?.let { walk(it, 0) }
        }
    }
    val listState = rememberLazyListState()
    // Bring the selection into view when it changes from the canvas.
    LaunchedEffect(selected?.start) {
        val i = rows.indexOfFirst { it.element === selected }
        if (i >= 0 && (i < listState.firstVisibleItemIndex || i > listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size - 1)) {
            listState.animateScrollToItem(i)
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.le_tree_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            BarButton(Sym.unfoldLess, stringResource(Res.string.le_tree_collapse_all)) {
                collapsed = document.elements().filter { it.children.isNotEmpty() && it.parent != null }.map(SelectionKey::of).toSet()
            }
            BarButton(Sym.search, stringResource(Res.string.le_tree_search), selected = searching) {
                searching = !searching
                if (!searching) query = ""
            }
            if (onClose != null) BarButton(Sym.close, stringResource(Res.string.le_close), onClick = onClose)
        }
        if (searching) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(stringResource(Res.string.le_tree_search_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    },
                )
            }
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(Res.string.le_tree_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.weight(1f, fill = true), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
            items(rows, key = { "${it.element.start}:${it.element.tag}" }) { row ->
                TreeRowView(
                    row = row,
                    hover = hover?.let { treeHover(it, row.element) },
                    bounds = rowBounds,
                    handle = if (drag == null || row.element.parent == null) Modifier else
                        Modifier.dragSource(drag, { DragPayload.Move(row.element) }, onDrop, immediate = true),
                    selected = row.element === selected,
                    onSelect = { onSelect(row.element) },
                    onToggle = {
                        val key = SelectionKey.of(row.element)
                        collapsed = if (key in collapsed) collapsed - key else collapsed + key
                    },
                )
            }
        }
    }
}

private class TreeRow(val element: LayoutElement, val depth: Int, val collapsed: Boolean)

@Composable
private fun TreeRowView(
    row: TreeRow,
    hover: TreeHover?,
    bounds: MutableMap<LayoutElement, androidx.compose.ui.geometry.Rect>,
    handle: Modifier,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val el = row.element
    val accent = MaterialTheme.colorScheme.primary
    val bg = when {
        hover == TreeHover.INSIDE -> MaterialTheme.colorScheme.primaryContainer
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { bounds[el] = it.boundsInRoot() }
            .drawBehind {
                // The insertion line for a drop before or after this row.
                if (hover == TreeHover.BEFORE || hover == TreeHover.AFTER) {
                    val y = if (hover == TreeHover.BEFORE) 1.dp.toPx() else size.height - 1.dp.toPx()
                    val x0 = (12 + row.depth * 18).dp.toPx()
                    drawLine(accent, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(size.width - 8.dp.toPx(), y), strokeWidth = 3.dp.toPx())
                    drawCircle(accent, 4.dp.toPx(), androidx.compose.ui.geometry.Offset(x0, y))
                }
            }
            .padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(50)).background(bg)
            .clickable(onClick = onSelect).height(44.dp).padding(start = (4 + row.depth * 18).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (el.children.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable(onClickLabel = stringResource(Res.string.le_tree_expand), onClick = onToggle), contentAlignment = Alignment.Center) {
                Symbol(if (row.collapsed) Sym.chevronRight else Sym.expandMore, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Box(Modifier.width(28.dp))
        }
        Symbol(glyphFor(el.tag), null, size = 18.dp, tint = if (selected) fg else MaterialTheme.colorScheme.onSurfaceVariant, filled = selected)
        Text(
            el.simpleName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val id = el.id ?: if (el.parent == null) stringResource(Res.string.le_tree_root) else null
        if (id != null) {
            Text(
                id,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (selected) fg.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Box(Modifier.weight(1f))
        // The handle: drag the row to another place in the tree, or onto the canvas.
        if (el.parent != null) {
            Box(Modifier.size(36.dp).then(handle), contentAlignment = Alignment.Center) {
                Symbol(Sym.dragIndicator, stringResource(Res.string.le_tree_drag), size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Where on a row a drop is going. */
internal enum class TreeHover { BEFORE, INSIDE, AFTER }

private fun treeHover(t: DropTarget, el: LayoutElement): TreeHover? {
    if (t.parent === el && t.area == null) return TreeHover.INSIDE
    val siblings = t.parent.children
    return when {
        t.index < siblings.size && siblings[t.index] === el -> TreeHover.BEFORE
        t.index > 0 && t.index == siblings.size && siblings.last() === el -> TreeHover.AFTER
        else -> null
    }
}

/**
 * The drop a point over the tree means: the row under it, then its top third inserts before it, its bottom third
 * after it, and the middle of a row that holds children puts the view inside, at the end. A row inside the view
 * being moved takes nothing.
 */
internal fun treeDropTarget(
    document: LayoutDocument,
    rows: Map<LayoutElement, androidx.compose.ui.geometry.Rect>,
    p: androidx.compose.ui.geometry.Offset,
    isViewGroup: (LayoutElement) -> Boolean,
    moving: LayoutElement?,
): DropTarget? {
    val (el, r) = rows.entries.firstOrNull { (e, r) -> r.contains(p) && document.elements().any { it === e } }?.toPair() ?: return null
    if (moving != null && moving.walk().any { it === el }) return null
    val parent = el.parent
    val f = (p.y - r.top) / r.height.coerceAtLeast(1f)
    return when {
        parent == null || (isViewGroup(el) && f in 0.3f..0.7f) -> DropTarget(el, el.children.size, null, null, 0f, 0f)
        f < 0.5f -> DropTarget(parent, parent.children.indexOf(el), null, null, 0f, 0f)
        else -> DropTarget(parent, parent.children.indexOf(el) + 1, null, null, 0f, 0f)
    }
}
