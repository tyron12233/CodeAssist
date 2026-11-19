package dev.ide.ui.platform

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowFoldTest {
    private val window = Rect(0f, 0f, 2208f, 1840f)

    private fun vertical(left: Int, right: Int) =
        WindowFold(left, 0, right, 1840, vertical = true, halfOpened = true, separating = true)

    private fun horizontal(top: Int, bottom: Int) =
        WindowFold(0, top, 2208, bottom, vertical = false, halfOpened = true, separating = true)

    @Test
    fun aCentredVerticalFoldGivesTheTrailingSide() {
        assertEquals(Rect(1104f, 0f, 2208f, 1840f), vertical(1104, 1104).sideOf(window))
    }

    @Test
    fun anOffCentreVerticalFoldGivesTheLargerSide() {
        assertEquals(Rect(0f, 0f, 1400f, 1840f), vertical(1400, 1420).sideOf(window))
    }

    @Test
    fun aCentredHorizontalFoldGivesTheUpperSide() {
        assertEquals(Rect(0f, 0f, 2208f, 920f), horizontal(920, 920).sideOf(window))
    }

    @Test
    fun aFoldOutsideTheContainerChangesNothing() {
        val pane = Rect(1200f, 0f, 2208f, 1840f)
        assertEquals(pane, vertical(1104, 1104).sideOf(pane))
    }
}
