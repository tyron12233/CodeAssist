package dev.ide.ui.editor

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The idle layout-prefetch policy. Kept pure (see [prefetchOrder]) so it is testable without a `TextMeasurer`,
 * which cannot be constructed headlessly.
 */
class PrefetchOrderTest {

    @Test
    fun warmsBothEdgesNearestFirstStartingBelow() {
        // viewport shows 10..12 in a 100-line file, reach 2 each side
        assertContentEquals(intArrayOf(13, 9, 14, 8), prefetchOrder(first = 10, last = 12, lineCount = 100, reach = 2))
    }

    @Test
    fun stopsAtTheDocumentEdges() {
        // at the top of the file there is nothing above to warm
        assertContentEquals(intArrayOf(3, 4), prefetchOrder(first = 0, last = 2, lineCount = 5, reach = 4))
        // and at the bottom, nothing below
        assertContentEquals(intArrayOf(1, 0), prefetchOrder(first = 2, last = 4, lineCount = 5, reach = 4))
    }

    @Test
    fun neverWarmsWhatIsAlreadyOnScreen() {
        val visible = 40..60
        val order = prefetchOrder(visible.first, visible.last, lineCount = 200, reach = 20)
        assertTrue(order.none { it in visible }, "prefetch must not re-shape lines the frame already shaped")
    }

    @Test
    fun staysWithinReachOnEachSide() {
        val order = prefetchOrder(first = 100, last = 150, lineCount = 1_000, reach = 30)
        assertEquals(60, order.size, "reach lines on each side")
        assertTrue(order.all { it in 70..180 }, "nothing beyond reach: ${order.toList()}")
    }

    @Test
    fun aCancelledPrefetchLeavesBothEdgesPartlyWarm() {
        // Alternating sides is the point: taking only the first few entries (the user moved again) must have
        // touched both directions, not spent the whole budget below.
        val order = prefetchOrder(first = 50, last = 70, lineCount = 500, reach = 40)
        val firstSix = order.take(6)
        assertTrue(firstSix.any { it > 70 } && firstSix.any { it < 50 }, "both edges: $firstSix")
    }

    @Test
    fun degenerateInputsWarmNothing() {
        assertEquals(0, prefetchOrder(0, 0, lineCount = 0, reach = 10).size)
        assertEquals(0, prefetchOrder(0, 0, lineCount = 100, reach = 0).size)
    }

    @Test
    fun reachIsBoundedWellUnderTheRenderCacheCapacity() {
        // Warming must never be able to evict what is on screen. A viewport is bounded by the reach used at the
        // call site (one viewport per side), so the working set is ~3 viewports; the cache holds far more.
        val viewport = 60
        val order = prefetchOrder(first = 500, last = 500 + viewport, lineCount = 50_000, reach = viewport + 1)
        assertTrue(order.size + viewport < 512, "working set ${order.size + viewport} must fit the layout cache")
    }
}
