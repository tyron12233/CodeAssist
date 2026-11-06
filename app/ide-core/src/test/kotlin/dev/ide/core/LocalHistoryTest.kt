package dev.ide.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalHistoryTest {
    private val project = Files.createTempDirectory("lh-project")
    private val history = LocalHistory(project.resolve(".platform/local-history"), project)
    private val file = project.resolve("src/Main.kt")

    @Test
    fun theFirstSaveKeepsWhatItReplacedThenEachNewVersionOnce() {
        history.recordSave(file, before = "v0", after = "v1")
        Thread.sleep(5)
        history.recordSave(file, before = "v1", after = "v1") // no change: not kept again
        Thread.sleep(5)
        history.recordSave(file, before = "v1", after = "v2")
        history.flush()
        val texts = history.snapshots(file).map { history.text(file, it.id) }
        assertEquals(listOf("v2", "v1", "v0"), texts, "newest first, the pre-edit version included")
    }

    @Test
    fun keepsAtMostTheNewestFiftyAndNothingOutsideTheProject() {
        for (i in 1..55) { history.recordSave(file, before = null, after = "v$i"); Thread.sleep(2) }
        history.flush()
        val kept = history.snapshots(file)
        assertEquals(LocalHistory.MAX_SNAPSHOTS, kept.size)
        assertEquals("v55", history.text(file, kept.first().id))
        history.recordSave(Files.createTempDirectory("elsewhere").resolve("x.kt"), null, "x")
        history.flush()
        assertTrue(history.snapshots(Files.createTempDirectory("elsewhere").resolve("x.kt")).isEmpty())
    }
}
