package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
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
 * The classpath `classNames` index answers a prefix with the top-N fuzzy-scored hits, so a broad prefix
 * returns a CAPPED page that a long-named type (`Superwidget` here) only scores into at a longer prefix. If the
 * capped page is reported as the complete match set, the editor narrows it client-side as the user keeps
 * typing and NEVER re-queries — so the long type never surfaces (typing `S`,`u`,`p`,… one by one) until the
 * completion session is restarted (move the caret away and back). Regression: [KotlinSymbolService.typeNameCandidates]
 * must flag the capped page so the completion result is marked incomplete and the engine re-queries.
 *
 * NOTE: the deep example is a synthetic `com.example.Superwidget`, NOT a real stdlib type — `StringBuilder`
 * and friends are default-in-scope simple types now, so they surface at `S` regardless of the index page.
 */
class KotlinTypeNameCappedCompletionTest {

    private val CLASS_NAMES = IndexId("java.classNames")

    // A large flat `S…` universe; `Superwidget` sits deep so a short-prefix top-N page excludes it, exactly
    // like the real fuzzy scorer ranking it below hundreds of shorter `S*` names until the prefix narrows.
    private val allTypes = buildList {
        repeat(500) { add("com.example.S${"a".repeat(1)}class$it") }
        add("com.example.Superwidget")
    }

    private fun service() = KotlinSymbolService(
        sourceRoots = emptyList(),
        classpathJars = emptyList(),
        index = fakeIndex(),
    )

    @Test
    fun broadPrefixPageIsFlaggedCappedSoTheEngineReQueries() {
        val svc = service()
        val broad = svc.typeNameCandidates("S", limit = 100)
        assertTrue(broad.capped, "a capped `S` page must be flagged incomplete so completion re-queries")
        assertFalse(broad.symbols.any { it.name == "Superwidget" },
            "sanity: Superwidget is beyond the capped `S` page in this fixture")
    }

    @Test
    fun narrowedPrefixSurfacesTheDeepTypeAndIsComplete() {
        val svc = service()
        val narrow = svc.typeNameCandidates("Superwid", limit = 100)
        assertTrue(narrow.symbols.any { it.name == "Superwidget" },
            "Superwidget must be offered once the prefix narrows; got ${narrow.symbols.map { it.name }.take(10)}")
        assertFalse(narrow.capped, "a small exact-ish page is complete, so the engine can narrow it locally")
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeIndex() = object : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = emptySequence()

        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
            fuzzy(id, prefix, limit)

        // Mimic the real scorer: rank matches by name length (shorter = higher), return only the top [limit].
        // At prefix "S" the 500 short synthetic names outrank the longer `Superwidget`, so the page fills to
        // the cap without it; a longer prefix filters the noise out and it surfaces.
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> {
            if (id != CLASS_NAMES) return emptySequence()
            val p = pattern.lowercase()
            return allTypes.asSequence()
                .filter { it.substringAfterLast('.').lowercase().startsWith(p) }
                .sortedBy { it.substringAfterLast('.').length }
                .take(limit)
                .map { Hit(it, ClassNameValue(it, IndexOrigin.LIBRARY, "class") as V, 0) }
        }

        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }
}
