package dev.ide.core.completion

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The persisted per-project completion-acceptance counters behind the stats weigher. */
class CompletionStatsTest {

    @Test
    fun countsIncrementAndPersistAcrossInstances() {
        val dir = Files.createTempDirectory("stats")
        try {
            val file = dir.resolve("completion-stats.properties")
            val stats = CompletionStats(file)
            assertEquals(0, stats.countFor("Text"))
            stats.noteAccepted("Text")
            stats.noteAccepted("Text")
            stats.noteAccepted("remember")
            assertEquals(2, stats.countFor("Text"))
            assertEquals(1, stats.countFor("remember"))

            val reloaded = CompletionStats(file)
            assertEquals(2, reloaded.countFor("Text"))
            assertEquals(1, reloaded.countFor("remember"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun keyIsTheLeadingIdentifierOfTheLabel() {
        assertEquals("Text", CompletionStats.keyOf("Text(text: String)"))
        assertEquals("layout_width", CompletionStats.keyOf("layout_width"))
        assertEquals("", CompletionStats.keyOf("(no identifier)"))
    }
}
