package dev.ide.android.layouteditor

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutResourceError
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.*
import dev.ide.plugin.ui.EditorPreviewContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val PillShape = RoundedCornerShape(50)

/** A floating container: the surface the chrome bars sit on, raised over the canvas. */
@Composable
private fun Floating(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = PillShape, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.shadow(6.dp, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

/** An icon button for the floating bars, with an optional selected (tonal) state. */
@Composable
internal fun BarButton(
    glyph: Char,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(glyph, label, size = 20.dp, tint = fg, filled = selected)
    }
}

/**
 * The top bar: the Design / Blueprint switch on the left, the screen picker on the right. The code view and the
 * Split view are the editor's own toggle, a tap away, so they are not repeated here.
 */
@Composable
internal fun DesignTopBar(state: DesignerState, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Floating {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ModeSegment(Sym.designServices, stringResource(Res.string.le_mode_design), !state.blueprint) { state.blueprint = false }
                ModeSegment(Sym.architecture, stringResource(Res.string.le_mode_blueprint), state.blueprint) { state.blueprint = true }
            }
        }
        Box(Modifier.weight(1f))
        DevicePicker(state)
    }
}

@Composable
private fun ModeSegment(glyph: Char, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.height(36.dp).clip(PillShape).background(bg).clickable(onClick = onClick).padding(horizontal = if (selected) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(glyph, if (selected) null else label, size = 18.dp, tint = fg, filled = selected)
        if (selected) Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun DevicePicker(state: DesignerState) {
    var open by remember { mutableStateOf(false) }
    Box {
        Floating(Modifier.clip(PillShape).clickable { open = true }) {
            Row(
                Modifier.height(44.dp).padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(if (state.deviceIndex == 3) Sym.tablet else Sym.smartphone, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(state.device.label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                if (state.night) Symbol(Sym.darkMode, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Symbol(Sym.arrowDropDown, stringResource(Res.string.le_device_menu), size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DESIGN_DEVICES.forEachIndexed { i, d ->
                DropdownMenuItem(
                    text = { Text("${stringResource(d.label)}  ·  ${d.wdp}×${d.hdp}") },
                    leadingIcon = { Symbol(if (i == 3) Sym.tablet else Sym.smartphone, null, size = 18.dp) },
                    trailingIcon = { if (i == state.deviceIndex) Symbol(Sym.check, null, size = 18.dp) },
                    onClick = { state.deviceIndex = i; open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.le_rotate)) },
                leadingIcon = { Symbol(Sym.screenRotation, null, size = 18.dp) },
                trailingIcon = { if (state.landscape) Symbol(Sym.check, null, size = 18.dp) },
                onClick = { state.landscape = !state.landscape; open = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.le_night)) },
                leadingIcon = { Symbol(if (state.night) Sym.darkMode else Sym.lightMode, null, size = 18.dp) },
                trailingIcon = { if (state.night) Symbol(Sym.check, null, size = 18.dp) },
                onClick = { state.night = !state.night; open = false },
            )
        }
    }
}

/** The selected view, named the way the design names it: its kind and, in code type, its id. */
@Composable
internal fun SelectionChip(element: LayoutElement) {
    Floating {
        Row(
            Modifier.height(34.dp).padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Symbol(glyphFor(element.tag), null, size = 16.dp, tint = MaterialTheme.colorScheme.primary)
            Text(element.simpleName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            element.id?.let {
                Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The render pipeline's current stage, while one runs. */
@Composable
internal fun RenderStatusChip(stage: String?, modifier: Modifier) {
    Floating(modifier) {
        Row(
            Modifier.height(32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(stage ?: stringResource(Res.string.le_rendering), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The bottom toolbar: the panels. On a phone they open as sheets; docked, they toggle their pane. */
@Composable
internal fun DesignToolbar(state: DesignerState, docked: Boolean, modifier: Modifier) {
    Floating(modifier) {
        // 40dp buttons in 4dp of padding: 48dp tall, the same as the zoom controls beside it.
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val paletteOn = if (docked) state.paletteDocked else state.sheet == DesignPanel.Palette
            BarButton(Sym.widgets, stringResource(Res.string.le_panel_palette), selected = paletteOn, size = 40.dp) {
                if (docked) state.paletteDocked = !state.paletteDocked
                else state.sheet = if (state.sheet == DesignPanel.Palette) null else DesignPanel.Palette
            }
            val treeOn = if (docked) state.treeDocked else state.sheet == DesignPanel.Tree
            val attrsOn = if (docked) state.attributesDocked else state.sheet == DesignPanel.Attributes
            BarButton(Sym.accountTree, stringResource(Res.string.le_panel_tree), selected = treeOn, size = 40.dp) {
                if (docked) state.treeDocked = !state.treeDocked
                else state.sheet = if (state.sheet == DesignPanel.Tree) null else DesignPanel.Tree
            }
            BarButton(Sym.tune, stringResource(Res.string.le_panel_attributes), selected = attrsOn, size = 40.dp) {
                if (docked) state.attributesDocked = !state.attributesDocked
                else state.sheet = if (state.sheet == DesignPanel.Attributes) null else DesignPanel.Attributes
            }
        }
    }
}

/** Zoom out / in and fit. The percentage resets to fit on tap. */
@Composable
internal fun ZoomControls(state: DesignerState, modifier: Modifier) {
    Floating(modifier) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(Sym.zoomOut, stringResource(Res.string.le_zoom_out), size = 40.dp) {
                state.zoom = ((if (state.zoom <= 0f) 1f else state.zoom) / 1.25f).coerceIn(0.1f, 6f)
            }
            Text(
                if (state.zoom <= 0f) stringResource(Res.string.le_zoom_fit) else "${(state.zoom * 100).roundToInt()}%",
                Modifier.width(48.dp).clickable { state.resetView() },
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BarButton(Sym.zoomIn, stringResource(Res.string.le_zoom_in), size = 40.dp) {
                state.zoom = ((if (state.zoom <= 0f) 1f else state.zoom) * 1.25f).coerceIn(0.1f, 6f)
            }
        }
    }
}

/** The contextual actions for the selected view, floating beside it. */
@Composable
internal fun SelectionActionBar(element: LayoutElement, actions: DesignActions, modifier: Modifier) {
    var more by remember { mutableStateOf(false) }
    val isRoot = element.parent == null
    Box(modifier) {
        Surface(
            modifier = Modifier.shadow(8.dp, PillShape).pointerInput(Unit) { detectTapGestures { } },
            shape = PillShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InverseButton(Sym.contentCopy, stringResource(Res.string.le_action_duplicate), enabled = actions.canEdit && !isRoot) { actions.duplicate(element) }
                InverseButton(Sym.delete, stringResource(Res.string.le_action_delete), enabled = actions.canEdit && !isRoot) { actions.delete(element) }
                InverseButton(Sym.northWest, stringResource(Res.string.le_action_select_parent), enabled = !isRoot) { actions.selectParent(element) }
                Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.25f)))
                InverseButton(Sym.moreHoriz, stringResource(Res.string.le_action_more)) { more = true }
            }
        }
        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.le_action_show_in_code)) },
                leadingIcon = { Symbol(Sym.code, null, size = 18.dp) },
                onClick = { more = false; actions.showInCode(element) },
            )
        }
    }
}

@Composable
private fun InverseButton(glyph: Char, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Symbol(glyph, label, size = 20.dp, tint = tint) }
}

/** A bottom sheet over the canvas, with a handle to drag its height. */
@Composable
internal fun DesignSheet(state: DesignerState, maxHeight: Dp, onDismiss: () -> Unit, content: @Composable (Modifier) -> Unit) {
    // While a view is dragged out of it the sheet steps aside (it stays composed: the drag belongs to it).
    val aside by androidx.compose.animation.core.animateFloatAsState(if (state.drag.payload != null) 1f else 0f, label = "sheetAside")
    var fraction by state::sheetFraction
    val fullPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)
    val drag = rememberDraggableState { delta ->
        fraction = (fraction - delta / fullPx).coerceIn(0.2f, 0.92f)
    }
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(maxHeight * fraction)
            .graphicsLayer {
                alpha = 1f - 0.75f * aside
                translationY = aside * size.height * 0.6f
            }
            .shadow(16.dp, shape)
            .imePadding()
            .pointerInput(Unit) { detectTapGestures { } },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(24.dp).draggable(drag, Orientation.Vertical, onDragStopped = { if (fraction < 0.24f) onDismiss() }),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(PillShape).background(MaterialTheme.colorScheme.outlineVariant))
            }
            content(Modifier.weight(1f))
        }
    }
}

/** A docked panel's frame: the rounded surface the tree and attributes panes sit in when docked. */
@Composable
internal fun PanelFrame(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
}

@Composable
internal fun NothingSelected(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(24.dp)) {
            Symbol(Sym.tune, null, size = 28.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(Res.string.le_nothing_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Errors in the project's resources that stop the layout linking; each opens its file at the line. */
@Composable
internal fun ResourceErrorsCard(errors: List<LayoutResourceError>, lastRender: Boolean, ctx: EditorPreviewContext, modifier: Modifier) {
    Floating(modifier.widthIn(max = 420.dp), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.Center) {
                    Symbol(Sym.error, null, size = 22.dp, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
                Column {
                    Text(pluralStringResource(Res.plurals.le_resource_errors, errors.size, errors.size), style = MaterialTheme.typography.titleSmall)
                    if (lastRender) Text(stringResource(Res.string.le_resource_errors_last), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in errors) {
                    val file = e.file
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .then(if (file != null) Modifier.clickable { ctx.openFile(file, 0) } else Modifier)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (file != null) Text(
                            file.substringAfterLast('/') + (e.line?.let { ":$it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error,
                        )
                        Text(e.message, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The one-time "prepare libraries" step the real renderer needs, with the button that starts it. */
@Composable
internal fun PrepareLibrariesCard(count: Int, preparing: Boolean, onPrepare: () -> Unit, modifier: Modifier) {
    Floating(modifier.widthIn(max = 380.dp), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Symbol(Sym.build, null, size = 24.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(Res.string.le_prepare_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.le_prepare_body) + if (count > 0) " " + pluralStringResource(Res.plurals.le_prepare_count, count, count) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.clip(PillShape)
                    .background(if (preparing) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !preparing, onClick = onPrepare)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (preparing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.le_preparing), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Symbol(Sym.build, null, size = 18.dp, tint = MaterialTheme.colorScheme.onPrimary)
                    Text(stringResource(Res.string.le_prepare_action), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/**
 * A property or attribute editor as a dialog: the property named in its header (with the attribute it writes,
 * in code type), its editor in a scrolling body, and Done. Edits apply as they are made, so the canvas behind
 * already shows them; Done (or a tap outside) only closes.
 */
@Composable
internal fun EditorDialog(
    glyph: Char,
    title: String,
    attribute: String?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 560.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Symbol(glyph, null, size = 22.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer, filled = true) }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (attribute != null) Text(
                            attribute,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    Modifier.padding(horizontal = 20.dp).padding(top = 16.dp).heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) { content() }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(stringResource(Res.string.le_done), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** The render's problems: a count that opens the list. */
@Composable
internal fun ProblemsChip(problems: List<String>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Floating(Modifier.clip(PillShape).clickable { open = !open }) {
            Row(
                Modifier.height(32.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(Sym.warning, stringResource(Res.string.le_problems), size = 16.dp, tint = MaterialTheme.colorScheme.error)
                Text(problems.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.le_problems), style = MaterialTheme.typography.titleSmall)
                for (p in problems) Text(p, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
