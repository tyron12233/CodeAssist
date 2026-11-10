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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    iconSize: Dp = 20.dp,
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
        Symbol(glyph, label, size = iconSize, tint = fg, filled = selected)
    }
}

@Composable
private fun DevicePicker(state: DesignerState, flat: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box {
        val container: @Composable (@Composable () -> Unit) -> Unit = { content ->
            if (flat) Box(Modifier.clip(PillShape).clickable { open = true }) { content() }
            else Floating(Modifier.clip(PillShape).clickable { open = true }) { content() }
        }
        container {
            Row(
                Modifier.height(if (flat) 32.dp else 44.dp).padding(start = if (flat) 8.dp else 12.dp, end = if (flat) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (flat) 4.dp else 6.dp),
            ) {
                Symbol(if (state.deviceIndex == 3) Sym.tablet else Sym.smartphone, null, size = if (flat) 16.dp else 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(state.device.label), style = if (flat) ChromeText else MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                if (state.night) Symbol(Sym.darkMode, null, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Symbol(Sym.arrowDropDown, stringResource(Res.string.le_device_menu), size = if (flat) 18.dp else 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** What the compact menu can open besides the panels. */
internal enum class CompactDialog { Problems, ResourceErrors, PrepareLibraries }

/**
 * The designer's only chrome in a narrow Split, where the pane is a strip above the code (often ~200dp with the
 * keyboard up) and the full chrome covered all of it. One button in the canvas's corner; everything the full
 * chrome offers is in its menu. A ring circles it while a render runs, and a dot marks problems or errors.
 */
@Composable
internal fun CompactMenu(
    state: DesignerState,
    rendering: Boolean,
    problems: Int,
    resourceErrors: Int,
    librariesNeedPreparing: Boolean,
    onPanel: (DesignPanel) -> Unit,
    onDialog: (CompactDialog) -> Unit,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val alert = problems > 0 || resourceErrors > 0 || librariesNeedPreparing
    Box(modifier) {
        Floating(Modifier.size(40.dp), shape = CircleShape) {
            Box(contentAlignment = Alignment.Center) {
                BarButton(Sym.moreVert, stringResource(Res.string.le_compact_menu), size = 40.dp) { open = true }
                if (rendering) CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 2.dp)
            }
        }
        if (alert) Box(
            Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape)
                .background(if (resourceErrors > 0 || problems > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun close(then: () -> Unit) { open = false; then() }
            @Composable
            fun item(glyph: Char, label: String, checked: Boolean = false, onClick: () -> Unit) = DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Symbol(glyph, null, size = 18.dp) },
                trailingIcon = { if (checked) Symbol(Sym.check, null, size = 18.dp) },
                onClick = onClick,
            )
            if (resourceErrors > 0) item(Sym.error, stringResource(Res.string.le_compact_resource_errors, resourceErrors)) { close { onDialog(CompactDialog.ResourceErrors) } }
            if (problems > 0) item(Sym.warning, stringResource(Res.string.le_compact_problems, problems)) { close { onDialog(CompactDialog.Problems) } }
            if (librariesNeedPreparing) item(Sym.build, stringResource(Res.string.le_compact_prepare)) { close { onDialog(CompactDialog.PrepareLibraries) } }
            if (alert) HorizontalDivider()
            item(Sym.designServices, stringResource(Res.string.le_mode_design), checked = !state.blueprint) { close { state.blueprint = false } }
            item(Sym.architecture, stringResource(Res.string.le_mode_blueprint), checked = state.blueprint) { close { state.blueprint = true } }
            HorizontalDivider()
            DESIGN_DEVICES.forEachIndexed { i, d ->
                item(if (i == 3) Sym.tablet else Sym.smartphone, stringResource(d.label), checked = i == state.deviceIndex) { close { state.deviceIndex = i } }
            }
            item(Sym.screenRotation, stringResource(Res.string.le_rotate), checked = state.landscape) { close { state.landscape = !state.landscape } }
            item(if (state.night) Sym.darkMode else Sym.lightMode, stringResource(Res.string.le_night), checked = state.night) { close { state.night = !state.night } }
            HorizontalDivider()
            item(Sym.zoomIn, stringResource(Res.string.le_zoom_in)) { close { state.zoomBy(1.25f) } }
            item(Sym.zoomOut, stringResource(Res.string.le_zoom_out)) { close { state.zoomBy(1 / 1.25f) } }
            item(Sym.fitScreen, stringResource(Res.string.le_zoom_fit), checked = state.zoom <= 0f) { close { state.resetView() } }
            HorizontalDivider()
            item(Sym.widgets, stringResource(Res.string.le_panel_palette)) { close { onPanel(DesignPanel.Palette) } }
            item(Sym.accountTree, stringResource(Res.string.le_panel_tree)) { close { onPanel(DesignPanel.Tree) } }
            item(Sym.tune, stringResource(Res.string.le_panel_attributes)) { close { onPanel(DesignPanel.Attributes) } }
        }
    }
}

/** The render's problems, as a dialog (the compact designer has no room for the chip's list). */
@Composable
internal fun ProblemsDialog(problems: List<String>, onDismiss: () -> Unit) {
    EditorDialog(Sym.warning, stringResource(Res.string.le_problems), null, onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in problems) Text(p, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One of the designer's cards, shown as a dialog over the window instead of inside a pane too small for it. */
@Composable
internal fun CardDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) { content() }
}

/** A panel (palette, tree, attributes) in a sheet over the whole window, for the compact designer. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CompactPanelSheet(onDismiss: () -> Unit, content: @Composable (Modifier) -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        content(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp))
    }
}

/**
 * The design surface's top row, docked above the canvas so nothing covers the layout: Design / Blueprint, the
 * screen, the render's progress and problems, and zoom.
 */
@Composable
internal fun DesignTopRow(state: DesignerState, stage: String?, rendering: Boolean, problems: List<String>, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        // A phone's row has room for the controls only: the render stage is then just its spinner (and named for
        // accessibility), so it never pushes zoom off the edge.
        androidx.compose.foundation.layout.BoxWithConstraints {
        val roomy = maxWidth >= 560.dp
        Row(Modifier.height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CompactSegment(Sym.designServices, stringResource(Res.string.le_mode_design), !state.blueprint) { state.blueprint = false }
                CompactSegment(Sym.architecture, stringResource(Res.string.le_mode_blueprint), state.blueprint) { state.blueprint = true }
            }
            DevicePicker(state, flat = true)
            Box(Modifier.weight(1f))
            if (rendering) {
                CircularProgressIndicator(
                    Modifier.padding(horizontal = 6.dp).size(16.dp)
                        .then(if (stage != null) Modifier.semantics { contentDescription = stage } else Modifier),
                    strokeWidth = 2.dp,
                )
                if (stage != null && roomy) Text(
                    stage, Modifier.padding(start = 6.dp, end = 4.dp).widthIn(max = 120.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (problems.isNotEmpty()) ProblemsBadge(problems)
            BarButton(Sym.zoomOut, stringResource(Res.string.le_zoom_out), size = 32.dp, iconSize = 18.dp) { state.zoomBy(1 / 1.25f) }
            Text(
                if (state.zoom <= 0f) stringResource(Res.string.le_zoom_fit) else "${(state.zoom * 100).roundToInt()}%",
                Modifier.width(36.dp).clip(PillShape).clickable { state.resetView() },
                style = ChromeText, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BarButton(Sym.zoomIn, stringResource(Res.string.le_zoom_in), size = 32.dp, iconSize = 18.dp) { state.zoomBy(1.25f) }
        }
        }
    }
}

/** A mode of the top row's switch: an icon, and its name while it is the one shown. */
@Composable
private fun CompactSegment(glyph: Char, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.height(28.dp).clip(PillShape).background(bg).clickable(onClick = onClick).padding(horizontal = if (selected) 10.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Symbol(glyph, if (selected) null else label, size = 15.dp, tint = fg, filled = selected)
        if (selected) Text(label, style = ChromeText, color = fg)
    }
}

/** The render's problems in the top row: a warning and a count that opens the list. */
@Composable
private fun ProblemsBadge(problems: List<String>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(32.dp).clip(PillShape).clickable { open = !open }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Symbol(Sym.warning, stringResource(Res.string.le_problems), size = 16.dp, tint = MaterialTheme.colorScheme.error)
            Text(problems.size.toString(), style = ChromeText, color = MaterialTheme.colorScheme.error)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.le_problems), style = MaterialTheme.typography.titleSmall)
                for (p in problems) Text(p, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The design surface's bottom row, docked below the canvas: the panels. On a phone each opens as a sheet;
 * docked, each toggles its pane.
 */
@Composable
internal fun DesignBottomRow(state: DesignerState, docked: Boolean, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun panel(p: DesignPanel, glyph: Char, label: String, on: Boolean, toggleDocked: () -> Unit) = PanelTab(glyph, label, on, Modifier.weight(1f)) {
                if (docked) toggleDocked() else state.sheet = if (state.sheet == p) null else p
            }
            panel(DesignPanel.Palette, Sym.widgets, stringResource(Res.string.le_panel_palette),
                if (docked) state.paletteDocked else state.sheet == DesignPanel.Palette) { state.paletteDocked = !state.paletteDocked }
            panel(DesignPanel.Tree, Sym.accountTree, stringResource(Res.string.le_panel_tree_short),
                if (docked) state.treeDocked else state.sheet == DesignPanel.Tree) { state.treeDocked = !state.treeDocked }
            panel(DesignPanel.Attributes, Sym.tune, stringResource(Res.string.le_panel_attributes),
                if (docked) state.attributesDocked else state.sheet == DesignPanel.Attributes) { state.attributesDocked = !state.attributesDocked }
        }
    }
}

@Composable
private fun PanelTab(glyph: Char, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.height(32.dp).padding(horizontal = 4.dp).clip(PillShape).background(bg).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Symbol(glyph, null, size = 16.dp, tint = fg, filled = selected)
        Text(label, Modifier.padding(start = 6.dp), style = ChromeText, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The selected view's name, on a small tab stuck to its outline, with a menu of what can be done with it. It
 * replaces a floating chip and action bar that covered the views around the selection, which are the ones the
 * user is looking at.
 */
@Composable
internal fun SelectionTab(element: LayoutElement, actions: DesignActions, below: Boolean, modifier: Modifier) {
    var more by remember { mutableStateOf(false) }
    val isRoot = element.parent == null
    val shape = if (below) RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp) else RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
    Box(modifier) {
        Row(
            Modifier.height(SELECTION_TAB_HEIGHT).clip(shape).background(MaterialTheme.colorScheme.primary)
                .clickable(onClickLabel = stringResource(Res.string.le_action_more)) { more = true }
                .padding(start = 5.dp, end = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                element.id ?: element.simpleName,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Symbol(Sym.expandMore, null, size = 12.dp, tint = MaterialTheme.colorScheme.onPrimary)
        }
        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
            @Composable
            fun item(glyph: Char, label: String, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
                text = { Text(label) }, leadingIcon = { Symbol(glyph, null, size = 18.dp) }, enabled = enabled,
                onClick = { more = false; onClick() },
            )
            item(Sym.contentCopy, stringResource(Res.string.le_action_duplicate), actions.canEdit && !isRoot) { actions.duplicate(element) }
            item(Sym.delete, stringResource(Res.string.le_action_delete), actions.canEdit && !isRoot) { actions.delete(element) }
            item(Sym.northWest, stringResource(Res.string.le_action_select_parent), !isRoot) { actions.selectParent(element) }
            item(Sym.code, stringResource(Res.string.le_action_show_in_code)) { actions.showInCode(element) }
        }
    }
}

/** The selection tab's height: small, since it sits over whatever is drawn above the selection. */
internal val SELECTION_TAB_HEIGHT = 18.dp

/**
 * The text of the design surface's docked rows: the size of the IDE's own chrome around it (the breadcrumb, the
 * tabs), so the rows read as part of the editor rather than louder than it.
 */
private val ChromeText = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
