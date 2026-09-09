package dev.ide.lang.kotlin

import dev.ide.platform.Disposable
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.testkit.TestJars
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A wired index that SETTLED without becoming ready must not be trusted to answer a classpath MISS.
 *
 * The builder publishes `ready = skipped == 0 && missing == 0`, so one skipped artifact or one unbuilt segment
 * leaves a permanently partial index — `building = false, ready = false`. Resolution was index-only whenever an
 * index was wired at all, so every callable in the missing artifact read as "not on the classpath", and the
 * Compose preview (which deliberately proceeds once indexing settles, rather than wedge on "Preparing"
 * forever) reported ordinary library calls as `candidates=0`: the reported `Column` unresolved in a two-line
 * file while `Text`, from an artifact that did index, resolved fine. The JDT name environment already keeps
 * probing the classpath while the index isn't ready; this is the Kotlin side of the same rule.
 *
 * The two neighbouring states must keep their current behaviour, which is why this pins all four: a build in
 * PROGRESS answers progressively from open segments (a live jar scan per miss would be ruinous on a slow
 * device), and a NEVER-BUILT index must not provoke a full scan at cold start, when it costs the most.
 */
class KotlinPartialIndexFallbackTest {

    /** An index that knows nothing at all, reporting [status] — so any answer had to come from the classpath. */
    private class EmptyIndex(override val status: IndexStatus) : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override fun observeStatus(listener: (IndexStatus) -> Unit): Disposable = Disposable {}
    }

    private fun service(status: IndexStatus) = KotlinSymbolService(
        sourceRoots = emptyList(),
        classpathJars = listOf(TestJars.kotlinStdlib()),
        index = EmptyIndex(status),
    )

    /** A finished-but-partial build: `fraction` 1.0 and no `ready`, exactly what a skipped artifact leaves. */
    private val settledPartial = IndexStatus(building = false, message = "Indexed (partial: 1 artifact(s) skipped)", fraction = 1.0, ready = false)

    @Test
    fun aSettledPartialIndexFallsBackToTheLiveClasspath() {
        service(settledPartial).use { s ->
            assertTrue(
                s.topLevelByName("listOf").any { it.name == "listOf" },
                "a settled-but-partial index must not turn a real stdlib callable into a miss",
            )
            // Not a default builtin (those are known with no index at all) — a real class in the stdlib jar.
            assertTrue(s.isKnownType("kotlin.text.Regex"), "type existence must fall back too")
            assertTrue(
                s.extensionsFor("kotlin.collections.List", namePrefix = "map", exactName = true).any { it.name == "map" },
                "extensions must fall back too — a preview needs `Modifier.fillMaxSize()` as much as `Column`",
            )
        }
    }

    @Test
    fun aBuildingIndexStaysAuthoritative() {
        // Progressive, cheap: partial answers from open segments, never a live scan per miss.
        service(IndexStatus(building = true, message = "Indexing", fraction = 0.4, ready = false)).use { s ->
            assertTrue(s.topLevelByName("listOf").isEmpty(), "a mid-build miss must not trigger a live scan")
        }
    }

    @Test
    fun aNeverBuiltIndexStaysAuthoritative() {
        // The default status, before the first build: a probe here would duplicate the build about to run.
        service(IndexStatus()).use { s ->
            assertTrue(s.topLevelByName("listOf").isEmpty(), "cold start must not trigger a live scan")
        }
    }

    @Test
    fun aReadyIndexStaysAuthoritative() {
        service(IndexStatus(building = false, message = "Indexed", fraction = 1.0, ready = true)).use { s ->
            assertTrue(
                s.topLevelByName("listOf").isEmpty(),
                "a complete index owns the answer — a miss there is real and must not re-read jars",
            )
            assertFalse(s.isKnownType("kotlin.text.Regex"), "same for type existence")
        }
    }
}
