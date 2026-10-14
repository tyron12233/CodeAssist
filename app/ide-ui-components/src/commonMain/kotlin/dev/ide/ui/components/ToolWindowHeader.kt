package dev.ide.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.ide.ui.theme.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.ToolWindowSide
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ide.ui.generated.resources.toolwindow_dock_bottom
import dev.ide.ui.generated.resources.toolwindow_dock_left
import dev.ide.ui.generated.resources.toolwindow_dock_right
import dev.ide.ui.generated.resources.toolwindow_hide
import dev.ide.ui.generated.resources.toolwindow_maximize
import dev.ide.ui.generated.resources.toolwindow_move_to
import dev.ide.ui.generated.resources.toolwindow_options
import dev.ide.ui.generated.resources.toolwindow_restore
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.touchInput
import org.jetbrains.compose.resources.stringResource

/** Minimum height of a tool window's header strip, IntelliJ's tool window title bar. */
val ToolWindowHeaderHeight = 40.dp

/**
 * Where a panel's own header controls go when the host draws the tool window header. The panel publishes them
 * with [ToolWindowHeaderActions]; the host's [ToolWindowHeader] renders them. One per panel.
 */
@Stable
class ToolWindowHeaderSlot {
    internal var leading by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
    internal var actions by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
}

/** One [ToolWindowHeaderSlot] per panel id, for a host that shows several panels in turn. */
@Stable
class ToolWindowHeaderSlots {
    private val slots = mutableMapOf<String, ToolWindowHeaderSlot>()
    fun of(id: String): ToolWindowHeaderSlot = slots.getOrPut(id) { ToolWindowHeaderSlot() }
}

@Composable
fun rememberToolWindowHeaderSlots(): ToolWindowHeaderSlots = remember { ToolWindowHeaderSlots() }

/** The header slot of the tool window the current panel is shown in, or null when it is shown without one. */
val LocalToolWindowHeader = staticCompositionLocalOf<ToolWindowHeaderSlot?> { null }

/**
 * Puts a panel's header controls into the host's tool window header: [leading] right after the title (a model
 * or view picker), [actions] at the end before the hide button. Returns false when the panel is shown without
 * a host header (a test, a sheet), in which case the panel should draw its own header as before.
 */
@Composable
fun ToolWindowHeaderActions(
    leading: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
): Boolean {
    val slot = LocalToolWindowHeader.current ?: return false
    val latestLeading by rememberUpdatedState(leading)
    val latestActions by rememberUpdatedState(actions)
    DisposableEffect(slot) {
        val myLeading: @Composable RowScope.() -> Unit = { latestLeading?.invoke(this) }
        val myActions: @Composable RowScope.() -> Unit = { latestActions?.invoke(this) }
        slot.leading = myLeading
        slot.actions = myActions
        onDispose {
            // A newer instance of the panel (re-shown while this one animates out) may own the slot by now.
            if (slot.leading === myLeading) slot.leading = null
            if (slot.actions === myActions) slot.actions = null
        }
    }
    return true
}

/**
 * The host-drawn header strip of a tool window: [title], the panel's own controls from [slot], and the hide
 * button (—) that collapses the window. The same strip heads every pane, docked or in a drawer, so every tool
 * window closes the same way.
 */
@Composable
fun ToolWindowHeader(
    title: String,
    slot: ToolWindowHeaderSlot?,
    onHide: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ToolWindowHeader(slot, onHide, modifier) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * [ToolWindowHeader] with custom [titleContent] in place of the title (the mobile drawers' panel switcher).
 * [titleFills] is for title content that takes the free width itself (a weighted switcher); otherwise a spacer
 * pushes the panel's actions to the end.
 */
@Composable
fun ToolWindowHeader(
    slot: ToolWindowHeaderSlot?,
    onHide: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleFills: Boolean = false,
    titleContent: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = ToolWindowHeaderHeight).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (titleFills) {
            titleContent()
            slot?.leading?.invoke(this)
        } else {
            // The title side takes what the end buttons leave and gives way first, so in a narrow pane the hide
            // button is never pushed off the edge.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                titleContent()
                slot?.leading?.invoke(this)
            }
        }
        Row(
            Modifier.toolWindowActionsAlpha(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            slot?.actions?.invoke(this)
            ToolWindowPaneButtons()
            if (onHide != null) HideToolWindowButton(onHide)
        }
    }
}

/** Whether the enclosing tool window's header actions are showing (see [ToolWindowFrame]). */
val LocalToolWindowActionsVisible = compositionLocalOf { true }

/**
 * A tool window's body: shows its header actions only while the pointer is over it or it is the [active] tool
 * window, IntelliJ-style, unless [alwaysShowActions] is set or the host is touch (no hover there). A press
 * anywhere inside calls [onActivate] so the host can make it the active one.
 */
@Composable
fun ToolWindowFrame(
    active: Boolean,
    alwaysShowActions: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val activate by rememberUpdatedState(onActivate)
    Box(
        modifier.pointerInput(Unit) {
            // Initial pass, never consumed: watching the pointer must not take events from the panel's content.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move -> hovered = true
                        PointerEventType.Exit -> hovered = false
                        PointerEventType.Press -> activate()
                    }
                }
            }
        },
    ) {
        val visible = alwaysShowActions || touchInput || hovered || active
        CompositionLocalProvider(LocalToolWindowActionsVisible provides visible) { this@Box.content() }
    }
}

/** Fades a tool window's header actions with [LocalToolWindowActionsVisible]. Only alpha, so the header keeps
 *  its layout and nothing shifts as they come and go. */
@Composable
fun Modifier.toolWindowActionsAlpha(): Modifier {
    val alpha by animateFloatAsState(
        if (LocalToolWindowActionsVisible.current) 1f else 0f,
        tween(Motion.FAST),
        label = "toolWindowActions",
    )
    return graphicsLayer { this.alpha = alpha }
}

/**
 * The header for a host that switches between [panels] (the mobile drawers). With two or more panels the
 * segmented switcher stands in for the title and shares its row only with the hide button; the selected
 * panel's own controls (a view picker, its actions) get a row of their own below, since a phone-width drawer
 * has no room for both.
 */
@Composable
fun ToolWindowSwitcherHeader(
    panels: List<SidebarPanel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    slots: ToolWindowHeaderSlots,
    onHide: (() -> Unit)?,
) {
    val selected = panels.firstOrNull { it.id == selectedId } ?: panels.firstOrNull() ?: return
    val slot = slots.of(selected.id)
    if (panels.size < 2) {
        ToolWindowHeader(selected.title, slot, onHide)
    } else {
        Column(Modifier.fillMaxWidth()) {
            ToolWindowHeader(null, onHide, titleFills = true) {
                SegmentedPanelSwitcher(panels, selected.id, onSelect, Modifier.weight(1f), horizontalPadding = 0.dp)
            }
            val leading = slot.leading
            val actions = slot.actions
            if (leading != null || actions != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 12.dp, end = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    leading?.invoke(this)
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.toolWindowActionsAlpha(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) { actions?.invoke(this) }
                }
            }
        }
    }
}

/**
 * The hide button (—): collapses the tool window it heads. One control for every tool window, in place of
 * per-pane arrows and close crosses, so "hide this window" always looks the same.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HideToolWindowButton(onHide: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.toolwindow_hide)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label, style = MaterialTheme.typography.labelMedium) } },
        state = rememberTooltipState(),
        // Hover only: on touch a long-press would also count as the tap that hides the window.
        enableUserInput = !touchInput,
        modifier = modifier,
    ) {
        IconButtonCa(CaIcons.minus, label, onHide, boxSize = 28, iconSize = 16)
    }
}

/**
 * The window controls of a docked tool window in the wide layout: maximize its pane over the editor, and move
 * it to another side. The pane's host provides them through [LocalToolWindowPaneControls]; the host-drawn
 * [ToolWindowHeader] shows them, and a window that draws its own header (the build console, the Run terminal,
 * the logs) puts [ToolWindowPaneButtons] before its hide button. Nothing shows where no host provides them
 * (a phone's drawer or sheet, the full-screen Run).
 */
@Stable
class ToolWindowPaneControls(
    val maximized: Boolean,
    val onToggleMaximized: () -> Unit,
    val side: ToolWindowSide,
    val onMove: (ToolWindowSide) -> Unit,
)

val LocalToolWindowPaneControls = compositionLocalOf<ToolWindowPaneControls?> { null }

/**
 * The docked tool window's window menu, one button so it fits a narrow pane's header: maximize (or restore) the
 * pane, and move the window to another side. Its icon shows the side the window is on. Nothing where no host
 * provides [LocalToolWindowPaneControls].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolWindowPaneButtons(boxSize: Int = 28) {
    val controls = LocalToolWindowPaneControls.current ?: return
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.toolwindow_options)
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label, style = MaterialTheme.typography.labelMedium) } },
            state = rememberTooltipState(),
            enableUserInput = !touchInput,
        ) {
            IconButtonCa(sideIcon(controls.side), label, { open = true }, boxSize = boxSize, iconSize = 16, active = open)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CaMenuItem(
                label = stringResource(if (controls.maximized) Res.string.toolwindow_restore else Res.string.toolwindow_maximize),
                onClick = { open = false; controls.onToggleMaximized() },
                icon = if (controls.maximized) CaIcons.collapse else CaIcons.expand,
            )
            Text(
                stringResource(Res.string.toolwindow_move_to),
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            ToolWindowSide.entries.forEach { side ->
                CaMenuItem(
                    label = sideLabel(side),
                    onClick = { open = false; if (side != controls.side) controls.onMove(side) },
                    icon = sideIcon(side),
                    selected = side == controls.side,
                    trailing = if (side == controls.side) ({ CaMenuCheck() }) else null,
                )
            }
        }
    }
}

/** The name of a side, as the "Move to" menu and the drop targets show it. */
@Composable
fun sideLabel(side: ToolWindowSide): String = stringResource(
    when (side) {
        ToolWindowSide.Left -> Res.string.toolwindow_dock_left
        ToolWindowSide.Right -> Res.string.toolwindow_dock_right
        ToolWindowSide.Bottom -> Res.string.toolwindow_dock_bottom
    },
)

/** A window with a pane on that side. The side ones mirror with the stripes in a right-to-left layout. */
fun sideIcon(side: ToolWindowSide): ImageVector = when (side) {
    ToolWindowSide.Left -> CaIcons.sidebar
    ToolWindowSide.Right -> CaIcons.panelRight
    ToolWindowSide.Bottom -> CaIcons.panelBottom
}
