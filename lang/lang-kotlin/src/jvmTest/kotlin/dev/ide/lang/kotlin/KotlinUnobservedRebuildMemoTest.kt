package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexBuildStats
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A library lookup that missed before a dependency arrived finds it after the index rebuilt, even when no query
 * ran WHILE the index was rebuilding.
 *
 * The field bug: a new "Jetpack Compose App" project resolves its Compose libraries in the background. The
 * symbol service is recreated with the new jars and queried at once against the index's previous, settled
 * build, which doesn't hold them yet, so `Text` misses and the miss is memoized. The index then rebuilds with
 * nobody asking; the memos were only dropped when a query SAW a build running, so they never were, and the
 * template's preview stayed at `unresolved/ambiguous call Text (candidates=0)` for the rest of the session.
 */
class KotlinUnobservedRebuildMemoTest {

    @Test
    fun aMissFromThePreviousBuildDoesNotOutliveAnUnobservedRebuild() {
        val index = SwappableIndex()
        index.status = IndexStatus(ready = true, fraction = 1.0, stats = IndexBuildStats(artifacts = 1, artifactsBuilt = 1))
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = emptyList(), index = index)

        // The previous build: the library isn't in it, and the miss is memoized.
        assertTrue(service.topLevelByName("println").isEmpty(), "nothing serves println yet")

        // The dependency's build runs and finishes between two queries: `building` is never observed.
        index.served = stdlibCallables
        index.status = IndexStatus(ready = true, fraction = 1.0, stats = IndexBuildStats(artifacts = 2, artifactsBuilt = 1))

        assertTrue(
            service.topLevelByName("println").isNotEmpty(),
            "the rebuilt index serves println, but the lookup kept the miss memoized before the rebuild",
        )
    }

    /**
     * The analyzer's per-file resolver caches (call targets included) are reused while [externalContentStamp] is
     * unchanged. A classpath that changed under them must move it, or the resolved-against-the-old-index answers
     * outlive the memos above: the field symptom was the same `Text (candidates=0)` after the memos were fixed.
     * A resync over the same artifacts (the frequent case while typing) must not.
     */
    @Test
    fun theExternalStampMovesWhenARebuildChangesTheClasspathAndOnlyThen() {
        val index = SwappableIndex()
        index.status = IndexStatus(ready = true, fraction = 1.0, stats = IndexBuildStats(artifacts = 1, artifactsBuilt = 1))
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = emptyList(), index = index)
        service.topLevelByName("println")
        val before = service.externalContentStamp("Main.kt")

        index.served = stdlibCallables
        index.status = IndexStatus(ready = true, fraction = 1.0, stats = IndexBuildStats(artifacts = 2, artifactsBuilt = 1))
        val afterRebuild = service.externalContentStamp("Main.kt")
        assertTrue(before != afterRebuild, "a rebuild that indexed new artifacts must invalidate the resolver caches")

        index.status = IndexStatus(ready = true, fraction = 1.0, stats = IndexBuildStats(artifacts = 2, artifactsReused = 2))
        assertTrue(
            afterRebuild == service.externalContentStamp("Main.kt"),
            "a resync over the same artifacts must keep the resolver caches",
        )
    }

    private class SwappableIndex : IndexService {
        @Volatile var served: Map<String, List<CallableShape>> = emptyMap()
        @Volatile override var status: IndexStatus = IndexStatus()

        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
            if (id == KotlinCallableIndex.id) served[key]?.asSequence()?.map { it as V } ?: emptySequence()
            else emptySequence()

        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private companion object {
        /** The stdlib's console facade through the real producer: enough to serve `println`. */
        val stdlibCallables: Map<String, List<CallableShape>> = buildMap<String, MutableList<CallableShape>> {
            ZipFile(stdlibJarPath().toFile()).use { z ->
                val e = z.getEntry("kotlin/io/ConsoleKt.class")
                val bytes = z.getInputStream(e).use { it.readBytes() }
                KotlinCallableIndex.index(FacadeInput(e.name, bytes)).forEach { (k, v) -> getOrPut(k) { ArrayList() }.addAll(v) }
            }
        }

        class FacadeInput(override val unitName: String, private val b: ByteArray) : IndexInput {
            override val origin = IndexOrigin.LIBRARY
            override val contentHash = ContentHash("")
            override val sourcePath: String? = null
            override fun bytes() = b
            override fun text(): String? = null
            override fun dom() = null
        }
    }
}
