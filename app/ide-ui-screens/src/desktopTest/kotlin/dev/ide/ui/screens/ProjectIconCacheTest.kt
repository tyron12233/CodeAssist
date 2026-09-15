package dev.ide.ui.screens

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The home screen's project-icon cache.
 *
 * The load-bearing case is [clear]: pull-to-refresh empties the cache so an icon changed in the app-icon
 * studio is picked up, and the cards on screen at that moment have to resolve theirs again. They only do
 * that if something they key on changed, which is what the generation is for — so clearing and bumping it
 * are one operation here rather than two things a caller has to remember to do together. When they were
 * two, a refresh blanked every visible icon and each came back on its own only after a scroll had taken
 * its card out of composition.
 */
class ProjectIconCacheTest {

    private val root = "/storage/emulated/0/CodeAssist/dev/aurora-app"

    @Test
    fun anIconIsRememberedUntilTheCacheIsCleared() {
        val cache = ProjectIconCache()
        val icon = ImageBitmap(1, 1)
        assertFalse(cache.isResolved(root), "nothing is known about a project before it is resolved")

        cache.put(root, icon)

        assertTrue(cache.isResolved(root))
        assertSame(icon, cache[root])
    }

    @Test
    fun aProjectWithNoIconIsAskedAboutOnce() {
        val cache = ProjectIconCache()
        cache.put(root, null)
        // "No icon" is an answer, not a miss: without this the list would re-resolve every glyph-tile
        // project on every scroll, which means a manifest read and a drawable parse per row.
        assertTrue(cache.isResolved(root))
        assertNull(cache[root])
    }

    @Test
    fun clearingDropsTheIconsAndOpensANewGeneration() {
        val cache = ProjectIconCache()
        cache.put(root, ImageBitmap(1, 1))
        assertEquals(0, cache.generation)

        cache.clear()

        assertFalse(cache.isResolved(root), "cleared: the next composition resolves it again")
        assertNull(cache[root])
        assertEquals(
            1, cache.generation,
            "a clear must be visible to cards already composed — their loader keys on this",
        )
    }

    @Test
    fun everyClearIsItsOwnGeneration() {
        val cache = ProjectIconCache()
        repeat(3) { cache.clear() }
        assertEquals(3, cache.generation, "two refreshes in a row must not look like one to a composed card")
    }
}
