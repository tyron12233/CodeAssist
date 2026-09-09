package dev.ide.core.backend

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The device-side seed for "Because you installed X".
 *
 * Worth its own test because the shelf it feeds fails SILENTLY when this is wrong: a missing seed does not
 * error, it just means `store_explore()` is asked for no personalization and the section never arrives —
 * which is exactly the state the store shipped in.
 */
class StoreInstallHistoryTest {

    private val dir = kotlin.io.path.createTempDirectory("ca-install-history-").toFile()
    private val history = StoreInstallHistory { File(dir, "store/installed.txt") }

    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    @Test
    fun aDeviceThatHasInstalledNothingHasNoSeed() {
        assertNull(history.mostRecent())
        assertEquals(emptyList(), history.read())
    }

    @Test
    fun theMostRecentInstallIsTheSeed() {
        history.remember("alpha")
        history.remember("beta")
        assertEquals("beta", history.mostRecent())
        assertEquals(listOf("beta", "alpha"), history.read())
    }

    /** Reinstalling something moves it to the front rather than filling the list with one id. */
    @Test
    fun reinstallingMovesTheIdToTheFrontWithoutDuplicating() {
        listOf("a", "b", "c", "a").forEach(history::remember)
        assertEquals(listOf("a", "c", "b"), history.read())
    }

    @Test
    fun theListIsCapped() {
        (1..25).forEach { history.remember("item-$it") }
        assertEquals(10, history.read().size)
        assertEquals("item-25", history.mostRecent())
    }

    /** No storage root — a host with nowhere to write — must not throw on an install. */
    @Test
    fun aHostWithNoStorageDegradesToNoSeed() {
        val none = StoreInstallHistory { null }
        none.remember("alpha")
        assertNull(none.mostRecent())
    }
}
