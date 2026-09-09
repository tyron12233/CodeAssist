package dev.ide.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The horizontal scrollbar's geometry: thumb size, thumb position, and the drag conversion back to content
 * pixels. Pure arithmetic, so the round trip (drag the thumb across its travel, land exactly at the end of the
 * content) is checked without rendering an editor.
 */
class EditorScrollbarGeometryTest {

    private val track = 300f
    private val minThumb = 56f

    @Test
    fun `thumb takes the visible fraction of the track`() {
        // 1200px of content in a 300px viewport: a quarter is visible, so the thumb covers a quarter.
        assertEquals(75f, scrollbarThumbWidth(track, contentWidthPx = 1200f, maxScrollPx = 900f, minThumbPx = minThumb))
        // Content that fits leaves a full-width thumb (the bar itself is not shown in that case).
        assertEquals(track, scrollbarThumbWidth(track, contentWidthPx = 300f, maxScrollPx = 0f, minThumbPx = minThumb))
    }

    @Test
    fun `a very long line still leaves something to grab`() {
        val thumb = scrollbarThumbWidth(track, contentWidthPx = 60_000f, maxScrollPx = 59_700f, minThumbPx = minThumb)
        assertEquals(minThumb, thumb, "the thumb is clamped to the minimum, not proportional all the way down")
        // A track narrower than the minimum falls back to the track, never wider than it.
        assertEquals(20f, scrollbarThumbWidth(20f, contentWidthPx = 60_000f, maxScrollPx = 59_980f, minThumbPx = minThumb))
    }

    @Test
    fun `thumb spans its travel as the content spans its range`() {
        val thumb = scrollbarThumbWidth(track, 1200f, 900f, minThumb) // 75f
        val travel = track - thumb
        assertEquals(0f, scrollbarThumbOffset(track, thumb, scrollPx = 0f, maxScrollPx = 900f))
        assertEquals(travel / 2f, scrollbarThumbOffset(track, thumb, scrollPx = 450f, maxScrollPx = 900f))
        assertEquals(travel, scrollbarThumbOffset(track, thumb, scrollPx = 900f, maxScrollPx = 900f))
        // Out-of-range offsets (a stale read mid-clamp) stay on the track rather than running off it.
        assertEquals(travel, scrollbarThumbOffset(track, thumb, scrollPx = 5000f, maxScrollPx = 900f))
        assertEquals(0f, scrollbarThumbOffset(track, thumb, scrollPx = -20f, maxScrollPx = 900f))
    }

    @Test
    fun `dragging the thumb its full travel scrolls the whole content`() {
        val thumb = scrollbarThumbWidth(track, 1200f, 900f, minThumb)
        val travel = track - thumb
        assertEquals(900f, scrollbarContentDelta(travel, track, thumb, maxScrollPx = 900f))
        assertEquals(-900f, scrollbarContentDelta(-travel, track, thumb, maxScrollPx = 900f))
        // The conversion is the inverse of the placement: drag by d, and the thumb ends up d further along.
        val delta = 30f
        val scrolled = scrollbarContentDelta(delta, track, thumb, 900f)
        assertEquals(delta, scrollbarThumbOffset(track, thumb, scrolled, 900f), 0.001f)
    }

    @Test
    fun `nothing to scroll means nothing to drag`() {
        assertEquals(0f, scrollbarContentDelta(50f, track, thumbWidthPx = track, maxScrollPx = 0f))
        assertEquals(0f, scrollbarContentDelta(50f, track, thumbWidthPx = track, maxScrollPx = 900f))
        assertTrue(scrollbarThumbWidth(0f, 1200f, 900f, minThumb) == 0f, "an unmeasured track has no thumb")
    }
}
