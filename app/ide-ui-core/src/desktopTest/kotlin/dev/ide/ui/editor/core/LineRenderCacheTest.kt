package dev.ide.ui.editor.core

import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the per-line inlay invalidation that backs [LineRenderCache]. The cache keys each line's shaped
 * layout on (style revision, inlay revision); the inlay revision used to be a single global counter, so
 * *any* inlay change re-shaped every cached line. Because the host shifts inlay offsets on every keystroke,
 * that re-shaped the whole viewport per key — the exact mobile typing hotspot. [InlayRevisions] now stamps
 * each line independently, so changing one line's inlay leaves the others' stamps (and thus cached layouts)
 * untouched. Tested here without a `TextMeasurer` (it can't be constructed headlessly).
 */
class LineRenderCacheTest {

    @Test
    fun inlayChangeBumpsOnlyTheChangedLine() {
        val revs = InlayRevisions()
        revs.update(mapOf(0 to listOf(InlayPiece(1, "a")), 1 to listOf(InlayPiece(1, "b")), 2 to listOf(InlayPiece(1, "c"))))
        val before = intArrayOf(revs.stampOf(0), revs.stampOf(1), revs.stampOf(2))

        // change only line 1's pieces
        revs.update(mapOf(0 to listOf(InlayPiece(1, "a")), 1 to listOf(InlayPiece(1, "B!")), 2 to listOf(InlayPiece(1, "c"))))

        assertEquals(before[0], revs.stampOf(0), "line 0 unchanged ⇒ same stamp ⇒ stays cached")
        assertNotEquals(before[1], revs.stampOf(1), "line 1 changed ⇒ bumped ⇒ re-shapes")
        assertEquals(before[2], revs.stampOf(2), "line 2 unchanged ⇒ same stamp ⇒ stays cached")
    }

    @Test
    fun reapplyingIdenticalInlaysBumpsNothing() {
        val revs = InlayRevisions()
        val hints = mapOf(2 to listOf(InlayPiece(1, "x")), 5 to listOf(InlayPiece(0, "y")))
        revs.update(hints)
        val s2 = revs.stampOf(2); val s5 = revs.stampOf(5)
        revs.update(mapOf(2 to listOf(InlayPiece(1, "x")), 5 to listOf(InlayPiece(0, "y")))) // equal content
        assertEquals(s2, revs.stampOf(2))
        assertEquals(s5, revs.stampOf(5))
    }

    @Test
    fun addingAndRemovingLinesBumpsThoseLines() {
        val revs = InlayRevisions()
        revs.update(mapOf(1 to listOf(InlayPiece(0, "a"))))
        val s1 = revs.stampOf(1)
        // remove line 1's hint, add line 3
        revs.update(mapOf(3 to listOf(InlayPiece(0, "b"))))
        assertNotEquals(s1, revs.stampOf(1), "removed line must bump (its woven text disappears)")
        assertTrue(revs.stampOf(3) > 0, "newly hinted line must bump")
    }

    @Test
    fun stampsAreUniqueForeverAcrossUpdates() {
        // unique-forever stamps are what make stale cache hits impossible; assert they strictly increase.
        val revs = InlayRevisions()
        revs.update(mapOf(0 to listOf(InlayPiece(0, "a"))))
        val first = revs.stampOf(0)
        revs.update(mapOf(0 to listOf(InlayPiece(0, "b"))))
        val second = revs.stampOf(0)
        revs.update(mapOf(0 to listOf(InlayPiece(0, "c"))))
        val third = revs.stampOf(0)
        assertTrue(first < second && second < third, "stamps must strictly increase ($first < $second < $third)")
    }

    @Test
    fun shiftMovesStampsWithTheirLines() {
        val revs = InlayRevisions()
        revs.update(mapOf(2 to listOf(InlayPiece(0, "a")), 5 to listOf(InlayPiece(0, "b"))))
        val s2 = revs.stampOf(2); val s5 = revs.stampOf(5)
        // a newline inserted above line 2 pushes lines >=2 down by one
        revs.shift(fromOldLine = 2, delta = 1)
        assertEquals(s2, revs.stampOf(3), "old line 2's stamp moved to line 3")
        assertEquals(s5, revs.stampOf(6), "old line 5's stamp moved to line 6")
        assertEquals(0, revs.stampOf(2), "line 2 now holds no stamp")
    }

    @Test
    fun shiftMovesPiecesWithTheirLines() {
        // The stamps moving is only half of it: a line's woven text has to move too, or the frames between the
        // edit and the next analysis pass draw another line's hint.
        val revs = InlayRevisions()
        revs.update(mapOf(2 to listOf(InlayPiece(0, "a")), 5 to listOf(InlayPiece(0, "b"))))
        revs.shift(fromOldLine = 2, delta = 1)
        assertEquals(listOf(InlayPiece(0, "a")), revs.piecesFor(3))
        assertEquals(listOf(InlayPiece(0, "b")), revs.piecesFor(6))
        assertTrue(revs.piecesFor(2).isEmpty(), "the inserted line carries no inlay")
    }

    @Test
    fun deletingLinesShiftsUpAndDropsWhatFallsOffTheTop() {
        val revs = InlayRevisions()
        revs.update(mapOf(0 to listOf(InlayPiece(0, "gone")), 3 to listOf(InlayPiece(0, "kept"))))
        val kept = revs.stampOf(3)
        revs.shift(fromOldLine = 0, delta = -1) // the first line was deleted
        assertEquals(listOf(InlayPiece(0, "kept")), revs.piecesFor(2), "line 3 moved up to 2")
        assertEquals(kept, revs.stampOf(2), "and kept its stamp, so it stays cached")
        assertTrue(revs.piecesFor(3).isEmpty(), "the vacated tail must not keep the moved line's value alive")
    }

    @Test
    fun piecesAreReturnedInColumnOrder() {
        // rawToVisual walks the pieces assuming column order; it is sorted once on adoption, not per read.
        val revs = InlayRevisions()
        revs.update(mapOf(1 to listOf(InlayPiece(9, "late"), InlayPiece(2, "early"))))
        assertEquals(listOf(2, 9), revs.piecesFor(1).map { it.col })
    }

    @Test
    fun reorderedButEqualPiecesDoNotBump() {
        val revs = InlayRevisions()
        revs.update(mapOf(1 to listOf(InlayPiece(2, "a"), InlayPiece(9, "b"))))
        val s1 = revs.stampOf(1)
        revs.update(mapOf(1 to listOf(InlayPiece(9, "b"), InlayPiece(2, "a")))) // same pieces, other order
        assertEquals(s1, revs.stampOf(1), "same pieces in another order must not re-shape the line")
    }

    @Test
    fun semanticSpansShiftWithTheirLines() {
        val sem = SemanticSpansByLine()
        val style = SpanStyle()
        sem.update(mapOf(4 to listOf(SemSpan(0, 3, style))))
        val s4 = sem.stampOf(4)
        sem.shift(fromOldLine = 4, delta = 2)
        assertEquals(listOf(SemSpan(0, 3, style)), sem.spansFor(6))
        assertEquals(s4, sem.stampOf(6))
        assertTrue(sem.spansFor(4).isEmpty())
    }

    @Test
    fun manySplicesLeaveTheStoreConsistent() {
        // The store is arrays spliced in place, so a long run of edits is where an off-by-one would surface.
        val revs = InlayRevisions()
        val reference = HashMap<Int, List<InlayPiece>>()
        for (line in 0 until 40) reference[line] = listOf(InlayPiece(0, "v$line"))
        revs.update(reference.toMap())
        var model = reference.toMap()
        val edits = listOf(5 to 1, 0 to 2, 30 to -3, 12 to 4, 0 to -1, 20 to -2, 7 to 1)
        for ((from, delta) in edits) {
            revs.shift(from, delta)
            model = model.entries.mapNotNull { (k, v) ->
                val nk = if (k >= from) k + delta else k
                if (nk >= 0) nk to v else null
            }.toMap()
        }
        for (line in 0 until 60) {
            assertEquals(model[line].orEmpty(), revs.piecesFor(line), "line $line after $edits")
        }
    }

    @Test
    fun setLineBumpsOnlyOnAChangeAndClearsWithAnEmptyList() {
        val revs = InlayRevisions()
        revs.update(mapOf(0 to listOf(InlayPiece(1, "a")), 3 to listOf(InlayPiece(2, "b"))))
        val s0 = revs.stampOf(0)
        val s3 = revs.stampOf(3)
        revs.setLine(3, listOf(InlayPiece(2, "b"))) // same pieces
        assertEquals(s3, revs.stampOf(3), "an unchanged line keeps its stamp")
        revs.setLine(3, listOf(InlayPiece(4, "z"), InlayPiece(1, "y")))
        assertNotEquals(s3, revs.stampOf(3))
        assertEquals(listOf(InlayPiece(1, "y"), InlayPiece(4, "z")), revs.piecesFor(3), "normalized to column order")
        revs.setLine(0, emptyList())
        assertNotEquals(s0, revs.stampOf(0), "clearing a line bumps it")
        assertTrue(revs.piecesFor(0).isEmpty())
        revs.setLine(9, listOf(InlayPiece(0, "far")))
        assertEquals(listOf(InlayPiece(0, "far")), revs.piecesFor(9), "a line past the end grows the store")
        val s9 = revs.stampOf(9)
        // A full update afterwards still diffs against what the lines hold now.
        revs.update(mapOf(9 to listOf(InlayPiece(0, "far"))))
        assertEquals(s9, revs.stampOf(9))
        assertTrue(revs.piecesFor(3).isEmpty())
    }
}
