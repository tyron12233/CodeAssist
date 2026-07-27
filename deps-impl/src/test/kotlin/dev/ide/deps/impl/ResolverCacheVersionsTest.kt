package dev.ide.deps.impl

import dev.ide.model.Coordinate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [ResolverCache]'s cached-version management — the data behind the Dependencies editor's
 * "downloaded versions" cleanup list: enumerating what's on disk (newest-first, with sizes) and deleting a
 * single version dir without touching the others. Pure disk I/O over a temp dir; no network, runs in CI.
 */
class ResolverCacheVersionsTest {

    private val root: Path = Files.createTempDirectory("resolver-cache-versions")
    private val cache = ResolverCache(root)

    @AfterTest
    fun cleanup() {
        if (Files.exists(root)) Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** Populate the cache with a jar + pom for [group]:[name]:[version] the way the resolver would. */
    private fun seed(group: String, name: String, version: String, jarBytes: Int) {
        val c = Coordinate(group, name, version)
        cache.write(cache.relativePath(c, "jar"), ByteArray(jarBytes))
        cache.write(cache.relativePath(c, "pom"), ByteArray(POM_BYTES))
    }

    @Test
    fun listsCachedVersionsNewestFirstWithSizes() {
        seed("com.squareup.okhttp3", "okhttp", "3.14.9", 1_000)
        seed("com.squareup.okhttp3", "okhttp", "4.12.0", 2_000)
        seed("com.squareup.okhttp3", "okhttp", "10.0.0", 3_000) // numeric order: 10 > 4 > 3, not lexical
        val versions = cache.cachedVersions("com.squareup.okhttp3", "okhttp")
        assertEquals(listOf("10.0.0", "4.12.0", "3.14.9"), versions.map { it.first }, "newest-first, numeric-aware")
        assertEquals(1_000L + POM_BYTES, versions.first { it.first == "3.14.9" }.second, "size = jar + pom")
        assertEquals(3_000L + POM_BYTES, versions.first { it.first == "10.0.0" }.second)
    }

    @Test
    fun emptyWhenNothingCached() {
        assertTrue(cache.cachedVersions("org.example", "absent").isEmpty())
    }

    @Test
    fun deleteVersionRemovesOnlyThatVersion() {
        seed("g", "a", "1.0", 500)
        seed("g", "a", "2.0", 700)
        assertTrue(cache.deleteVersion("g", "a", "1.0"), "a present version returns true")
        assertEquals(listOf("2.0"), cache.cachedVersions("g", "a").map { it.first }, "only 1.0 was removed")
        assertFalse(cache.deleteVersion("g", "a", "9.9"), "an absent version returns false")
        // The surviving version's files are intact.
        assertEquals(700L + POM_BYTES, cache.cachedVersions("g", "a").single().second)
    }

    private companion object {
        const val POM_BYTES = 64
    }
}
