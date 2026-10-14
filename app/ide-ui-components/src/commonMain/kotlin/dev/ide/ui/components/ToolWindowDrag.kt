package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.ToolWindowSide
import dev.ide.ui.platform.touchInput
import dev.ide.ui.theme.Ca
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt

/** The tool window being dragged from its stripe button: what the ghost under the pointer shows. */
class DraggedToolWindow(val id: String, val title: String, val icon: ImageVector)

/**
 * A stripe button's drag to another side, IntelliJ-style. The host remembers one, provides it through
 * [LocalToolWindowDrag] and handles [onDrop]; stripe buttons start drags ([toolWindowDragSource]), and the
 * places a window can land register as drop zones ([toolWindowDropZone]). Positions are in root coordinates.
 */
@Stable
class ToolWindowDragState(val onDrop: (id: String, side: ToolWindowSide) -> Unit) {
    var dragging by mutableStateOf<DraggedToolWindow?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    private val zones = mutableStateMapOf<Any, Pair<Rect, ToolWindowSide>>()

    /** The zone under the pointer, the smallest when they nest (a stripe's bottom group inside the stripe). */
    val hovered: Pair<Any, ToolWindowSide>?
        get() {
            if (dragging == null) return null
            val p = pointer
            return zones.entries.filter { it.value.first.contains(p) }
                .minByOrNull { it.value.first.width * it.value.first.height }
                ?.let { it.key to it.value.second }
        }

    internal fun start(window: DraggedToolWindow, at: Offset) {
        dragging = window
        pointer = at
    }

    internal fun moveBy(delta: Offset) { pointer += delta }

    internal fun end(drop: Boolean) {
        val window = dragging
        val target = hovered?.second
        dragging = null
        if (drop && window != null && target != null) onDrop(window.id, target)
    }

    internal fun setZone(key: Any, bounds: Rect, side: ToolWindowSide) { zones[key] = bounds to side }
    internal fun removeZone(key: Any) { zones.remove(key) }
}

val LocalToolWindowDrag = staticCompositionLocalOf<ToolWindowDragState?> { null }

/**
 * Lets the element be dragged to another side as [window]. With a mouse the drag starts as soon as the pointer
 * moves; on touch it starts after a long-press (which also shows the button's label), so a swipe along the
 * stripe still scrolls it. [onDragStart] lets the button hide its label once the drag is under way.
 */
fun Modifier.toolWindowDragSource(window: DraggedToolWindow?, onDragStart: () -> Unit = {}): Modifier = composed {
    val drag = LocalToolWindowDrag.current
    if (drag == null || window == null) return@composed this
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestWindow by rememberUpdatedState(window)
    val latestStart by rememberUpdatedState(onDragStart)
    val touch = touchInput
    this.onGloballyPositioned { coords = it }
        .pointerInput(drag, touch) {
            val start: (Offset) -> Unit = { local ->
                val c = coords
                if (c != null && c.isAttached) {
                    drag.start(latestWindow, c.localToRoot(local))
                    latestStart()
                }
            }
            if (touch) {
                detectDragGesturesAfterLongPress(
                    onDragStart = start,
                    onDragEnd = { drag.end(drop = true) },
                    onDragCancel = { drag.end(drop = false) },
                    onDrag = { change, delta -> change.consume(); drag.moveBy(delta) },
                )
            } else {
                detectDragGestures(
                    onDragStart = start,
                    onDragEnd = { drag.end(drop = true) },
                    onDragCancel = { drag.end(drop = false) },
                    onDrag = { change, delta -> change.consume(); drag.moveBy(delta) },
                )
            }
        }
}

/** Makes the element a place a dragged tool window lands on [side], lit while one is over it. */
fun Modifier.toolWindowDropZone(side: ToolWindowSide): Modifier = composed {
    val drag = LocalToolWindowDrag.current ?: return@composed this
    val key = remember { Any() }
    DisposableEffect(drag, key) { onDispose { drag.removeZone(key) } }
    val lit = drag.hovered?.first === key
    this.onGloballyPositioned { drag.setZone(key, it.boundsInRoot(), side) }
        .then(
            if (lit) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(Ca.radius.md))
            else Modifier,
        )
}

/**
 * Shown over the editor while a tool window is dragged: three targets, one per side, so a window can be
 * dropped without aiming for a stripe (on a phone the stripes are narrow).
 */
@Composable
fun ToolWindowDropTargets(modifier: Modifier = Modifier) {
    val drag = LocalToolWindowDrag.current ?: return
    if (drag.dragging == null) return
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(scheme.scrim.copy(alpha = 0.28f)).padding(12.dp)) {
        Row(Modifier.fillMaxSize()) {
            DropTarget(ToolWindowSide.Left, Modifier.weight(0.3f).fillMaxHeight())
            Column(Modifier.weight(0.4f).fillMaxHeight().padding(horizontal = 12.dp)) {
                Spacer(Modifier.weight(0.5f))
                DropTarget(ToolWindowSide.Bottom, Modifier.weight(0.5f).fillMaxWidth())
            }
            DropTarget(ToolWindowSide.Right, Modifier.weight(0.3f).fillMaxHeight())
        }
    }
}

@Composable
private fun DropTarget(side: ToolWindowSide, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.md)
    Box(
        modifier
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.85f), shape)
            .border(1.dp, scheme.outline, shape)
            .toolWindowDropZone(side),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(sideIcon(side), null, Modifier.size(28.dp), tint = scheme.onSurfaceVariant)
            Text(
                sideLabel(side),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
        }
    }
}

/** The dragged window under the pointer. Place it in a full-size box covering the window; it positions itself. */
@Composable
fun ToolWindowDragGhost() {
    val drag = LocalToolWindowDrag.current ?: return
    val window = drag.dragging ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Row(
            Modifier
                .offset {
                    val p = drag.pointer - origin
                    IntOffset(p.x.roundToInt() + 12, p.y.roundToInt() + 12)
                }
                .background(scheme.inverseSurface, RoundedCornerShape(Ca.radius.md))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(window.icon, null, Modifier.size(16.dp), tint = scheme.inverseOnSurface)
            Text(
                window.title,
                Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.inverseOnSurface,
            )
        }
    }
}
