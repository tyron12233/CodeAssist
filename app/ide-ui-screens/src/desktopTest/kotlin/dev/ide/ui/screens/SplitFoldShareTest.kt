package dev.ide.ui.screens

import androidx.compose.ui.geometry.Rect
import dev.ide.ui.platform.WindowFold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SplitFoldShareTest {
    private val handle = 32f

    private fun vertical(x: Int) = WindowFold(x, 0, x, 1840, vertical = true, halfOpened = false, separating = false)

    private fun horizontal(y: Int) = WindowFold(0, y, 1080, y, vertical = false, halfOpened = true, separating = true)

    @Test
    fun sideBySideDividerLandsOnAVerticalFold() {
        val bounds = Rect(168f, 400f, 2040f, 1750f)
        val share = foldShare(vertical(1104), bounds, stacked = false, handlePx = handle)!!
        // The editor's width plus half the handle is the fold's distance from the pane's left edge.
        assertEquals(1104f - 168f, share * (bounds.width - handle) + handle / 2f, 0.5f)
    }

    @Test
    fun stackedDividerLandsOnAHorizontalFold() {
        val bounds = Rect(0f, 300f, 1080f, 2300f)
        val share = foldShare(horizontal(1300), bounds, stacked = true, handlePx = handle)!!
        // The editor is the lower pane: its height plus half the handle reaches up to the fold.
        assertEquals(2300f - 1300f, share * (bounds.height - handle) + handle / 2f, 0.5f)
    }

    @Test
    fun aFoldRunningTheOtherWayIsIgnored() {
        assertNull(foldShare(horizontal(900), Rect(0f, 0f, 2208f, 1840f), stacked = false, handlePx = handle))
    }

    @Test
    fun aFoldThatWouldCrushAPaneIsIgnored() {
        assertNull(foldShare(vertical(1104), Rect(1000f, 0f, 2208f, 1840f), stacked = false, handlePx = handle))
    }
}
