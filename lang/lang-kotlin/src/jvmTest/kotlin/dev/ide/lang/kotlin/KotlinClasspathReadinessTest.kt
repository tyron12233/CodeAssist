package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The classpath-readiness signals the Compose preview gates on. The bug: a finished-but-PARTIAL index (a jar was
 * skipped / an undecodable segment wasn't built) leaves `IndexStatus.ready` false FOREVER — but the preview used
 * `classpathReady()` as a hard gate, so it wedged at "Preparing libraries" after indexing had actually finished.
 * The fix gates the preview on [KotlinSymbolService.classpathIndexBuilding] instead: block only while the index is
 * ACTIVELY building; a finished index (complete or partial) proceeds (resolution still answers from the open
 * segments). `classpathReady()` keeps its strict meaning (it drives whether the editor trusts negative lookups).
 */
class KotlinClasspathReadinessTest {

    private fun indexWith(status: IndexStatus) = object : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status: IndexStatus = status
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private fun service(status: IndexStatus?) = when (status) {
        null -> KotlinSymbolService(sourceRoots = emptyList(), classpathJars = emptyList())
        else -> KotlinSymbolService(sourceRoots = emptyList(), classpathJars = emptyList(), index = indexWith(status))
    }

    @Test
    fun stillBuildingBlocks() {
        val s = service(IndexStatus(building = true, ready = false))
        assertFalse(s.classpathReady(), "an actively-building index isn't ready")
        assertTrue(s.classpathIndexBuilding(), "an actively-building index reports building → the preview waits")
    }

    @Test
    fun finishedButPartialProceeds() {
        // building = false (done), ready = false (a jar was skipped) — the exact state that used to wedge the preview.
        val s = service(IndexStatus(building = false, ready = false, fraction = 1.0))
        assertFalse(s.classpathReady(), "a partial index is still not strictly 'ready' (negatives can't be trusted)")
        assertFalse(s.classpathIndexBuilding(), "but it's no longer BUILDING → the preview must proceed, not wait forever")
    }

    @Test
    fun finishedCompleteReady() {
        val s = service(IndexStatus(building = false, ready = true, fraction = 1.0))
        assertTrue(s.classpathReady())
        assertFalse(s.classpathIndexBuilding())
    }

    @Test
    fun noIndexIsReadyAndNotBuilding() {
        // Standalone / no-index-wired: the live reader IS the classpath, so ready and nothing is building.
        val s = service(null)
        assertTrue(s.classpathReady())
        assertFalse(s.classpathIndexBuilding())
    }
}
