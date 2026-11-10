package dev.ide.core.index

import dev.ide.android.support.pack.LibraryPack
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Prebuilt index segments: a library's segments are keyed by its content, so the ones the publisher built are the
 * ones a phone looks up, and a phone installs only the indexes (and versions) it runs, from a signed archive.
 */
class LibraryIndexRepositoryTest {
    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val keys = keys()
    private fun tmp(): Path = Files.createTempDirectory("index-repo-test")

    private class MapNet(val files: Map<String, ByteArray>) : LibraryPack.Net {
        val requests = AtomicInteger()
        override fun get(url: String): ByteArray? { requests.incrementAndGet(); return files[url] }
    }

    private fun resolvedJar(root: Path, rel: String, content: String): Path {
        val jar = root.resolve("resolved-deps").resolve(rel)
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { z -> z.putNextEntry(ZipEntry("a/A.class")); z.write(content.toByteArray()); z.closeEntry() }
        return jar
    }

    /** A publisher's cache holding [indexes]' segments for each segment key (one non-empty, the rest empty). */
    private fun builtCache(segKeys: Collection<String>, indexes: Map<String, Int>): Path {
        val cache = tmp()
        for (k in segKeys) indexes.entries.forEachIndexed { i, (ix, v) ->
            val f = cache.resolve(LibraryIndexRepository.segmentPath(ix, v, k)); Files.createDirectories(f.parent)
            Files.write(f, if (i == 0) "segment-$ix-$k".toByteArray() else ByteArray(0))
        }
        return cache
    }

    private fun published(keys: Map<Path, String>, indexes: Map<String, Int>, key: KeyPair = this.keys): Map<String, ByteArray> {
        val repo = tmp()
        LibraryIndexRepository.publish(keys, builtCache(keys.values, indexes), repo, key.private, indexes)
        return LibraryPack.filesOf(repo).associate { (rel, f) -> "https://repo/$rel" to Files.readAllBytes(f) }
    }

    private val indexes = mapOf("java.classes" to 3, "android.resources" to 7)
    private fun jarKey(jar: Path) = LibraryIndexRepository.jarKey(JarContentIds.compute(jar)!!)
    private fun repo(net: LibraryPack.Net) = LibraryIndexRepository("https://repo", keys.public, net)

    @Test
    fun theSameJarAnywhereHasTheSameIdAndADifferentJarDoesNot() {
        val a = resolvedJar(tmp(), "g/lib/1.0/lib-1.0.jar", "x")
        val b = resolvedJar(tmp(), "g/lib/1.0/lib-1.0.jar", "x")
        val c = resolvedJar(tmp(), "g/lib/1.0/lib-1.0.jar", "y")
        assertEquals(JarContentIds.compute(a), JarContentIds.compute(b))
        assertNotEquals(JarContentIds.compute(a), JarContentIds.compute(c))
    }

    @Test
    fun idsAreCachedUntilTheJarChanges() {
        val dir = tmp()
        val jar = resolvedJar(dir, "g/lib/1.0/lib-1.0.jar", "x")
        val ids = JarContentIds(dir.resolve(".jar-ids"))
        val first = ids.idOf(jar); ids.flush()
        assertEquals(first, JarContentIds(dir.resolve(".jar-ids")).idOf(jar), "read back from the cache file")
        resolvedJar(dir, "g/lib/1.0/lib-1.0.jar", "changed")
        Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 5_000))
        assertNotEquals(first, JarContentIds(dir.resolve(".jar-ids")).idOf(jar), "a rewritten jar is hashed again")
    }

    /** An AAR's classes.jar and its res/ folder come from one archive per library version. */
    @Test
    fun aJarAndItsAarsResFolderInstallFromOneArchive() {
        val root = tmp()
        val jar = resolvedJar(root, "g/lib/1.0/lib-1.0-exploded/classes.jar", "x")
        val res = Files.createDirectories(root.resolve("resolved-deps/g/lib/1.0/lib-1.0-exploded/res"))
        val keys = mapOf(jar to jarKey(jar), res to LibraryIndexRepository.resKey("sha-aar"))
        val net = MapNet(published(keys, indexes))
        val cache = tmp()
        assertEquals(2, repo(net).fetch(keys, cache, indexes))
        assertEquals(1, net.requests.get(), "one archive for the library version")
        assertEquals("segment-java.classes-res-sha-aar", String(Files.readAllBytes(cache.resolve(LibraryIndexRepository.segmentPath("java.classes", 3, "res-sha-aar")))))
        assertEquals(0L, Files.size(cache.resolve(LibraryIndexRepository.segmentPath("android.resources", 7, jarKey(jar)))), "an empty segment stays empty")
    }

    /** IntelliJ's per-indexer rule: an index whose version moved on is built on the phone, the rest still arrive. */
    @Test
    fun anIndexAtAnotherVersionIsLeftToBeBuiltLocally() {
        val jar = resolvedJar(tmp(), "g/lib/1.0/lib-1.0.jar", "x")
        val keys = mapOf(jar to jarKey(jar))
        val device = mapOf("java.classes" to 4, "android.resources" to 7)
        // The phone looks under its own flavor; serve the publisher's archive there to exercise the per-index rule.
        val served = published(keys, indexes).mapKeys { it.key.replace(LibraryIndexRepository.flavorOf(indexes), LibraryIndexRepository.flavorOf(device)) }
        val cache = tmp()
        assertEquals(0, repo(MapNet(served)).fetch(keys, cache, device))
        assertFalse(Files.exists(cache.resolve(LibraryIndexRepository.segmentPath("java.classes", 4, jarKey(jar)))))
        assertTrue(Files.exists(cache.resolve(LibraryIndexRepository.segmentPath("android.resources", 7, jarKey(jar)))))
    }

    /** A release that changes an indexer reads (and publishes) a flavor of its own, never stale segments. */
    @Test
    fun theFlavorFollowsTheIndexerSet() {
        assertEquals(LibraryIndexRepository.flavorOf(indexes), LibraryIndexRepository.flavorOf(mapOf("android.resources" to 7, "java.classes" to 3)))
        assertNotEquals(LibraryIndexRepository.flavorOf(indexes), LibraryIndexRepository.flavorOf(indexes + ("java.classes" to 4)))
    }

    @Test
    fun anArchiveSignedByAnotherKeyIsIgnored() {
        val jar = resolvedJar(tmp(), "g/lib/1.0/lib-1.0.jar", "x")
        val keys = mapOf(jar to jarKey(jar))
        val cache = tmp()
        assertEquals(0, repo(MapNet(published(keys, indexes, key = keys()))).fetch(keys, cache, indexes))
        assertFalse(Files.exists(cache.resolve("java.classes")))
    }

    @Test
    fun segmentsAlreadyInTheCacheAreNotDownloaded() {
        val jar = resolvedJar(tmp(), "g/lib/1.0/lib-1.0.jar", "x")
        val keys = mapOf(jar to jarKey(jar))
        val net = MapNet(published(keys, indexes))
        repo(net).fetch(keys, builtCache(keys.values, indexes), indexes)
        assertEquals(0, net.requests.get())
    }
}
