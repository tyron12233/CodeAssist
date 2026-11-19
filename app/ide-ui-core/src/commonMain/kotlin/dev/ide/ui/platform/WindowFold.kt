package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints

/**
 * The fold or hinge of a foldable device where it crosses the app's window, in window pixels.
 *
 * [separating] is true when the two sides should be treated as two areas: the device is half opened, or the
 * hinge physically covers part of the screen. A device lying flat with a seamless fold reports false, and
 * content may run across it.
 */
data class WindowFold(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** The fold runs top to bottom, with a left and a right side. False for a top and a bottom side. */
    val vertical: Boolean,
    val halfOpened: Boolean,
    val separating: Boolean,
)

/** The fold crossing the window, or null on a device or host without one. Follows the device's posture. */
@Composable
expect fun rememberWindowFold(): WindowFold?

/**
 * The part of [container] a floating element should be kept inside so the fold does not cut through it: the
 * larger side, and on a tie the trailing side of a vertical fold or the upper side of a horizontal one. The
 * whole container when the fold does not cross it.
 */
fun WindowFold.sideOf(container: Rect): Rect {
    if (vertical) {
        if (left <= container.left || right >= container.right) return container
        val before = left - container.left
        val after = container.right - right
        return if (before > after) container.copy(right = left.toFloat()) else container.copy(left = right.toFloat())
    }
    if (top <= container.top || bottom >= container.bottom) return container
    val above = top - container.top
    val below = container.bottom - bottom
    return if (below > above) container.copy(top = bottom.toFloat()) else container.copy(bottom = top.toFloat())
}

/**
 * Lays the content out on one side of a [WindowFold.separating] fold instead of across it. For a container
 * that spans the window and holds something centred, such as a dialog. Does nothing without such a fold.
 */
@Composable
fun Modifier.avoidFold(): Modifier {
    val fold = rememberWindowFold()?.takeIf { it.separating } ?: return this
    var bounds by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { bounds = it.boundsInWindow() }
        .layout { measurable, constraints ->
            val container = bounds
            val side = if (container.isEmpty) container else fold.sideOf(container)
            if (container.isEmpty || side == container) {
                val placeable = measurable.measure(constraints)
                return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            val width = side.width.toInt().coerceIn(0, constraints.maxWidth)
            val height = side.height.toInt().coerceIn(0, constraints.maxHeight)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((side.left - container.left).toInt(), (side.top - container.top).toInt())
            }
        }
}
