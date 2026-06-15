package dev.ide.ui.editor.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca

/**
 * The drag-and-drop engine for the block canvas (the Sketchware interaction). A long-press on a block (or
 * a palette template) lifts a **ghost** that follows the finger; drop zones — gaps between statements,
 * value sockets, the trash — register their bounds and highlight when hovered; releasing over one resolves
 * to a [DropDescriptor]. The canvas turns that into a `Move`/`Insert`/`Delete`/`ReplaceSlot` block edit, so
 * dragging is just another way to author the same projected AST.
 */
class DragState {
    var payload by mutableStateOf<DragPayload?>(null)
    var pointer by mutableStateOf(Offset.Zero)            // current finger position, in root coordinates
    var hovered by mutableStateOf<DropDescriptor?>(null)  // the drop zone under the finger
    val targets = mutableStateMapOf<DropDescriptor, Rect>()
    /** Top-left of the canvas root in root coordinates — lets the ghost (a child of that root) translate a
     *  root-space [pointer] into its own local space. Set by the canvas via [Modifier.canvasOrigin]. */
    var canvasOrigin by mutableStateOf(Offset.Zero)

    val isDragging: Boolean get() = payload != null

    fun updateHover() {
        hovered = targets.entries
            .filter { it.value.contains(pointer) }
            .minByOrNull { it.value.area }       // most specific (smallest) zone wins
            ?.key
    }

    fun end(): DropDescriptor? {
        val drop = hovered
        payload = null; hovered = null
        return drop
    }

    private val Rect.area get() = width * height
}

/** What is being dragged. */
sealed interface DragPayload {
    val label: String

    /** A template dragged out of the palette → insert. */
    data class Template(override val label: String, val text: String, val cat: BlockCat) : DragPayload
    /** An existing block being relocated → move (or delete on the trash). */
    data class MoveBlock(override val label: String, val blockId: String, val sourceText: String) : DragPayload
}

/** Where a drop lands. Equality is the registry key, so each zone registers exactly one bounds. */
sealed interface DropDescriptor {
    /** Insert position [index] in the list slot [slotIndex] of [ownerId] (a gap between statements). */
    data class StatementGap(val ownerId: String, val slotIndex: Int, val index: Int) : DropDescriptor
    /** A value input — replace its content with the dropped value's text. */
    data class ValueSocket(val ownerId: String, val slotIndex: Int) : DropDescriptor
    /** The trash — delete the dragged block. */
    object Trash : DropDescriptor
}

/** Register this composable's bounds as a drop zone for [desc] (re-registers on every (re)placement). */
fun Modifier.dropZone(state: DragState, desc: DropDescriptor): Modifier =
    this.onGloballyPositioned { state.targets[desc] = it.boundsInRoot() }

/** Record this composable's root-space top-left into [DragState.canvasOrigin] (the ghost's frame of reference). */
fun Modifier.canvasOrigin(state: DragState): Modifier =
    this.onGloballyPositioned { state.canvasOrigin = it.boundsInRoot().topLeft }

/**
 * Make this composable draggable. A long-press starts the drag (so a plain tap still selects/edits);
 * [payload] is captured at start, [onResolve] is called with the drop zone on release (null = dropped
 * nowhere). While this source is the active drag, it dims via [graphicsLayer].
 */
fun Modifier.dragSource(state: DragState, payload: () -> DragPayload, onResolve: (DropDescriptor?) -> Unit): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    // pointerInput(Unit) runs its gesture block once and never restarts, so it captures whatever [payload]/
    // [onResolve] were on first composition. After an edit re-projects the tree those close over a stale
    // node/ctx, so a later move/delete acted on the wrong (or vanished) block. rememberUpdatedState keeps the
    // frozen gesture pointed at the latest closures without restarting the pointer pipeline.
    val currentPayload by rememberUpdatedState(payload)
    val currentResolve by rememberUpdatedState(onResolve)
    this
        .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
        .graphicsLayer { alpha = if (active) 0.4f else 1f }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { off -> active = true; state.payload = currentPayload(); state.pointer = origin + off; state.updateHover() },
                onDrag = { change, amount -> change.consume(); state.pointer += amount; state.updateHover() },
                onDragEnd = { active = false; currentResolve(state.end()) },
                onDragCancel = { active = false; state.end() },
            )
        }
}

/** The block-shaped chip that follows the finger during a drag. Drawn in the canvas root overlay. */
@Composable
fun DragGhost(state: DragState) {
    val payload = state.payload ?: return
    val cat = when (payload) {
        is DragPayload.Template -> payload.cat
        is DragPayload.MoveBlock -> BlockCat.Opaque
    }
    val fill = blockColor(cat)
    Text(
        payload.label,
        color = Ca.colors.block.text,
        style = Ca.type.code,
        modifier = Modifier
            .graphicsLayer {
                // [pointer] is in root coordinates; this ghost is placed at the canvas root's top-left, so
                // shift back by [canvasOrigin] to land it under the finger (the canvas is rarely at 0,0 —
                // it sits below the top bar / breadcrumb and beside any side panel).
                translationX = state.pointer.x - state.canvasOrigin.x - 36.dp.toPx()
                translationY = state.pointer.y - state.canvasOrigin.y - 16.dp.toPx()
                alpha = 0.92f
            }
            .background(fill, RoundedCornerShape(BlockMetrics.corner))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
