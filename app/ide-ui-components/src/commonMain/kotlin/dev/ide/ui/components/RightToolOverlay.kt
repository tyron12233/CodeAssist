package dev.ide.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The right-edge zone where a leftward swipe grabs the drawer open. */
private val EdgeGrabWidth = 24.dp

/** Fling speed (px/s, expressed in dp) past which a release commits to open/close regardless of position. */
private val FlingCommit = 320.dp

/**
 * The phone right-edge tool-window drawer: an overlay that tracks the finger continuously, mirroring
 * [PushDrawer] (which is left-only). It is fully plugin-derived — it hosts the RIGHT-anchored panels
 * ([pluginPanels]; the AI chat is the first such plugin), so it self-gates: when no plugin contributes a
 * RIGHT tool window it renders nothing, not even the gesture strip. When more than one plugin contributes a
 * RIGHT panel a [SegmentedPanelSwitcher] at the top flips between them (the phone counterpart of the desktop
 * right activity rail).
 *
 * An [Animatable] `shown` (0 = closed, panel off-screen right → openPx = fully open) is driven live: a
 * leftward swipe from the right edge opens, a drag or tap on the scrim closes, and a release settles to the
 * nearer edge (a fling commits). External toggles ([IdeUiState.selectedRightPanel], the phone swipe, back)
 * animate the same value.
 */
@Composable
fun RightToolOverlay(state: IdeUiState) {
    val panels = pluginPanels(ToolWindowAnchor.RIGHT, state.backend, state.active?.path)
    if (panels.isEmpty()) return
    // The drawer opens the currently-selected panel, defaulting to the first.
    val primaryId = panels.first().id
    // The panels' saveable state (their scroll positions above all), held outside the `if (visible)` below:
    // a closed drawer composes nothing, so this is what brings a panel back where the user left it.
    val panelState = rememberSaveableStateHolder()
    val headerSlots = rememberToolWindowHeaderSlots()

    val scope = rememberCoroutineScope()
    // 0 = fully closed (panel off-screen right), openPx = fully open (panel flush at the right edge). The open
    // width follows the layout below; it is kept here so the edge gesture, on the outer node, can read it.
    val shown = remember { Animatable(0f) }
    val openPxState = remember { mutableFloatStateOf(0f) }
    // Composited while open OR mid-animation; a fully closed drawer composes nothing over the editor.
    val visible by remember { derivedStateOf { shown.value > 0.5f || state.selectedRightPanel != null } }

    // A leftward drag (negative delta) opens; a rightward drag closes.
    fun dragBy(delta: Float) {
        scope.launch { shown.snapTo((shown.value - delta).coerceIn(0f, openPxState.floatValue)) }
    }

    suspend fun settle(velocityX: Float, flingPx: Float) {
        val openPx = openPxState.floatValue
        val target = when {
            velocityX < -flingPx -> openPx
            velocityX > flingPx -> 0f
            shown.value >= openPx / 2f -> openPx
            else -> 0f
        }
        // `shown` moves opposite to the pointer, so its initial velocity is the negated pointer velocity.
        shown.animateTo(target, tween(Motion.BASE, easing = Motion.quiet), initialVelocity = -velocityX)
        state.selectedRightPanel = if (target >= openPx / 2f) (state.selectedRightPanel ?: primaryId) else null
    }

    // The right-edge catcher, on the overlay's own node: while the drawer is closed nothing else is laid over the
    // screen. A leftward, horizontal-dominant drag past slop that starts within EdgeGrabWidth of the right edge
    // grabs the drawer and drives it continuously. Closed, the node shares its input with the siblings under it,
    // so taps and scrolls there still reach the top bar, editor and bottom bar; only a claimed drag is consumed.
    // Open, it stops sharing and the scrim keeps the editor from being touched.
    val edgeGrab = if (!isMobilePlatform) Modifier else Modifier.pointerInputSharedWithSiblings(
        key = Unit,
        share = { !visible },
    ) {
        val edgePx = EdgeGrabWidth.toPx()
        val flingPx = FlingCommit.toPx()
        val touchSlop = viewConfiguration.touchSlop
        // On the Initial pass, which reaches every node before any sees Main: the editor under the strip gets the
        // same swipe now and consumes its drags, so the drawer has to see the movement, and claim it, first.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (shown.value > 0.5f || down.position.x < size.width - edgePx) return@awaitEachGesture
            // Cooperate with the editor's horizontal scroll: if the active editor still has
            // content to reveal rightward (it is not at its horizontal end), this leftward
            // swipe belongs to it — don't grab it for the drawer. Grabbing unconditionally
            // stole horizontal scrolling near the right edge (issue #1493). The drawer still
            // opens by swipe once the editor is at its horizontal end or the file has no
            // horizontal overflow, and always via the top-bar button.
            val ed = state.active?.session
            if (ed != null && ed.hScrollOffsetPx < ed.hScrollMaxPx - 0.5f) return@awaitEachGesture
            var totalX = 0f
            var totalY = 0f
            var claimed = false
            val tracker = VelocityTracker()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (claimed) {
                        change.consume()
                        scope.launch { settle(tracker.calculateVelocity().x, flingPx) }
                    }
                    break
                }
                val delta = change.positionChangeIgnoreConsumed()
                totalX += delta.x
                totalY += delta.y
                if (!claimed) {
                    if (abs(totalY) > touchSlop && abs(totalY) > abs(totalX)) break
                    if (-totalX > touchSlop && -totalX > abs(totalY)) claimed = true
                }
                if (claimed) {
                    tracker.addPosition(change.uptimeMillis, change.position)
                    dragBy(delta.x)
                    change.consume()
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().then(edgeGrab)) {
        val density = LocalDensity.current
        val panelWidth = if (maxWidth < 480.dp) maxWidth - 48.dp else 420.dp
        val openPx = with(density) { panelWidth.toPx() }
        val flingPx = with(density) { FlingCommit.toPx() }
        SideEffect { openPxState.floatValue = openPx }
        shown.updateBounds(0f, openPx)

        // External toggles (top-bar button, back press, initial state) animate to the same offset.
        LaunchedEffect(state.selectedRightPanel, openPx) {
            val target = if (state.selectedRightPanel != null) openPx else 0f
            if (shown.value != target) shown.animateTo(target, tween(Motion.BASE, easing = Motion.quiet))
        }

        if (visible) {
            // Dimming scrim (modal): it blocks touches to the editor behind it and, on mobile, can be dragged
            // to close, but a plain tap does NOT dismiss — closing is explicit (the header's hide button, back, or swipe).
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = (shown.value / openPx).coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = 0.32f))
                    .draggable(
                        rememberDraggableState { dragBy(it) },
                        Orientation.Horizontal,
                        enabled = isMobilePlatform,
                        onDragStopped = { velocity -> scope.launch { settle(velocity, flingPx) } },
                    ),
            )
            // The open panel, falling back to the first so content stays rendered through the close animation.
            val selectedId = state.selectedRightPanel
            val effectiveId = selectedId ?: primaryId
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    // Slide from the right edge: translationX 0 fully open, off-screen when closed.
                    .offset { IntOffset((openPx - shown.value).roundToInt(), 0) },
            ) {
                ToolWindowSurface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        ToolWindowSwitcherHeader(
                            panels = panels,
                            selectedId = effectiveId,
                            onSelect = { state.selectRightPanel(it) },
                            slots = headerSlots,
                            onHide = { state.selectedRightPanel = null },
                        )
                        // Key on the stable id (not the panel object) so the crossfade doesn't restart on every
                        // incidental recompose (e.g. while the drawer slides).
                        AnimatedContent(
                            targetState = effectiveId,
                            transitionSpec = { fadeIn(tween(Motion.BASE)) togetherWith fadeOut(tween(Motion.FAST)) },
                            label = "rightPanelSwitch",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { id ->
                            // Keyed by the id being shown, not by the panel it resolved to: a stale selection
                            // falls back to the first panel, which the panel it is crossfading with may be.
                            PanelContent(
                                panels.firstOrNull { it.id == id } ?: panels.first(),
                                panelState,
                                Modifier.fillMaxSize(),
                                key = id,
                                headerSlot = headerSlots.of(id),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * [androidx.compose.ui.input.pointer.pointerInput] whose hits also reach the siblings laid out under it.
 *
 * By default Compose hit-tests only the topmost sibling under a pointer, so an overlay that never consumes
 * anything still keeps every sibling below it from seeing the event: the edge strip the drawer used to lay over
 * the screen left a dead band down its right side, where the top bar's buttons and the editor ignored taps. The
 * parent asks only its direct children whether they share, so this must sit on the overlay's outermost node, not
 * on a child inside it. Shares while [share] is true; [block] restarts when [key] changes, as with `pointerInput`.
 */
private fun Modifier.pointerInputSharedWithSiblings(
    key: Any?,
    share: () -> Boolean,
    block: suspend PointerInputScope.() -> Unit,
): Modifier = this then SharedPointerInputElement(key, share, block)

private class SharedPointerInputElement(
    val key: Any?,
    val share: () -> Boolean,
    val block: suspend PointerInputScope.() -> Unit,
) : ModifierNodeElement<SharedPointerInputNode>() {
    override fun create() = SharedPointerInputNode(key, share, block)
    override fun update(node: SharedPointerInputNode) = node.update(key, share, block)
    // Equal on the key alone: a new lambda each recomposition must not restart a gesture in progress.
    override fun equals(other: Any?) = other is SharedPointerInputElement && other.key == key
    override fun hashCode() = key.hashCode()
}

private class SharedPointerInputNode(
    private var key: Any?,
    private var share: () -> Boolean,
    private var block: suspend PointerInputScope.() -> Unit,
) : DelegatingNode(), PointerInputModifierNode {
    private val input = delegate(SuspendingPointerInputModifierNode { block(this) })

    fun update(key: Any?, share: () -> Boolean, block: suspend PointerInputScope.() -> Unit) {
        this.share = share
        this.block = block
        if (key != this.key) {
            this.key = key
            input.resetPointerInputHandler()
        }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
        input.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = input.onCancelPointerInput()

    override fun sharePointerInputWithSiblings() = share()
}
