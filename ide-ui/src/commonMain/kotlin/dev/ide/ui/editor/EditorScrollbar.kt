package dev.ide.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** The bar's touch target; the drawn thumb is [ThumbHeight], centred inside it. */
private val StripHeight = 14.dp
private val ThumbHeight = 5.dp
private val ThumbMinWidth = 28.dp

/** Opacity while the thumb is held, and at rest. */
private const val ACTIVE_ALPHA = 1f
private const val IDLE_ALPHA = 0.45f

/**
 * The editor's horizontal scrollbar (Settings → Editor): a draggable bar along the bottom edge of the content
 * area, shown while a line runs past the right edge of the view. It is an overlay rather than reserved layout,
 * so it costs the document no height, and it disappears with the overflow that produced it: word wrap, which
 * removes horizontal scrolling entirely, also removes the bar.
 *
 * Dragging feeds [EditorGeometry.hScroll] through `dispatchRawDelta`, the same state a touch drag or a wheel
 * moves, so clamping and the session's scroll mirrors stay in one place. Thumb geometry is read in the draw
 * phase, as the rest of the editor's scroll-dependent painting is, so a fling repaints without recomposing.
 */
@Composable
internal fun BoxScope.HorizontalScrollbarLayer(
    geometry: EditorGeometry,
    gutterWidthPx: Float,
    thumbColor: Color,
    trackColor: Color,
) {
    // Reads the viewport size, the document's longest line and the chip extent, all snapshot state: the bar
    // appears and disappears as the content outgrows the viewport, but not per scrolled pixel.
    if (geometry.maxH() <= 0.5f) return

    val density = LocalDensity.current
    val gutter = with(density) { gutterWidthPx.toDp() }
    val minThumbPx = with(density) { ThumbMinWidth.toPx() }
    val trackWidthPx = remember { mutableFloatStateOf(0f) }

    // Brightens while the thumb is held. Deliberately keyed on the drag and NOT on the scroll offset: watching
    // the offset would recompose this layer and run an animation on the first pixel of every pan, which is the
    // last thing a scrolling editor needs. Scrolling repaints the thumb through the draw phase alone.
    var dragging by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (dragging) ACTIVE_ALPHA else IDLE_ALPHA, label = "editorHScrollbar")

    val drag = rememberDraggableState { delta ->
        val track = trackWidthPx.floatValue
        val maxH = geometry.maxH()
        val thumb = scrollbarThumbWidth(track, geometry.contentWidth(), maxH, minThumbPx)
        val content = scrollbarContentDelta(delta, track, thumb, maxH)
        if (content != 0f) geometry.hScroll.dispatchRawDelta(content)
    }

    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(start = gutter)
            .height(StripHeight)
            .onSizeChanged { trackWidthPx.floatValue = it.width.toFloat() }
            .draggable(
                drag,
                Orientation.Horizontal,
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false },
            )
            .drawBehind {
                val maxH = geometry.maxH()
                if (maxH <= 0.5f) return@drawBehind
                val thumbW = scrollbarThumbWidth(size.width, geometry.contentWidth(), maxH, minThumbPx)
                val x = scrollbarThumbOffset(size.width, thumbW, geometry.hOffset.floatValue, maxH)
                val h = ThumbHeight.toPx()
                val y = (size.height - h) / 2f
                val radius = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(
                    color = trackColor.copy(alpha = trackColor.alpha * alpha),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, h),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = thumbColor.copy(alpha = thumbColor.alpha * alpha),
                    topLeft = Offset(x, y),
                    size = Size(thumbW, h),
                    cornerRadius = radius,
                )
            },
    )
}

/**
 * The thumb's width: the same fraction of the track that the viewport is of the content, so the thumb reads as
 * "how much of the line you can see". Clamped to [minThumbPx] so a very long line still leaves something to
 * grab (the track itself is the floor when it is smaller than that).
 */
internal fun scrollbarThumbWidth(
    trackWidthPx: Float,
    contentWidthPx: Float,
    maxScrollPx: Float,
    minThumbPx: Float,
): Float {
    if (trackWidthPx <= 0f) return 0f
    if (contentWidthPx <= 0f) return trackWidthPx
    val visible = (contentWidthPx - maxScrollPx).coerceAtLeast(0f)
    return (trackWidthPx * (visible / contentWidthPx)).coerceIn(minThumbPx.coerceAtMost(trackWidthPx), trackWidthPx)
}

/** Where the thumb sits: it reaches the end of its travel exactly when the content is scrolled to [maxScrollPx]. */
internal fun scrollbarThumbOffset(
    trackWidthPx: Float,
    thumbWidthPx: Float,
    scrollPx: Float,
    maxScrollPx: Float,
): Float {
    val travel = (trackWidthPx - thumbWidthPx).coerceAtLeast(0f)
    if (travel <= 0f || maxScrollPx <= 0f) return 0f
    return travel * (scrollPx / maxScrollPx).coerceIn(0f, 1f)
}

/** Thumb pixels to content pixels: dragging the thumb its full travel scrolls the content its full range. */
internal fun scrollbarContentDelta(
    thumbDeltaPx: Float,
    trackWidthPx: Float,
    thumbWidthPx: Float,
    maxScrollPx: Float,
): Float {
    val travel = (trackWidthPx - thumbWidthPx).coerceAtLeast(0f)
    if (travel <= 0f || maxScrollPx <= 0f) return 0f
    return thumbDeltaPx * (maxScrollPx / travel)
}
