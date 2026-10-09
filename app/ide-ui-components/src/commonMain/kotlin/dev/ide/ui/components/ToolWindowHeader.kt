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
import dev.ide.ui.generated.resources.toolwindow_hide
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
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
        titleContent()
        slot?.leading?.invoke(this)
        if (!titleFills) Spacer(Modifier.weight(1f))
        Row(
            Modifier.toolWindowActionsAlpha(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            slot?.actions?.invoke(this)
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
        val visible = alwaysShowActions || isMobilePlatform || hovered || active
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
 * The header for a host that switches between [panels] (the mobile drawers): the segmented switcher stands in
 * for the title while there are two or more panels, followed by the selected panel's controls and the hide
 * button.
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
        ToolWindowHeader(slot, onHide, titleFills = true) {
            SegmentedPanelSwitcher(panels, selected.id, onSelect, Modifier.weight(1f), horizontalPadding = 0.dp)
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
        enableUserInput = !isMobilePlatform,
        modifier = modifier,
    ) {
        IconButtonCa(CaIcons.minus, label, onHide, boxSize = 28, iconSize = 16)
    }
}
