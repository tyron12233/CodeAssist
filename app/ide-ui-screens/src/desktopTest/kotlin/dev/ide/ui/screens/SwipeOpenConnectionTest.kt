package dev.ide.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The swipe that brings a tool window back in zen mode on the wide layout (a phone in landscape), where the
 * stripes and the top bar are hidden. It must open the pane from the editor's horizontal start and stay out
 * of the way of every other scroll.
 */
class SwipeOpenConnectionTest {
    private var opened = 0
    private fun connection(direction: Float = 1f) = SwipeOpenConnection(thresholdPx = 80f, direction = direction) { opened++ }

    private fun SwipeOpenConnection.drag(consumed: Offset, leftover: Offset, times: Int = 1) =
        repeat(times) { onPostScroll(consumed, leftover, NestedScrollSource.UserInput) }

    private fun SwipeOpenConnection.lift() = runBlocking { onPreFling(Velocity.Zero); onPostFling(Velocity.Zero, Velocity.Zero) }

    @Test
    fun `a rightward swipe the editor could not scroll opens the pane once`() {
        val c = connection()
        c.drag(Offset.Zero, Offset(30f, 2f), times = 6)
        assertEquals(1, opened)
    }

    @Test
    fun `a short nudge does not open it`() {
        val c = connection()
        c.drag(Offset.Zero, Offset(30f, 0f), times = 2)
        c.lift()
        assertEquals(0, opened)
    }

    @Test
    fun `a stroke that scrolled the editor back to its start does not open it`() {
        val c = connection()
        c.drag(Offset(40f, 0f), Offset.Zero, times = 3)
        c.drag(Offset.Zero, Offset(40f, 0f), times = 6)
        assertEquals(0, opened)
        // Lifting and swiping again, with the editor now at its start, does.
        c.lift()
        c.drag(Offset.Zero, Offset(40f, 0f), times = 6)
        assertEquals(1, opened)
    }

    @Test
    fun `a vertical scroll that drifts sideways does not open it`() {
        val c = connection()
        c.drag(Offset(0f, 60f), Offset(12f, 0f), times = 12)
        assertEquals(0, opened)
    }

    @Test
    fun `a leftward swipe does not open a left pane, and a fling's scroll is ignored`() {
        val c = connection()
        c.drag(Offset.Zero, Offset(-40f, 0f), times = 6)
        repeat(6) { c.onPostScroll(Offset.Zero, Offset(40f, 0f), NestedScrollSource.SideEffect) }
        assertEquals(0, opened)
    }

    @Test
    fun `under a right to left layout the leftward swipe opens it`() {
        val c = connection(direction = -1f)
        c.drag(Offset.Zero, Offset(-40f, 0f), times = 6)
        assertEquals(1, opened)
    }
}
