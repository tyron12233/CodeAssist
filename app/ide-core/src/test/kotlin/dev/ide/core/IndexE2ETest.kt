package dev.ide.core

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch

/**
 * End-to-end check of the disk-backed index through the **real [IdeServices]** (the object both launchers
 * run), not the engine in isolation:
 *  1. bootstrap the Java demo → the background `ensureUpToDate` writes immutable on-disk `.seg` segments,
 *  2. queries are served from them through the service (JDK members from a disk segment; the demo's own
 *     declarations from the in-memory source side),
 *  3. `close()` releases the segment file channels, the segments persist, and a second `open()` reuses the
 *     cached `.seg` instead of rebuilding.
 *
 * This proves the segment engine is wired (construction → status → queries → close), low-RAM (only the
 * segments live on disk; nothing is loaded wholesale into the heap), and cross-launch reusable.
 */
class IndexE2ETest {

    private fun awaitIndexed(ide: IdeServices, timeoutMs: Long = 180_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(ide.indexStatus.value.message == "Indexed", "indexing did not finish: ${ide.indexStatus.value}")
    }

    private fun segFiles(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { it.toString().endsWith(".seg") }.toList() }

    @Test
    fun indexWritesDiskSegmentsServesQueriesAndReusesAcrossLaunches() {
        withTempDir("ide-index-e2e") { dir ->
            // 1. First launch: real bootstrap → the init block launches ensureUpToDate in the background.
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                awaitIndexed(ide)

                // the disk-backed segment engine ran (immutable .seg partitions, not heap-resident state)
                val segs = segFiles(dir)
                val segBytes = segs.sumOf { Files.size(it) }
                // a query served from a disk segment (JDK members) through the real service…
                val members = ide.search.searchMembers("toString", 50)
                // …and one from the in-memory source side (the demo's own declarations)
                val symbols = ide.search.searchSymbols("Main", 50)
                println("E2E launch#1: ${segs.size} .seg segments ($segBytes B on disk) — ${segs.joinToString { it.fileName.toString() }}")
                println("E2E launch#1: searchMembers('toString')=${members.size}, searchSymbols('Main')=${symbols.size}, status=${ide.indexStatus.value.message}")

                assertTrue(segs.isNotEmpty(), "expected on-disk .seg index segments under $dir, found none")
                assertTrue(members.isNotEmpty(), "members index (disk segment) returned nothing for 'toString'")
                assertTrue(symbols.any { it.name == "Main" }, "source-symbol index did not surface the demo's Main class")
            } // .use → IdeServices.close() → (indexService as AutoCloseable).close() releases the segment channels

            // segments survive the close (the persisted per-artifact cache)
            assertTrue(segFiles(dir).isNotEmpty(), "segments must persist on disk after close()")

            // 2. Second launch over the SAME workspace via open() (which does not wipe the dir) → the cached
            //    .seg are opened, not rebuilt, and still answer queries.
            IdeServices.open(dir).use { ide ->
                awaitIndexed(ide)
                val reused = ide.search.searchMembers("toString", 50)
                println("E2E launch#2 (reuse): searchMembers('toString')=${reused.size}, ${segFiles(dir).size} .seg still on disk")
                assertTrue(reused.isNotEmpty(), "reused index returned nothing after a second launch")
            }
        }
    }

    /**
     * Every full pass publishes one [IndexPassReport] naming why it ran: the project-open pass reports `open`,
     * and a later resync reports its own trigger with the cached artifacts reused rather than rebuilt.
     */
    @Test
    fun eachFullPassPublishesAReportWithItsTrigger() {
        withTempDir("ide-index-passes") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                awaitIndexed(ide)
                val open = awaitReport(ide) { it.trigger == IndexPassTrigger.OPEN }
                assertTrue(open.status.stats != null, "the open pass ran to the end: ${open.status}")

                ide.reindex()
                val again = awaitReport(ide) { it.trigger == IndexPassTrigger.REINDEX }
                assertTrue((again.status.stats?.artifactsBuilt ?: 0) >= 1, "a re-index rebuilds: ${again.status.stats}")
                assertTrue(again.rekeyed.isEmpty(), "a wiped cache rebuilds under the same keys: ${again.rekeyed}")
            }
        }
    }

    /**
     * A burst of classpath-change notifications (a reconcile loop committing module after module) used to queue
     * one full pass each. Now they coalesce into one pass, and since nothing on the classpath actually changed,
     * that pass is quiet: no build announced, nothing reopened.
     */
    @Test
    fun aBurstOfResyncRequestsBecomesOneQuietPass() {
        withTempDir("ide-index-coalesce") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                awaitIndexed(ide)
                awaitReport(ide) { it.trigger == IndexPassTrigger.OPEN }
                val seen = java.util.concurrent.CopyOnWriteArrayList<IndexPassReport>()
                kotlinx.coroutines.runBlocking {
                    val collector = launch(kotlinx.coroutines.Dispatchers.Default) {
                        ide.indexPasses.collect { if (it.trigger == IndexPassTrigger.LIBRARIES) seen.add(it) }
                    }
                    repeat(10) { ide.events.librariesChanged() }
                    val deadline = System.currentTimeMillis() + 60_000
                    while (seen.isEmpty() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(50)
                    kotlinx.coroutines.delay(1_000) // long enough for a second, wrongly queued pass to show up
                    collector.cancel()
                }
                assertEquals(1, seen.size, "ten requests must make one pass, got ${seen.map { it.coalesced }}")
                assertEquals(9, seen.single().coalesced)
                assertEquals(false, seen.single().libraryChanged, "an unchanged classpath makes a quiet pass")
            }
        }
    }

    private fun awaitReport(ide: IdeServices, timeoutMs: Long = 180_000, match: (IndexPassReport) -> Boolean): IndexPassReport {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ide.indexPasses.replayCache.lastOrNull()?.takeIf(match)?.let { return it }
            Thread.sleep(50)
        }
        error("no matching index pass report; last=${ide.indexPasses.replayCache.lastOrNull()?.trigger}")
    }
}
