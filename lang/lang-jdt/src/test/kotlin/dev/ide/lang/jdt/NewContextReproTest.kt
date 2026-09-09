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
 * Type-position completion: `new Foo|` / `throw new Foo|` offer (unimported) class names, and when the
 * position has an expected type (`List<String> s = new A|`) the assignable types rank above the rest.
 */
class NewContextReproTest {
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

    private val idx = FakeIndex(listOf(
        ClassNameValue("java.util.ArrayList", IndexOrigin.SDK, "class"),
        ClassNameValue("java.util.LinkedList", IndexOrigin.SDK, "class"),
        ClassNameValue("java.util.Arrays", IndexOrigin.SDK, "class"),
        ClassNameValue("java.lang.ArrayStoreException", IndexOrigin.SDK, "class"),
    ))

    private fun labels(code: String): List<String> {
        val (analyzer, dir) = workspaceWith()
        analyzer.indexService = idx
        return try { completeLabels(analyzer, dir.resolve("app/T.java"), code) } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun newCompletesUnimportedTypeNames() {
        val labels = labels("package app; class T { void m() { Object o = new ArrayL|CARET| } }")
        assertTrue("ArrayList" in labels, "new should complete unimported types: $labels")
    }

    @Test
    fun throwNewCompletesTypes() {
        val labels = labels("package app; class T { void m() { throw new ArrayS|CARET| } }")
        assertTrue("ArrayStoreException" in labels, "throw new should complete types: $labels")
    }

    @Test
    fun assignableTypesRankAboveUnrelatedOnes() {
        // expected List<String>: ArrayList implements List → ranks above Arrays / ArrayStoreException.
        val labels = labels("package app; class T { void m() { java.util.List<String> s = new A|CARET| } }")
        val arrayList = labels.indexOf("ArrayList")
        assertTrue(arrayList >= 0, "ArrayList should be offered: $labels")
        assertTrue(arrayList < labels.indexOf("Arrays"), "ArrayList (assignable) should outrank Arrays: $labels")
        assertTrue(arrayList < labels.indexOf("ArrayStoreException"), "ArrayList should outrank ArrayStoreException: $labels")
    }
}
