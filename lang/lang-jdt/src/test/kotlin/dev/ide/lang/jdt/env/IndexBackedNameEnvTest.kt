package dev.ide.lang.jdt.env

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.java.index.JavaClassLocatorIndex
import dev.ide.index.normalizedJarKey
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The index-backed library access in [JdtEnvironmentCache]: a ready `java.classLocator` index opens exactly
 * the owning jar (filtered to this module's classpath) and gives authoritative negatives; a not-ready index
 * falls back to probing; and the LRU handle pool caps open descriptors without breaking resolution.
 */
class IndexBackedNameEnvTest {

    private val dir: Path = Files.createTempDirectory("idx-nameenv")

    @AfterTest fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun readyIndexOpensExactlyTheOwningJar() {
        val foo = "com/example/Foo.class" to "FOO-BYTES".toByteArray()
        val jar = makeJar("a.jar", foo)
        val cache = cache(listOf(jar), index(ready = true, locator = mapOf("com.example.Foo" to listOf(normalizedJarKey(jar)))))
        try {
            assertContentEquals("FOO-BYTES".toByteArray(), cache.libraryBytes("com.example.Foo"))
        } finally { cache.close() }
    }

    @Test
    fun resolvesNestedTypeViaItsEnclosingTopLevelJar() {
        // ecj requests a nested supertype/interface by its binary name (Outer$Inner) — e.g. the Activity
        // hierarchy implements android.view.Window$Callback / KeyEventDispatcher$Component. The locator keys
        // only the top-level Outer (classEntryToFqn skips '$'), but Outer$Inner lives in the SAME jar, so it
        // must still resolve via the enclosing type's locator entry. (Regression: the missing nested supertype
        // made ecj report "hierarchy is inconsistent".)
        val jar = makeJar(
            "a.jar",
            "com/example/Outer.class" to "OUTER".toByteArray(),
            "com/example/Outer\$Inner.class" to "INNER".toByteArray(),
        )
        val cache = cache(listOf(jar), index(ready = true, locator = mapOf("com.example.Outer" to listOf(normalizedJarKey(jar)))))
        try {
            assertContentEquals(
                "INNER".toByteArray(),
                cache.libraryBytes("com.example.Outer\$Inner"),
                "a nested type must resolve via its enclosing top-level type's jar",
            )
        } finally { cache.close() }
    }

    @Test
    fun readyIndexNegativeIsAuthoritativeAndDoesNotProbe() {
        // The jar physically contains Foo, but the locator says nothing, a ready index is trusted, so the
        // class must NOT resolve (proving we don't fall back to probing when ready).
        val jar = makeJar("a.jar", "com/example/Foo.class" to "FOO".toByteArray())
        val cache = cache(listOf(jar), index(ready = true, locator = emptyMap()))
        try {
            assertNull(cache.libraryBytes("com.example.Foo"), "a ready index with no locator hit is authoritative")
        } finally { cache.close() }
    }

    @Test
    fun locatorHitOutsideThisClasspathIsIgnored() {
        // The workspace-wide locator points at a jar that is NOT on this module's classpath → not visible here.
        val onCp = makeJar("a.jar", "com/example/Foo.class" to "FOO".toByteArray())
        val offCp = makeJar("other.jar", "com/example/Bar.class" to "BAR".toByteArray())
        val cache = cache(listOf(onCp), index(ready = true, locator = mapOf("com.example.Bar" to listOf(normalizedJarKey(offCp)))))
        try {
            assertNull(cache.libraryBytes("com.example.Bar"), "a hit in another module's jar must not resolve here")
        } finally { cache.close() }
    }

    @Test
    fun notReadyFallsBackToProbing() {
        val jar = makeJar("a.jar", "com/example/Foo.class" to "FOO".toByteArray())
        // Not ready + empty locator: must still find Foo by probing the classpath jars.
        val cache = cache(listOf(jar), index(ready = false, locator = emptyMap()))
        try {
            assertContentEquals("FOO".toByteArray(), cache.libraryBytes("com.example.Foo"))
        } finally { cache.close() }
    }

    @Test
    fun lruPoolCapsOpenHandlesYetResolutionStaysCorrect() {
        val jars = (0 until 40).map { i -> makeJar("j$i.jar", "p/C$i.class" to "C$i".toByteArray()) }
        val locator = jars.mapIndexed { i, j -> "p.C$i" to listOf(normalizedJarKey(j)) }.toMap()
        val cache = cache(jars, index(ready = true, locator = locator))
        try {
            // Touch every jar (forces evictions), then re-read an early (evicted) one: it reopens on demand.
            for (i in 0 until 40) assertContentEquals("C$i".toByteArray(), cache.libraryBytes("p.C$i"))
            assertContentEquals("C0".toByteArray(), cache.libraryBytes("p.C0"), "an evicted jar must reopen on demand")
            val open = cache.openHandleCount()
            assertTrue(open in 1..24, "open handles must be capped (was $open of 40 jars)")
        } finally { cache.close() }
    }

    @Test
    fun locatorIndexMapsTopLevelClassesToTheirJar() {
        val jar = dir.resolve("lib.jar")
        assertEquals(
            mapOf("com.example.Foo" to listOf(normalizedJarKey(jar))),
            JavaClassLocatorIndex.index(libInput("com/example/Foo.class", jar)),
        )
        // Nested types resolve via their enclosing type, never via findType → not located.
        assertTrue(JavaClassLocatorIndex.index(libInput("com/example/Foo\$Inner.class", jar)).isEmpty())
        // Only library units are accepted (a source unit is some other index's job).
        assertTrue(!JavaClassLocatorIndex.inputFilter.accepts(libInput("X.class", jar, IndexOrigin.SOURCE)))
    }

    // ---- helpers ----

    private fun makeJar(name: String, vararg entries: Pair<String, ByteArray>): Path {
        val jar = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            for ((entryName, bytes) in entries) { zos.putNextEntry(ZipEntry(entryName)); zos.write(bytes); zos.closeEntry() }
        }
        return jar
    }

    private fun cache(jars: List<Path>, index: IndexService) =
        JdtEnvironmentCache(sourceRoots = emptyList(), classpathJars = jars, jdkHome = null, indexProvider = { index })

    private fun index(ready: Boolean, locator: Map<String, List<String>>) = object : IndexService {
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
            if (id == IndexId("java.classLocator")) locator[key].orEmpty().asSequence() as Sequence<V> else emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status get() = IndexStatus(ready = ready)
        override fun observeStatus(listener: (IndexStatus) -> Unit): Disposable = Disposable {}
    }

    private fun libInput(entry: String, jar: Path, originKind: IndexOrigin = IndexOrigin.LIBRARY) = object : IndexInput {
        override val origin = originKind
        override val contentHash = ContentHash("test")
        override val unitName = entry
        override val sourcePath = jar
        override fun bytes() = ByteArray(0)
        override fun text(): String? = null
        override fun dom(): ParsedFile? = null
    }
}
