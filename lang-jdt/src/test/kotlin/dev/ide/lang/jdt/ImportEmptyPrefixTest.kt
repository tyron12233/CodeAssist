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
 * Empty-prefix completion under a package qualifier — `import java.|` (and `java.|` in code) must list the
 * sub-packages/types of `java`, not nothing. The fix queries the package index for the qualifier's
 * children (the `q.` prefix), since a bare `q` query matches only `q` itself.
 */
class ImportEmptyPrefixTest {
    private class FakeIndex(val packages: List<String>, val packageTypes: Map<String, List<ClassNameValue>>) : IndexService {
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
            if (id.value == "java.packageTypes") (packageTypes[key] ?: emptyList()).asSequence().map { it as V } else emptySequence()
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
            if (id.value == "java.packages") packages.filter { it.startsWith(prefix) }.asSequence().map { Hit(it, it as V, 700) } else emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus()
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable {}
    }

    private fun labels(code: String): List<String> {
        val (analyzer, dir) = workspaceWith()
        analyzer.indexService = FakeIndex(
            packages = listOf("java", "java.util", "java.io", "java.lang", "java.util.concurrent"),
            packageTypes = mapOf("java.util" to listOf(ClassNameValue("java.util.List", IndexOrigin.SDK, "interface"))),
        )
        return try { completeLabels(analyzer, dir.resolve("app/T.java"), code) } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun emptyPrefixImportListsSubPackages() {
        val labels = labels("package app;\nimport java.|CARET|\nclass T {}")
        assertTrue(labels.containsAll(listOf("io", "lang", "util")), "expected java's sub-packages: $labels")
    }

    @Test
    fun emptyPrefixPackageRefInCodeListsSubPackages() {
        val labels = labels("package app; class T { void m() { java.|CARET| } }")
        assertTrue(labels.containsAll(listOf("io", "lang", "util")), "expected java's sub-packages in code: $labels")
    }

    @Test
    fun emptyPrefixUnderDeeperPackageListsTypesAndSubPackages() {
        val labels = labels("package app;\nimport java.util.|CARET|\nclass T {}")
        assertTrue(labels.containsAll(listOf("List", "concurrent")), "expected types + sub-packages under java.util: $labels")
    }

    @Test
    fun nonEmptyPrefixStillNarrows() {
        val labels = labels("package app;\nimport java.u|CARET|\nclass T {}")
        assertTrue("util" in labels && "io" !in labels, "prefix should still narrow: $labels")
    }
}
