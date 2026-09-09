package dev.ide.ui.editor.core

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
}
