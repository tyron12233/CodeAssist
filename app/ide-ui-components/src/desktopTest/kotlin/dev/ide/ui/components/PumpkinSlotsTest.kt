package dev.ide.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Halloween build bar's pumpkin layout. 3.35.0 crashed with OutOfMemoryError while building: the dock laid
 * the bar out at full width and zero height, and width / 0 became a request for Int.MAX_VALUE pumpkins.
 */
class PumpkinSlotsTest {

    @Test
    fun aZeroHeightRowIsEmptyNotTwoBillionPumpkins() {
        assertEquals(emptyList(), pumpkinSlots(width = 1080f, height = 0f, gap = 0f))
        assertEquals(emptyList(), pumpkinSlots(width = 1080f, height = 0f, gap = 132f))
    }

    @Test
    fun aSliverOfHeightIsEmptyAndAnyCountIsCapped() {
        assertEquals(emptyList(), pumpkinSlots(width = 1080f, height = 0.001f, gap = 0f))
        assertTrue(pumpkinSlots(width = 100_000f, height = 2f, gap = 0f).size <= 64)
    }

    @Test
    fun degenerateSizesAreEmpty() {
        assertEquals(emptyList(), pumpkinSlots(width = 0f, height = 24f, gap = 0f))
        assertEquals(emptyList(), pumpkinSlots(width = Float.NaN, height = 24f, gap = 0f))
        assertEquals(emptyList(), pumpkinSlots(width = 1080f, height = Float.NaN, gap = 0f))
        assertEquals(emptyList(), pumpkinSlots(width = -5f, height = 24f, gap = 0f))
    }

    @Test
    fun anOrdinaryBarStillGetsItsRow() {
        // The dock's 8dp row at 2.75x density on a 411dp phone.
        val slots = pumpkinSlots(width = 1064f, height = 22f, gap = 0f)
        assertTrue(slots.size in 20..40, "got ${slots.size}")
        assertTrue(slots.all { it in 0f..1064f })
        assertTrue(slots.zipWithNext().all { (a, b) -> b > a })
        // The notch gap removes the middle ones.
        assertTrue(pumpkinSlots(width = 1064f, height = 22f, gap = 121f).size < slots.size)
    }
}
