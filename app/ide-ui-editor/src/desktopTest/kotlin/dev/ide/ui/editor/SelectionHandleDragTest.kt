package dev.ide.ui.editor

import androidx.compose.ui.geometry.Offset
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SelectionHandleDrag] on a fake monospace grid: 10px characters, 20px rows, [COLS] characters plus a line
 * break per row. Offset `row * (COLS + 1) + col` has its caret at `x = col * 10`, row top `row * 20`.
 *
 * Guards issue #1751, where a slight hand shake while dragging a handle sent the selection elsewhere.
 */
class SelectionHandleDragTest {

    @Test
    fun `grabbing a handle off-centre does not snap it to the finger`() {
        // The handle stands for offset 5 (x = 50); the finger lands 15px right of it and a row lower.
        val drag = drag(start = 5, grab = Offset(65f, 40f))
        drag.moveTo(Offset(68f, 41f), ::offsetAt, ::caretAt)
        assertEquals(5, drag.moving, "a 3px wobble on the grab moved the handle")
    }

    @Test
    fun `movement inside the dead zone changes nothing`() {
        val drag = drag(start = 5, grab = Offset(50f, 40f))
        assertFalse(drag.moveTo(Offset(53f, 41f), ::offsetAt, ::caretAt))
        assertFalse(drag.started)
        assertEquals(5, drag.moving)
    }

    @Test
    fun `the handle moves by how far the finger travelled`() {
        val drag = drag(start = 5, grab = Offset(65f, 40f))
        assertTrue(drag.moveTo(Offset(95f, 40f), ::offsetAt, ::caretAt))
        assertEquals(8, drag.moving, "30px of travel should move the handle three characters")
    }

    @Test
    fun `a finger on the boundary between two characters does not flicker`() {
        val drag = drag(start = 5, grab = Offset(50f, 10f), deadZone = 0f)
        // Just past the midpoint (x = 55) between offsets 5 and 6: not far enough yet.
        drag.moveTo(Offset(56f, 10f), ::offsetAt, ::caretAt)
        assertEquals(5, drag.moving)
        drag.moveTo(Offset(58f, 10f), ::offsetAt, ::caretAt)
        assertEquals(6, drag.moving)
        // Back over the midpoint by a hair: still 6.
        drag.moveTo(Offset(54f, 10f), ::offsetAt, ::caretAt)
        assertEquals(6, drag.moving, "a hair back over the midpoint flipped the handle back")
        drag.moveTo(Offset(52f, 10f), ::offsetAt, ::caretAt)
        assertEquals(5, drag.moving)
    }

    @Test
    fun `the handle changes row only once the finger is well inside the next one`() {
        val drag = drag(start = 5, grab = Offset(50f, 10f), deadZone = 0f)
        drag.moveTo(Offset(50f, 22f), ::offsetAt, ::caretAt)
        assertEquals(5, drag.moving, "2px past the row boundary moved the handle down a line")
        drag.moveTo(Offset(50f, 28f), ::offsetAt, ::caretAt)
        assertEquals(COLS + 1 + 5, drag.moving)
    }

    @Test
    fun `dragging the start handle past the end keeps the far end where it was`() {
        // Selection 5..10; drag the start handle (offset 5) right, past the end.
        val drag = drag(start = 5, fixedEnd = 10, grab = Offset(50f, 10f), deadZone = 0f)
        drag.moveTo(Offset(150f, 10f), ::offsetAt, ::caretAt)
        assertEquals(10 to 15, drag.selection)
        drag.moveTo(Offset(170f, 10f), ::offsetAt, ::caretAt)
        assertEquals(10 to 17, drag.selection, "the far end followed the finger once the handles crossed")
        drag.moveTo(Offset(20f, 10f), ::offsetAt, ::caretAt)
        assertEquals(10 to 2, drag.selection)
    }

    @Test
    fun `the caret handle moves the caret`() {
        val drag = drag(start = 5, grab = Offset(50f, 10f), deadZone = 0f)
        drag.moveTo(Offset(90f, 10f), ::offsetAt, ::caretAt)
        assertEquals(9 to 9, drag.selection)
    }

    private fun drag(start: Int, grab: Offset, fixedEnd: Int? = null, deadZone: Float = 4f): SelectionHandleDrag {
        val p = caretAt(start)
        return SelectionHandleDrag(
            fixedEnd = fixedEnd,
            start = start,
            grab = grab,
            hotspot = Offset(p.x, p.rowTop + LINE / 2f),
            lineHeight = LINE,
            deadZone = deadZone,
            resistX = CHAR * 0.5f,
            resistY = LINE * 0.3f,
        )
    }

    private fun offsetAt(p: Offset): Int {
        val row = floor(p.y / LINE).toInt().coerceIn(0, ROWS - 1)
        val col = (p.x / CHAR).roundToInt().coerceIn(0, COLS)
        return row * (COLS + 1) + col
    }

    private fun caretAt(offset: Int) =
        SelectionHandleDrag.CaretPoint(x = (offset % (COLS + 1)) * CHAR, rowTop = (offset / (COLS + 1)) * LINE)

    private companion object {
        const val CHAR = 10f
        const val LINE = 20f
        const val COLS = 40
        const val ROWS = 10
    }
}
