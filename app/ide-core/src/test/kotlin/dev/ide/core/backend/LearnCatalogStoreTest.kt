package dev.ide.core.backend

import dev.ide.store.LearnCatalogPayload
import dev.ide.store.LearnRemoteService
import dev.ide.store.StoreResult
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Bundled vs downloaded: the newer wins, a bad download never replaces a good catalog, and it is cached. */
class LearnCatalogStoreTest {

    private val bundled = LearnContent.catalog

    /** A newer catalog: the bundled one with a higher version and one track renamed, so a swap is visible. */
    private fun newer(version: Long = bundled.version + 1) = bundled.copy(
        version = version,
        tracks = bundled.tracks.mapIndexed { i, t -> if (i == 0) t.copy(title = "Renamed") else t },
    )

    private class FakeRemote(var answer: StoreResult<LearnCatalogPayload?>) : LearnRemoteService {
        var calls = 0
        override fun learnRemoteAvailable() = true
        override fun latestCatalog(sinceVersion: Long, format: Int): StoreResult<LearnCatalogPayload?> {
            calls++
            return answer
        }
    }

    private fun payload(c: LearnCatalogDef, hash: String? = null): LearnCatalogPayload {
        val body = LearnCatalogCodec.encode(c)
        return LearnCatalogPayload(c.version, 1, hash ?: LearnCatalogStore.sha256(body), body)
    }

    @Test
    fun servesBundledUntilANewerCatalogArrivesThenCachesIt() = withTempDir("learn-store") { dir ->
        val cache = dir.resolve("learn/catalog.json")
        val remote = FakeRemote(StoreResult.Ok(payload(newer())))
        val store = LearnCatalogStore({ bundled }, remote, { cache })
        assertEquals(bundled.version, store.catalog().version)

        assertTrue(store.refresh())
        assertEquals("Renamed", store.catalog().tracks[0].title)
        assertTrue(Files.isRegularFile(cache))

        // A fresh session reads the cache without asking the server.
        val offline = FakeRemote(StoreResult.Unavailable("offline"))
        val next = LearnCatalogStore({ bundled }, offline, { cache })
        assertEquals("Renamed", next.catalog().tracks[0].title)
        assertEquals(0, offline.calls)
    }

    @Test
    fun aReleaseNewerThanTheCacheWins() = withTempDir("learn-store-release") { dir ->
        val cache = dir.resolve("learn/catalog.json")
        Files.createDirectories(cache.parent)
        Files.write(cache, LearnCatalogCodec.encode(newer(version = bundled.version - 5)).toByteArray())
        val store = LearnCatalogStore({ bundled }, LearnRemoteService.Unsupported, { cache })
        assertEquals(bundled.version, store.catalog().version)
    }

    @Test
    fun aMismatchedHashOrAnOlderVersionChangesNothing() = withTempDir("learn-store-bad") { dir ->
        val cache = dir.resolve("learn/catalog.json")
        val bad = FakeRemote(StoreResult.Ok(payload(newer(), hash = "0".repeat(64))))
        val store = LearnCatalogStore({ bundled }, bad, { cache })
        assertFalse(store.refresh())
        assertEquals(bundled.version, store.catalog().version)
        assertFalse(Files.exists(cache))

        val older = FakeRemote(StoreResult.Ok(payload(newer(version = bundled.version - 1))))
        assertFalse(LearnCatalogStore({ bundled }, older, { cache }).refresh())
    }

    @Test
    fun asksOncePerSessionAndRetriesAfterAnOutage() {
        val remote = FakeRemote(StoreResult.Unavailable("offline"))
        val store = LearnCatalogStore({ bundled }, remote, { null })
        assertFalse(store.refresh())
        // An outage does not count as having checked: the next call tries again.
        remote.answer = StoreResult.Ok(null)
        assertFalse(store.refresh())
        assertFalse(store.refresh())
        assertEquals(2, remote.calls)
    }

    @Test
    fun anUnreadableCacheIsDiscarded() = withTempDir("learn-store-junk") { dir ->
        val cache = dir.resolve("learn/catalog.json")
        Files.createDirectories(cache.parent)
        Files.write(cache, "{ truncated".toByteArray())
        val store = LearnCatalogStore({ bundled }, LearnRemoteService.Unsupported, { cache })
        assertEquals(bundled.version, store.catalog().version)
        assertFalse(Files.exists(cache))
    }
}
