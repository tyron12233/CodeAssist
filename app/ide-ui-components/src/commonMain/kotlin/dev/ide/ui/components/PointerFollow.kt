package dev.ide.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * Places this element beside a pointer, on the reading direction's trailing side: right of it in a left-to-right
 * layout, left of it in a right-to-left one. [pointer] is in the parent's coordinates, which are physical, so this
 * places with [place][androidx.compose.ui.layout.Placeable.PlacementScope.place] and not `Modifier.offset`, which
 * mirrors under RTL and would put the element on the opposite side of the screen from the finger.
 *
 * The element takes up the whole parent (the parent must have a bounded size) so the parent's own alignment,
 * which also mirrors, does not move it. It is kept inside the parent's bounds.
 */
internal fun Modifier.followPointer(gapX: Int, gapY: Int, pointer: () -> Offset): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    layout(width, height) {
        val p = pointer()
        val x = if (layoutDirection == LayoutDirection.Rtl) p.x - gapX - placeable.width else p.x + gapX
        val y = p.y + gapY
        placeable.place(
            x.roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0)),
            y.roundToInt().coerceIn(0, (height - placeable.height).coerceAtLeast(0)),
        )
    }
}
