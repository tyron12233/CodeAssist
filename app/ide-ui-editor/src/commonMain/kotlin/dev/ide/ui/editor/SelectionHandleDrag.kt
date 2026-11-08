package dev.ide.ui.editor

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * One drag of a touch selection handle, as a document offset that follows the finger without jumping.
 *
 * Three things keep a shaky finger from throwing the selection around (issue #1751):
 * - **Relative movement.** The handle moves by how far the finger has travelled since it grabbed it, not to
 *   where the finger is. The grab area is much wider than the drawn handle, so a finger that landed off its
 *   centre used to snap it a few characters sideways on the first pixel of movement.
 * - **A fixed far end.** The end that isn't being dragged is recorded once, at the grab. Reading it back from
 *   the live selection each frame meant that once the dragged end crossed it, the "fixed" end became the
 *   finger itself and the selection collapsed onto the finger.
 * - **A dead zone and boundary resistance.** Nothing moves until the finger has travelled [deadZone], and the
 *   handle only changes character once the finger is clearly past the boundary between two of them, so a
 *   finger resting on a boundary no longer flickers between the two.
 *
 * Pure over two lookups so it can be tested on a fake grid: [offsetAt] maps a viewport point to the nearest
 * caret offset, [caretAt] gives an offset's caret x and the top of its visual row (viewport coordinates, as
 * [EditorGeometry.caretGeometry] does).
 */
internal class SelectionHandleDrag(
    /** The selection end that stays put, or null when dragging the lone caret handle. */
    private val fixedEnd: Int?,
    /** The dragged end's offset at the grab. */
    start: Int,
    /** Where the finger went down. */
    private val grab: Offset,
    /** The point in the text the dragged end stands for: its caret x, mid-row. */
    hotspot: Offset,
    private val lineHeight: Float,
    private val deadZone: Float,
    /** How far past the midpoint between two characters the finger must be, horizontally, to change character. */
    private val resistX: Float,
    /** How far into a neighbouring row the finger must be to change row. */
    private val resistY: Float,
) {
    private val fingerToHotspot = hotspot - grab

    /** The dragged end's current offset. */
    var moving: Int = start
        private set

    /** True once the finger has left the dead zone; until then the handle hasn't moved. */
    var started: Boolean = false
        private set

    /** The selection to show: `fixedEnd..moving`, or a caret at [moving]. */
    val selection: Pair<Int, Int> get() = (fixedEnd ?: moving) to moving

    /** Follow the finger to [finger]. Returns true when [moving] changed. */
    fun moveTo(
        finger: Offset,
        offsetAt: (Offset) -> Int,
        caretAt: (Int) -> CaretPoint,
    ): Boolean {
        if (!started) {
            if ((finger - grab).getDistance() <= deadZone) return false
            started = true
        }
        val target = finger + fingerToHotspot
        val candidate = offsetAt(target)
        if (candidate == moving) return false
        val now = caretAt(moving)
        val next = caretAt(candidate)
        val accept = if (next.rowTop != now.rowTop) {
            // Another row: the finger must sit [resistY] inside it, measured from the edge facing the current row.
            if (next.rowTop > now.rowTop) target.y >= next.rowTop + resistY
            else target.y <= next.rowTop + lineHeight - resistY
        } else {
            // Same row: the candidate is the nearer caret position, and must be nearer by a margin. Since two
            // neighbouring positions are a character apart, this puts the switch [resistX]/2 past their midpoint.
            abs(target.x - next.x) + resistX <= abs(target.x - now.x)
        }
        if (!accept) return false
        moving = candidate
        return true
    }

    /** An offset's caret position: its x and the top of its visual row. */
    data class CaretPoint(val x: Float, val rowTop: Float)
}
