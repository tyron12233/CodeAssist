package dev.ide.android.layouteditor

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutViewClass
import kotlin.math.roundToInt

/** What is being dragged: a new view from the palette, or an element of the layout being moved. */
internal sealed interface DragPayload {
    val tag: String

    class New(val cls: LayoutViewClass) : DragPayload {
        override val tag: String get() = cls.tag
    }

    class Move(val element: LayoutElement) : DragPayload {
        override val tag: String get() = element.tag
    }
}

/**
 * A drag in flight. [position] is in root coordinates (the window's), the one space the palette, the canvas and
 * the tree all share, so a drag can start in one and end in another. A surface that can take a drop registers a
 * resolver for the point ([canvasTarget], [treeTarget]); the tree is asked first, so a drop on a tree row means
 * that row.
 */
@Stable
internal class DragState {
    var payload by mutableStateOf<DragPayload?>(null)
    var position by mutableStateOf(Offset.Zero)

    var canvasTarget: ((Offset) -> DropTarget?)? = null
    var treeTarget: ((Offset) -> DropTarget?)? = null

    fun targetAt(p: Offset): DropTarget? = treeTarget?.invoke(p) ?: canvasTarget?.invoke(p)
}

/**
 * Makes a composable a drag source: a long press picks up [payload], the drag follows the finger, and the release
 * hands the drop target under it to [onDrop] (null when it was let go over nothing that takes it).
 */
@Composable
internal fun Modifier.dragSource(
    drag: DragState,
    payload: () -> DragPayload?,
    onDrop: (DragPayload, DropTarget?) -> Unit,
    /** Start on the first movement rather than after a long press: for a drag handle, which does nothing else. */
    immediate: Boolean = false,
): Modifier {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentPayload by rememberUpdatedState(payload)
    val currentDrop by rememberUpdatedState(onDrop)
    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(drag, immediate) {
            val start = { at: Offset ->
                currentPayload()?.let { p ->
                    drag.position = (coords?.positionInRoot() ?: Offset.Zero) + at
                    drag.payload = p
                }
                Unit
            }
            val move = { change: androidx.compose.ui.input.pointer.PointerInputChange, amount: Offset ->
                if (drag.payload != null) {
                    change.consume()
                    drag.position += amount
                }
            }
            val end = {
                drag.payload?.let { p ->
                    val target = drag.targetAt(drag.position)
                    drag.payload = null
                    currentDrop(p, target)
                }
                Unit
            }
            val cancel = { drag.payload = null }
            if (immediate) detectDragGestures(start, end, cancel, move)
            else detectDragGesturesAfterLongPress(start, end, cancel, move)
        }
}

/** The view under the finger while it is dragged: its glyph and name, lifted. [origin] is the host's root offset. */
@Composable
internal fun DragGhost(drag: DragState, origin: Offset) {
    val payload = drag.payload ?: return
    val p = drag.position - origin
    Surface(
        modifier = Modifier
            .offset { IntOffset((p.x - 40.dp.toPx()).roundToInt(), (p.y - 64.dp.toPx()).roundToInt()) }
            .graphicsLayer { rotationZ = -4f }
            .shadow(12.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Symbol(glyphFor(payload.tag), null, size = 20.dp, filled = true)
            Text(payload.tag.substringAfterLast('.'), style = MaterialTheme.typography.labelLarge)
        }
    }
}
