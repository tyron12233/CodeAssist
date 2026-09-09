package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A synthetic class (Android `R`) is resource-driven and VOLATILE: when the host swaps the synthetic list (a
 * `strings.xml` add/edit → `invalidateSyntheticClasses`), the new member must appear in `R.string.` /
 * `stringResource(R.string.…)` completion at once — even with a READY index.
 *
 * Regression: `R.string`'s members (it is not a source class) were pinned in the session-stable classpath
 * member memo, which is dropped only on a full (re)build. On device (indexing finishes fast, so the index is
 * `ready`), an added string never appeared in code completion until a rebuild. The fix enumerates a synthetic
 * class's members uncached, so the host's list-identity swap is honored immediately. This test forces the
 * ready-index condition that a bootstrap-based test can miss.
 */
class KotlinSyntheticResourceRefreshTest {

    @Volatile private var current: List<SyntheticClass> = rClass("app_name")

    private fun rClass(vararg strings: String): List<SyntheticClass> = listOf(
        SyntheticClass(
            fqName = "demo.R",
            nestedClasses = listOf(
                SyntheticClass(fqName = "demo.R.string", fields = strings.map { SyntheticField(it) }),
            ),
        ),
    )

    /** A READY index — the on-device condition that enables the classpath member memo (where the bug lived). */
    private val readyIndex = object : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private val service = KotlinSymbolService(
        sourceRoots = emptyList(), classpathJars = emptyList(), index = readyIndex,
        syntheticProvider = { current },
    )

    private fun stringMembers(): List<String> =
        service.membersForCompletion("demo.R.string", emptyList(), "").map { it.name }

    @Test
    fun addedResourceMemberAppearsAfterListSwapWithReadyIndex() {
        assertTrue("app_name" in stringMembers(), "the initial R.string member must resolve")
        assertFalse("new_string" in stringMembers(), "the not-yet-added string is absent")
        // The host swaps the resource-driven synthetic list (a fresh instance) on a strings.xml edit/save.
        current = rClass("app_name", "new_string")
        val after = stringMembers()
        assertTrue("new_string" in after, "an added synthetic-R member must appear once the list swaps; got $after")
        assertTrue("app_name" in after, "existing members must remain")
    }
}
