package dev.ide.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContext
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.icons.actionIcon
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlin.math.roundToInt

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

private val RailWidth = 76.dp
private val RailIconBox = 46.dp

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
    val ctx = remember(backend, activeFilePath) {
        object : ToolWindowContext {
            override val backend = backend
            override val activeFilePath = activeFilePath
        }
    }
    return tools.map { tw -> SidebarPanel(tw.id, tw.title, actionIcon(tw.iconId), tw.order) { tw.content(ctx) } }
}

/**
 * The vertical activity rail (glass, 76px): one icon per [SidebarPanel], with an accent-soft **sliding
 * indicator** that glides between icons on selection (the signature motion). [header] (the project tile on the
 * left rail) and [footer] (the More/Settings buttons) bracket the panel icons. Tapping an icon calls
 * [onSelect]; the host decides open-vs-collapse (tap-again collapses).
 */
@Composable
fun ActivityRail(
    panels: List<SidebarPanel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable (ColumnScope.() -> Unit)? = null,
    footer: @Composable (ColumnScope.() -> Unit)? = null,
) {
    GlassSurface(modifier.width(RailWidth).fillMaxHeight(), GlassMaterial.Regular) {
        // Each icon reports its top Y in root coordinates; the indicator's position is that minus the rail's
        // own root Y (both read live, so it self-corrects regardless of which lays out first).
        var railTop by remember { mutableStateOf(0f) }
        val itemY = remember { mutableStateMapOf<String, Float>() }
        val target = selectedId?.let { id -> itemY[id]?.let { it - railTop } }
        val indicatorY by animateFloatAsState(
            target ?: 0f,
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "railIndicator",
        )
        Box(Modifier.fillMaxHeight().onGloballyPositioned { railTop = it.positionInRoot().y }) {
            if (target != null) {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .offset { IntOffset(0, indicatorY.roundToInt()) }
                        .size(RailIconBox)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.sm)),
                )
            }
            Column(
                Modifier.fillMaxHeight().padding(top = 18.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                header?.invoke(this)
                panels.forEach { panel ->
                    RailIcon(
                        panel = panel,
                        active = panel.id == selectedId,
                        onClick = { onSelect(panel.id) },
                        reportY = { y -> itemY[panel.id] = y },
                    )
                }
                Box(Modifier.weight(1f))
                footer?.invoke(this)
            }
        }
    }
}

/** One rail icon + label. The accent-soft background is drawn by the rail's sliding indicator (so it animates
 *  between items), not per-item — the button itself only tints its glyph/label when active. */
@Composable
private fun RailIcon(
    panel: SidebarPanel,
    active: Boolean,
    onClick: () -> Unit,
    reportY: (Float) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.onGloballyPositioned { reportY(it.positionInRoot().y) }) {
            IconButtonCa(
                icon = panel.icon,
                contentDescription = panel.title,
                onClick = onClick,
                active = false, // the sliding indicator provides the selected background
                iconSize = 22,
                boxSize = 46,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            panel.title,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            fontSize = 10.5f.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The docked panel host: slides open/collapse ([expandHorizontally]/[shrinkHorizontally] + fade) as
 * [selectedId] goes non-null/null, and cross-slides between panels ([AnimatedContent], direction following the
 * rail-index delta) when switched. A hairline divider sits on the editor-facing edge. The last-selected panel
 * keeps rendering through the collapse so the content doesn't blink out before the pane finishes shrinking.
 */
@Composable
fun SidebarPane(
    panels: List<SidebarPanel>,
    selectedId: String?,
    side: RailSide,
    modifier: Modifier = Modifier,
    paneWidth: Dp = 300.dp,
) {
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
            if (side == RailSide.Right) SidebarDivider()
            GlassSurface(Modifier.width(paneWidth).fillMaxHeight(), GlassMaterial.Regular) {
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
                ) { id ->
                    val panel = panels.firstOrNull { it.id == id }
                    Box(Modifier.fillMaxSize()) { panel?.content?.invoke() }
                }
            }
            if (side == RailSide.Left) SidebarDivider()
        }
    }
}

/** A 1px full-height separator between the pane and the editor. */
@Composable
fun SidebarDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
}

/** A non-panel rail action (icon + label), styled like a [RailIcon] but without the sliding indicator — used
 *  for the left rail's footer (More, Settings & Tools). */
@Composable
fun RailActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        IconButtonCa(icon, label, onClick, iconSize = 22, boxSize = 46)
        Text(
            label,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 10.5f.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
) {
    if (panels.size < 2) return
    val selectedIndex = panels.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val showLabels = panels.size <= 3
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
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
