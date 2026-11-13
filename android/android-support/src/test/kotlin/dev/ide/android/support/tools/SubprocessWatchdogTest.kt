package dev.ide.android.support.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * A forked VM whose worker thread died of an `OutOfMemoryError` can print the error and then never exit.
 * [Subprocess.run] gives a process that printed such a line a grace period and then kills it, so the build
 * fails (or falls back) instead of hanging.
 */
class SubprocessWatchdogTest {

    @Test
    fun aProcessThatHangsAfterAFatalLineIsKilled() {
        assumeTrue(File("/bin/sh").exists())
        val started = System.nanoTime()
        val r = Subprocess.run(
            listOf("/bin/sh", "-c", "echo java.lang.OutOfMemoryError: no heap; exec sleep 60"),
            fatalLine = { DexDiagnostics.OOM_TOKEN in it }, fatalGraceMs = 300,
        )
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertFalse(r.success, "a killed tool is a failed tool")
        assertTrue(DexDiagnostics.isOutOfMemory(r.log), "the fatal line is kept: ${r.log}")
        assertTrue(seconds < 20, "the hung process must be killed, not waited out (${seconds}s)")
    }

    @Test
    fun aProcessThatExitsOnItsOwnIsLeftAlone() {
        assumeTrue(File("/bin/sh").exists())
        val r = Subprocess.run(listOf("/bin/sh", "-c", "echo fine"), fatalLine = { DexDiagnostics.OOM_TOKEN in it }, fatalGraceMs = 300)
        assertTrue(r.success, "an ordinary run is unaffected: ${r.log}")
    }
}
