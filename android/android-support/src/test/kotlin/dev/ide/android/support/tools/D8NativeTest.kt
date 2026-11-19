package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A host with D8 compiled into it ([D8Native]) is driven with the arguments a launched D8 gets, in a file, and
 * what it writes to its log is the result's. The D8 here is a stand-in that records what it was given.
 */
class D8NativeTest {

    @AfterTest
    fun reset() { D8Native.runner = null }

    @Test
    fun aHostWithoutOneHasNoSuchDexer() {
        assertNull(D8Native.dexer())
    }

    @Test
    fun theHostsD8GetsItsArgumentsInAFileAndItsLogIsTheResults() {
        withTempDir("d8native") { tmp ->
            val input = Files.write(tmp.resolve("lib.jar"), byteArrayOf(1))
            val out = tmp.resolve("out")
            var given: List<String> = emptyList()
            D8Native.runner = { argFile, log ->
                given = Files.readAllLines(argFile)
                Files.write(log, listOf("warning: something about lib.jar"))
                0
            }
            val dexer = D8Native.dexer()!!
            assertFalse(dexer.runsOffHeap(), "libraries are dexed one at a time, so each is cached by itself")

            val r = dexer.dexArchive(listOf(input), emptyList(), tmp.resolve("android.jar"), 24, false, out, threads = 3)

            assertTrue(r.success, r.log.toString())
            assertEquals(listOf("warning: something about lib.jar"), r.log)
            assertEquals(listOf("--debug", "--file-per-class-file", "--intermediate", "--globals-output", out.toString()), given.take(5))
            assertEquals(listOf("--min-api", "24", "--thread-count", "3"), given.subList(5, 9))
            assertEquals(listOf("--output", out.toString(), input.toString()), given.takeLast(3))
            assertEquals(emptyList(), Files.list(out).use { it.toList() }, "neither file outlives the run")
        }
    }

    @Test
    fun aFailingRunSaysSoWithWhatItReported() {
        withTempDir("d8native") { tmp ->
            val input = Files.write(tmp.resolve("lib.jar"), byteArrayOf(1))
            D8Native.runner = { _, log -> Files.write(log, listOf("error: bad class")); 1 }
            val r = D8Native.dexer()!!.dex(listOf(input), tmp.resolve("android.jar"), 24, true, tmp.resolve("out"))
            assertFalse(r.success)
            assertTrue(r.log.any { "bad class" in it } && r.log.any { "code 1" in it }, r.log.toString())

            D8Native.runner = { _, _ -> throw UnsatisfiedLinkError("no d8") }
            val broken = D8Native.dexer()!!.dex(listOf(input), tmp.resolve("android.jar"), 24, true, tmp.resolve("out"))
            assertTrue(!broken.success && broken.processFailed)
        }
    }
}
