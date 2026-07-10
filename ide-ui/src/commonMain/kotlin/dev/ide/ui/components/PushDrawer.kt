package dev.ide.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The zone along the left screen edge where a rightward swipe always grabs the drawer. */
private val EdgeGrabWidth = 24.dp

/** Fling speed (px/s, expressed in dp) past which a release commits to open/close regardless of position. */
private val FlingCommit = 320.dp

/**
 * A **push** navigation drawer: [content] slides right to make room for [drawerContent] on the left —
 * the two travel as one plane (no scrim drawn *over* the content, the screen itself moves), the compact
 * counterpart to the expanded layout's docked navigator pane.
 *
 * Gestures (when [gesturesEnabled]) are nested-scroll aware, so they never fight the code editor:
 *  - a swipe **starting at the left screen edge** always drags the drawer open (intercepted before the
 *    editor sees it);
 *  - elsewhere, a rightward drag opens the drawer only once the content underneath has **consumed all it
 *    can** — i.e. the editor is already at its horizontal start — and only when the gesture is
 *    horizontal-dominant, so free 2D panning with a little x-drift never creeps the drawer out;
 *  - with the drawer open, the pushed content is covered by a tap-to-close catcher and a horizontal drag
 *    anywhere (drawer or content) moves the drawer; release settles to the nearer edge, flings commit.
 *
 * [open] is the hoisted state of record ([onOpenChange] reports gesture-driven settles); toggling it from
 * chrome (top-bar button, back press) animates the same offset the gestures drive.
 */
@Composable
fun PushDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    maxDrawerWidth: Dp = 320.dp,
    /** Observes the live open fraction (0 closed → 1 open), every frame of a gesture or settle — for
     *  chrome that mirrors the drawer's motion (the top-bar sidebar icon). */
    onProgress: (Float) -> Unit = {},
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val drawerWidth = if (maxWidth - 56.dp < maxDrawerWidth) maxWidth - 56.dp else maxDrawerWidth
        val maxPx = with(density) { drawerWidth.toPx() }
        val flingPx = with(density) { FlingCommit.toPx() }
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val scope = rememberCoroutineScope()
        val offset = remember { Animatable(0f) }
        offset.updateBounds(0f, maxPx)
        val drawerVisible by remember { derivedStateOf { offset.value > 0.5f } }

        fun dragBy(delta: Float) {
            val target = (offset.value + delta).coerceIn(0f, maxPx)
            scope.launch { offset.snapTo(target) }
        }

        // Settle after a release: fling velocity commits a direction, otherwise snap to the nearer edge.
        // The hoisted state is reported after the animation so an interrupted settle stays gesture-owned.
        suspend fun settle(velocityX: Float) {
            val target = when {
                velocityX > flingPx -> maxPx
                velocityX < -flingPx -> 0f
                offset.value >= maxPx / 2f -> maxPx
                else -> 0f
            }
            offset.animateTo(target, tween(Motion.BASE, easing = Motion.quiet), initialVelocity = velocityX)
            onOpenChange(target > 0f)
        }

        // External toggles (top-bar button, back press, initial state) animate to the same offset.
        LaunchedEffect(open, maxPx) {
            val target = if (open) maxPx else 0f
            if (offset.value != target) offset.animateTo(target, tween(Motion.SLOW, easing = Motion.quiet))
        }

        // Stream the open fraction to the caller — reads the same offset the panes are placed by, so a
        // mirroring icon can never drift from the drawer itself.
        val progress by rememberUpdatedState(onProgress)
        LaunchedEffect(maxPx) {
            snapshotFlow { if (maxPx > 0f) (offset.value / maxPx).coerceIn(0f, 1f) else 0f }
                .collect { progress(it) }
        }

        // Cooperates with any scrollable child (the editor's 2D/1D scroll, the tabs strip, lists): a
        // rightward drag's *leftover* — what the child chose not to consume, i.e. it's at its start —
        // opens the drawer once it exceeds slop and the gesture reads horizontal overall. From capture
        // until release every horizontal delta is claimed here (pre-scroll), so a mid-gesture reversal
        // moves the drawer back instead of scrolling the editor sideways underneath it.
        val connection = remember(maxPx, gesturesEnabled) {
            object : NestedScrollConnection {
                private var sumX = 0f
                private var sumY = 0f
                private var leakX = 0f
                private var captured = false
                private fun reset() { sumX = 0f; sumY = 0f; leakX = 0f; captured = false }

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!gesturesEnabled || source != NestedScrollSource.UserInput) return Offset.Zero
                    if (!captured) return Offset.Zero
                    dragBy(available.x)
                    return Offset(available.x, 0f)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (!gesturesEnabled || source != NestedScrollSource.UserInput) return Offset.Zero
                    sumX += consumed.x + available.x
                    sumY += consumed.y + available.y
                    if (!captured && offset.value == 0f) {
                        leakX = (leakX + available.x).coerceAtLeast(0f)
                        if (leakX > touchSlop && abs(sumX) > abs(sumY)) {
                            captured = true
                            dragBy(available.x)
                            return Offset(available.x, 0f)
                        }
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (captured) {
                        reset()
                        // Settle on `scope`, not this fling coroutine: it must be queued AFTER the last
                        // dragBy's snapTo (same single-threaded scope, FIFO) so a stale snap can't cancel
                        // the settle animation via the Animatable's mutation mutex and leave it stuck.
                        scope.launch { settle(available.x) }
                        return Velocity(available.x, 0f)
                    }
                    reset()
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    reset()
                    return Velocity.Zero
                }
            }
        }

        // Horizontal drags no child claimed (on the drawer panel, chrome, or the tap-catcher over pushed
        // content) move an open drawer directly — this is the drag-to-close path.
        val closeDrag = rememberDraggableState { delta -> dragBy(delta) }

        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                .draggable(
                    closeDrag,
                    Orientation.Horizontal,
                    enabled = gesturesEnabled && drawerVisible,
                    // Settle on `scope` so it is ordered after the drag's pending snapTo (see onPreFling):
                    // running it inline on the draggable coroutine let a stale snap cancel it, leaving the
                    // drawer stuck mid-swipe on release instead of snapping to the nearer edge.
                    onDragStopped = { velocity -> scope.launch { settle(velocity) } },
                )
                // Edge grab: a drag that *starts* within the left edge zone opens the drawer no matter
                // what sits under the finger. Watched on the Initial pass so, once claimed (past slop,
                // rightward, horizontal-dominant), the events are consumed before the editor's scroll
                // containers can treat them as a pan. A vertical-dominant start bails out and leaves the
                // gesture to the child untouched.
                .pointerInput(gesturesEnabled, maxPx) {
                    if (!gesturesEnabled) return@pointerInput
                    val edgePx = EdgeGrabWidth.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (offset.value > 0.5f || down.position.x > edgePx) return@awaitEachGesture
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
                                    scope.launch { settle(tracker.calculateVelocity().x) }
                                }
                                break
                            }
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                            if (!claimed) {
                                if (abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(totalX)) break
                                if (totalX > viewConfiguration.touchSlop && totalX > abs(totalY)) claimed = true
                            }
                            if (claimed) {
                                tracker.addPosition(change.uptimeMillis, change.position)
                                dragBy(delta.x)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            // ONE moving plane carries both panes (a push, not an overlay): the drawer hangs off the
            // plane's left edge at a constant offset, so the single dynamic placement lambda below is the
            // only state-driven one — the drawer can never fall out of sync with the content it pushes.
            // (Splitting them into two dynamically-offset siblings left the fully-offscreen drawer's
            // placement un-invalidated during the animation.)
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offset.value.roundToInt(), 0) },
            ) {
                // Composed only while it could be seen, so a closed drawer costs the editor nothing.
                if (drawerVisible || open) {
                    Box(
                        Modifier
                            .width(drawerWidth)
                            .fillMaxHeight()
                            .offset(x = -drawerWidth)
                            .background(Ca.colors.bg),
                    ) {
                        drawerContent()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    content()
                    if (drawerVisible) {
                        // Seam shading on the pushed content's leading edge — reads as depth, fades with openness.
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(14.dp)
                                .graphicsLayer { alpha = (offset.value / maxPx).coerceIn(0f, 1f) }
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent),
                                    ),
                                ),
                        )
                        // While the drawer is out, the pushed content is one tap from returning — and never
                        // accidentally editable under a stray touch.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures { onOpenChange(false) } },
                        )
                    }
                }
            }
        }
    }
}
