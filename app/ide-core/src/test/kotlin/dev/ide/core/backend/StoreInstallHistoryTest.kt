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
        (1..80).forEach { history.remember("item-$it") }
        assertEquals(50, history.read().size)
        assertEquals("item-80", history.mostRecent())
    }

    /**
     * The path is what the store's "Open" button opens, so it has to survive a relaunch, and it has to be
     * dropped again the moment the project is not there, or Open would offer to open nothing.
     */
    @Test
    fun anInstallRemembersWhereItLanded() {
        val project = File(dir, "projects/Alpha").apply { mkdirs() }
        history.remember("alpha", project.absolutePath)
        history.remember("beta")
        assertEquals(mapOf("alpha" to project.absolutePath), history.installedPaths())
        assertEquals(listOf("beta", "alpha"), history.read())

        project.deleteRecursively()
        assertEquals(emptyMap(), history.installedPaths())
    }

    /**
     * `recordInstall` remembers the id with no path, and runs right after the unpack that does know one.
     * If that second write cleared the path, Open would work for exactly as long as the process lived.
     */
    @Test
    fun rememberingAnIdAgainWithoutAPathKeepsTheKnownOne() {
        val project = File(dir, "projects/Alpha").apply { mkdirs() }
        history.remember("alpha", project.absolutePath)
        history.remember("alpha")
        assertEquals(mapOf("alpha" to project.absolutePath), history.installedPaths())
    }

    /** Reinstalling replaces the remembered directory: the newest unpack is the one on disk. */
    @Test
    fun reinstallingReplacesTheRememberedPath() {
        val first = File(dir, "projects/Alpha").apply { mkdirs() }
        val second = File(dir, "projects/Alpha-2").apply { mkdirs() }
        history.remember("alpha", first.absolutePath)
        history.remember("alpha", second.absolutePath)
        assertEquals(mapOf("alpha" to second.absolutePath), history.installedPaths())
        assertEquals(listOf("alpha"), history.read())
    }

    /**
     * Lines written before installs remembered their path are bare ids. They still have to read as a seed,
     * or upgrading the app would silently reset the recommendation shelf on every existing device.
     */
    @Test
    fun aFileWrittenBeforePathsWereKeptStillReads() {
        val file = File(dir, "store/installed.txt")
        file.parentFile.mkdirs()
        file.writeText("beta\nalpha\n")
        assertEquals(listOf("beta", "alpha"), history.read())
        assertEquals("beta", history.mostRecent())
        assertEquals(emptyMap(), history.installedPaths())
    }

    /** No storage root — a host with nowhere to write — must not throw on an install. */
    @Test
    fun aHostWithNoStorageDegradesToNoSeed() {
        val none = StoreInstallHistory { null }
        none.remember("alpha")
        assertNull(none.mostRecent())
    }
}
