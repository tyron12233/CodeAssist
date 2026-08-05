package dev.ide.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EngineBreadcrumb] is the only signal we get for the native 32-bit-ART SIGSEGV (uncatchable in-process), so
 * it must reliably persist the last engine op and read it back on the next launch. These pin that round-trip,
 * the "latest wins" overwrite, and the clean-shutdown [EngineBreadcrumb.clear].
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
}
