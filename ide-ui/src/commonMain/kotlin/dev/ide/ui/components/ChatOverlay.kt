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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContext
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The right-edge zone where a leftward swipe grabs the chat drawer open. */
private val EdgeGrabWidth = 24.dp

/** Fling speed (px/s, expressed in dp) past which a release commits to open/close regardless of position. */
private val FlingCommit = 320.dp

/**
 * The phone chat drawer: a right-edge overlay that tracks the finger continuously, mirroring [PushDrawer]
 * (which is left-only). An [Animatable] `shown` (0 = closed, panel off-screen right → openPx = fully open)
 * is driven live: a leftward swipe from the right edge opens, a drag or tap on the scrim closes, and a
 * release settles to the nearer edge (a fling commits). External toggles ([IdeUiState.chatOpen], the top-bar
 * button, back) animate the same value.
 *
 * IMPORTANT: while closed, the ONLY overlay laid over the editor is a thin right-edge catcher strip, so the
 * editor stays fully touchable — the dimming scrim (which is modal and does block touches) is composed only
 * once the drawer is open or animating. The catcher strip is persistent, so a swipe it captures keeps
 * driving the drag (pointer capture) even after the panel appears and the finger travels off the strip.
 */
@Composable
internal fun ChatOverlay(state: IdeUiState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val panelWidth = if (maxWidth < 480.dp) maxWidth - 48.dp else 420.dp
        val openPx = with(density) { panelWidth.toPx() }
        val flingPx = with(density) { FlingCommit.toPx() }
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val scope = rememberCoroutineScope()

        // 0 = fully closed (panel off-screen right), openPx = fully open (panel flush at the right edge).
        val shown = remember { Animatable(0f) }
        shown.updateBounds(0f, openPx)
        // Composited while open OR mid-animation; a fully closed drawer composes nothing over the editor.
        val visible by remember { derivedStateOf { shown.value > 0.5f || state.chatOpen } }

        // A leftward drag (negative delta) opens; a rightward drag closes.
        fun dragBy(delta: Float) {
            scope.launch { shown.snapTo((shown.value - delta).coerceIn(0f, openPx)) }
        }

        suspend fun settle(velocityX: Float) {
            val target = when {
                velocityX < -flingPx -> openPx
                velocityX > flingPx -> 0f
                shown.value >= openPx / 2f -> openPx
                else -> 0f
            }
            // `shown` moves opposite to the pointer, so its initial velocity is the negated pointer velocity.
            shown.animateTo(target, tween(Motion.BASE, easing = Motion.quiet), initialVelocity = -velocityX)
            state.chatOpen = target >= openPx / 2f
        }

        // External toggles (top-bar sparkle, back press, initial state) animate to the same offset.
        LaunchedEffect(state.chatOpen, openPx) {
            val target = if (state.chatOpen) openPx else 0f
            if (shown.value != target) shown.animateTo(target, tween(Motion.BASE, easing = Motion.quiet))
        }

        // Persistent thin right-edge catcher: the only thing laid over the editor while closed. A leftward,
        // horizontal-dominant drag past slop grabs the drawer and drives it continuously; taps and vertical
        // scrolls in the strip are not consumed, so they fall through to the editor.
        if (isMobilePlatform) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EdgeGrabWidth)
                    .pointerInput(openPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (shown.value > 0.5f) return@awaitEachGesture
                            var totalX = 0f
                            var totalY = 0f
                            var claimed = false
                            val tracker = VelocityTracker()
                            while (true) {
                                val event = awaitPointerEvent()
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
                    },
            )
        }

        if (visible) {
            // Dimming scrim (modal): tap or, on mobile, drag it to close; alpha tracks openness.
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = (shown.value / openPx).coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pointerInput(Unit) { detectTapGestures { state.chatOpen = false } }
                    .draggable(
                        rememberDraggableState { dragBy(it) },
                        Orientation.Horizontal,
                        enabled = isMobilePlatform,
                        onDragStopped = { velocity -> scope.launch { settle(velocity) } },
                    ),
            )
            UiPluginHost.ensureLoaded()
            val tool = ToolWindowRegistry.forAnchor(ToolWindowAnchor.RIGHT).firstOrNull()
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    // Slide from the right edge: translationX 0 fully open, off-screen when closed.
                    .offset { IntOffset((openPx - shown.value).roundToInt(), 0) },
            ) {
                GlassSurface(Modifier.fillMaxSize(), GlassMaterial.Thick) {
                    if (tool != null) {
                        val backend = state.backend
                        val active = state.active?.path
                        val ctx = remember(backend, active) {
                            object : ToolWindowContext {
                                override val backend = backend
                                override val activeFilePath = active
                            }
                        }
                        tool.content(ctx)
                    }
                }
            }
        }
    }
}
