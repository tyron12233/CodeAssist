package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A forked dex VM that dies without a verdict (ART aborting because it could not reserve its heap, SIGABRT 134,
 * issues #1625 and #1236) used to fail the merge outright. [ForkedDexPolicy.runWithFallback] re-runs that work
 * in-process instead, and [Subprocess] is what tells a dead VM apart from a tool that reported a real error.
 */
class ForkedDexPolicyTest {

    @Test
    fun aSignalDeathIsAProcessFailure() {
        // `kill -ABRT $$` ends the shell the way ART's abort ends dalvikvm64: exit status 128 + 6.
        val r = Subprocess.run(listOf("/bin/sh", "-c", "kill -ABRT $$"))
        assertFalse(r.success)
        assertTrue(r.processFailed, "a SIGABRT death is not the tool's own verdict: ${r.log}")
        assertTrue(r.log.last().contains("SIGABRT (code 134)"), r.log.toString())
    }

    @Test
    fun aLaunchTheOsRefusesIsAProcessFailure() {
        val r = Subprocess.run(listOf("/nonexistent/dalvikvm64", "-Xmx1536m"))
        assertFalse(r.success)
        assertTrue(r.processFailed)
    }

    @Test
    fun aVmThatCannotLoadTheToolIsAProcessFailure() {
        // The reported failure: dalvikvm64 started with an r8.dex.zip that had gone missing, printed
        // "Unable to locate class 'com/android/tools/r8/D8'" and exited 1, and dexExtLibs failed outright
        // instead of dexing in-process. ART's wording first, then a real JVM launched over an empty classpath.
        val art = Subprocess.run(listOf("/bin/sh", "-c",
            "echo \"Unable to locate class 'com/android/tools/r8/D8'\"; " +
                "echo 'java.lang.ClassNotFoundException: com.android.tools.r8.D8'; exit 1"))
        assertFalse(art.success)
        assertTrue(art.processFailed, "a VM that never loaded the tool reached no verdict: ${art.log}")

        withTempDir("empty-cp") { empty ->
            val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
            val jvm = Subprocess.run(listOf(java, "-cp", empty.resolve("r8.dex.zip").toString(), "com.android.tools.r8.D8"))
            assertFalse(jvm.success)
            assertTrue(jvm.processFailed, "the JVM's missing-main-class launch is a process failure too: ${jvm.log}")
        }
    }

    @Test
    fun aToolThatReportsAnErrorIsNotAProcessFailure() {
        val r = Subprocess.run(listOf("/bin/sh", "-c", "echo 'Error: Type a.B is defined multiple times'; exit 1"))
        assertFalse(r.success)
        assertFalse(r.processFailed, "an ordinary non-zero exit is the tool's verdict and must be reported as is")
    }

    @Test
    fun aDeadForkIsRetriedInProcessOverACleanOutputDir() {
        withTempDir("forked-dex-policy") { out ->
            Files.write(out.resolve("classes2.dex"), byteArrayOf(1, 2, 3))     // what a VM killed mid-merge leaves
            Files.createDirectories(out.resolve("g0"))
            var failures = 0
            var sawPartial = true
            val r = ForkedDexPolicy.runWithFallback(
                out,
                forked = { ToolResult(false, listOf("dalvikvm64 exited with SIGABRT (code 134)"), processFailed = true) },
                inProcess = {
                    sawPartial = Files.exists(out.resolve("classes2.dex"))
                    Files.write(out.resolve("classes.dex"), byteArrayOf(9))
                    ToolResult.ok(listOf("merged"))
                },
                onProcessFailure = { failures++ },
            )
            assertTrue(r.success)
            assertEquals(1, failures)
            assertFalse(sawPartial, "the dead VM's partial output was still there when the retry ran")
            assertTrue(Files.isDirectory(out.resolve("g0")), "only the dex files directly in the output dir are dropped")
            assertTrue(r.log.first().contains("SIGABRT") && r.log.first().contains("in-process"), r.log.toString())
            assertEquals("merged", r.log.last())
        }
    }

    @Test
    fun aForkThatReportedARealErrorIsNotRetried() {
        withTempDir("forked-dex-policy") { out ->
            var retried = false
            val failed = ToolResult(false, listOf("error: Type a.B is defined multiple times"))
            val r = ForkedDexPolicy.runWithFallback(out, forked = { failed }, inProcess = { retried = true; ToolResult.ok() })
            assertFalse(retried)
            assertEquals(failed, r)
        }
    }

    @Test
    fun onlyAMergeAboveTheThresholdForks() {
        withTempDir("forked-dex-policy") { dir ->
            val small = (0 until 20).map { i -> dir.resolve("C$i.dex").also { Files.write(it, ByteArray(2_000)) } }
            val bytes = ForkedDexPolicy.inputBytes(small + dir.resolve("missing.dex"))
            assertEquals(40_000L, bytes)
            assertFalse(ForkedDexPolicy.worthForking(bytes, 8L * 1024 * 1024), "a few dozen classes are not worth a VM")
            assertTrue(ForkedDexPolicy.worthForking(9L * 1024 * 1024, 8L * 1024 * 1024))
        }
    }
}
