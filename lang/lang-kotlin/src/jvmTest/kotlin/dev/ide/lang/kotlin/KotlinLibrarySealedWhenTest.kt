package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.symbols.TypeShape
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `when`-exhaustiveness over a LIBRARY (classpath) sealed type — the subclasses come from the `@Metadata`
 * `sealedSubclasses` carried on the type shape (here served by a fake index), not the source model.
 */
class KotlinLibrarySealedWhenTest {

    private fun diag(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun nonExhaustiveLibrarySealedWhenIsFlagged() {
        val d = diag("import lib.State\nfun f(s: State): Int = when (s) {\n  is Loading -> 1\n}")
        assertTrue(d.any { it.code == "kt.whenExhaustive" && it.message.contains("Done") }, "missing `Done` must be flagged; got $d")
    }

    @Test
    fun exhaustiveLibrarySealedWhenIsNotFlagged() {
        val d = diag("import lib.State\nfun f(s: State): Int = when (s) {\n  is Loading -> 1\n  is Done -> 2\n}")
        assertTrue(d.none { it.code == "kt.whenExhaustive" }, "an exhaustive library-sealed `when` must not be flagged; got $d")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        // A library sealed type `lib.State` with two subclasses, served straight from the type-shape index.
        private val served: Map<String, TypeShape> = mapOf(
            "lib.State" to TypeShape(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                isAbstract = true, isKotlin = true, sealedSubclasses = listOf("lib.Loading", "lib.Done"),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id.value == "kotlin.typeShape") served[key]?.let { sequenceOf(it as V) } ?: emptySequence() else emptySequence()
            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath())))
            .apply { indexService = fakeIndex }
    }
}
