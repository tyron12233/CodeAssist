package dev.codeassist.ndk

import dev.ide.platform.log.Log
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PreambleCacheTest {

    @TempDir
    lateinit var dir: Path

    /** Runs queued builds only when asked, so a test can see what was queued in between. */
    private class ManualExecutor : Executor {
        val queued = ArrayList<Runnable>()
        override fun execute(command: Runnable) { queued.add(command) }
        fun runAll() { while (queued.isNotEmpty()) queued.removeAt(0).run() }
    }

    private val built = ArrayList<String>()
    private var succeed = true

    private fun cache(executor: Executor, max: Int = 4) = PreambleCache(
        dir.resolve("preambles"), Log.logger("test"),
        build = { request, header, pch ->
            built.add(request.text)
            assertEquals(request.text, header.readText())
            if (succeed) pch.writeText("pch:" + request.text)
            NdkToolchain.ToolResult(succeed, if (succeed) "" else "fatal error: 'nope.h' file not found")
        },
        maxEntries = max,
        executor = executor,
    )

    private fun request(text: String, flags: List<String> = listOf("-std=c++17")) =
        PreambleCache.Request(text, cpp = true, flags = flags, sourceDir = dir, toolchainStamp = "1")

    @Test
    fun firstAskQueuesTheBuildAndTheNextIsServed() {
        val executor = ManualExecutor()
        val cache = cache(executor)
        val req = request("#include <vector>\n")

        assertNull(cache.readyOrQueue(req, 18))
        executor.runAll()
        val ready = cache.readyOrQueue(req, 18)!!
        assertEquals(18, ready.bytes)
        assertEquals("pch:#include <vector>\n", ready.pch.readText())
        assertEquals(1, built.size)
    }

    @Test
    fun onlyTheNewestWaitingRequestIsBuilt() {
        val executor = ManualExecutor()
        val cache = cache(executor)
        // Typing an #include changes the preamble on every keystroke.
        cache.readyOrQueue(request("#include <v>\n"), 0)
        cache.readyOrQueue(request("#include <ve>\n"), 0)
        cache.readyOrQueue(request("#include <vec>\n"), 0)
        executor.runAll()
        assertEquals(listOf("#include <vec>\n"), built)
    }

    @Test
    fun aFailedBuildIsNotRetriedForTheSamePreamble() {
        val executor = ManualExecutor()
        val cache = cache(executor)
        succeed = false
        val req = request("#include <nope.h>\n")
        cache.readyOrQueue(req, 0); executor.runAll()
        cache.readyOrQueue(req, 0); executor.runAll()
        assertEquals(1, built.size)
        assertTrue(Files.list(dir.resolve("preambles")).use { it.count() } == 0L, "a failed build leaves nothing behind")
    }

    @Test
    fun rebuildReplacesAStaleOne() {
        val executor = ManualExecutor()
        val cache = cache(executor)
        val req = request("#include \"a.h\"\n")
        cache.readyOrQueue(req, 0); executor.runAll()
        cache.rebuild(req)
        assertNull(cache.readyOrQueue(req, 0), "dropped until the rebuild lands")
        executor.runAll()
        assertNotNull(cache.readyOrQueue(req, 0))
        assertEquals(2, built.size)
    }

    @Test
    fun keyCoversFlagsLanguageAndDirectory() {
        val base = request("#include <vector>\n")
        assertNotEquals(base.key, request("#include <vector>\n", flags = listOf("-std=c++20")).key)
        assertNotEquals(base.key, base.copy(cpp = false).key)
        assertNotEquals(base.key, base.copy(sourceDir = dir.resolve("other")).key)
        assertNotEquals(base.key, base.copy(toolchainStamp = "2").key)
        assertEquals(base.key, request("#include <vector>\n").key)
    }

    @Test
    fun keepsOnlyTheMostRecentlyUsed() {
        val executor = ManualExecutor()
        val cache = cache(executor, max = 2)
        for (n in 1..3) {
            cache.readyOrQueue(request("#include <h$n>\n"), 0)
            executor.runAll()
            Thread.sleep(15) // distinct mtimes
        }
        val left = Files.list(dir.resolve("preambles")).use { s -> s.map { it.fileName.toString() }.toArray().toList() }
        assertEquals(2, left.count { (it as String).endsWith(".pch") })
        assertEquals(2, left.count { (it as String).endsWith(".h") }, "a header goes with its .pch")
        assertNull(cache.readyOrQueue(request("#include <h1>\n"), 0), "the oldest was evicted")
    }

    @Test
    fun recognisesARefusedPreamble() {
        assertTrue(PreambleCache.isStale("fatal error: file '/p/a.h' has been modified since the AST file '/c/x.pch' was built: size changed"))
        assertTrue(PreambleCache.isStale("error: C++20 was disabled in PCH file but is currently enabled"))
        assertTrue(PreambleCache.isStale("note: please rebuild precompiled header '/c/x.pch'"))
        assertFalse(PreambleCache.isStale("<stdin>:13:5: error: expected unqualified-id"))
    }
}
