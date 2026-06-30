package dev.ide.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.ide.ui.platform.isMobilePlatform
import kotlinx.coroutines.launch

/**
 * Hosts the "peek the timestamp" gesture shared by the log views (the build console Log tab and the Logs
 * viewer).
 *
 * On mobile the per-row timestamp is hidden to reclaim horizontal width; the user drags the rows sideways
 * to peek it tucked at the left edge (Instagram/Snapchat style) and it snaps back when the drag ends. On
 * desktop this is a transparent pass-through — the timestamp stays inline (no width pressure, no touch).
 *
 * [content] renders the list and receives:
 *  - `reveal`: the current peek offset in px, or null on desktop. Read it from a draw-phase lambda
 *    (`graphicsLayer { translationX = reveal() }`) so dragging never recomposes the list. Translate the row
 *    body by `reveal()` and park the hidden timestamp at `reveal() - slotPx`.
 *  - `slotPx`: [slotWidth] in px — the fully-revealed gutter width and the drag's max travel.
 */
@Composable
internal fun PeekTimestampReveal(
    slotWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (reveal: (() -> Float)?, slotPx: Float) -> Unit,
) {
    if (!isMobilePlatform) {
        Box(modifier) { content(null, 0f) }
        return
    }
    val slotPx = with(LocalDensity.current) { slotWidth.toPx() }
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.clipToBounds().draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                scope.launch { reveal.snapTo((reveal.value + delta).coerceIn(0f, slotPx)) }
            },
            onDragStopped = { reveal.animateTo(0f) },
        ),
    ) {
        content({ reveal.value }, slotPx)
    }
}
