package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeShape
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the false "Type mismatch: inferred type is SolidColor but Color was expected" editor error
 * (fixed in 3.6.0). A classpath supertype chain queried WHILE the index is (re)building is incomplete (the
 * type-shape lookups are gated / the segments are mid-rewrite). Such a partial chain must never be pinned into
 * the session `classpathSupertypeMemo`: if `SolidColor`'s chain is cached WITHOUT `Brush`, then
 * `Brush.isAssignableFrom(SolidColor)` stays false for the whole session and `BorderStroke(width, SolidColor(…))`
 * — whose `Brush` constructor should accept the `SolidColor` — falsely reports the `Color`-overload mismatch.
 *
 * The invariant: after a build settles, `supertypesOf` returns the COMPLETE chain even if it was queried
 * mid-build. Modeled with a fake index whose type-shape lookups are unavailable while `building`, then available.
 */
class KotlinSupertypeCacheRebuildTest {

    /** A fake index with a mutable build [status] and a [settled] flag gating whether the `kotlin.typeShape`
     *  lookups answer — mid-rebuild they return nothing (the segment is being rewritten). */
    private class RebuildingIndex : IndexService {
        @Volatile var currentStatus: IndexStatus = IndexStatus(ready = true, building = true)
        @Volatile var settled: Boolean = false

        // SolidColor : Brush : Any — the shape the mismatch check walks.
        private val shapes = mapOf(
            "p.SolidColor" to shape("p.Brush"),
            "p.Brush" to shape("kotlin.Any"),
        )

        private fun shape(vararg supertypes: String): TypeShape =
            TypeShape(emptyList(), emptyList(), emptyList(), supertypes.map { KotlinType(it) }, emptyList())

        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
            if (settled && id.value == "kotlin.typeShape") shapes[key]?.let { sequenceOf(it as V) } ?: emptySequence()
            else emptySequence()

        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status: IndexStatus get() = currentStatus
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    @Test
    fun midBuildPartialSupertypeChainIsNotPinnedAfterTheBuildSettles() {
        val idx = RebuildingIndex()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = emptyList(), index = idx)

        // Phase 1: a rebuild is in progress — `ready` is still true from the prior build, but `building` is true
        // and the SolidColor shape is transiently unavailable, so its classpath chain comes back partial (no Brush).
        idx.currentStatus = IndexStatus(ready = true, building = true)
        idx.settled = false
        val duringBuild = service.supertypesOf("p.SolidColor").map { it.qualifiedName }
        assertTrue(
            "p.Brush" !in duringBuild,
            "sanity: mid-build the shape is unavailable so the chain is partial; got $duringBuild",
        )

        // Phase 2: the build settles and the shape is available. The mid-build partial must NOT have been pinned —
        // the chain must now include Brush (else Brush.isAssignableFrom(SolidColor) is false → the false mismatch).
        idx.settled = true
        idx.currentStatus = IndexStatus(ready = true, building = false)
        val afterBuild = service.supertypesOf("p.SolidColor").map { it.qualifiedName }
        assertTrue(
            "p.Brush" in afterBuild,
            "after the build settles the classpath supertype chain must be complete (Brush present), not a pinned partial; got $afterBuild",
        )
    }
}
