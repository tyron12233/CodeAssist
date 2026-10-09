package dev.ide.ui.editor.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The canvas's pan and zoom: a canvas point `c` shows at `c * scale + offset` in the canvas's own space. */
@Stable
class BlockCanvasState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun toCanvas(local: Offset): Offset = (local - offset) / scale
    fun toLocal(canvas: Offset): Offset = canvas * scale + offset

    /** Zoom by [factor] keeping the canvas point under [focus] (local) where it is. */
    fun zoomAround(focus: Offset, factor: Float) {
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        offset = focus - (focus - offset) * (next / scale)
        scale = next
    }

    fun reset() { scale = 1f; offset = Offset.Zero }

    companion object {
        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 2.5f
    }
}

/** What a drag carries. */
sealed interface DragPayload {
    /**
     * [count] statements starting at [firstId] in [doc]: a block dragged with the stack below it. [text] is
     * their source; its continuation lines are indented by [indent]. [wholeScratch] = the run is an entire
     * scratch stack (so dropping it loose just moves the stack).
     */
    data class Run(val doc: DocRef, val firstId: String, val count: Int, val ids: Set<String>, val text: String, val indent: String, val wholeScratch: Boolean) : DragPayload

    /** The value in socket [slotIndex] of [ownerId] ([nodeId] is the value itself). */
    data class Value(
        val doc: DocRef, val ownerId: String, val slotIndex: Int, val nodeId: String, val text: String, val wholeScratch: Boolean,
        /** It is a call's argument: lifting it removes the argument (and its comma), not just its value. */
        val argument: Boolean = false,
    ) : DragPayload

    /** A palette template. */
    data class Template(val template: PaletteTemplate) : DragPayload
}

/**
 * A drag in flight: what it carries, its laid-out preview ([body] for statements, [value] for a reporter),
 * how it connects ([shape]), and [grab], the finger's offset from the preview's top-left in canvas units.
 */
class ActiveDrag(val payload: DragPayload, val body: LBody?, val value: LBlock?, val shape: DragShape, val grab: Offset) {
    /** The block the drop silhouette takes the outline of. */
    val lead: LBlock? get() = body?.blocks?.firstOrNull() ?: value
}

/**
 * The one drag in progress, shared by the canvas and the palette (a template is dragged out of the palette
 * onto the canvas). [pointer] is in root coordinates; [trash] holds root-space rects that delete on drop.
 */
@Stable
class BlockDrag {
    var active by mutableStateOf<ActiveDrag?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    val trash = mutableStateMapOf<String, Rect>()
    val overTrash: Boolean get() = active != null && trash.values.any { it.contains(pointer) }

    internal var onMove: (() -> Unit)? = null
    internal var onRelease: ((ActiveDrag) -> Unit)? = null

    fun begin(drag: ActiveDrag, at: Offset) { active = drag; pointer = at; onMove?.invoke() }
    fun move(at: Offset) { pointer = at; onMove?.invoke() }
    fun release() { val d = active ?: return; active = null; onRelease?.invoke(d) }
    fun cancel() { active = null }
}

private const val SNAP_STATEMENT_DP = 56f
private const val SNAP_VALUE_DP = 40f
private const val HYSTERESIS_DP = 8f
private const val EDGE_DP = 36f

/**
 * The block canvas: draws a laid-out page under a pan/zoom transform and turns gestures into block intent.
 *
 * A tap goes to [onTap]. A long-press (immediately, for a mouse) on a block asks [startDrag] what it lifts;
 * while it moves, the nearest connection within reach is found against the page laid out without the lifted
 * blocks, the list opens up where it would land and a silhouette of the block marks the spot, and release
 * hands the target (or none, or the trash) to [onDrop]. A drag can also arrive from the palette through the
 * shared [drag]. Empty space pans; two fingers, Ctrl+wheel or the zoom buttons zoom.
 *
 * [layoutFor] lays the page out with a dragged run hidden and a drop gap opened; it is called with nulls for
 * the resting page. [overlay] (the inline editor) is placed over [overlayRect] and scaled with the canvas.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BlockCanvas(
    layoutFor: (Hidden?, Gap?) -> CanvasLayout,
    state: BlockCanvasState,
    drag: BlockDrag,
    ink: BlockInk,
    selected: Pair<DocRef, String>?,
    onTap: (CanvasIndex.Hit?, CanvasIndex) -> Unit,
    startDrag: (CanvasIndex.Hit, CanvasIndex, Offset) -> ActiveDrag?,
    onDrop: (ActiveDrag, DropTarget?, Offset, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    overlayRect: ((CanvasIndex) -> Rect?)? = null,
    overlay: (@Composable () -> Unit)? = null,
    description: String = "",
    zoomControls: Boolean = true,
) {
    val base = remember(layoutFor) { layoutFor(null, null) }
    val g = base.geometry
    val active = drag.active
    // While dragging: the page without the lifted blocks, and every place they could connect.
    val hidden = remember(active) { active?.payload?.let(::hiddenOf) }
    val dragBase = remember(layoutFor, hidden) { if (hidden == null) base else layoutFor(hidden, null) }
    val targets = remember(dragBase, active) {
        val idx = CanvasIndex(dragBase)
        when {
            active == null -> emptyList()
            active.shape.isValue -> idx.socketTargets()
            else -> idx.stackTargets()
        }
    }
    var target by remember { mutableStateOf<DropTarget?>(null) }
    var originRoot by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    // The page as shown: opened up where an insertion would land.
    val gap = (target as? DropTarget.Stack)?.takeUnless { it.above }?.let { t ->
        Gap(t.doc, t.ownerId, t.slotIndex, t.index, active?.lead?.h ?: 0f)
    }
    val shown = remember(dragBase, gap) { if (gap == null) dragBase else layoutFor(hidden, gap) }
    val index = remember(shown) { CanvasIndex(shown) }

    val currentIndex by rememberUpdatedState(index)
    val currentTap by rememberUpdatedState(onTap)
    val currentStart by rememberUpdatedState(startDrag)
    val currentDrop by rememberUpdatedState(onDrop)
    val currentTargets by rememberUpdatedState(targets)
    val haptics = LocalHapticFeedback.current

    fun draggedTopLeft(d: ActiveDrag): Offset = state.toCanvas(drag.pointer - originRoot) - d.grab

    fun retarget() {
        val d = drag.active ?: run { target = null; return }
        if (drag.overTrash) { target = null; return }
        val threshold = (if (d.shape.isValue) SNAP_VALUE_DP else SNAP_STATEMENT_DP) * g.density / state.scale
        target = nearestTarget(currentTargets, d.shape, draggedTopLeft(d), threshold, target, HYSTERESIS_DP * g.density / state.scale)
    }

    // Wire the shared drag: every move re-picks the target; release resolves it.
    SideEffect {
        drag.onMove = { retarget() }
        drag.onRelease = { d ->
            val t = target
            val landing = draggedTopLeft(d)
            val trash = drag.trash.values.any { it.contains(drag.pointer) }
            target = null
            currentDrop(d, if (trash) null else t, landing, trash)
        }
    }
    LaunchedEffect(targets) { if (drag.active != null) retarget() }

    // Near an edge mid-drag, the canvas scrolls by itself so a drop can reach off-screen.
    LaunchedEffect(active != null) {
        if (active == null) return@LaunchedEffect
        val edge = EDGE_DP * g.density
        while (drag.active != null) {
            withFrameNanos { }
            val p = drag.pointer - originRoot
            val dx = when { p.x < edge -> edge - p.x; p.x > size.width - edge -> size.width - edge - p.x; else -> 0f }
            val dy = when { p.y < edge -> edge - p.y; p.y > size.height - edge -> size.height - edge - p.y; else -> 0f }
            if (dx != 0f || dy != 0f) {
                state.offset += Offset(dx, dy) * 0.25f
                retarget()
            }
        }
    }

    Box(
        modifier
            .onGloballyPositioned { originRoot = it.positionInRoot(); size = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()) }
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type != PointerEventType.Scroll) continue
                        val c = ev.changes.firstOrNull() ?: continue
                        val d = c.scrollDelta
                        if (ev.keyboardModifiers.isCtrlPressed || ev.keyboardModifiers.isMetaPressed) {
                            state.zoomAround(c.position, if (d.y < 0f) 1.1f else 1f / 1.1f)
                        } else {
                            state.offset -= d * (40f * g.density)
                        }
                        ev.changes.forEach { it.consume() }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val mouse = down.type == PointerType.Mouse
                    val idx = currentIndex
                    val at = state.toCanvas(down.position)
                    val hit = idx.hitAt(at)
                    // A function's hat stays put; pressing it pans.
                    val canDrag = hit != null && !(hit is CanvasIndex.Hit.Block && hit.placed.block.kind == LKind.Hat)
                    val slop = viewConfiguration.touchSlop
                    val timeout = if (hit != null && canDrag && !mouse) (viewConfiguration.longPressTimeoutMillis * 0.6).toLong() else Long.MAX_VALUE
                    // 1 = lifted (a tap), 2 = moved past slop, 3 = a second finger; null = held (a long-press).
                    val outcome = withTimeoutOrNull(timeout) {
                        var result = 0
                        while (result == 0) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            result = when {
                                ev.changes.count { it.pressed } > 1 -> 3
                                c == null || !c.pressed -> 1
                                (c.position - down.position).getDistance() > slop -> 2
                                else -> 0
                            }
                        }
                        result
                    }
                    when {
                        outcome == 1 -> currentTap(hit, idx)
                        (outcome == null || (outcome == 2 && mouse)) && hit != null && canDrag -> {
                            val d = currentStart(hit, idx, at)
                            if (d == null) {
                                panLoop(state)
                            } else {
                                if (!mouse) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                drag.begin(d, down.position + originRoot)
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!c.pressed) break
                                        drag.move(c.position + originRoot)
                                        c.consume()
                                    }
                                    drag.release()
                                } finally {
                                    if (drag.active === d) drag.cancel()
                                }
                            }
                        }
                        else -> panLoop(state) // a held or moved press on empty space pans
                    }
                }
            },
    ) {
        val ps = PaintState(ink, g, selected)
        Canvas(Modifier.fillMaxSize()) {
            // The page stays inside the canvas; only the block in hand may leave it (towards the trash or palette).
            clipRect {
                withTransform({
                    translate(state.offset.x, state.offset.y)
                    scale(state.scale, state.scale, pivot = Offset.Zero)
                }) {
                    drawLayout(shown, ps)
                    val d = drag.active
                    if (d != null) when (val t = target) {
                        is DropTarget.Stack -> {
                            if (t.above) d.lead?.let { drawSilhouette(it, landingOf(t, d.shape), ps) }
                            else index.bodies.firstOrNull { it.doc == t.doc && it.body.ownerId == t.ownerId && it.body.slotIndex == t.slotIndex && it.body.gapY != null }
                                ?.let { pb -> d.lead?.let { drawSilhouette(it, pb.origin + Offset(0f, pb.body.gapY!!), ps) } }
                        }
                        is DropTarget.Wrap -> d.lead?.let { drawSilhouette(it, landingOf(t, d.shape), ps) }
                        is DropTarget.Socket -> drawSocketHighlight(t.rect, t.shape, ps)
                        null -> {}
                    }
                }
            }
            withTransform({
                translate(state.offset.x, state.offset.y)
                scale(state.scale, state.scale, pivot = Offset.Zero)
            }) {
                val d = drag.active
                if (d != null) {
                    drawFloating(d.body, d.value, draggedTopLeft(d), ps.copyUnselected())
                }
            }
        }
        val rect = overlayRect?.invoke(index)
        if (overlay != null && rect != null) {
            val p = state.toLocal(rect.topLeft)
            Box(Modifier.matchParentSize().clipToBounds()) {
                Box(
                    Modifier
                        .offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                        .graphicsLayer { scaleX = state.scale; scaleY = state.scale; transformOrigin = TransformOrigin(0f, 0f) },
                ) { overlay() }
            }
        }
        if (zoomControls) ZoomControls(state, { Offset(size.width / 2, size.height / 2) }, Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}

private fun PaintState.copyUnselected() = PaintState(ink, g, null)

/** What the layout leaves out while [p] is being dragged. */
private fun hiddenOf(p: DragPayload): Hidden? = when (p) {
    is DragPayload.Run -> Hidden(p.doc, p.ids)
    is DragPayload.Value -> Hidden(p.doc, setOf(p.nodeId))
    is DragPayload.Template -> null
}

/** Pan with one finger; a second one turns it into pinch-zoom. Ends when every finger lifts. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.panLoop(state: BlockCanvasState) {
    while (true) {
        val ev = awaitPointerEvent()
        val pressed = ev.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        if (pressed.size > 1) {
            val zoom = ev.calculateZoom()
            val centroid = ev.calculateCentroid(useCurrent = true)
            if (zoom != 1f && centroid != Offset.Unspecified) state.zoomAround(centroid, zoom)
            state.offset += ev.calculatePan()
        } else {
            val c = pressed.first()
            state.offset += c.position - c.previousPosition
        }
        ev.changes.forEach { it.consume() }
    }
}

@Composable
private fun ZoomControls(state: BlockCanvasState, center: () -> Offset, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        ZoomButton("+") { state.zoomAround(center(), 1.2f) }
        ZoomButton("\u2212") { state.zoomAround(center(), 1f / 1.2f) }
        ZoomButton("1:1") { state.reset() }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
    }
}
