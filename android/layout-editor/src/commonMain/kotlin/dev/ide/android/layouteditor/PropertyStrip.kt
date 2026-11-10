package dev.ide.android.layouteditor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The properties edited most recently this session, newest first, across views and files. */
internal object RecentProperties {
    private const val MAX = 10
    val keys = mutableStateListOf<String>()

    fun touch(key: String) {
        keys.remove(key)
        keys.add(0, key)
        while (keys.size > MAX) keys.removeAt(keys.lastIndex)
    }
}

private enum class StripTab { Basic, Recent }

/**
 * The phone's property strip: the selected view (and a switcher to any other), Basic and Recent tabs, and a
 * scrolling row of property tiles, each showing its current value. A tile opens its visual editor above the
 * row; the full attribute list is one tap away on the expand button.
 */
@Composable
internal fun PropertyStrip(
    document: LayoutDocument,
    element: LayoutElement,
    pc: PropertyContext,
    strings: Map<String, String>,
    onSelect: (LayoutElement) -> Unit,
    actions: DesignActions,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    var tab by remember { mutableStateOf(StripTab.Basic) }
    var open by remember(element.tag) { mutableStateOf<String?>(null) }
    val basic = remember(element.tag, pc.catalog.keys, element.attributes.size) { basicPropertiesFor(element.tag, pc::accepts) }
    val recent = RecentProperties.keys.mapNotNull { k -> ALL_PROPERTIES.firstOrNull { it.key == k } }
        .filter { p -> p.attributes.any(pc::accepts) }
    val tiles = if (tab == StripTab.Basic) basic else recent
    val openSpec = (basic + recent).firstOrNull { it.key == open }
    val shape = RoundedCornerShape(28.dp)

    // The open property's editor, as a dialog over the canvas; its edits show in the render behind it.
    if (openSpec != null) {
        EditorDialog(
            glyph = openSpec.glyph,
            title = stringResource(openSpec.label),
            // The attribute it writes, when it is one; Size or Margin span several.
            attribute = openSpec.writeAttribute(pc::accepts).takeIf {
                openSpec.attributes.size == 1 || openSpec.editor == PropertyEditor.IMAGE || openSpec.editor == PropertyEditor.COLOR
            },
            onDismiss = { open = null },
        ) {
            PropertyEditorBody(openSpec, pc.recording(openSpec.key))
        }
    }

    Surface(
        modifier = modifier.shadow(12.dp, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.animateContentSize()) {
            // Which view: its kind and id, with a switcher to any element of the layout.
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ElementSwitcher(document, element, onSelect, Modifier.weight(1f))
                // The selection's actions, here rather than floating over the canvas where they hid the view.
                val isRoot = element.parent == null
                BarButton(Sym.contentCopy, stringResource(Res.string.le_action_duplicate), enabled = actions.canEdit && !isRoot, size = 36.dp) { actions.duplicate(element) }
                BarButton(Sym.delete, stringResource(Res.string.le_action_delete), enabled = actions.canEdit && !isRoot, size = 36.dp) { actions.delete(element) }
                BarButton(Sym.northWest, stringResource(Res.string.le_action_select_parent), enabled = !isRoot, size = 36.dp) { actions.selectParent(element) }
                MoreActions(element, actions)
                Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                BarButton(Sym.openInFull, stringResource(Res.string.le_strip_all_attributes), size = 36.dp, onClick = onExpand)
                BarButton(Sym.close, stringResource(Res.string.le_close), size = 36.dp, onClick = onClose)
            }

            // Tabs.
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabChip(stringResource(Res.string.le_strip_basic), tab == StripTab.Basic) { tab = StripTab.Basic }
                TabChip(stringResource(Res.string.le_strip_recent), tab == StripTab.Recent) { tab = StripTab.Recent }
            }

            if (tiles.isEmpty()) {
                Text(
                    stringResource(if (tab == StripTab.Recent) Res.string.le_strip_recent_empty else Res.string.le_strip_basic_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tiles, key = { it.key }) { spec ->
                        PropertyTile(
                            spec = spec,
                            preview = previewOf(spec, element, strings),
                            open = open == spec.key,
                            onClick = { open = if (open == spec.key) null else spec.key },
                        )
                    }
                }
            }
        }
    }
}

/** A context that records the property as recently edited whenever it writes. */
private fun PropertyContext.recording(key: String): PropertyContext = PropertyContext(
    element = element,
    catalog = catalog,
    canEdit = canEdit,
    write = { writes -> RecentProperties.touch(key); write(writes) },
    complete = complete,
    resources = resources,
    themeColors = themeColors,
)

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(bg)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One property: its glyph, its name, and what it is set to (or a dash), tinted when set. */
@Composable
private fun PropertyTile(spec: PropertySpec, preview: String?, open: Boolean, onClick: () -> Unit) {
    val set = preview != null
    val bg by animateColorAsState(
        when {
            open -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
    )
    val fg = if (open) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.width(112.dp).height(92.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Symbol(spec.glyph, null, size = 22.dp, tint = if (set || open) MaterialTheme.colorScheme.primary.takeUnless { open } ?: fg else MaterialTheme.colorScheme.onSurfaceVariant, filled = open)
        Column {
            Text(stringResource(spec.label), style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                preview ?: "—",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (set) fg.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The selected view's kind and id; tapping lists every element of the layout to switch to. */
@Composable
private fun ElementSwitcher(document: LayoutDocument, element: LayoutElement, onSelect: (LayoutElement) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Symbol(glyphFor(element.tag), null, size = 18.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer, filled = true)
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(element.id ?: element.simpleName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (element.id != null) Text(element.simpleName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Symbol(Sym.arrowDropDown, stringResource(Res.string.le_strip_switch), size = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (el in document.elements()) {
                DropdownMenuItem(
                    text = {
                        Row(Modifier.padding(start = (el.depth * 14).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Symbol(glyphFor(el.tag), null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(el.simpleName, style = MaterialTheme.typography.bodyMedium)
                            el.id?.let { Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    trailingIcon = { if (el === element) Symbol(Sym.check, null, size = 16.dp) },
                    onClick = { open = false; onSelect(el) },
                )
            }
        }
    }
}

/** The overflow of the selection's actions: what does not earn a button of its own. */
@Composable
private fun MoreActions(element: LayoutElement, actions: DesignActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton(Sym.moreVert, stringResource(Res.string.le_action_more), size = 36.dp) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.le_action_show_in_code)) },
                leadingIcon = { Symbol(Sym.code, null, size = 18.dp) },
                onClick = { open = false; actions.showInCode(element) },
            )
        }
    }
}
