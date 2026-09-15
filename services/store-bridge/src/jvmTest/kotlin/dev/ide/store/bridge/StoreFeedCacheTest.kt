package dev.ide.store.bridge

import dev.ide.store.RemoteCatalog
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The feed's three answers — memo, network, disk — and the thing that is easy to lose between them.
 *
 * The payload coordinates are the fragile part. They arrive only as part of a feed, and the install path
 * looks them up by item id much later, from a different screen. Nothing about `install()` fails to compile
 * when they stop being recorded: it starts answering "that project has nothing to download yet" instead,
 * which reads like a store problem. It has to hold on the cached path too, because a cold offline start
 * shows the cached feed and the install it offers has to work.
 */
class StoreFeedCacheTest {

    private val dir = createTempDirectory("ca-feed-cache-").toFile()
    private val cacheFile = File(dir, "store/explore-feed.json")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun cache(source: StoreCatalogSource) =
        StoreFeedCache(source, cachePath = { cacheFile.absolutePath })

    @Test
    fun learnsWhatAnItemNeedsToDownload() = runTest {
        val cache = cache(FeedSource(DOCUMENT))

        assertNotNull(cache.feed(seed = null, refresh = false), "the feed should load")

        val payload = assertNotNull(cache.payload("nimbus"), "the feed's payload should be remembered")
        assertEquals("nimbus/1.2.0.zip", payload.storagePath)
        assertEquals("abc123", payload.sha256)
        assertEquals(4096L, payload.sizeBytes)
        assertEquals("1.2.0", payload.version, "the version a review is about travels with the payload")
        assertNull(cache.payload("no-such-item"))
    }

    /** An item whose card routes through a bundled template has nothing in a bucket to fetch. */
    @Test
    fun anItemWithNoArchiveHasNoPayload() = runTest {
        val cache = cache(FeedSource(DOCUMENT))
        cache.feed(seed = null, refresh = false)
        assertNull(cache.payload("bundled-sample"))
    }

    @Test
    fun writesWhatItRenderedSoTheNextColdStartHasIt() = runTest {
        cache(FeedSource(DOCUMENT)).feed(seed = null, refresh = false)
        assertTrue(cacheFile.isFile, "the feed document should be cached")
        assertEquals(DOCUMENT, cacheFile.readText(), "the cached copy must be the bytes that were rendered")
    }

    /**
     * Offline, with a cached feed: the install it offers still has to know what to download.
     *
     * This is the case a live-only implementation gets wrong, because the payloads are recorded on the
     * response path and the cached path does not go through it.
     */
    @Test
    fun aCachedFeedStillCarriesItsPayloads() = runTest {
        cache(FeedSource(DOCUMENT)).feed(seed = null, refresh = false)

        val offline = cache(FeedSource(null))
        val feed = assertNotNull(offline.feed(seed = null, refresh = false), "the cached feed should load")
        assertTrue(feed.fromCache, "a feed off the disk must say so rather than pass as live")
        assertEquals("nimbus/1.2.0.zip", assertNotNull(offline.payload("nimbus")).storagePath)
    }

    /** Nothing cached and nothing reachable is "we cannot reach the store", not "the store is empty". */
    @Test
    fun answersNullWhenThereIsNothingToShowAtAll() = runTest {
        assertNull(cache(FeedSource(null)).feed(seed = null, refresh = false))
    }

    @Test
    fun asksOnceForRepeatedReadsAndAgainOnRefresh() = runTest {
        val source = FeedSource(DOCUMENT)
        val cache = cache(source)

        cache.feed(seed = null, refresh = false)
        cache.feed(seed = null, refresh = false)
        assertEquals(1, source.requests, "a second look at the Store tab should not be a second request")

        cache.feed(seed = null, refresh = true)
        assertEquals(2, source.requests, "a deliberate reload goes past the memo")

        cache.clear()
        cache.feed(seed = null, refresh = false)
        assertEquals(3, source.requests, "an install invalidates the memo: the counts it ranks on changed")
    }

    /** A different seed is a different feed — the personalized shelf is built from it. */
    @Test
    fun aDifferentSeedIsAskedForSeparately() = runTest {
        val source = FeedSource(DOCUMENT)
        val cache = cache(source)

        cache.feed(seed = "nimbus", refresh = false)
        cache.feed(seed = "other", refresh = false)

        assertEquals(2, source.requests)
        assertEquals("other", source.lastSeed)
    }

    /** Serves one feed document, or nothing at all. */
    private class FeedSource(private val document: String?) : StoreCatalogSource {
        var requests = 0
        var lastSeed: String? = null

        override fun configured() = true
        override fun catalog(appBuild: Int) = StoreResult.Unavailable<RemoteCatalog>("n/a")
        override fun search(query: StoreQuery, appBuild: Int) =
            StoreResult.Unavailable<List<RemoteStoreItem>>("n/a")

        override fun feedDocument(seedSlug: String?): StoreResult<String> {
            requests++
            lastSeed = seedSlug
            return document?.let { StoreResult.Ok(it) } ?: StoreResult.Unavailable("offline")
        }

        override fun recordInstall(slug: String, installId: String) = Unit
    }

    private companion object {
        /**
         * Hand-written rather than captured, unlike the parser's fixtures: what is under test is that a
         * payload recorded on the way past is still there later, and the captured feeds carry no archive
         * at all (every item in them routes through a bundled template).
         */
        val DOCUMENT = """
            {
              "mode": "populated",
              "version": 4,
              "storeState": { "count": 2, "acceptingSubmissions": true },
              "sections": [
                {
                  "id": "everything",
                  "type": "catalogue",
                  "items": [
                    {
                      "id": "nimbus",
                      "kind": "community",
                      "title": "Nimbus",
                      "summary": "A weather app",
                      "category": "android",
                      "version": "1.2.0",
                      "storagePath": "nimbus/1.2.0.zip",
                      "sizeBytes": 4096,
                      "sha256": "abc123"
                    },
                    {
                      "id": "bundled-sample",
                      "kind": "sample",
                      "title": "Calculator",
                      "summary": "A Java REPL",
                      "category": "java",
                      "templateId": "sample-calculator"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
