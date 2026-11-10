package dev.ide.android.layouteditor

import androidx.compose.ui.geometry.Rect
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutViewNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitionTest {

    @Test
    fun anOffsetAfterAnEditShiftsByWhatItAdded() {
        val insert = listOf(LayoutEdit(10, 10, "<Space/>"))
        assertEquals(5, mapOffset(5, listOf(insert)))
        // A tag starting where the insertion went is pushed along by it.
        assertEquals(18, mapOffset(10, listOf(insert)))
        assertEquals(28, mapOffset(20, listOf(insert)))
    }

    @Test
    fun anOffsetInsideReplacedTextIsGone() {
        val delete = listOf(LayoutEdit(10, 30, ""))
        assertNull(mapOffset(15, listOf(delete)))
        assertEquals(20, mapOffset(40, listOf(delete)))
    }

    @Test
    fun batchesApplyInOrder() {
        val first = listOf(LayoutEdit(0, 0, "abc")) // 40 -> 43
        val second = listOf(LayoutEdit(43, 50, "")) // 43 is replaced
        assertNull(mapOffset(40, listOf(first, second)))
        assertEquals(63, mapOffset(60, listOf(first)))
        assertEquals(56, mapOffset(60, listOf(first, second)))
    }

    private fun node(cls: String, l: Int, t: Int, r: Int, b: Int, off: Int?, id: String? = null, vararg kids: LayoutViewNode) =
        LayoutViewNode(cls, id, l, t, r, b, off, children = kids.toList())

    /** A vertical list of two views; then a view inserted between them (at source offset 50, 10 chars long). */
    private val before = node(
        "DecorView", 0, 0, 100, 200, null, null,
        node("LinearLayout", 0, 0, 100, 100, 10, null,
            node("TextView", 0, 0, 100, 40, 40, "title"),
            node("Button", 0, 40, 100, 80, 50, null),
        ),
    )
    private val insert = listOf(listOf(LayoutEdit(50, 50, "<Switch/>\n")))
    private val after = node(
        "DecorView", 0, 0, 100, 200, null, null,
        node("LinearLayout", 0, 0, 100, 140, 10, null,
            node("TextView", 0, 0, 100, 40, 40, "title"),
            node("Switch", 0, 40, 100, 80, 50, null),
            node("Button", 0, 80, 100, 120, 60, null),
        ),
    )

    @Test
    fun viewsMatchByIdByMappedOffsetAndUndeclaredByPlace() {
        val m = matchViews(before, after, insert)
        val byClass = m.entries.associate { it.key.className to it.value }
        assertEquals(Rect(0f, 0f, 100f, 200f), byClass["DecorView"]) // undeclared: same place in the tree
        assertEquals(Rect(0f, 0f, 100f, 40f), byClass["TextView"]) // id
        assertEquals(Rect(0f, 40f, 100f, 80f), byClass["Button"]) // its offset 50 became 60
        assertTrue("Switch" !in byClass) // new
    }

    @Test
    fun aGrowingListMovesItsChildrenInsteadOfStretching() {
        val plan = planMotion(after, matchViews(before, after, insert))
        // The list changed size: it is looked into. The title stayed. The button moved down whole.
        assertEquals(listOf("Button"), plan.map { it.node.className })
        val button = plan.single()
        assertEquals(Rect(0f, 40f, 100f, 80f), button.from)
        assertEquals(false, button.scaled)
    }

    @Test
    fun aLeafThatChangedSizeTravelsScaled() {
        val old = node("FrameLayout", 0, 0, 100, 100, 0, null, node("TextView", 0, 0, 50, 20, 5, "t"))
        val new = node("FrameLayout", 0, 0, 100, 100, 0, null, node("TextView", 0, 0, 80, 20, 5, "t"))
        val plan = planMotion(new, matchViews(old, new, emptyList()))
        assertEquals(1, plan.size)
        assertTrue(plan.single().scaled)
    }

    @Test
    fun aGhostSitsOnTheInsertionLineOfAVerticalList() {
        val area = node("LinearLayout", 0, 0, 100, 140, 10)
        val target = DropTarget(parseTestLayout("<LinearLayout/>").root!!, 1, area, floatArrayOf(0f, 40f, 100f, 40f), 10f, 40f)
        val r = ghostRect(target, null, density = 2f)
        assertEquals(Rect(0f, -8f, 100f, 88f), r)
    }
}
