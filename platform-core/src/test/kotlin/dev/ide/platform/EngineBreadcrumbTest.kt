package dev.ide.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EngineBreadcrumb] is the only signal we get for the native ART SIGSEGV (uncatchable in-process; on 32-bit
 * AND 64-bit devices), so it must reliably persist the last engine op — and whether an index build was in
 * flight — and read them back on the next launch. These pin that round-trip, the "latest wins" overwrite, the
 * index-concurrency marker, and the clean-shutdown [EngineBreadcrumb.clear].
 */
class EngineBreadcrumbTest {

    @Test
    fun recordsAndReadsBackTheLastOp() {
        val dir = Files.createTempDirectory("crumb")
        try {
            EngineBreadcrumb.init(dir.resolve("last-engine-op.log"))
            EngineBreadcrumb.record("background")
            val c = EngineBreadcrumb.readLast()
            assertTrue(c != null, "a recorded op must read back")
            assertEquals("background", c!!.op)
            assertEquals(Thread.currentThread().name, c.thread, "records the engine thread it ran on")
            assertTrue(c.epochMillis > 0, "carries a timestamp")
        } finally {
            EngineBreadcrumb.clear(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun latestRecordOverwritesThePrevious() {
        val dir = Files.createTempDirectory("crumb")
        try {
            EngineBreadcrumb.init(dir.resolve("c.log"))
            EngineBreadcrumb.record("interactive")
            EngineBreadcrumb.record("preview")
            assertEquals("preview", EngineBreadcrumb.readLast()?.op, "the op in flight is the latest recorded")
        } finally {
            EngineBreadcrumb.clear(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun clearDropsTheBreadcrumbAndReadIsNullBeforeAnyRecord() {
        val dir = Files.createTempDirectory("crumb")
        try {
            EngineBreadcrumb.init(dir.resolve("c.log"))
            assertNull(EngineBreadcrumb.readLast(), "armed but nothing written yet ⇒ null")
            EngineBreadcrumb.record("background")
            assertTrue(EngineBreadcrumb.readLast() != null)
            EngineBreadcrumb.clear()
            assertNull(EngineBreadcrumb.readLast(), "a clean shutdown clears the breadcrumb")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun crumbCarriesWhetherAnIndexBuildWasInFlight() {
        val dir = Files.createTempDirectory("crumb")
        try {
            EngineBreadcrumb.init(dir.resolve("c.log"))
            EngineBreadcrumb.record("analysis")
            assertEquals(false, EngineBreadcrumb.readLast()?.indexBuilding, "no build marked ⇒ false")

            EngineBreadcrumb.noteIndexBuilding(true)
            EngineBreadcrumb.record("semantic")
            assertEquals(true, EngineBreadcrumb.readLast()?.indexBuilding, "a build in flight is recorded")

            EngineBreadcrumb.noteIndexBuilding(false)
            assertEquals(false, EngineBreadcrumb.readLast()?.indexBuilding, "the flag clears when the build ends")
        } finally {
            EngineBreadcrumb.clear(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun anIndexBuildWithNoEngineOpStillReportsAsIndexOnly() {
        val dir = Files.createTempDirectory("crumb")
        try {
            // A native death during the startup index build, before any editor op recorded a crumb.
            EngineBreadcrumb.init(dir.resolve("c.log"))
            EngineBreadcrumb.noteIndexBuilding(true)
            val c = EngineBreadcrumb.readLast()
            assertTrue(c != null, "an index-only death must still be reported")
            assertEquals("(index-only)", c!!.op)
            assertTrue(c.indexBuilding)
            assertTrue(c.epochMillis > 0, "timestamped from the flag file")
        } finally {
            EngineBreadcrumb.clear(); dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun clearAlsoDropsTheIndexFlag() {
        val dir = Files.createTempDirectory("crumb")
        try {
            EngineBreadcrumb.init(dir.resolve("c.log"))
            EngineBreadcrumb.noteIndexBuilding(true)
            EngineBreadcrumb.clear()
            assertNull(EngineBreadcrumb.readLast(), "clear drops both the crumb and the index flag")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
