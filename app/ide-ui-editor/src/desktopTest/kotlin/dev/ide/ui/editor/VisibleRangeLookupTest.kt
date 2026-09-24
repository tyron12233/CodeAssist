package dev.ide.ui.editor

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The viewport lookups the draw pass and the chip layer use instead of walking the whole file. */
class VisibleRangeLookupTest {

    /** The draw pass's own per-match test: skipped when it ends before [from] or starts after [to]. */
    private fun referenceIndices(matches: List<Match>, from: Int, to: Int): List<Int> =
        matches.indices.filter { !(matches[it].end < from || matches[it].start > to) }

    @Test
    fun visibleMatchIndicesSelectExactlyTheMatchesTheDrawPassKeeps() {
        val rnd = Random(17)
        repeat(500) {
            val matches = ArrayList<Match>()
            var at = rnd.nextInt(0, 5)
            repeat(rnd.nextInt(0, 40)) {
                val len = rnd.nextInt(1, 6)
                matches.add(Match(at, at + len))
                at += len + rnd.nextInt(0, 8) // sorted and non-overlapping, sometimes touching
            }
            val from = rnd.nextInt(0, at + 5)
            val to = from + rnd.nextInt(0, 40)
            assertEquals(referenceIndices(matches, from, to), visibleMatchIndices(matches, from, to).toList())
        }
    }

    @Test
    fun visibleMatchIndicesOfNothingIsEmpty() {
        assertTrue(visibleMatchIndices(emptyList(), 0, 100).isEmpty())
        assertTrue(visibleMatchIndices(listOf(Match(0, 3)), 10, 20).isEmpty())
    }

    @Test
    fun chipWindowCoversTheViewportWithAMarginAndMovesInBuckets() {
        val w = chipWindow(100..140)
        assertTrue(w.first <= 100 - 32 && w.last >= 140 + 32, "margin on both sides: $w")
        // Scrolling a few lines within a bucket keeps the same window, so nothing recomposes.
        assertEquals(w, chipWindow(101..141))
        assertEquals(0, chipWindow(0..20).first)
    }
}
