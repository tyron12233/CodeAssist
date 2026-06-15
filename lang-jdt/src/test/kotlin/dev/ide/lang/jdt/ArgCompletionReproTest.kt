package dev.ide.lang.jdt

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Completion of an argument inside a call whose receiver chain contains a lambda, when the statement is
 * not yet terminated — e.g. `…map(x -> 1).collect(Colle|)` with no trailing `;`. The marker lands as an
 * argument mid-expression, so neither the bare nor the `;`-suffixed splice forms a valid statement (the
 * `;` would land inside the parens). ecj's recovery discards the lambda-bearing statement, so nothing
 * resolved. The balanced-tail splice fallback (drop trailing text, synthesise closers + `;`) fixes it.
 */
class ArgCompletionReproTest {

    private class FakeIndex(val classNames: List<ClassNameValue>) : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
            if (id.value == "java.classNames")
                classNames.filter { it.fqn.substringAfterLast('.').startsWith(pattern, ignoreCase = true) }
                    .asSequence().map { Hit(it.fqn.substringAfterLast('.'), it as V, 800) }
            else emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus()
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable {}
    }

    private fun complete(code: String): List<String> {
        val (analyzer, dir) = workspaceWith()
        analyzer.indexService = FakeIndex(listOf(ClassNameValue("java.util.stream.Collectors", IndexOrigin.SDK, "class")))
        return try {
            completeLabels(analyzer, dir.resolve("app/T.java"), code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun completesArgInLambdaChainWithoutTrailingSemicolon() {
        // The reported bug: no `;` after the unterminated call.
        val labels = complete("package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1).collect(Colle|CARET|) } }")
        assertTrue("Collectors" in labels, "expected unimported Collectors: $labels")
    }

    @Test
    fun completesArgInLambdaChainWithTrailingSemicolon() {
        // Regression guard for the form that already worked.
        val labels = complete("package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1).collect(Colle|CARET|); } }")
        assertTrue("Collectors" in labels, "expected unimported Collectors: $labels")
    }

    @Test
    fun completesInScopeNameArgInLambdaChain() {
        // Index-independent: an in-scope local must appear, proving the statement now parses.
        val labels = complete("package app; class T { void m() { int collectVar = 0; java.util.List.of(1).stream().map(it -> 1).collect(collect|CARET|) } }")
        assertTrue("collectVar" in labels, "expected in-scope local collectVar: $labels")
    }
}
