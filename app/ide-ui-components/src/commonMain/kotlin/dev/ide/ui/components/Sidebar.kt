package dev.ide.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import dev.ide.ui.platform.horizontalResizeCursor
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.platform.secondaryClickable
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.LocalHostFileActions
import dev.ide.ui.LocalPluginFileOpener
import dev.ide.ui.LocalPluginNavigator
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContext
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.icons.actionIcon
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion

/**
 * The sidebar model (IntelliJ/VSCode activity bar). A [SidebarPanel] is one dockable panel — a built-in pane
 * (Files/Search/Structure/Source) OR a plugin-contributed tool window — unified so the [ActivityRail] and the
 * docked [SidebarPane] iterate them identically. Built-in panels carry a resolved [ImageVector] directly;
 * plugin panels resolve theirs from the string icon id via [actionIcon]. See `EditorLayouts` for the wiring.
 */
class SidebarPanel(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val order: Int = 1000,
    val content: @Composable () -> Unit,
)

/** Which edge a rail/pane sits on — drives the open/collapse animation direction and the divider placement. */
enum class RailSide { Left, Right }

/**
 * Render [panel]'s body with the saveable state [holder] keeps for it under its id: its list scroll positions
 * (and anything else it holds in `rememberSaveable`) come back as the user left them.
 *
 * A panel host composes only the panel it is showing, and composes nothing at all while it is collapsed,
 * which is what makes a closed drawer cost the editor nothing. That also disposes the panel, so its scroll
 * offset is gone by the time it is shown again unless it is saved on the way out. [holder] must therefore be
 * remembered in the host's own body, OUTSIDE the conditional that composes the panel: see [SidebarPane].
 *
 * [key] is the slot the state is saved under and defaults to the panel's id. A host that can show one panel
 * under more than one id (a stale selection falling back to the first panel) passes the id it resolved from
 * instead, since two slots alive at once under one key is an error.
 *
 * [headerSlot] is where the panel's own header controls go when the host draws its tool window header (see
 * [ToolWindowHeaderActions]); null when there is no host header.
 *
 * The slot clips to its bounds. Hosts pin a footer (the sidebar ad) under it, and a panel whose content
 * outgrows the slot must be cut off at the slot's edge rather than drawn underneath that footer, where it
 * looks present but cannot be reached.
 */
@Composable
fun PanelContent(
    panel: SidebarPanel?,
    holder: SaveableStateHolder,
    modifier: Modifier = Modifier,
    key: Any? = null,
    headerSlot: ToolWindowHeaderSlot? = null,
) {
    Box(modifier.clipToBounds()) {
        if (panel != null) {
            CompositionLocalProvider(LocalToolWindowHeader provides headerSlot) {
                holder.SaveableStateProvider(key ?: panel.id) { panel.content() }
            }
        }
    }
}

/** Stripe width: IntelliJ's 40px tool window bar on a pointer host, 48dp on touch so each button keeps a usable
 *  target. Public so the top bar can line its leading button up over the left stripe. */
val ToolStripeWidth: Dp get() = if (isMobilePlatform) 48.dp else 40.dp
private val StripeButtonSize: Dp get() = if (isMobilePlatform) 40.dp else 32.dp
private val StripeIconSize = 20.dp
private val StripeGap = 4.dp

/** The side of the stripe being composed, so its buttons (including host-supplied footer items) place their
 *  tooltips on the editor-facing side. */
private val LocalRailSide = staticCompositionLocalOf { RailSide.Left }

/** The narrowest a docked pane can be dragged. */
val MinPaneWidth = 200.dp

/**
 * Map the plugin tool windows registered for [anchor] (`ToolWindowRegistry`) to [SidebarPanel]s over a neutral
 * [ToolWindowContext] (`backend` + `activeFilePath`). The host prepends its built-in panels and sorts the
 * merged list by [SidebarPanel.order], so a plugin can slot itself among the built-ins by its declared order.
 * The public plugin contract is unchanged: plugins keep contributing `ToolWindowContribution(anchor = …)`.
 */
@Composable
fun pluginPanels(anchor: ToolWindowAnchor, backend: IdeBackend, activeFilePath: String?): List<SidebarPanel> {
    UiPluginHost.ensureLoaded()
    val tools = ToolWindowRegistry.forAnchor(anchor)
    val hostFileActions = LocalHostFileActions.current
    val navigate = LocalPluginNavigator.current
    val openInEditor = LocalPluginFileOpener.current
    val ctx = remember(backend, activeFilePath, hostFileActions, navigate, openInEditor) {
        object : ToolWindowContext {
            override val backend = backend
            override val activeFilePath = activeFilePath
            override val fileActions = hostFileActions
            override fun openScreen(id: String) = navigate(id)
            override fun openFile(path: String, offset: Int) = openInEditor(path, offset)
        }
    }
    return tools.map { tw -> SidebarPanel(tw.id, tw.title, actionIcon(tw.iconId), tw.order) { tw.content(ctx) } }
}

/**
 * The vertical tool-window stripe (IntelliJ's tool window bar): one icon-only button per [SidebarPanel]. A
 * panel's name shows as a tooltip on hover, or on long-press on touch, beside the stripe on its editor-facing
 * side. [header] and [footer] bracket the panel icons; the footer stays pinned while the panel icons scroll when
 * the window is too short to show them all (a phone in landscape). Tapping an icon calls [onSelect]; the host
 * decides open-vs-collapse (tap-again collapses).
 *
 * [menu], when given, is the stripe's context menu: it opens on a secondary click anywhere on the stripe, or a
 * long-press on its empty area, and receives a `dismiss` callback.
 */
@Composable
fun ActivityRail(
    panels: List<SidebarPanel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    side: RailSide = RailSide.Left,
    header: @Composable (ColumnScope.() -> Unit)? = null,
    footer: @Composable (ColumnScope.() -> Unit)? = null,
    menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalRailSide provides side) {
        Row(modifier.fillMaxHeight()) {
            if (side == RailSide.Right) SidebarDivider()
            Box(
                Modifier.width(ToolStripeWidth).fillMaxHeight()
                    // The top bar's colour, so bar and stripes read as one frame around the editor.
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .secondaryClickable(enabled = menu != null) { menuOpen = true },
            ) {
                // Below the buttons, so a long-press only reaches it on the stripe's empty area.
                if (menu != null) {
                    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) })
                }
                Column(
                    Modifier.fillMaxSize().padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(StripeGap),
                ) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(StripeGap),
                    ) {
                        header?.invoke(this)
                        panels.forEach { panel ->
                            StripeButton(panel.icon, panel.title, active = panel.id == selectedId) { onSelect(panel.id) }
                        }
                    }
                    footer?.invoke(this)
                }
                if (menu != null) {
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        menu { menuOpen = false }
                    }
                }
            }
            if (side == RailSide.Left) SidebarDivider()
        }
    }
}

/** A short horizontal rule between groups of stripe buttons (e.g. tool windows above, bottom tools below). */
@Composable
fun RailDivider() {
    Box(Modifier.padding(vertical = 3.dp).width(StripeButtonSize - 12.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/**
 * One icon-only stripe button. [active] fills it (the selected tool window, or a lit toggle); hover gets a faint
 * fill on a pointer host. [label] is the tooltip, shown on hover or long-press, and the accessibility label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StripeButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    TooltipBox(
        positionProvider = rememberStripeTooltipPosition(LocalRailSide.current),
        tooltip = { PlainTooltip { Text(label, style = MaterialTheme.typography.labelMedium) } },
        state = tooltip,
        // Hover is handled by the box; on touch the long-press below drives it, so it can't also count as a tap.
        enableUserInput = !isMobilePlatform,
    ) {
        Box(
            Modifier.size(StripeButtonSize)
                .pressScale(interaction)
                .background(
                    when {
                        active -> scheme.secondaryContainer
                        hovered -> scheme.onSurface.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    },
                    MaterialTheme.shapes.small,
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onLongClick = { scope.launch { tooltip.show() } },
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                label,
                Modifier.size(StripeIconSize),
                tint = if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
        }
    }
}

/** Places a stripe tooltip beside its button, on the editor-facing side, vertically centred on it. */
@Composable
private fun rememberStripeTooltipPosition(side: RailSide): PopupPositionProvider {
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    return remember(side, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // RailSide is the start/end edge; in RTL the "left" stripe is laid out on the right.
                val toRight = (side == RailSide.Left) == (layoutDirection == LayoutDirection.Ltr)
                val x = if (toRight) anchorBounds.right + gap else anchorBounds.left - gap - popupContentSize.width
                val y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
                return IntOffset(
                    x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                )
            }
        }
    }
}

/**
 * The docked panel host: slides open/collapse ([expandHorizontally]/[shrinkHorizontally] + fade) as
 * [selectedId] goes non-null/null, and cross-slides between panels ([AnimatedContent], direction following the
 * stripe-index delta) when switched. A hairline divider sits on the editor-facing edge. The last-selected panel
 * keeps rendering through the collapse so the content doesn't blink out before the pane finishes shrinking.
 *
 * [onResize], when given, makes the editor-facing edge a splitter: dragging it reports the change in pane width
 * (positive = wider), and [onResizeEnd] fires when the drag ends. [floating] renders the pane as an elevated
 * sheet over the editor instead of beside it (narrow windows), without dividers. The pane is headed by a
 * [ToolWindowHeader] carrying the panel's title, its own controls, and a hide button calling [onHide]. Those
 * controls show while the pane is hovered or [active] (or always, with [alwaysShowActions]); a press inside
 * calls [onActivate].
 */
@Composable
fun SidebarPane(
    panels: List<SidebarPanel>,
    selectedId: String?,
    side: RailSide,
    modifier: Modifier = Modifier,
    paneWidth: Dp = 300.dp,
    floating: Boolean = false,
    onResize: ((Dp) -> Unit)? = null,
    onResizeEnd: () -> Unit = {},
    onHide: (() -> Unit)? = null,
    active: Boolean = false,
    alwaysShowActions: Boolean = true,
    onActivate: () -> Unit = {},
) {
    // Each panel's own saveable state, held here rather than inside the pane: the pane's content is disposed
    // when the pane collapses or another panel is selected, so this is what brings a panel back scrolled to
    // where it was left. Keyed by panel id, so plugin tool windows get it too.
    val panelState = rememberSaveableStateHolder()
    val headerSlots = rememberToolWindowHeaderSlots()
    // Hold the last non-null selection so the exit animation still has content to show (updated off-composition).
    var displayId by remember { mutableStateOf(selectedId) }
    LaunchedEffect(selectedId) { if (selectedId != null) displayId = selectedId }
    val display = panels.firstOrNull { it.id == displayId }
        ?: panels.firstOrNull { it.id == selectedId }
        ?: panels.firstOrNull()
    val expandFrom = if (side == RailSide.Left) Alignment.Start else Alignment.End
    AnimatedVisibility(
        visible = selectedId != null && display != null,
        enter = expandHorizontally(tween(Motion.BASE, easing = Motion.quiet), expandFrom = expandFrom) +
            fadeIn(tween(Motion.BASE)),
        exit = shrinkHorizontally(tween(Motion.BASE, easing = Motion.quiet), shrinkTowards = expandFrom) +
            fadeOut(tween(Motion.BASE / 2)),
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxHeight()) {
            if (side == RailSide.Right && !floating) SidebarDivider()
            ToolWindowFrame(
                active = active,
                alwaysShowActions = alwaysShowActions,
                onActivate = onActivate,
                modifier = Modifier.width(paneWidth).fillMaxHeight()
                    .then(if (floating) Modifier.shadow(12.dp) else Modifier),
            ) {
                GlassSurface(Modifier.fillMaxSize(), if (floating) GlassMaterial.Thick else GlassMaterial.Regular) {
                    Column(Modifier.fillMaxSize()) {
                        if (display != null) ToolWindowHeader(display.title, headerSlots.of(display.id), onHide)
                        // Key on the stable id (not the panel object, which the host rebuilds every recomposition) so
                        // the switch animation fires only on a real panel change — not on every incidental recompose
                        // (e.g. while the IME inset animates), which would otherwise restart the transition per frame.
                        AnimatedContent(
                            targetState = display?.id,
                            transitionSpec = {
                                val fromIdx = panels.indexOfFirst { it.id == initialState }
                                val toIdx = panels.indexOfFirst { it.id == targetState }
                                val dir = if (toIdx >= fromIdx) 1 else -1
                                (fadeIn(tween(Motion.BASE)) +
                                    slideInVertically(tween(Motion.BASE, easing = Motion.quiet)) { h -> dir * h / 14 }) togetherWith
                                    (fadeOut(tween(Motion.FAST)) +
                                        slideOutVertically(tween(Motion.BASE, easing = Motion.quiet)) { h -> -dir * h / 14 })
                            },
                            label = "sidebarPanelSwitch",
                            modifier = Modifier.weight(1f),
                        ) { id ->
                            PanelContent(
                                panels.firstOrNull { it.id == id }, panelState, Modifier.fillMaxSize(),
                                headerSlot = id?.let(headerSlots::of),
                            )
                        }
                        // A native ad pinned to the foot of the LEFT tool pane — below the tool content, off the
                        // editor canvas entirely. AdSlot self-collapses when ads are inactive (desktop / ads off).
                        if (side == RailSide.Left) {
                            AdSlot(AdPlacement.SIDEBAR, Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
                        }
                    }
                }
                if (onResize != null) {
                    // An invisible grab strip just inside the editor-facing edge, so resizing takes no layout space.
                    val density = LocalDensity.current
                    val sign = if ((side == RailSide.Left) == (LocalLayoutDirection.current == LayoutDirection.Ltr)) 1f else -1f
                    Box(
                        Modifier.align(if (side == RailSide.Left) Alignment.CenterEnd else Alignment.CenterStart)
                            .width(if (isMobilePlatform) 10.dp else 6.dp).fillMaxHeight()
                            .horizontalResizeCursor()
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { dx -> onResize(with(density) { (dx * sign).toDp() }) },
                                onDragStopped = { onResizeEnd() },
                            ),
                    )
                }
            }
            if (side == RailSide.Left && !floating) SidebarDivider()
        }
    }
}

/** A 1px full-height separator between the pane and the editor. */
@Composable
fun SidebarDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
}

/** A non-panel stripe action, styled like a panel button: used for the left stripe's footer (Build console,
 *  More, Settings & Tools). [active] renders it as a lit toggle (the bottom-tool-window button uses this so it
 *  reflects whether the console is open). [label] is its tooltip. */
@Composable
fun RailActionItem(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    StripeButton(icon, label, active, onClick)
}

/**
 * The mobile in-drawer panel switcher: a horizontal segmented control with a sliding accent-soft selected
 * segment. Shown only when a side has ≥2 panels (a single panel needs no switch). Tapping a segment calls
 * [onSelect]. Labels are dropped when there are more than three panels so the segments stay legible.
 */
@Composable
fun SegmentedPanelSwitcher(
    panels: List<SidebarPanel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 12.dp,
) {
    if (panels.size < 2) return
    val selectedIndex = panels.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val showLabels = panels.size <= 3
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 10.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(Ca.radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val segW = maxWidth / panels.size
        val indicatorX by animateDpAsState(
            segW * selectedIndex,
            tween(Motion.BASE, easing = Motion.quiet),
            label = "segIndicator",
        )
        // The sliding selected segment.
        Box(
            Modifier.offset(x = indicatorX).width(segW).fillMaxHeight().padding(3.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.sm)),
        )
        Row(Modifier.fillMaxSize()) {
            panels.forEach { panel ->
                val active = panel.id == selectedId
                val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    Modifier.width(segW).fillMaxHeight().clickable { onSelect(panel.id) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(panel.icon, panel.title, Modifier.size(16.dp), tint = tint)
                    if (showLabels) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            panel.title,
                            color = tint,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
