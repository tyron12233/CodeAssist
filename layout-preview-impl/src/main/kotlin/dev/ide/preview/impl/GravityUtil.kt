package dev.ide.preview.impl

import dev.ide.preview.Props

/**
 * Resolves gravity flags into placement offsets within an available span. Used for `layout_gravity` (a
 * child's alignment in its parent) and `android:gravity` (a container/text view aligning its content).
 */
internal object GravityUtil {

    /** Left offset of a [size]-wide item in [available] px, honouring start/end/center_horizontal + margins. */
    fun horizontal(gravity: Int, available: Int, size: Int, marginStart: Int, marginEnd: Int): Int = when {
        gravity and Props.GRAVITY_CENTER_HORIZONTAL != 0 -> marginStart + (available - size - marginStart - marginEnd) / 2
        gravity and Props.GRAVITY_END != 0 -> available - size - marginEnd
        else -> marginStart // start / unspecified
    }

    /** Top offset of a [size]-tall item in [available] px, honouring top/bottom/center_vertical + margins. */
    fun vertical(gravity: Int, available: Int, size: Int, marginTop: Int, marginBottom: Int): Int = when {
        gravity and Props.GRAVITY_CENTER_VERTICAL != 0 -> marginTop + (available - size - marginTop - marginBottom) / 2
        gravity and Props.GRAVITY_BOTTOM != 0 -> available - size - marginBottom
        else -> marginTop // top / unspecified
    }

    /** A child's effective gravity: its own `layout_gravity`, else the parent's content gravity. */
    fun effective(childGravity: Int, parentContentGravity: Int): Int =
        if (childGravity != Props.GRAVITY_NONE) childGravity else parentContentGravity
}
