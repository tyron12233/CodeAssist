package dev.ide.core.backend

import dev.ide.store.impl.SupabaseLearnService
import dev.ide.testkit.withTempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real client against a real server: SupabaseLearnService fetching a published catalog over HTTP, and
 * LearnCatalogStore verifying, caching and serving it. Opt-in, because it needs a backend with the Learn
 * migrations applied and a catalog published newer than the bundled one:
 *
 *   LEARN_LIVE_URL=http://127.0.0.1:33000 LEARN_LIVE_KEY=<anon key> ./gradlew :ide-core:test --tests '*LearnCatalogLiveTest'
 */
class LearnCatalogLiveTest {

    @Test
    fun fetchesVerifiesAndCachesAPublishedCatalog() {
        val url = System.getenv("LEARN_LIVE_URL").orEmpty()
        val key = System.getenv("LEARN_LIVE_KEY").orEmpty()
        assumeTrue(url.isNotBlank() && key.isNotBlank(), "set LEARN_LIVE_URL and LEARN_LIVE_KEY")
        withTempDir("learn-live") { dir ->
            val remote = SupabaseLearnService(url, key, accounts = null)
            val published = (remote.latestCatalog(0, LearnCatalogCodec.ENVELOPE_FORMAT) as dev.ide.store.StoreResult.Ok).value!!
            // Serve an older bundled catalog so the published one must win.
            val older = LearnContent.catalog.copy(version = published.version - 1)
            val cache = dir.resolve("learn/catalog.json")
            val store = LearnCatalogStore({ older }, remote, { cache })
            assertTrue(store.refresh(), "a newer published catalog should be taken")
            assertEquals(published.version, store.catalog().version)
            assertEquals(LearnContent.tracks.map { it.id }, store.catalog().tracks.map { it.id })
            assertEquals(published.body, String(Files.readAllBytes(cache), Charsets.UTF_8), "cache holds the body as served")
            // Already current: the server answers null and nothing changes.
            assertFalse(LearnCatalogStore({ store.catalog() }, remote, { cache }).refresh(force = true))
        }
    }
}
